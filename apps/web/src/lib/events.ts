import type { AiAssessmentContext } from '../features/quote/aiSelection';

export const AI_BUILD_ASSISTANT_OPEN_EVENT = 'buildgraph.aiAssistant.open';
export const AI_BUILD_ASSISTANT_TOGGLE_EVENT = 'buildgraph.aiAssistant.toggle';
export const AI_BUILD_ASSISTANT_CLOSE_EVENT = 'buildgraph.aiAssistant.close';
export const AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT = 'buildgraph.aiAssistant.visibilityChanged';
export const SUPPORT_CHAT_OPEN_EVENT = 'buildgraph.supportChat.open';
export const SUPPORT_CHAT_CLOSE_EVENT = 'buildgraph.supportChat.close';
export const PERF_COMPARE_REQUEST_EVENT = 'buildgraph.perfCompare.request';

/**
 * 셀프견적 성능 패널의 "기존 조합 vs 변경 조합" 비교 대상.
 * FPS 비교는 CPU/GPU만 의미가 있다(벤치마크 근거가 있는 카테고리).
 * linkedChanges: AI 연계 변경안(예: 새 GPU에 필요한 파워 교체)이 함께 바꾸는 부품 —
 * 종합점수 고스트가 이걸 빼고 계산하면 기존 파워+새 GPU 조합이 전력 FAIL로 0점 처리된다.
 */
export type PerfCompareTarget = {
  category: 'CPU' | 'GPU';
  partId: string;
  name: string;
  price: number;
  origin?: 'AI' | 'MANUAL';
  requestKey?: string;
  totalPriceComparison?: {
    before: number;
    after: number;
  };
  linkedChanges?: Array<{ category: string; partId: string; name: string; price: number }>;
};

export type AiAssistantOpenDetail = {
  prefill?: string;
  autoSubmit?: boolean;
  placement?: 'side' | 'center';
  assessmentContext?: AiAssessmentContext;
};

export type AiAssistantVisibilityDetail = {
  open: boolean;
};

let aiAssistantOpen = false;

export function isAiAssistantOpen() {
  return aiAssistantOpen;
}

export function setAiAssistantOpen(open: boolean) {
  if (aiAssistantOpen === open) return;
  aiAssistantOpen = open;
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AiAssistantVisibilityDetail>(AI_BUILD_ASSISTANT_VISIBILITY_CHANGED_EVENT, {
    detail: { open }
  }));
}

export function openAiAssistant(detail?: AiAssistantOpenDetail) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AiAssistantOpenDetail>(AI_BUILD_ASSISTANT_OPEN_EVENT, { detail }));
}

export function closeAiAssistant() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(AI_BUILD_ASSISTANT_CLOSE_EVENT));
}

const PERF_COMPARE_PENDING_KEY = 'buildgraph.perfComparePending';
const PERF_COMPARE_PENDING_TTL_MS = 10 * 60 * 1000;

/**
 * 성능 패널 교체 비교를 요청한다 — SelfQuotePage가 수신해 비교 모드를 켠다.
 * 수신자가 없는 화면(홈·내 견적함 등)에서 발행되거나, 셀프견적 첫 마운트에서 자식 카드
 * 이펙트가 부모 리스너보다 먼저 돌면 이벤트는 사라진다 — 세션에도 남겨 나중에 소비하게 한다.
 */
export function requestPerfCompare(detail: PerfCompareTarget) {
  if (typeof window === 'undefined') return;
  try {
    sessionStorage.setItem(PERF_COMPARE_PENDING_KEY, JSON.stringify({ detail, at: Date.now() }));
  } catch {
    // 저장 불가 환경이면 이벤트만으로 동작한다(종전 동작).
  }
  window.dispatchEvent(new CustomEvent<PerfCompareTarget>(PERF_COMPARE_REQUEST_EVENT, { detail }));
}

/**
 * 대기 중인 비교 요청을 지운다. 라이브 리스너가 이벤트를 즉시 소비했거나 사용자가 비교를
 * 명시적으로 해제했을 때 호출한다 — 남겨 두면 10분 안에 재방문 시 해제한 비교가 되살아난다.
 */
export function clearPendingPerfCompare() {
  if (typeof window === 'undefined') return;
  try {
    sessionStorage.removeItem(PERF_COMPARE_PENDING_KEY);
  } catch {
    // 저장 불가 환경이면 애초에 남은 것도 없다.
  }
}

/** 리스너 부재로 유실된 비교 요청을 셀프견적 마운트 시 1회 소비한다. 오래된 요청은 버린다. */
export function consumePendingPerfCompare(): PerfCompareTarget | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = sessionStorage.getItem(PERF_COMPARE_PENDING_KEY);
    if (!raw) return null;
    sessionStorage.removeItem(PERF_COMPARE_PENDING_KEY);
    const parsed = JSON.parse(raw) as { detail?: PerfCompareTarget; at?: number };
    if (!parsed.detail || typeof parsed.at !== 'number' || Date.now() - parsed.at > PERF_COMPARE_PENDING_TTL_MS) {
      return null;
    }
    return parsed.detail;
  } catch {
    return null;
  }
}

export function openSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_OPEN_EVENT));
}

export function closeSupportChat() {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new Event(SUPPORT_CHAT_CLOSE_EVENT));
}
