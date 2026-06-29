import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { CategorySidebar, DataTable, MetricCard, Panel, Screen } from '../../../components/ui';
import { getToken } from '../../../lib/api';
import { partImageUrl, partShortSpec } from '../partDisplay';
import { deleteQuoteDraftItem, getCurrentQuoteDraft, getPartPriceHistory, listParts, patchQuoteDraftItem, putQuoteDraftItem } from '../partsApi';
import type { PartRow, PartSearchParams, QuoteDraftItem } from '../types';

const selfQuoteCategories = [
  { label: '셀프 견적', value: '' },
  { label: 'CPU', value: 'CPU' },
  { label: '메인보드', value: 'MOTHERBOARD' },
  { label: 'RAM', value: 'RAM' },
  { label: 'GPU', value: 'GPU' },
  { label: 'SSD', value: 'STORAGE' },
  { label: '파워', value: 'PSU' },
  { label: '케이스', value: 'CASE' },
  { label: '쿨러', value: 'COOLER' }
];

const PAGE_SIZE = 20;

export function SelfQuotePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [category, setCategory] = useState<string>(() => normalizeCategory(searchParams.get('category')));
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<PartSearchParams['sort']>('category');
  const [page, setPage] = useState(() => normalizePage(searchParams.get('page')));
  const hasToken = Boolean(getToken());
  const { data, isError, isLoading } = useQuery({
    queryKey: ['parts', 'self-quote', category, query, sort, page],
    queryFn: () => listParts({ category, q: query, page, size: PAGE_SIZE, sort }),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true
  });
  const { data: quoteDraft, isError: isQuoteDraftError, isLoading: isQuoteDraftLoading } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft,
    enabled: hasToken
  });
  const addMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => putQuoteDraftItem(partId, quantity),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
  });
  const deleteMutation = useMutation({
    mutationFn: (partId: string) => deleteQuoteDraftItem(partId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
  });
  const quantityMutation = useMutation({
    mutationFn: ({ partId, quantity }: { partId: string; quantity: number }) => patchQuoteDraftItem(partId, quantity),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] })
  });
  const parts = data?.items ?? [];
  const total = data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const fromIndex = total === 0 ? 0 : safePage * PAGE_SIZE + 1;
  const toIndex = total === 0 ? 0 : Math.min((safePage + 1) * PAGE_SIZE, total);
  const draftItems = quoteDraft?.items ?? [];
  const selectedTotal = quoteDraft?.totalPrice ?? 0;
  const selectedPartIds = new Set(draftItems.map((part) => part.partId));

  useEffect(() => {
    const nextCategory = normalizeCategory(searchParams.get('category'));
    setCategory((current) => current === nextCategory ? current : nextCategory);
    const nextPage = normalizePage(searchParams.get('page'));
    setPage((current) => current === nextPage ? current : nextPage);
  }, [searchParams]);

  const selectCategory = (nextCategory: string) => {
    const normalizedCategory = normalizeCategory(nextCategory);
    setCategory(normalizedCategory);
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (normalizedCategory) {
        nextParams.set('category', normalizedCategory);
      } else {
        nextParams.delete('category');
      }
      nextParams.delete('page');
      return nextParams;
    });
  };

  const updateQuery = (nextQuery: string) => {
    setQuery(nextQuery);
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('page');
      return nextParams;
    });
  };

  const updateSort = (nextSort: PartSearchParams['sort']) => {
    setSort(nextSort);
    setPage(0);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      nextParams.delete('page');
      return nextParams;
    });
  };

  const movePage = useCallback((nextPage: number) => {
    const normalizedPage = Math.min(Math.max(nextPage, 0), totalPages - 1);
    setPage(normalizedPage);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (normalizedPage === 0) {
        nextParams.delete('page');
      } else {
        nextParams.set('page', String(normalizedPage));
      }
      return nextParams;
    });
  }, [setSearchParams, totalPages]);

  useEffect(() => {
    if (!data || page === safePage) {
      return;
    }
    movePage(safePage);
  }, [data, movePage, page, safePage]);

  const addPart = (part: PartRow) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    addMutation.mutate({ partId: part.id, quantity: 1 });
  };

  const removePart = (partId: string) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    deleteMutation.mutate(partId);
  };

  const updateQuantity = (partId: string, quantity: number) => {
    if (!hasToken) {
      navigate(`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`);
      return;
    }
    quantityMutation.mutate({ partId, quantity });
  };

  return (
    <Screen>
      <div className="grid grid-cols-[216px_1fr_300px] gap-5">
        <CategorySidebar items={selfQuoteCategories} activeValue={category} onSelect={selectCategory} />
        <Panel title={categoryLabel(category)} subtitle="CPU/GPU/메인보드/파워/케이스/쿨러는 교체 저장, RAM/SSD는 여러 상품 추가가 가능합니다.">
          <div className="mb-4 grid grid-cols-[1fr_160px_140px] gap-3">
            <input value={query} onChange={(event) => updateQuery(event.target.value)} placeholder="부품명, 제조사, 사양 검색" className="rounded border border-slate-300 px-3 py-2 text-sm" />
            <select aria-label="정렬 기준" value={sort} onChange={(event) => updateSort(event.target.value as PartSearchParams['sort'])} className="rounded border border-slate-300 px-3 py-2 text-sm">
              <option value="category">카테고리순</option>
              <option value="price_asc">가격 낮은순</option>
              <option value="price_desc">가격 높은순</option>
              <option value="name">이름순</option>
            </select>
            <button type="button" onClick={() => { selectCategory(''); updateQuery(''); }} className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 hover:border-brand-blue hover:text-brand-blue">
              전체 보기
            </button>
          </div>
          {isLoading ? <div className="rounded border border-slate-200 p-5 text-sm text-slate-500">부품 목록을 불러오는 중입니다.</div> : null}
          {isError ? <div className="rounded border border-orange-200 bg-orange-50 p-5 text-sm text-orange-700">부품 목록 API를 불러오지 못했습니다.</div> : null}
          {!isLoading && !isError ? (
            <>
              <div className="mb-3 flex items-center justify-between text-xs text-slate-500">
                <span>{total.toLocaleString()}개 중 {fromIndex.toLocaleString()}-{toIndex.toLocaleString()}개 표시</span>
                <span>페이지 {safePage + 1} / {totalPages}</span>
              </div>
              <DataTable columns={['product', 'manufacturer', 'supplier', 'price', 'action']} rows={partRows(parts, selectedPartIds, addPart)} />
              <div className="mt-4 flex items-center justify-end gap-2">
                <button
                  type="button"
                  onClick={() => movePage(safePage - 1)}
                  disabled={safePage === 0}
                  className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                >
                  이전
                </button>
                <button
                  type="button"
                  onClick={() => movePage(safePage + 1)}
                  disabled={safePage >= totalPages - 1}
                  className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 disabled:cursor-not-allowed disabled:border-slate-200 disabled:text-slate-300"
                >
                  다음
                </button>
              </div>
            </>
          ) : null}
        </Panel>
        <Panel title="내 견적 / 검증">
          <MetricCard label="견적 합계" value={`${selectedTotal.toLocaleString()}원`} />
          <div className="mt-4 space-y-2">
            {!hasToken ? (
              <div className="rounded border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                로그인하면 제품 상세와 목록에서 담은 부품이 서버 견적초안에 저장됩니다.
                <Link to={`/login?redirect=${encodeURIComponent(`${location.pathname}${location.search}`)}`} className="mt-3 block rounded bg-brand-blue px-3 py-2 text-center text-xs font-bold text-white">
                  로그인하고 견적 담기
                </Link>
              </div>
            ) : isQuoteDraftLoading ? (
              <div className="rounded border border-slate-200 p-4 text-sm text-slate-500">내 견적초안을 불러오는 중입니다.</div>
            ) : isQuoteDraftError ? (
              <div className="rounded border border-orange-200 bg-orange-50 p-4 text-sm text-orange-700">견적초안 API를 불러오지 못했습니다.</div>
            ) : draftItems.length === 0 ? (
              <div className="rounded border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.
              </div>
            ) : draftItems.map((part) => (
              <div key={part.partId} className="rounded border border-slate-200 bg-white p-3 text-xs">
                <div className="mb-1 font-bold text-slate-900">{part.category}</div>
                <div className="text-slate-700">{part.name}</div>
                <div className="mt-1 text-slate-500">수량 {part.quantity}개</div>
                <PriceTrendBadge partId={part.partId} />
                <div className="mt-2 flex items-center justify-between gap-2">
                  <span className="font-bold text-brand-blue">{part.lineTotal.toLocaleString()}원</span>
                  <div className="flex items-center gap-2">
                    {allowsQuantity(part.category) ? <DraftQuantityStepper item={part} onChange={updateQuantity} disabled={quantityMutation.isPending} /> : null}
                    <button type="button" aria-label={`${part.name} 견적에서 제거`} onClick={() => removePart(part.partId)} className="rounded border border-slate-300 px-2 py-1 font-bold text-slate-600 hover:border-orange-400 hover:text-orange-600">
                      빼기
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 space-y-3">
            <button className="w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white">Tool 검증하기</button>
            <Link to="/builds/00000000-0000-4000-8000-000000002001" className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">추천 결과로 보기</Link>
          </div>
        </Panel>
      </div>
    </Screen>
  );
}

function partRows(parts: PartRow[], selectedPartIds: Set<string>, onAddPart: (part: PartRow) => void) {
  return parts.map((part) => ({
    product: <PartProductCell part={part} />,
    manufacturer: part.manufacturer ?? '-',
    supplier: <SupplierCell part={part} />,
    price: `${part.price.toLocaleString()}원`,
    action: (
      <button
        type="button"
        aria-label={`${part.name} 견적 담기`}
        disabled={selectedPartIds.has(part.id)}
        onClick={() => onAddPart(part)}
        className="rounded bg-brand-blue px-3 py-1.5 text-xs font-bold text-white disabled:bg-slate-300"
      >
        {selectedPartIds.has(part.id) ? '담김' : '담기'}
      </button>
    )
  }));
}

function PriceTrendBadge({ partId }: { partId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['parts', partId, 'price-history', 'NAVER_SHOPPING_SEARCH'],
    queryFn: () => getPartPriceHistory(partId, { days: 3650, source: 'NAVER_SHOPPING_SEARCH', limit: 60 })
  });
  if (isLoading) {
    return <div className="mt-2 text-[11px] text-slate-400">가격 기록 확인 중</div>;
  }
  if (isError || !data) {
    return <div className="mt-2 text-[11px] text-slate-400">가격 기록 없음</div>;
  }
  const sampleCount = data.summary.sampleCount;
  if (sampleCount < 2) {
    return <div className="mt-2 text-[11px] text-slate-500">가격 기록 {sampleCount}개</div>;
  }
  const change = data.summary.changeAmount;
  const tone = change > 0 ? 'text-orange-700' : change < 0 ? 'text-emerald-700' : 'text-slate-500';
  const sign = change > 0 ? '+' : '';
  return (
    <div className={`mt-2 text-[11px] font-bold ${tone}`}>
      {sampleCount}회 기록 · {sign}{change.toLocaleString()}원 ({sign}{data.summary.changeRatePercent.toFixed(2)}%)
    </div>
  );
}

function DraftQuantityStepper({ item, onChange, disabled }: { item: QuoteDraftItem; onChange: (partId: string, quantity: number) => void; disabled: boolean }) {
  return (
    <div className="flex h-7 overflow-hidden rounded border border-slate-300" aria-label={`${item.name} 수량 선택`}>
      <button
        type="button"
        aria-label={`${item.name} 수량 감소`}
        disabled={disabled || item.quantity <= 1}
        onClick={() => onChange(item.partId, item.quantity - 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        -
      </button>
      <div className="flex w-8 items-center justify-center border-x border-slate-300 text-[11px] font-bold text-slate-900">{item.quantity}</div>
      <button
        type="button"
        aria-label={`${item.name} 수량 증가`}
        disabled={disabled || item.quantity >= 9}
        onClick={() => onChange(item.partId, item.quantity + 1)}
        className="w-7 bg-slate-50 text-sm font-bold text-slate-600 disabled:text-slate-300"
      >
        +
      </button>
    </div>
  );
}

function allowsQuantity(category: string) {
  return category === 'RAM' || category === 'STORAGE';
}

function PartProductCell({ part }: { part: PartRow }) {
  return (
    <div className="flex min-w-[260px] items-center gap-3">
      <Link to={`/parts/${part.id}`} aria-label={`${part.name} 상세 보기`}>
        <img
          src={partImageUrl(part)}
          alt={`${part.name} 제품 사진`}
          className="h-14 w-14 rounded border border-slate-200 bg-slate-100 object-cover hover:border-brand-blue"
        />
      </Link>
      <div>
        <Link to={`/parts/${part.id}`} className="font-bold text-slate-900 hover:text-brand-blue hover:underline">{part.name}</Link>
        <div className="mt-1 text-[11px] text-slate-500">{partShortSpec(part)}</div>
      </div>
    </div>
  );
}

function SupplierCell({ part }: { part: PartRow }) {
  const supplierName = part.externalOffer?.supplierName;
  const offerUrl = part.externalOffer?.offerUrl;
  if (!supplierName) {
    return '-';
  }
  if (!offerUrl) {
    return supplierName;
  }
  return (
    <a href={offerUrl} target="_blank" rel="noreferrer" className="font-bold text-brand-blue hover:underline">
      {supplierName}
    </a>
  );
}

function categoryLabel(category: string) {
  if (!category) {
    return '셀프 견적 / 전체 부품 목록';
  }
  const item = selfQuoteCategories.find((entry) => entry.value === category);
  return item ? `${item.label} 부품 목록` : '셀프 견적 / 전체 부품 목록';
}

function normalizeCategory(category: string | null) {
  return selfQuoteCategories.some((entry) => entry.value === category) ? category ?? '' : '';
}

function normalizePage(page: string | null) {
  if (!page) {
    return 0;
  }
  const parsed = Number.parseInt(page, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}
