import { useEffect, useRef, useState } from 'react';
import { AUTH_CHANGED_EVENT } from '../../../lib/api';
import { getCurrentQuoteDraft } from '../../parts/partsApi';
import type { QuoteDraft, QuoteDraftItem } from '../../parts/types';
import {
  AI_ASSISTANT_SESSION_CHANGED_EVENT,
  createAiMessageId,
  getAiStorageOwnerKey,
  readAssistantSession,
  saveAssistantSession,
  DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION,
  type AiDraftApplicationFeedback,
  type AiDraftPerformanceSelection,
  type AiRecommendedBuild
} from '../aiSelection';
import { checkBuildPerformance, resolveBuildGraph } from '../quoteApi';

const FEEDBACK_TTL_MS = 60_000;
const ANALYSIS_TIMEOUT_MS = 5_000;
const ANALYSIS_START_DELAY_MS = 100;
const PERFORMANCE_VIEW_STORAGE_PREFIX = 'buildgraph.ai.performance-view';

export type AiDraftPerformanceView = AiDraftPerformanceSelection & {
  sourceFingerprint: string;
  evidenceSettled: boolean;
  avgFps?: number;
  updatedAt: string;
};

type ApplicationAnalysis =
  | {
      status: 'READY';
      score: number;
      maxScore: number;
      avgFps?: number;
      hasBlockingFail: boolean;
      gameLabel: string;
      resolutionLabel: string;
    }
  | { status: 'STALE' | 'FAILED' | 'TIMEOUT' };

export function applicationKindForBuild(build: AiRecommendedBuild): AiDraftApplicationFeedback['applicationKind'] {
  return build.badges.includes('DRAFT_EDIT_PREVIEW') ? 'PARTIAL_CHANGE' : 'COMPLETE_BUILD';
}

export function quoteDraftFingerprint(draftOrItems: QuoteDraft | QuoteDraftItem[]) {
  const items = Array.isArray(draftOrItems) ? draftOrItems : draftOrItems.items;
  return items
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|') || 'empty';
}

export function rememberAiDraftPerformanceView(view: AiDraftPerformanceView) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return;
  try {
    sessionStorage.setItem(`${PERFORMANCE_VIEW_STORAGE_PREFIX}:${ownerKey}`, JSON.stringify(view));
  } catch {
    // Storage가 차단돼도 견적 적용과 기본 배그/4K 재조회는 계속 동작한다.
  }
}

export function startAiDraftApplicationFeedback({
  draft,
  applicationKind,
  activeBuildId,
  changeNote
}: {
  draft: QuoteDraft;
  applicationKind: AiDraftApplicationFeedback['applicationKind'];
  activeBuildId?: string;
  /** 담기/수량 변경처럼 어떤 상품이 몇 개가 됐는지 영수증 첫 줄에 그대로 남길 짧은 문구. */
  changeNote?: string;
}) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return undefined;

  const session = readAssistantSession(ownerKey);
  const previous = session.draftApplicationFeedback;
  const messages = previous?.status === 'PENDING'
    ? session.messages.filter((message) => message.id !== previous.messageId)
    : session.messages;
  const startedAt = new Date().toISOString();
  const feedback: AiDraftApplicationFeedback = {
    id: createAiMessageId('draft-feedback'),
    messageId: createAiMessageId('draft-feedback-status'),
    draftFingerprint: quoteDraftFingerprint(draft),
    applicationKind,
    changeNote: changeNote?.trim() || undefined,
    status: 'PENDING',
    startedAt,
    performanceView: readAiDraftPerformanceView()
  };

  saveAssistantSession({
    ...session,
    messages: [...messages, {
      id: feedback.messageId,
      role: 'assistant',
      text: withChangeNote(feedback.changeNote, '견적 반영이 완료되었습니다. 종합 점수와 게임 성능을 확인하고 있습니다.'),
      createdAt: startedAt,
      kind: 'part'
    }],
    latestActiveBuildId: activeBuildId ?? session.latestActiveBuildId,
    draftApplicationFeedback: feedback,
    updatedAt: startedAt
  }, ownerKey);
  return feedback;
}

export function AiDraftApplicationFeedbackCoordinator() {
  const [feedback, setFeedback] = useState(() => readAssistantSession().draftApplicationFeedback);
  const inFlightIdRef = useRef<string | null>(null);

  useEffect(() => {
    const sync = () => setFeedback(readAssistantSession().draftApplicationFeedback);
    window.addEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, sync);
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AI_ASSISTANT_SESSION_CHANGED_EVENT, sync);
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  useEffect(() => {
    if (!feedback || feedback.status !== 'PENDING' || inFlightIdRef.current === feedback.id) return;
    const startTimer = window.setTimeout(() => {
      if (inFlightIdRef.current === feedback.id) return;
      inFlightIdRef.current = feedback.id;
      void analyzeWithTimeout(feedback)
        .then((analysis) => completeFeedback(feedback, analysis))
        .finally(() => {
          if (inFlightIdRef.current === feedback.id) inFlightIdRef.current = null;
        });
    }, ANALYSIS_START_DELAY_MS);
    return () => window.clearTimeout(startTimer);
  }, [feedback]);

  return null;
}

async function analyzeWithTimeout(feedback: AiDraftApplicationFeedback): Promise<ApplicationAnalysis> {
  if (Date.now() - Date.parse(feedback.startedAt) > FEEDBACK_TTL_MS) return { status: 'TIMEOUT' };

  let timeoutId: number | undefined;
  const timeout = new Promise<ApplicationAnalysis>((resolve) => {
    timeoutId = window.setTimeout(() => resolve({ status: 'TIMEOUT' }), ANALYSIS_TIMEOUT_MS);
  });
  const result = await Promise.race([analyzeCurrentDraft(feedback), timeout]);
  if (timeoutId !== undefined) window.clearTimeout(timeoutId);
  return result;
}

async function analyzeCurrentDraft(feedback: AiDraftApplicationFeedback): Promise<ApplicationAnalysis> {
  try {
    const draft = await getCurrentQuoteDraft();
    if (quoteDraftFingerprint(draft) !== feedback.draftFingerprint) return { status: 'STALE' };
    const performanceView = feedback.performanceView ?? readAiDraftPerformanceView();

    const performancePartIds = draft.items
      .filter((item) => item.category === 'CPU' || item.category === 'GPU')
      .map((item) => item.partId);
    const hasGpu = draft.items.some((item) => item.category === 'GPU');
    const [graphResult, performanceResult] = await Promise.allSettled([
      resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT', view: 'FOCUSED', focus: { mode: 'ISSUE_PATH' } }),
      hasGpu && performancePartIds.length > 0
        ? checkBuildPerformance({
            partIds: performancePartIds,
            game: performanceView.gameQuery,
            resolution: performanceView.resolutionQuery
          })
        : Promise.resolve(null)
    ]);

    const latestDraft = await getCurrentQuoteDraft();
    if (quoteDraftFingerprint(latestDraft) !== feedback.draftFingerprint) return { status: 'STALE' };
    if (graphResult.status !== 'fulfilled' || !graphResult.value.compositeScore) return { status: 'FAILED' };

    const score = Math.round(graphResult.value.compositeScore.score);
    const maxScore = Math.round(graphResult.value.compositeScore.maxScore);
    const hasBlockingFail = score <= 0 || graphResult.value.toolResults.some((result) => result.status === 'FAIL');
    const evidence = performanceResult.status === 'fulfilled'
      ? performanceResult.value?.details?.gameFpsEvidence?.[0]
      : undefined;
    const rawAvgFps = Number(evidence?.avgFps);
    return {
      status: 'READY',
      score,
      maxScore,
      avgFps: Number.isFinite(rawAvgFps) && rawAvgFps > 0 ? Math.round(rawAvgFps) : undefined,
      hasBlockingFail,
      gameLabel: performanceView.gameLabel,
      resolutionLabel: performanceView.resolutionLabel
    };
  } catch {
    return { status: 'FAILED' };
  }
}

function completeFeedback(feedback: AiDraftApplicationFeedback, analysis: ApplicationAnalysis) {
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return;
  const session = readAssistantSession(ownerKey);
  const pending = session.draftApplicationFeedback;
  if (!pending || pending.id !== feedback.id || pending.status !== 'PENDING') return;

  const completedAt = new Date().toISOString();
  const text = withChangeNote(feedback.changeNote, feedbackText(feedback.applicationKind, analysis));
  saveAssistantSession({
    ...session,
    messages: session.messages.map((message) => message.id === feedback.messageId
      ? { ...message, text }
      : message),
    draftApplicationFeedback: {
      ...pending,
      status: 'CONSUMED',
      completedAt
    },
    updatedAt: completedAt
  }, ownerKey);
}

// 담기/수량 변경 에코("삼성 990 PRO 추가됨 · 현재 수량 2개")를 영수증 첫 줄로 보존한다.
// 점수/FPS 분석 결과가 어느 분기로 끝나든(성공·0점·근거 없음·STALE) 상품·수량 정보는 사라지지 않는다.
function withChangeNote(changeNote: string | undefined, text: string) {
  return changeNote ? `${changeNote}\n${text}` : text;
}

function feedbackText(
  applicationKind: AiDraftApplicationFeedback['applicationKind'],
  analysis: ApplicationAnalysis
) {
  if (analysis.status === 'STALE') {
    return '견적 반영 후 구성이 다시 변경되었습니다. 최신 종합 점수와 게임 성능은 상단에서 확인해 주세요.';
  }
  if (analysis.status !== 'READY') {
    return '견적 반영이 완료되었습니다. 종합 점수와 게임 성능은 상단에서 확인해 주세요.';
  }
  if (analysis.hasBlockingFail) {
    return '변경은 반영됐지만 호환성 또는 장착 문제로 종합 점수는 0점입니다. 게임 성능보다 상단 경고를 먼저 확인해 주세요.';
  }

  const prefix = applicationKind === 'COMPLETE_BUILD'
    ? '완성 견적이 담겼습니다.'
    : '요청한 변경이 반영되었습니다.';
  const scoreText = `현재 종합 점수는 ${analysis.score.toLocaleString('ko-KR')}점입니다.`;
  if (analysis.avgFps === undefined) {
    return `${prefix} ${scoreText} ${analysis.gameLabel} ${analysis.resolutionLabel} FPS 근거가 없어 수치를 임의로 표시하지 않았습니다.\n\n상단에서 지원 게임별 예상 성능을 확인해 보세요.`;
  }
  return `${prefix} 현재 종합 점수는 ${analysis.score.toLocaleString('ko-KR')}점이며, ${analysis.gameLabel} ${analysis.resolutionLabel} 예상 성능은 평균 ${analysis.avgFps.toLocaleString('ko-KR')}FPS입니다.\n\n상단에서 종합 점수와 게임별 예상 성능을 확인해 보세요.`;
}

function readAiDraftPerformanceView(): AiDraftPerformanceSelection {
  const fallback = { ...DEFAULT_AI_DRAFT_PERFORMANCE_SELECTION };
  const ownerKey = getAiStorageOwnerKey();
  if (!ownerKey) return fallback;
  try {
    const raw = sessionStorage.getItem(`${PERFORMANCE_VIEW_STORAGE_PREFIX}:${ownerKey}`);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw) as Partial<AiDraftPerformanceView>;
    if (!parsed.gameLabel || !parsed.gameQuery || !parsed.resolutionLabel || !parsed.resolutionQuery) return fallback;
    return {
      gameLabel: parsed.gameLabel,
      gameQuery: parsed.gameQuery,
      resolutionLabel: parsed.resolutionLabel,
      resolutionQuery: parsed.resolutionQuery
    };
  } catch {
    return fallback;
  }
}
