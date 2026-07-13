import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  AlertTriangle,
  ArrowRight,
  Bot,
  Check,
  CircleAlert,
  CircleDashed,
  LogIn,
  Minus,
  Plus,
  RotateCcw,
  X
} from 'lucide-react';
import { Link } from 'react-router-dom';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import { openAiAssistant } from '../../../lib/events';
import { CURRENT_QUOTE_DRAFT_STALE_TIME_MS, getCurrentQuoteDraft } from '../../parts/partsApi';
import type { QuoteDraft, QuoteDraftItem } from '../../parts/types';
import type { AiToolResult, BuildGraphResolveResponse, BuildGraphStatus, PartCategory } from '../aiSelection';
import { resolveBuildGraph } from '../quoteApi';
import './HomeSelfQuoteStatusBoard.css';

type QuoteSlot = {
  category: PartCategory;
  label: string;
  image: string;
  reason: string;
};

type ValidationKey = 'compatibility' | 'power' | 'size';
type ValidationState = BuildGraphStatus | 'PENDING' | 'LOADING' | 'ERROR';

const QUOTE_SLOTS: QuoteSlot[] = [
  {
    category: 'CPU',
    label: 'CPU',
    image: '/slot-board/parts/cpu.svg',
    reason: '견적의 성능 기준을 먼저 정할 수 있습니다.'
  },
  {
    category: 'MOTHERBOARD',
    label: '메인보드',
    image: '/slot-board/parts/motherboard.svg',
    reason: 'CPU 소켓과 메모리 규격을 검증하려면 필요합니다.'
  },
  {
    category: 'RAM',
    label: '메모리',
    image: '/slot-board/parts/ram.svg',
    reason: '용량과 메모리 규격을 확인하려면 필요합니다.'
  },
  {
    category: 'GPU',
    label: 'GPU',
    image: '/slot-board/parts/gpu.svg',
    reason: '성능과 전력, 케이스 장착 여유를 확인하려면 필요합니다.'
  },
  {
    category: 'STORAGE',
    label: '저장장치',
    image: '/slot-board/parts/storage.svg',
    reason: '저장 용량과 인터페이스 구성을 완성하려면 필요합니다.'
  },
  {
    category: 'PSU',
    label: '파워',
    image: '/slot-board/parts/psu.svg',
    reason: 'CPU와 GPU 기준 전력 여유를 확인하려면 파워 선택이 필요합니다.'
  },
  {
    category: 'CASE',
    label: '케이스',
    image: '/slot-board/parts/case.svg',
    reason: 'GPU 길이와 쿨러 높이의 장착 여유를 확인하려면 필요합니다.'
  },
  {
    category: 'COOLER',
    label: '쿨러',
    image: '/slot-board/parts/cooler.svg',
    reason: 'CPU 냉각 성능과 케이스 장착 여부를 확인하려면 필요합니다.'
  }
];

const SUPPORTED_CATEGORIES = new Set<PartCategory>(QUOTE_SLOTS.map((slot) => slot.category));

const VALIDATION_ROWS: Array<{ key: ValidationKey; label: string }> = [
  { key: 'compatibility', label: '호환성' },
  { key: 'power', label: '전력' },
  { key: 'size', label: '장착 규격' }
];

export function HomeSelfQuoteStatusBoard() {
  const [hasToken, setHasToken] = useState(() => Boolean(getToken()));

  useEffect(() => {
    const syncAuth = () => setHasToken(Boolean(getToken()));
    window.addEventListener(AUTH_CHANGED_EVENT, syncAuth);
    window.addEventListener('storage', syncAuth);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, syncAuth);
      window.removeEventListener('storage', syncAuth);
    };
  }, []);

  const quoteDraftQuery = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken,
    staleTime: CURRENT_QUOTE_DRAFT_STALE_TIME_MS,
    retry: false
  });
  const draftItems = quoteDraftQuery.data?.items ?? [];
  const graphSignature = draftItems.map((item) => [item.partId, item.quantity, item.currentPrice]);
  const graphQuery = useQuery({
    queryKey: ['build-graph', 'home-quote-status', quoteDraftQuery.data?.updatedAt ?? null, graphSignature],
    queryFn: () => resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT' }),
    enabled: hasToken && quoteDraftQuery.isSuccess && draftItems.length > 0,
    retry: false
  });

  return (
    <section
      data-testid="home-self-quote-status"
      className="home-self-quote-status"
      aria-labelledby="home-self-quote-status-title"
    >
      <header className="home-self-quote-status__header">
        <h2 id="home-self-quote-status-title">내 셀프 견적</h2>
        <p>지금 구성과 다음 선택, 검증할 항목을 한 곳에서 이어서 확인합니다.</p>
      </header>

      {!hasToken ? <GuestQuoteState /> : null}
      {hasToken && quoteDraftQuery.isLoading ? <QuoteStatusSkeleton /> : null}
      {hasToken && quoteDraftQuery.isError ? (
        <QuoteDraftError
          isFetching={quoteDraftQuery.isFetching}
          onRetry={() => void quoteDraftQuery.refetch()}
        />
      ) : null}
      {hasToken && quoteDraftQuery.data ? (
        <QuoteStatusContent
          draft={quoteDraftQuery.data}
          graph={graphQuery.data}
          isGraphLoading={graphQuery.isLoading}
          isGraphError={graphQuery.isError}
        />
      ) : null}
    </section>
  );
}

function GuestQuoteState() {
  return (
    <div className="home-self-quote-status__guest">
      <span className="home-self-quote-status__guest-icon" aria-hidden="true">
        <LogIn size={23} />
      </span>
      <div>
        <h3>저장한 견적을 이어서 구성하세요</h3>
        <p>로그인하면 저장한 셀프 견적을 이어서 확인할 수 있습니다.</p>
      </div>
      <Link to="/login?redirect=%2Fself-quote" className="home-self-quote-status__primary-action">
        로그인하고 견적 확인
        <ArrowRight size={17} aria-hidden="true" />
      </Link>
    </div>
  );
}

function QuoteDraftError({ isFetching, onRetry }: { isFetching: boolean; onRetry: () => void }) {
  return (
    <div className="home-self-quote-status__error" role="alert">
      <div>
        <h3>현재 셀프 견적을 불러오지 못했습니다</h3>
        <p>잠시 후 다시 시도하거나 셀프 견적 화면에서 직접 확인해 주세요.</p>
      </div>
      <div className="home-self-quote-status__error-actions">
        <button type="button" onClick={onRetry} disabled={isFetching}>
          <RotateCcw size={17} aria-hidden="true" />
          {isFetching ? '다시 불러오는 중' : '다시 시도'}
        </button>
        <Link to="/self-quote">셀프 견적 열기</Link>
      </div>
    </div>
  );
}

function QuoteStatusContent({
  draft,
  graph,
  isGraphLoading,
  isGraphError
}: {
  draft: QuoteDraft;
  graph?: BuildGraphResolveResponse;
  isGraphLoading: boolean;
  isGraphError: boolean;
}) {
  const itemsByCategory = useMemo(() => groupItemsByCategory(draft.items), [draft.items]);
  const selectedCategories = new Set(itemsByCategory.keys());
  const selectedCount = selectedCategories.size;
  const missingSlots = QUOTE_SLOTS.filter((slot) => !selectedCategories.has(slot.category));
  const firstMissingSlot = missingSlots[0];
  const latestItem = useMemo(() => findLatestDraftItem(draft.items), [draft.items]);
  const latestTimestamp = latestItem ? itemTimestamp(latestItem) : null;
  const progress = Math.round((selectedCount / QUOTE_SLOTS.length) * 100);
  const primaryHref = selectedCount === 0
    ? '/self-quote?category=CPU'
    : '/self-quote';
  const primaryLabel = selectedCount === 0
    ? '셀프 견적 시작하기'
    : selectedCount === QUOTE_SLOTS.length
      ? '견적 최종 확인하기'
      : '셀프 견적 이어서 구성';

  function askAiForMissingParts() {
    const missingLabels = missingSlots.map((slot) => slot.label).join(', ');
    openAiAssistant({
      placement: 'side',
      prefill: missingLabels
        ? `현재 셀프 견적에서 아직 선택하지 않은 ${missingLabels} 부품을 추천해줘`
        : '현재 셀프 견적의 호환성과 전력, 장착 규격을 검토해줘'
    });
  }

  return (
    <div className="home-self-quote-status__layout">
      <article className="home-self-quote-status__main-card">
        <div className="home-self-quote-status__main-heading">
          <div className="home-self-quote-status__title-row">
            <h3>진행 중인 견적</h3>
            <span>{selectedCount} / 8 선택</span>
          </div>
          <div className="home-self-quote-status__total">
            <span>현재 합계</span>
            <strong>{formatWon(draft.totalPrice)}</strong>
          </div>
        </div>

        <div className="home-self-quote-status__progress-copy">
          <span>견적 완성도</span>
          <strong>{progress}%</strong>
        </div>
        <div
          className="home-self-quote-status__progress"
          role="progressbar"
          aria-label={`견적 완성도 ${selectedCount} / 8`}
          aria-valuemin={0}
          aria-valuemax={8}
          aria-valuenow={selectedCount}
        >
          <span style={{ width: `${progress}%` }} />
        </div>

        <div className="home-self-quote-status__slots" aria-label="셀프 견적 부품 슬롯">
          {QUOTE_SLOTS.map((slot) => {
            const selectedItems = itemsByCategory.get(slot.category) ?? [];
            const selected = selectedItems.length > 0;
            const firstItem = selectedItems[0];
            const itemLabel = firstItem
              ? `${firstItem.name}${selectedItems.length > 1 ? ` 외 ${selectedItems.length - 1}개` : ''}`
              : '선택 필요';
            return (
              <Link
                key={slot.category}
                to={`/self-quote?category=${slot.category}`}
                data-testid={`home-self-quote-slot-${slot.category.toLowerCase()}`}
                data-state={selected ? 'selected' : 'missing'}
                className="home-self-quote-status__slot"
                aria-label={`${slot.label} ${selected ? `${itemLabel} 선택됨` : '미선택, 후보 보기'}`}
              >
                <span className="home-self-quote-status__slot-state" aria-hidden="true">
                  {selected ? <Check size={12} strokeWidth={3} /> : <Plus size={13} strokeWidth={2.5} />}
                </span>
                <span className="home-self-quote-status__slot-image" aria-hidden="true">
                  <img src={slot.image} alt="" />
                </span>
                <strong>{slot.label}</strong>
                <small>{itemLabel}</small>
              </Link>
            );
          })}
        </div>

        <div className="home-self-quote-status__summary">
          <div>
            <span>아직 필요한 부품</span>
            <strong>{missingSlots.length ? missingSlots.map((slot) => slot.label).join(' · ') : '모든 부품 선택 완료'}</strong>
          </div>
          {latestItem ? (
            <div>
              <span>{latestTimestamp ? '최근 선택' : '선택한 부품'}</span>
              <strong>{categoryLabel(latestItem.category)} · {latestItem.name}</strong>
              {latestTimestamp ? <small>{formatSavedAt(latestTimestamp)} 저장</small> : null}
            </div>
          ) : (
            <div>
              <span>첫 선택</span>
              <strong>CPU부터 견적을 시작해 보세요</strong>
            </div>
          )}
        </div>

        <div className="home-self-quote-status__actions">
          <Link to={primaryHref} className="home-self-quote-status__primary-action">
            {primaryLabel}
          </Link>
          <button type="button" className="home-self-quote-status__secondary-action" onClick={askAiForMissingParts}>
            <Bot size={17} aria-hidden="true" />
            {missingSlots.length ? 'AI로 빈 슬롯 채우기' : 'AI로 견적 점검하기'}
          </button>
          <Link to="/self-quote" className="home-self-quote-status__detail-link">
            전체 견적 상세 보기
            <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </div>
      </article>

      <div className="home-self-quote-status__side">
        <NextActionCard firstMissingSlot={firstMissingSlot} onAskAi={askAiForMissingParts} />
        <ValidationCard graph={graph} isLoading={isGraphLoading} isError={isGraphError} hasItems={draft.items.length > 0} />
      </div>
    </div>
  );
}

function NextActionCard({ firstMissingSlot, onAskAi }: { firstMissingSlot?: QuoteSlot; onAskAi: () => void }) {
  if (!firstMissingSlot) {
    return (
      <article className="home-self-quote-status__next-card">
        <div className="home-self-quote-status__side-heading">
          <h3>다음으로 할 일</h3>
          <span>구성 완료</span>
        </div>
        <div className="home-self-quote-status__next-content">
          <span className="home-self-quote-status__next-icon home-self-quote-status__next-icon--complete" aria-hidden="true">
            <Check size={25} strokeWidth={2.6} />
          </span>
          <div>
            <small>8개 슬롯 선택 완료</small>
            <strong>최종 검증을 확인하세요</strong>
          </div>
        </div>
        <p>호환성, 전력, 장착 규격의 경고가 남아 있는지 확인한 뒤 견적을 저장하세요.</p>
        <Link to="/self-quote" className="home-self-quote-status__next-link">
          견적 검증 확인하기
          <ArrowRight size={16} aria-hidden="true" />
        </Link>
      </article>
    );
  }

  return (
    <article className="home-self-quote-status__next-card">
      <div className="home-self-quote-status__side-heading">
        <h3>다음으로 할 일</h3>
        <span>추천</span>
      </div>
      <div className="home-self-quote-status__next-content">
        <span className="home-self-quote-status__next-icon" aria-hidden="true">
          <img src={firstMissingSlot.image} alt="" />
        </span>
        <div>
          <small>첫 미선택 슬롯</small>
          <strong>{firstMissingSlot.label}부터 선택하세요</strong>
        </div>
      </div>
      <p>{firstMissingSlot.reason}</p>
      <div className="home-self-quote-status__next-actions">
        <Link to={`/self-quote?category=${firstMissingSlot.category}`} className="home-self-quote-status__next-link">
          {firstMissingSlot.label} 후보 보기
        </Link>
        <button type="button" onClick={onAskAi}>
          AI에게 추천 받기
          <ArrowRight size={15} aria-hidden="true" />
        </button>
      </div>
    </article>
  );
}

function ValidationCard({
  graph,
  isLoading,
  isError,
  hasItems
}: {
  graph?: BuildGraphResolveResponse;
  isLoading: boolean;
  isError: boolean;
  hasItems: boolean;
}) {
  const tools = new Map(graph?.toolResults.map((result) => [result.tool, result]) ?? []);
  const representative = graph ? representativeGraphMessage(graph) : null;
  const attentionCount = graph?.toolResults.filter((result) => result.status !== 'PASS').length ?? 0;

  return (
    <article className="home-self-quote-status__validation-card">
      <div className="home-self-quote-status__validation-heading">
        <h3>검증 체크포인트</h3>
        <Link to="/self-quote">
          검증 상세 보기
          <ArrowRight size={15} aria-hidden="true" />
        </Link>
      </div>

      {isError ? (
        <div className="home-self-quote-status__validation-error" role="alert">
          <CircleAlert size={16} aria-hidden="true" />
          검증 확인 불가 · 셀프 견적에서 다시 확인해 주세요.
        </div>
      ) : null}

      <div className="home-self-quote-status__validation-list">
        {VALIDATION_ROWS.map((row) => {
          const tool = tools.get(row.key);
          const state = validationState({ tool, isLoading, isError });
          return (
            <div
              key={row.key}
              data-testid={`home-self-quote-validation-${row.key}`}
              data-state={state.toLowerCase()}
              className="home-self-quote-status__validation-row"
            >
              <span className="home-self-quote-status__validation-label">
                <ValidationIcon state={state} />
                {row.label}
              </span>
              <strong>{validationStateLabel(state)}</strong>
            </div>
          );
        })}
      </div>

      {!isError ? (
        <div
          className="home-self-quote-status__validation-note"
          data-state={highestAttentionState(graph).toLowerCase()}
        >
          {isLoading
            ? '현재 선택한 부품의 검증 결과를 불러오고 있습니다.'
            : representative
              ? `${attentionCount ? `확인 필요 ${attentionCount}건 · ` : ''}${representative}`
              : hasItems
                ? '아직 확인되지 않은 항목은 셀프 견적에서 다시 검증할 수 있습니다.'
                : '부품을 선택하면 호환성, 전력, 장착 규격을 확인합니다.'}
        </div>
      ) : null}
    </article>
  );
}

function ValidationIcon({ state }: { state: ValidationState }) {
  return (
    <span className="home-self-quote-status__validation-icon" data-state={state.toLowerCase()} aria-hidden="true">
      {state === 'PASS' ? <Check size={12} strokeWidth={3} /> : null}
      {state === 'WARN' ? <AlertTriangle size={12} strokeWidth={2.5} /> : null}
      {state === 'FAIL' ? <X size={12} strokeWidth={3} /> : null}
      {state === 'PENDING' ? <Minus size={12} strokeWidth={2.5} /> : null}
      {state === 'LOADING' ? <CircleDashed size={12} strokeWidth={2.5} /> : null}
      {state === 'ERROR' ? <CircleAlert size={12} strokeWidth={2.5} /> : null}
    </span>
  );
}

function QuoteStatusSkeleton() {
  return (
    <div className="home-self-quote-status__skeleton" aria-label="셀프 견적 현황 불러오는 중" aria-busy="true">
      <div className="home-self-quote-status__skeleton-main">
        <span className="home-self-quote-status__skeleton-line home-self-quote-status__skeleton-line--title" />
        <span className="home-self-quote-status__skeleton-line home-self-quote-status__skeleton-line--progress" />
        <div className="home-self-quote-status__skeleton-slots">
          {QUOTE_SLOTS.map((slot) => <span key={slot.category} />)}
        </div>
        <span className="home-self-quote-status__skeleton-line home-self-quote-status__skeleton-line--summary" />
      </div>
      <div className="home-self-quote-status__skeleton-side">
        <span />
        <span />
      </div>
    </div>
  );
}

function groupItemsByCategory(items: QuoteDraftItem[]) {
  const grouped = new Map<PartCategory, QuoteDraftItem[]>();
  for (const item of items) {
    const category = item.category.toUpperCase() as PartCategory;
    if (!SUPPORTED_CATEGORIES.has(category)) continue;
    grouped.set(category, [...(grouped.get(category) ?? []), item]);
  }
  return grouped;
}

function findLatestDraftItem(items: QuoteDraftItem[]) {
  let latest: { item: QuoteDraftItem; timestamp: number } | null = null;
  for (const item of items) {
    const timestamp = itemTimestamp(item);
    if (!timestamp) continue;
    const time = Date.parse(timestamp);
    if (!Number.isFinite(time)) continue;
    if (!latest || time > latest.timestamp) latest = { item, timestamp: time };
  }
  return latest?.item ?? items[0] ?? null;
}

function itemTimestamp(item: QuoteDraftItem) {
  const value = item.updatedAt ?? item.createdAt;
  return value && Number.isFinite(Date.parse(value)) ? value : null;
}

function validationState({
  tool,
  isLoading,
  isError
}: {
  tool?: AiToolResult;
  isLoading: boolean;
  isError: boolean;
}): ValidationState {
  if (isError) return 'ERROR';
  if (isLoading) return 'LOADING';
  if (tool) return tool.status;
  return 'PENDING';
}

function validationStateLabel(state: ValidationState) {
  if (state === 'PASS') return '통과';
  if (state === 'WARN') return '확인 필요';
  if (state === 'FAIL') return '적용 불가';
  if (state === 'ERROR') return '확인 불가';
  if (state === 'LOADING') return '검증 중';
  return '선택 후 확인';
}

function representativeGraphMessage(graph: BuildGraphResolveResponse) {
  const insight = graph.insights.find((item) => item.status === 'FAIL')
    ?? graph.insights.find((item) => item.status === 'WARN')
    ?? graph.insights[0];
  if (insight) return `${insight.title} · ${insight.description}`;
  const tool = graph.toolResults.find((item) => item.status === 'FAIL')
    ?? graph.toolResults.find((item) => item.status === 'WARN')
    ?? graph.toolResults[0];
  return tool?.summary ?? graph.summary ?? null;
}

function highestAttentionState(graph?: BuildGraphResolveResponse): BuildGraphStatus | 'PENDING' {
  if (!graph) return 'PENDING';
  const statuses = [...graph.toolResults.map((item) => item.status), ...graph.insights.map((item) => item.status)];
  if (statuses.includes('FAIL')) return 'FAIL';
  if (statuses.includes('WARN')) return 'WARN';
  if (statuses.includes('PASS')) return 'PASS';
  return 'PENDING';
}

function categoryLabel(category: string) {
  const normalized = category.toUpperCase();
  return QUOTE_SLOTS.find((slot) => slot.category === normalized)?.label ?? category;
}

function formatSavedAt(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value));
}

function formatWon(value: number) {
  return `${new Intl.NumberFormat('ko-KR').format(value)}원`;
}
