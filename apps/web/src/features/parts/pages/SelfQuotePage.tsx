import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Bell, LayoutGrid, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Screen } from '../../../components/ui';
import { AUTH_CHANGED_EVENT, getToken } from '../../../lib/api';
import {
  AI_SELECTED_BUILD_CHANGED_EVENT,
  PART_CATEGORY_LABELS,
  clearSelectedAiBuild,
  readSelectedAiBuild,
  type AiBuildItem,
  type AiRecommendedBuild,
  type BuildGraphFocus,
  type BuildGraphResolveResponse,
  type PartCategory,
  type AiSelectedBuild
} from '../../quote/aiSelection';
import { resolveBuildGraph, saveBuildFromChat } from '../../quote/quoteApi';
import { SlotBoard } from '../components/slot-board/SlotBoard';
import { SlotCandidatePanel } from '../components/slot-board/SlotCandidatePanel';
import { SlotStatusBar } from '../components/slot-board/SlotStatusBar';
import { SLOT_CONFIGS, SLOT_COUNT, isSlotCategory, slotConfigFor } from '../components/slot-board/slotBoardConfig';
import { deleteQuoteDraftItem, getCurrentQuoteDraft, patchQuoteDraftItem, putQuoteDraftItem } from '../partsApi';
import type { PartRow, QuoteDraft, QuoteDraftItem } from '../types';

export function SelfQuotePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryParam = searchParams.get('category');
  const selectedCategory: PartCategory | null = isSlotCategory(categoryParam) ? categoryParam : null;
  const selectedSlot = selectedCategory ? slotConfigFor(selectedCategory) ?? null : null;
  const [aiBuild, setAiBuild] = useState<AiSelectedBuild | null>(() => readSelectedAiBuild());
  const hasToken = Boolean(getToken());
  const loginHref = `/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`;

  const { data: quoteDraft, isError: isQuoteDraftError, isLoading: isQuoteDraftLoading } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  const invalidateQuoteDraft = () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] });
  const addMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => putQuoteDraftItem(partId, quantity),
    onSuccess: invalidateQuoteDraft
  });
  const deleteMutation = useMutation({
    mutationFn: (partId: string) => deleteQuoteDraftItem(partId),
    onSuccess: invalidateQuoteDraft
  });
  const quantityMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => patchQuoteDraftItem(partId, quantity),
    onSuccess: invalidateQuoteDraft
  });
  const replaceMutation = useMutation({
    mutationFn: async ({ removePartId, partId }: { removePartId: string; partId: string }) => {
      await putQuoteDraftItem(partId, 1);
      return deleteQuoteDraftItem(removePartId);
    },
    onSuccess: invalidateQuoteDraft
  });
  const saveQuoteMutation = useMutation({
    mutationFn: (draft: QuoteDraft) => saveBuildFromChat({
      sourceBuildId: selfQuoteBuildId(draft),
      lastUserMessage: '셀프 견적에서 저장',
      build: quoteDraftToRecommendedBuild(draft)
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['build-history'] })
  });

  const draftItems = quoteDraft?.items ?? [];
  const selectedTotal = quoteDraft?.totalPrice ?? 0;

  // 관계선 라벨의 optional source. 실패해도 슬롯 보드와 기본 topology 관계선은 항상 렌더링된다.
  const graphFocus = quoteGraphFocus(selectedCategory);
  const graphQuery = useQuery({
    queryKey: ['build-graph', 'quote-draft-current', quoteGraphSignature(draftItems), graphFocus.mode, graphFocus.category],
    queryFn: () => resolveBuildGraph({
      source: 'QUOTE_DRAFT_CURRENT',
      view: 'FOCUSED',
      focus: graphFocus
    }),
    placeholderData: keepPreviousData,
    enabled: hasToken && !isQuoteDraftLoading
  });

  useEffect(() => {
    const syncSelectedBuild = () => setAiBuild(readSelectedAiBuild());
    window.addEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
    window.addEventListener(AUTH_CHANGED_EVENT, syncSelectedBuild);
    window.addEventListener('storage', syncSelectedBuild);
    return () => {
      window.removeEventListener(AI_SELECTED_BUILD_CHANGED_EVENT, syncSelectedBuild);
      window.removeEventListener(AUTH_CHANGED_EVENT, syncSelectedBuild);
      window.removeEventListener('storage', syncSelectedBuild);
    };
  }, []);

  const selectSlot = (category: PartCategory) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.set('category', category);
      return nextParams;
    });
  };

  const closePanel = useCallback(() => {
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('category');
      return nextParams;
    });
  }, [setSearchParams]);

  const addPart = (part: PartRow) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    addMutation.mutate({ partId: part.id, quantity: 1 });
  };

  const replacePart = (removePartId: string, part: PartRow) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    replaceMutation.mutate({ removePartId, partId: part.id });
  };

  const removeItem = (partId: string) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    deleteMutation.mutate(partId);
  };

  const updateQuantity = (partId: string, quantity: number) => {
    if (!hasToken) {
      navigate(loginHref);
      return;
    }
    quantityMutation.mutate({ partId, quantity });
  };

  const isMutating = addMutation.isPending || deleteMutation.isPending || replaceMutation.isPending || quantityMutation.isPending;
  const hasCompatibilityFail = quoteHasCompatibilityFail(graphQuery.data, draftItems);

  const filledCount = SLOT_CONFIGS.filter((slot) => draftItems.some((item) => item.category === slot.category)).length;
  const warnCount = graphQuery.data
    ? graphQuery.data.nodes.filter((node) => node.type === 'PART' && node.status === 'WARN').length
    : 0;
  const failCount = graphQuery.data
    ? graphQuery.data.nodes.filter((node) => node.type === 'PART' && node.status === 'FAIL').length
    : 0;

  return (
    <Screen>
      <div className="space-y-4">
        {/* 페이지 헤더 */}
        <div className="flex flex-wrap items-center gap-2">
          <LayoutGrid size={15} className="text-brand-blue" />
          <h1 className="text-base font-black tracking-tight text-commerce-ink">셀프 견적 · 구성 관계도</h1>
          <span className="text-xs text-slate-400">슬롯을 눌러 후보를 확인하고 교체하세요</span>
        </div>

        {/* 상단 요약 지표 바 */}
        <QuoteSummaryBar
          totalPrice={selectedTotal}
          filledCount={filledCount}
          slotCount={SLOT_COUNT}
          warnCount={warnCount}
          failCount={failCount}
          storageItems={draftItems.filter((item) => item.category === 'STORAGE')}
          graphLoading={graphQuery.isLoading}
        />

        {aiBuild ? (
          <AiSelectedBuildPanel
            build={aiBuild}
            draftItems={draftItems}
            currentTotal={selectedTotal}
            onClear={() => {
              clearSelectedAiBuild();
              setAiBuild(null);
            }}
          />
        ) : null}

        {/* 본문: 보드 + 우측 상세 패널 (우측 컬럼은 항상 유지) */}
        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
          <SlotBoard
            items={draftItems}
            selectedCategory={selectedCategory}
            onSlotSelect={selectSlot}
            onRemoveItem={removeItem}
            isRemovePending={deleteMutation.isPending}
            graph={graphQuery.data}
          />
          {selectedSlot ? (
            <SlotCandidatePanel
              key={selectedSlot.category}
              slot={selectedSlot}
              draftItems={draftItems.filter((item) => item.category === selectedSlot.category)}
              onClose={closePanel}
              onAddPart={addPart}
              onReplacePart={replacePart}
              onRemoveItem={removeItem}
              onUpdateQuantity={updateQuantity}
              isMutating={isMutating}
            />
          ) : (
            <SlotDetailPlaceholder onPick={selectSlot} />
          )}
        </div>

        {/* 하단 상태바 */}
        <SlotStatusBar
          quoteDraft={quoteDraft}
          hasToken={hasToken}
          loginHref={loginHref}
          isDraftLoading={hasToken && isQuoteDraftLoading}
          isDraftError={isQuoteDraftError}
          hasCompatibilityFail={hasCompatibilityFail}
          onSave={() => quoteDraft && saveQuoteMutation.mutate(quoteDraft)}
          isSavePending={saveQuoteMutation.isPending}
          isSaveSuccess={saveQuoteMutation.isSuccess}
          isSaveError={saveQuoteMutation.isError}
        />
      </div>
    </Screen>
  );
}

function AiSelectedBuildPanel({
  build,
  draftItems,
  currentTotal,
  onClear
}: {
  build: AiSelectedBuild;
  draftItems: QuoteDraftItem[];
  currentTotal: number;
  onClear: () => void;
}) {
  const displayItems = build.items.map((item) => createAiBuildDisplayItem(item, draftItems));
  const reflectedCount = displayItems.filter((item) => item.status !== '미반영').length;
  const appliedPartCategories = build.appliedPartCategories ?? [];
  const initialTotalDiffers = build.totalPrice > 0 && currentTotal !== build.totalPrice;

  return (
    <section data-testid="ai-selected-build-panel" className="panel overflow-hidden border-blue-100 bg-blue-50/60">
      <div className="flex flex-col gap-4 p-5 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="rounded bg-commerce-ink px-2 py-1 text-[11px] font-black text-white">AI 선택</span>
            <span className="rounded bg-white px-2 py-1 text-[11px] font-black text-brand-blue">실제 장바구니 적용 기록</span>
            {appliedPartCategories.map((category) => (
              <span key={category} className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">
                {PART_CATEGORY_LABELS[category]} 반영됨
              </span>
            ))}
            {reflectedCount > 0 ? <span className="rounded bg-emerald-50 px-2 py-1 text-[11px] font-black text-emerald-700">현재 견적 {reflectedCount}개 반영</span> : null}
          </div>
          <h2 className="text-xl font-black text-commerce-ink">AI 선택 조합</h2>
          <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
            {build.title} · {build.summary} AI로 시작한 조합을 현재 견적 장바구니 기준으로 보여줍니다.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="rounded-md bg-white px-4 py-3 text-right">
            <div className="text-xs font-bold text-slate-500">현재 견적 합계</div>
            <div data-testid="ai-selected-build-current-total" className="text-lg font-black text-commerce-sale">{currentTotal.toLocaleString()}원</div>
            {initialTotalDiffers ? (
              <div className="mt-1 text-[11px] font-bold text-slate-400">최초 AI 조합: {build.totalPrice.toLocaleString()}원</div>
            ) : null}
          </div>
          <button
            type="button"
            aria-label="AI 선택 조합 비우기"
            onClick={onClear}
            className="grid h-10 w-10 place-items-center rounded-md border border-commerce-line bg-white text-slate-600 hover:border-commerce-sale hover:text-commerce-sale"
          >
            <X size={17} />
          </button>
        </div>
      </div>

      <div className="grid gap-3 border-t border-blue-100 bg-white/75 p-5 md:grid-cols-2 xl:grid-cols-4">
        {displayItems.map((item) => {
          const categoryLabel = PART_CATEGORY_LABELS[item.category] ?? item.category;
          return (
            <div key={item.key} className="rounded-lg border border-commerce-line bg-white p-3 text-xs">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="rounded bg-slate-100 px-2 py-1 font-black text-slate-700">{categoryLabel}</span>
                <span className={`rounded px-2 py-1 font-black ${aiStatusClass(item.status)}`}>
                  {item.status}
                </span>
              </div>
              <div className="min-h-10 font-black leading-5 text-commerce-ink">{item.name}</div>
              <div className="mt-1 text-slate-500">{item.manufacturer} · 수량 {item.quantity}</div>
              <div className="mt-2 break-keep text-slate-500">{item.note}</div>
              <div className="mt-3 font-black text-brand-blue">{item.lineTotal.toLocaleString()}원</div>
            </div>
          );
        })}
      </div>

      <div className="flex flex-col gap-2 border-t border-blue-100 bg-white px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="break-keep text-xs font-bold leading-5 text-slate-500">
          AI 조합 이후 챗봇으로 바꾼 부품까지 현재 견적 장바구니 기준으로 표시합니다.
        </div>
        <Link to="/my/quotes" className="inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 text-sm font-black text-commerce-ink hover:border-commerce-ink">
          <Bell size={16} />
          목표가 알림 설정
        </Link>
      </div>
    </section>
  );
}

type AiBuildDisplayItem = {
  key: string;
  category: AiBuildItem['category'];
  name: string;
  manufacturer: string;
  quantity: number;
  lineTotal: number;
  note: string;
  status: '담김' | '교체됨' | '미반영';
};

function createAiBuildDisplayItem(item: AiBuildItem, draftItems: QuoteDraftItem[]): AiBuildDisplayItem {
  const matchingDraftItems = draftItems.filter((draftItem) => draftItem.category === item.category);
  const samePart = matchingDraftItems.find((draftItem) => draftItem.partId === item.partId);
  const currentItem = samePart ?? matchingDraftItems[0];

  if (!currentItem) {
    return {
      key: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer,
      quantity: item.quantity,
      lineTotal: item.price * item.quantity,
      note: item.note,
      status: '미반영'
    };
  }

  const categoryLineTotal = matchingDraftItems.reduce((sum, draftItem) => sum + draftItem.lineTotal, 0);
  const hasMultiple = matchingDraftItems.length > 1;
  const sameQuantity = currentItem.quantity === item.quantity;
  const unchanged = currentItem.partId === item.partId && sameQuantity && !hasMultiple;

  return {
    key: `${item.category}-${currentItem.partId}`,
    category: item.category,
    name: hasMultiple ? `${currentItem.name} 외 ${matchingDraftItems.length - 1}개` : currentItem.name,
    manufacturer: currentItem.manufacturer ?? item.manufacturer,
    quantity: hasMultiple ? matchingDraftItems.reduce((sum, draftItem) => sum + draftItem.quantity, 0) : currentItem.quantity,
    lineTotal: hasMultiple ? categoryLineTotal : currentItem.lineTotal,
    note: unchanged ? item.note : 'AI 이후 챗봇 변경 반영',
    status: unchanged ? '담김' : '교체됨'
  };
}

function aiStatusClass(status: AiBuildDisplayItem['status']) {
  if (status === '담김') return 'bg-emerald-50 text-emerald-700';
  if (status === '교체됨') return 'bg-amber-50 text-amber-700';
  return 'bg-slate-100 text-slate-500';
}

function quoteGraphFocus(category: PartCategory | null): BuildGraphFocus {
  if (category) {
    return {
      mode: 'PART_IMPACT',
      category,
      tool: graphToolForCategory(category)
    };
  }
  return {
    mode: 'ISSUE_PATH'
  };
}

function quoteGraphSignature(items: QuoteDraftItem[]) {
  if (items.length === 0) {
    return 'empty';
  }
  return items
    .map((item) => `${item.partId}:${item.quantity}:${item.lineTotal}`)
    .sort()
    .join('|');
}

export function selfQuoteBuildId(draft: QuoteDraft) {
  return `self-quote-${draft.id ?? 'current'}`;
}

export function quoteDraftToRecommendedBuild(draft: QuoteDraft): AiRecommendedBuild {
  const items = draft.items
    .filter((item): item is QuoteDraftItem & { category: PartCategory } => isPartCategory(item.category))
    .map((item) => ({
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer ?? 'BuildGraph',
      quantity: item.quantity,
      price: item.currentPrice,
      note: '셀프 견적 장바구니에서 저장'
    }));
  const categories = Array.from(new Set(items.map((item) => item.category)));

  return {
    id: selfQuoteBuildId(draft),
    tier: 'balanced',
    label: '셀프',
    title: '셀프 견적 저장 조합',
    summary: '셀프 견적 페이지에서 선택한 부품을 내 견적함에 저장했습니다.',
    totalPrice: draft.totalPrice,
    badges: ['셀프 견적', `${items.length}개 부품`],
    budgetWon: draft.totalPrice,
    budgetLabel: '셀프 견적',
    tierLabel: '셀프 견적',
    appliedPartCategories: categories,
    items,
    toolResults: [],
    warnings: [],
    confidence: 'HIGH'
  };
}

// FAIL이 있으면 구매만 차단한다. 장착된 카테고리와 관련된 검증 결과만 본다.
function quoteHasCompatibilityFail(graph: BuildGraphResolveResponse | undefined, items: QuoteDraftItem[]) {
  if (!graph || items.length === 0) {
    return false;
  }
  const filledCategories = new Set(items.map((item) => item.category));
  const categoryByNodeId = new Map(graph.nodes.map((node) => [node.id, node.category]));
  const nodeFail = graph.nodes.some((node) =>
    node.type === 'PART' && node.status === 'FAIL' && typeof node.category === 'string' && filledCategories.has(node.category)
  );
  const edgeFail = graph.edges.some((edge) => {
    if (edge.status !== 'FAIL') {
      return false;
    }
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    return typeof sourceCategory === 'string' && typeof targetCategory === 'string'
      && filledCategories.has(sourceCategory) && filledCategories.has(targetCategory);
  });
  return nodeFail || edgeFail;
}

function isPartCategory(category: string): category is PartCategory {
  return Object.keys(PART_CATEGORY_LABELS).includes(category);
}

function graphToolForCategory(category: PartCategory): BuildGraphFocus['tool'] {
  if (category === 'GPU' || category === 'PSU') return 'power';
  if (category === 'CASE' || category === 'COOLER') return 'size';
  if (category === 'CPU' || category === 'MOTHERBOARD' || category === 'RAM') return 'compatibility';
  return undefined;
}

// 슬롯 미선택 시 우측 컬럼을 채우는 상세 placeholder. 슬롯을 고르면 실제 후보 패널로 전환된다.
function SlotDetailPlaceholder({ onPick }: { onPick: (category: PartCategory) => void }) {
  return (
    <section
      data-testid="slot-detail-placeholder"
      className="hidden rounded-lg border border-commerce-line bg-white lg:block"
    >
      <div className="border-b border-commerce-line px-4 py-3">
        <h2 className="text-base font-black text-commerce-ink">구성 상세 · 교체 후보</h2>
        <p className="mt-0.5 text-[11px] font-bold text-slate-500">슬롯을 선택하면 현재 견적 기준 교체 후보를 보여줍니다.</p>
      </div>
      <div className="space-y-3 p-4">
        <div className="rounded-md border border-dashed border-slate-300 bg-slate-50/60 p-4 text-center">
          <div className="mx-auto mb-2 flex h-10 w-10 items-center justify-center rounded-full bg-blue-50 text-brand-blue">
            <LayoutGrid size={18} />
          </div>
          <p className="text-xs font-black text-slate-600">왼쪽 보드에서 슬롯을 눌러보세요</p>
          <p className="mt-1 text-[11px] font-bold text-slate-400">부품 관계를 확인하고 교체 후보를 비교할 수 있습니다.</p>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {SLOT_CONFIGS.map((slot) => (
            <button
              key={slot.category}
              type="button"
              onClick={() => onPick(slot.category)}
              className="inline-flex items-center gap-1 rounded-full border border-commerce-line bg-white px-2.5 py-1 text-[11px] font-black text-slate-600 hover:border-brand-blue hover:text-brand-blue"
            >
              <img src={slot.glyph} alt="" aria-hidden="true" className="h-3.5 w-3.5" />
              {slot.label}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

function QuoteSummaryBar({
  totalPrice,
  filledCount,
  slotCount,
  warnCount,
  failCount,
  storageItems,
  graphLoading
}: {
  totalPrice: number;
  filledCount: number;
  slotCount: number;
  warnCount: number;
  failCount: number;
  storageItems: QuoteDraftItem[];
  graphLoading: boolean;
}) {
  const compatibilityText = graphLoading
    ? '확인 중'
    : failCount > 0
      ? `안 맞음 ${failCount}개`
      : warnCount > 0
        ? `주의 ${warnCount}개`
        : filledCount === 0
          ? '부품 없음'
          : '이상 없음';
  const compatibilityColor = failCount > 0
    ? 'text-red-600'
    : warnCount > 0
      ? 'text-amber-600'
      : filledCount === 0
        ? 'text-slate-400'
        : 'text-emerald-600';
  const storageCount = storageItems.reduce((sum, item) => sum + item.quantity, 0);

  return (
    <div data-testid="quote-summary-bar" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-blue-50 text-xl font-black text-brand-blue">₩</span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">총액</div>
          <div className="truncate text-sm font-black text-commerce-ink">{totalPrice > 0 ? `${totalPrice.toLocaleString()}원` : '—'}</div>
        </div>
      </div>
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600">
          <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="3" width="6" height="6" rx="1" /><rect x="11" y="3" width="6" height="6" rx="1" />
            <rect x="3" y="11" width="6" height="6" rx="1" /><rect x="11" y="11" width="6" height="6" rx="1" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">장착 슬롯</div>
          <div className="text-sm font-black text-commerce-ink">{filledCount} / {slotCount}</div>
        </div>
      </div>
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg ${failCount > 0 ? 'bg-red-50' : warnCount > 0 ? 'bg-amber-50' : 'bg-emerald-50'}`}>
          <svg viewBox="0 0 20 20" className={`h-5 w-5 ${failCount > 0 ? 'text-red-500' : warnCount > 0 ? 'text-amber-500' : 'text-emerald-500'}`} fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M10 2a8 8 0 100 16A8 8 0 0010 2zm0 5v4m0 2.5v.5" strokeLinecap="round" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">호환 상태</div>
          <div className={`text-sm font-black ${compatibilityColor}`}>{compatibilityText}</div>
        </div>
      </div>
      <div className="panel flex items-center gap-3 px-4 py-3">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-500">
          <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="6" width="14" height="8" rx="1" /><path d="M7 10h6M10 8v4" strokeLinecap="round" />
          </svg>
        </span>
        <div className="min-w-0">
          <div className="text-[11px] font-bold text-slate-500">스토리지</div>
          <div className="text-sm font-black text-commerce-ink">{storageCount > 0 ? `SSD ${storageCount}개` : 'SSD 없음'}</div>
        </div>
      </div>
    </div>
  );
}
