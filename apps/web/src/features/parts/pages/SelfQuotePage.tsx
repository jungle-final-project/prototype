import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CategorySidebar, DataTable, MetricCard, Panel, Screen, StatusBadge } from '../../../components/ui';
import { listParts } from '../partsApi';
import type { PartRow, PartSearchParams } from '../types';

const selfQuoteCategories = [
  { label: '셀프 견적', value: '' },
  { label: 'CPU', value: 'CPU' },
  { label: '메인보드', value: 'MOTHERBOARD' },
  { label: 'RAM', value: 'RAM' },
  { label: 'GPU', value: 'GPU' },
  { label: 'SSD', value: 'STORAGE' },
  { label: '파워', value: 'PSU' },
  { label: '케이스', value: 'CASE' }
];

export function SelfQuotePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [category, setCategory] = useState<string>(() => normalizeCategory(searchParams.get('category')));
  const [query, setQuery] = useState('');
  const [sort, setSort] = useState<PartSearchParams['sort']>('category');
  const [selectedParts, setSelectedParts] = useState<PartRow[]>([]);
  const { data, isError, isLoading } = useQuery({
    queryKey: ['parts', 'self-quote', category, query, sort],
    queryFn: () => listParts({ category, q: query, size: 100, sort })
  });
  const parts = data?.items ?? [];
  const selectedTotal = selectedParts.reduce((sum, part) => sum + part.price, 0);
  const selectedPartIds = new Set(selectedParts.map((part) => part.id));

  useEffect(() => {
    const nextCategory = normalizeCategory(searchParams.get('category'));
    setCategory((current) => current === nextCategory ? current : nextCategory);
  }, [searchParams]);

  const selectCategory = (nextCategory: string) => {
    const normalizedCategory = normalizeCategory(nextCategory);
    setCategory(normalizedCategory);
    setSearchParams((current) => {
      const nextParams = new URLSearchParams(current);
      if (normalizedCategory) {
        nextParams.set('category', normalizedCategory);
      } else {
        nextParams.delete('category');
      }
      return nextParams;
    });
  };

  const addPart = (part: PartRow) => {
    setSelectedParts((current) => current.some((item) => item.id === part.id) ? current : [...current, part]);
  };

  const removePart = (partId: string) => {
    setSelectedParts((current) => current.filter((part) => part.id !== partId));
  };

  return (
    <Screen>
      <div className="grid grid-cols-[216px_1fr_300px] gap-5">
        <CategorySidebar items={selfQuoteCategories} activeValue={category} onSelect={selectCategory} />
        <Panel title={categoryLabel(category)} subtitle="왼쪽 카테고리를 누르면 내부 부품 DB 후보가 여기에 나열됩니다.">
          <div className="mb-4 grid grid-cols-[1fr_160px_140px] gap-3">
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="부품명, 제조사, 사양 검색" className="rounded border border-slate-300 px-3 py-2 text-sm" />
            <select aria-label="정렬 기준" value={sort} onChange={(event) => setSort(event.target.value as PartSearchParams['sort'])} className="rounded border border-slate-300 px-3 py-2 text-sm">
              <option value="category">카테고리순</option>
              <option value="price_asc">가격 낮은순</option>
              <option value="price_desc">가격 높은순</option>
              <option value="name">이름순</option>
            </select>
            <button type="button" onClick={() => { selectCategory(''); setQuery(''); }} className="rounded border border-slate-300 px-3 py-2 text-sm font-bold text-slate-700 hover:border-brand-blue hover:text-brand-blue">
              전체 보기
            </button>
          </div>
          {isLoading ? <div className="rounded border border-slate-200 p-5 text-sm text-slate-500">부품 목록을 불러오는 중입니다.</div> : null}
          {isError ? <div className="rounded border border-orange-200 bg-orange-50 p-5 text-sm text-orange-700">부품 목록 API를 불러오지 못했습니다.</div> : null}
          {!isLoading && !isError ? (
            <DataTable columns={['product', 'manufacturer', 'supplier', 'price', 'status', 'score', 'action']} rows={partRows(parts, selectedPartIds, addPart)} />
          ) : null}
        </Panel>
        <Panel title="내 견적 / 검증">
          <MetricCard label="견적 합계" value={`${selectedTotal.toLocaleString()}원`} />
          <div className="mt-4 space-y-2">
            {selectedParts.length === 0 ? (
              <div className="rounded border border-dashed border-slate-300 p-4 text-sm text-slate-500">
                왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.
              </div>
            ) : selectedParts.map((part) => (
              <div key={part.id} className="rounded border border-slate-200 bg-white p-3 text-xs">
                <div className="mb-1 font-bold text-slate-900">{part.category}</div>
                <div className="text-slate-700">{part.name}</div>
                <div className="mt-2 flex items-center justify-between">
                  <span className="font-bold text-brand-blue">{part.price.toLocaleString()}원</span>
                  <button type="button" aria-label={`${part.name} 견적에서 제거`} onClick={() => removePart(part.id)} className="rounded border border-slate-300 px-2 py-1 font-bold text-slate-600 hover:border-orange-400 hover:text-orange-600">
                    빼기
                  </button>
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
    status: <StatusBadge status={part.status} />,
    score: formatScore(part.benchmarkSummary?.score),
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

function PartProductCell({ part }: { part: PartRow }) {
  return (
    <div className="flex min-w-[260px] items-center gap-3">
      <img
        src={partImageUrl(part)}
        alt={`${part.name} 제품 사진`}
        className="h-14 w-14 rounded border border-slate-200 bg-slate-100 object-cover"
      />
      <div>
        <div className="font-bold text-slate-900">{part.name}</div>
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

function formatScore(score?: number | string) {
  if (score === undefined || score === null) {
    return '-';
  }
  return typeof score === 'number' ? score.toFixed(1) : score;
}

function partShortSpec(part: PartRow) {
  const shortSpec = part.attributes?.shortSpec;
  return typeof shortSpec === 'string' ? shortSpec : part.category;
}

function partImageUrl(part: PartRow) {
  const imageUrl = part.externalOffer?.imageUrl ?? part.attributes?.imageUrl;
  if (typeof imageUrl === 'string' && imageUrl.trim()) {
    return imageUrl;
  }

  const label = part.category === 'STORAGE' ? 'SSD' : part.category;
  const accent = categoryAccent(part.category);
  const svg = `
    <svg xmlns="http://www.w3.org/2000/svg" width="112" height="112" viewBox="0 0 112 112">
      <rect width="112" height="112" rx="14" fill="#f8fafc"/>
      <rect x="12" y="20" width="88" height="56" rx="10" fill="${accent}" opacity="0.92"/>
      <rect x="20" y="28" width="72" height="40" rx="6" fill="#ffffff" opacity="0.16"/>
      <text x="56" y="54" text-anchor="middle" font-family="Arial, sans-serif" font-size="18" font-weight="700" fill="#ffffff">${label}</text>
      <rect x="24" y="84" width="64" height="6" rx="3" fill="#cbd5e1"/>
    </svg>
  `;
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`;
}

function categoryAccent(category: string) {
  switch (category) {
    case 'CPU':
      return '#2563eb';
    case 'MOTHERBOARD':
      return '#475569';
    case 'RAM':
      return '#16a34a';
    case 'GPU':
      return '#7c3aed';
    case 'STORAGE':
      return '#0891b2';
    case 'PSU':
      return '#ca8a04';
    case 'CASE':
      return '#dc2626';
    default:
      return '#334155';
  }
}
