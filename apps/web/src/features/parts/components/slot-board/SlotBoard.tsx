import { useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import type { BuildGraphResolveResponse, PartCategory } from '../../../quote/aiSelection';
import { partImageUrl, partShortSpec } from '../../partDisplay';
import type { QuoteDraftItem } from '../../types';
import {
  FALLBACK_EDGES,
  SLOT_CONFIGS,
  isMultiItemCategory,
  isSlotBoardPercentPosition,
  isSlotCategory,
  slotConfigFor,
  slotLayoutWithPosition,
  type SlotBoardPosition,
  type SlotConfig,
  type SlotEdgeConfig
} from './slotBoardConfig';

type SlotBoardProps = {
  items: QuoteDraftItem[];
  selectedCategory: PartCategory | null;
  nextCategory?: PartCategory | null;
  onSlotSelect: (category: PartCategory) => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
  graph?: BuildGraphResolveResponse;
};

export function SlotBoard({ items, selectedCategory, nextCategory, onSlotSelect, onRemoveItem, isRemovePending, graph }: SlotBoardProps) {
  const statusByCategory = partStatusByCategory(graph);
  const slotPositions = useMemo(() => slotPositionsFromGraph(graph), [graph]);
  return (
    <div className="panel overflow-hidden">
      {/* 보드 헤더: 제목 + 호환 상태 범례(초록/노랑/빨강/회색) */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-commerce-line px-4 py-2.5">
        <span className="text-xs font-black text-slate-700">구성 관계도 — 부품 간 호환 상태</span>
        <div className="flex items-center gap-3 text-[10px] font-bold text-slate-500">
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#16a34a" strokeWidth="3" /></svg>
            호환 가능
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#d97706" strokeWidth="3" /></svg>
            주의
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#ef4444" strokeWidth="3" strokeDasharray="6 4" /></svg>
            장착 불가
          </span>
          <span className="flex items-center gap-1.5">
            <svg width="20" height="4" viewBox="0 0 20 4" aria-hidden="true"><line x1="0" y1="2" x2="20" y2="2" stroke="#94a3b8" strokeWidth="2" strokeDasharray="4 3" /></svg>
            미장착
          </span>
        </div>
      </div>
      {/* 보드 본체 — 메인보드 허브 방사형: 중앙 허브에서 스포크가 뻗고 크로스 관계는 외곽 곡선 */}
      <div
        data-testid="slot-board"
        data-visual-mode="motherboard"
        className="relative flex flex-col gap-2 bg-slate-50/60 p-3 lg:block lg:aspect-[16/10] lg:overflow-hidden lg:bg-[#f8fbff] lg:p-4"
      >
        <SlotBoardEdges items={items} graph={graph} slotPositions={slotPositions} selectedCategory={selectedCategory} />
        {SLOT_CONFIGS.map((slot) => (
          <BoardSlot
            key={slot.category}
            slot={slot}
            layout={slotLayoutWithPosition(slot, slotPositions[slot.category])}
            items={items.filter((item) => item.category === slot.category)}
            problemStatus={statusByCategory.get(slot.category)}
            isSelected={selectedCategory === slot.category}
            isNext={nextCategory === slot.category}
            onSelect={() => onSlotSelect(slot.category)}
            onRemoveItem={onRemoveItem}
            isRemovePending={isRemovePending}
          />
        ))}
      </div>
    </div>
  );
}

function BoardSlot({
  slot,
  layout,
  items,
  problemStatus,
  isSelected,
  isNext,
  onSelect,
  onRemoveItem,
  isRemovePending
}: {
  slot: SlotConfig;
  layout: SlotConfig['layout'];
  items: QuoteDraftItem[];
  problemStatus?: 'PASS' | 'WARN' | 'FAIL';
  isSelected: boolean;
  isNext: boolean;
  onSelect: () => void;
  onRemoveItem: (partId: string) => void;
  isRemovePending: boolean;
}) {
  const filled = items.length > 0;
  const primaryItem = items[0];
  const lineTotal = items.reduce((sum, item) => sum + item.lineTotal, 0);
  // 문제 상태는 장착된 슬롯에만 표시한다. 숨기지 않고 강조한다.
  const slotStatus = filled ? problemStatus ?? 'PASS' : 'NONE';
  // 메인보드는 방사형의 중앙 허브 — 모든 스포크가 모이는 기준점이라 시각적으로 구분한다.
  const isHub = slot.category === 'MOTHERBOARD';
  const isFlashing = useAttachFlash(items);
  const layoutVars: CSSProperties = {
    ['--sx' as string]: `${layout.x}%`,
    ['--sy' as string]: `${layout.y}%`,
    ['--sw' as string]: `${layout.w}%`,
    ['--sh' as string]: `${layout.h}%`
  };
  const borderClass = isSelected
    ? 'border-2 border-brand-blue ring-2 ring-blue-100 shadow-lg'
    : slotStatus === 'FAIL'
      ? 'border-2 border-red-500 ring-2 ring-red-50'
      : slotStatus === 'WARN'
        ? 'border-2 border-amber-400 ring-2 ring-amber-50'
        : filled
          ? isHub
            ? 'border-2 border-slate-300 shadow-md hover:border-slate-400'
            : 'border border-emerald-200 hover:border-emerald-400 shadow-sm'
          : isNext
            ? 'border-2 border-brand-blue bg-blue-50/40 hover:border-blue-600'
            : 'border border-dashed border-slate-300 bg-white/75 hover:border-brand-blue';
  const visibleName = filled
    ? items.length > 1 ? `${primaryItem.name} 외 ${items.length - 1}개` : primaryItem.name
    : '';
  const visibleSpec = filled ? partShortSpec(primaryItem) : '';

  return (
    <div
      data-testid={`slot-${slot.category}`}
      data-selected={isSelected ? 'true' : 'false'}
      data-status={slotStatus}
      data-flash={isFlashing ? 'true' : 'false'}
      style={layoutVars}
      data-next={isNext ? 'true' : 'false'}
      className={`group relative z-20 rounded-lg bg-white/95 p-2 text-left transition backdrop-blur-[1px] lg:absolute lg:left-[var(--sx)] lg:top-[var(--sy)] lg:h-[var(--sh)] lg:w-[var(--sw)] ${borderClass} ${
        isFlashing ? 'slot-attach-flash' : ''
      } ${isNext && !isSelected ? 'slot-empty-pulse' : ''}`}
    >
      <button
        type="button"
        aria-label={`${slot.label} 슬롯 열기`}
        aria-pressed={isSelected}
        onClick={onSelect}
        className="absolute inset-0 z-0 h-full w-full rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-blue"
      />
      <div className="pointer-events-none relative z-10 flex h-full flex-col gap-1 overflow-hidden">
        {/* 카드 헤더: 아이콘 + 카테고리명 + 상태 배지 */}
        <div className="flex items-center justify-between gap-1">
          <span className="flex items-center gap-1 text-[10px] font-black text-slate-600">
            <img src={slot.glyph} alt="" aria-hidden="true" className={`h-4 w-4 shrink-0 ${filled ? 'opacity-70' : 'opacity-35'}`} />
            {slot.label}
          </span>
          {slotStatus === 'FAIL' ? (
            <span className="rounded border border-red-200 bg-red-50 px-1 py-0.5 text-[9px] font-black text-red-700">안 맞음</span>
          ) : slotStatus === 'WARN' ? (
            <span className="rounded border border-amber-200 bg-amber-50 px-1 py-0.5 text-[9px] font-black text-amber-700">간섭 주의</span>
          ) : filled ? (
            <span className="rounded border border-emerald-200 bg-emerald-50 px-1 py-0.5 text-[9px] font-black text-emerald-700">호환 가능</span>
          ) : isNext ? (
            <span className="rounded border border-blue-200 bg-blue-50 px-1 py-0.5 text-[9px] font-black text-brand-blue">다음 선택</span>
          ) : null}
        </div>
        {/* 카드 본체 */}
        {filled ? (
          <>
            <div className="flex min-h-0 flex-1 gap-2 overflow-hidden">
              <img
                data-testid="slot-part-image"
                src={partImageUrl(primaryItem)}
                alt=""
                aria-hidden="true"
                className="hidden h-12 w-14 shrink-0 rounded border border-slate-200 bg-slate-50 object-contain lg:block"
              />
              <div className="min-w-0 flex-1">
                <div className="line-clamp-2 text-[11px] font-black leading-[1.32] text-commerce-ink">
                  {visibleName}
                </div>
                {visibleSpec ? (
                  <div className="mt-0.5 line-clamp-1 text-[10px] font-bold leading-4 text-slate-500">
                    {visibleSpec}
                  </div>
                ) : null}
              </div>
            </div>
            <div className="mt-auto flex items-end justify-between gap-1">
              <span className="text-[11px] font-black text-brand-blue">{lineTotal.toLocaleString()}원</span>
              <div className="flex items-center gap-1">
                {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
                {!isMultiItemCategory(slot.category) ? (
                  <button
                    type="button"
                    aria-label={`${primaryItem.name} 견적에서 제거`}
                    disabled={isRemovePending}
                    onClick={() => onRemoveItem(primaryItem.partId)}
                    className="pointer-events-auto rounded border border-commerce-line bg-white px-1.5 py-0.5 text-[9px] font-black text-slate-400 opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-commerce-sale hover:text-commerce-sale disabled:cursor-wait"
                  >
                    빼기
                  </button>
                ) : null}
              </div>
            </div>
          </>
        ) : (
          <div className="flex flex-1 items-center justify-start gap-1">
            <span className="text-[11px] font-black text-brand-blue">+ 부품 선택</span>
            {slot.miniSlots ? <MiniSlotRow slot={slot} items={items} /> : null}
          </div>
        )}
      </div>
    </div>
  );
}

// 장착/교체로 슬롯 구성이 바뀌면 잠깐 flash 상태를 켠다. 애니메이션 자체는 CSS가 담당하고
// prefers-reduced-motion에서는 CSS에서 꺼진다.
function useAttachFlash(items: QuoteDraftItem[]) {
  const signature = items.map((item) => `${item.partId}:${item.quantity}`).join('|');
  const previousSignature = useRef<string | null>(null);
  const [isFlashing, setIsFlashing] = useState(false);

  useEffect(() => {
    const previous = previousSignature.current;
    previousSignature.current = signature;
    if (previous === null || previous === signature || signature === '') {
      return;
    }
    setIsFlashing(true);
    const timer = window.setTimeout(() => setIsFlashing(false), 900);
    return () => window.clearTimeout(timer);
  }, [signature]);

  return isFlashing;
}

type SlotEdgeStatus = 'PASS' | 'WARN' | 'FAIL' | 'PENDING' | 'BASE';

type ResolvedSlotEdge = {
  config: SlotEdgeConfig;
  status: SlotEdgeStatus;
  label: string;
  summary?: string;
};

// 호환 상태 색 체계: 정상 = 초록, 주의 = 노랑, 불가 = 빨강, 미장착 = 회색 (전 화면 공통 규칙)
const EDGE_STROKES: Record<SlotEdgeStatus, { stroke: string; dash?: string }> = {
  PASS: { stroke: '#16a34a' },
  WARN: { stroke: '#d97706' },
  FAIL: { stroke: '#ef4444', dash: '6 4' },
  PENDING: { stroke: '#94a3b8', dash: '4 4' },
  BASE: { stroke: '#16a34a' }
};

const EDGE_LABEL_CLASSES: Record<SlotEdgeStatus, string> = {
  PASS: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  WARN: 'border-amber-200 bg-amber-50 text-amber-700',
  FAIL: 'border-red-200 bg-red-50 text-red-700',
  PENDING: 'border-slate-200 bg-white text-slate-400',
  BASE: 'border-slate-200 bg-white text-slate-600'
};

// 기본 topology 관계선은 graph API 없이 항상 렌더링되고,
// graph 응답이 있으면 카테고리 쌍이 일치하는 edge의 라벨/상태만 덧입힌다.
function SlotBoardEdges({
  items,
  graph,
  slotPositions,
  selectedCategory
}: {
  items: QuoteDraftItem[];
  graph?: BuildGraphResolveResponse;
  slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>;
  selectedCategory: PartCategory | null;
}) {
  const filledCategories = new Set(items.map((item) => item.category));
  const edges: ResolvedSlotEdge[] = FALLBACK_EDGES.map((config) => {
    if (!filledCategories.has(config.from) || !filledCategories.has(config.to)) {
      return { config, status: 'PENDING', label: config.label, summary: '부품 선택 후 계산됩니다.' };
    }
    const graphEdge = findGraphEdge(graph, config.from, config.to);
    if (graphEdge) {
      return {
        config,
        status: graphEdge.status,
        label: graphEdge.label || config.label,
        summary: graphEdge.summary
      };
    }
    return { config, status: 'BASE', label: config.label };
  });

  return (
    <div data-testid="slot-board-edges" aria-hidden="true" className="pointer-events-none absolute inset-0 z-10 hidden lg:block">
      <svg viewBox="0 0 100 100" preserveAspectRatio="none" className="h-full w-full">
        {edges.map((edge) => {
          const { path, start, end } = edgeGeometry(edge.config, slotPositions);
          const style = EDGE_STROKES[edge.status];
          const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
          return (
            <g key={`${edge.config.from}-${edge.config.to}`}>
              <path
                d={path}
                fill="none"
                stroke={style.stroke}
                strokeWidth={isHighlighted ? 4.5 : 3}
                strokeDasharray={style.dash}
                strokeOpacity={isHighlighted ? 1 : 0.6}
                strokeLinecap="round"
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
              />
              <circle cx={start.x} cy={start.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
              <circle cx={end.x} cy={end.y} r={0.7} fill={style.stroke} fillOpacity={isHighlighted ? 1 : 0.6} />
            </g>
          );
        })}
      </svg>
      {edges.map((edge) => {
        const { label } = edgeGeometry(edge.config, slotPositions);
        const isHighlighted = selectedCategory === edge.config.from || selectedCategory === edge.config.to;
        return (
          <span
            key={`label-${edge.config.from}-${edge.config.to}`}
            data-testid={`slot-edge-${edge.config.from}-${edge.config.to}`}
            data-status={edge.status}
            title={edge.summary}
            style={{ left: `${label.x}%`, top: `${label.y}%`, opacity: isHighlighted ? 1 : 0.8 }}
            className={`absolute -translate-x-1/2 -translate-y-1/2 whitespace-nowrap rounded-full border px-1.5 py-0.5 text-[9px] font-black shadow-sm ${EDGE_LABEL_CLASSES[edge.status]}`}
          >
            {edge.label}
          </span>
        );
      })}
    </div>
  );
}

type Box = { x: number; y: number; w: number; h: number };
type Point = { x: number; y: number };

const BOARD_CENTER: Point = { x: 50, y: 50 };

function boxCenter(box: Box): Point {
  return { x: box.x + box.w / 2, y: box.y + box.h / 2 };
}

// 카드 중심에서 target 방향으로 나가는 광선이 카드 테두리와 만나는 점 — 선이 카드 밖에서 시작하게 한다.
function boxAnchorToward(box: Box, target: Point): Point {
  const center = boxCenter(box);
  const dx = target.x - center.x;
  const dy = target.y - center.y;
  if (dx === 0 && dy === 0) {
    return center;
  }
  const scaleX = dx === 0 ? Number.POSITIVE_INFINITY : (box.w / 2) / Math.abs(dx);
  const scaleY = dy === 0 ? Number.POSITIVE_INFINITY : (box.h / 2) / Math.abs(dy);
  const scale = Math.min(scaleX, scaleY);
  return { x: center.x + dx * scale, y: center.y + dy * scale };
}

// 허브 방사형 지오메트리: 허브(메인보드) 스포크는 중심을 향한 직선, 크로스 관계는
// 설정된 곡률(bow)만큼 보드 바깥(+)/안쪽(-)으로 휘는 곡선. 라벨은 실제 선의 중앙에 둔다.
function edgeGeometry(config: SlotEdgeConfig, slotPositions: Partial<Record<PartCategory, SlotBoardPosition>>) {
  const fromSlot = slotConfigFor(config.from);
  const toSlot = slotConfigFor(config.to);
  const a: Box = fromSlot ? slotLayoutWithPosition(fromSlot, slotPositions[config.from]) : { x: 0, y: 0, w: 0, h: 0 };
  const b: Box = toSlot ? slotLayoutWithPosition(toSlot, slotPositions[config.to]) : { x: 0, y: 0, w: 0, h: 0 };
  const ac = boxCenter(a);
  const bc = boxCenter(b);
  const isSpoke = config.from === 'MOTHERBOARD' || config.to === 'MOTHERBOARD';

  const labelT = config.labelT ?? 0.5;
  if (isSpoke || !config.bow) {
    const start = boxAnchorToward(a, bc);
    const end = boxAnchorToward(b, ac);
    return {
      path: `M ${start.x} ${start.y} L ${end.x} ${end.y}`,
      start,
      end,
      label: { x: start.x + (end.x - start.x) * labelT, y: start.y + (end.y - start.y) * labelT }
    };
  }

  // 크로스 곡선: 두 중심의 중점을 보드 중앙 기준 바깥/안쪽으로 bow만큼 밀어 제어점을 만든다.
  const mid = { x: (ac.x + bc.x) / 2, y: (ac.y + bc.y) / 2 };
  const outward = { x: mid.x - BOARD_CENTER.x, y: mid.y - BOARD_CENTER.y };
  const outwardLength = Math.hypot(outward.x, outward.y) || 1;
  const control = {
    x: mid.x + (outward.x / outwardLength) * config.bow,
    y: mid.y + (outward.y / outwardLength) * config.bow
  };
  const start = boxAnchorToward(a, control);
  const end = boxAnchorToward(b, control);
  // 2차 베지어의 t 지점 — 라벨을 곡선 위에 정확히 얹는다.
  const inv = 1 - labelT;
  return {
    path: `M ${start.x} ${start.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`,
    start,
    end,
    label: {
      x: inv * inv * start.x + 2 * inv * labelT * control.x + labelT * labelT * end.x,
      y: inv * inv * start.y + 2 * inv * labelT * control.y + labelT * labelT * end.y
    }
  };
}

function slotPositionsFromGraph(graph?: BuildGraphResolveResponse) {
  const positions: Partial<Record<PartCategory, SlotBoardPosition>> = {};
  graph?.nodes.forEach((node) => {
    const category = typeof node.category === 'string' ? node.category : null;
    if (node.type === 'PART' && isSlotCategory(category) && isSlotBoardPercentPosition(category, node.position)) {
      positions[category] = node.position;
    }
  });
  return positions;
}

function partStatusByCategory(graph?: BuildGraphResolveResponse) {
  const statusMap = new Map<string, 'PASS' | 'WARN' | 'FAIL'>();
  graph?.nodes.forEach((node) => {
    if (node.type === 'PART' && node.category) {
      statusMap.set(node.category, node.status);
    }
  });
  return statusMap;
}

function findGraphEdge(graph: BuildGraphResolveResponse | undefined, from: PartCategory, to: PartCategory) {
  if (!graph) {
    return undefined;
  }
  const categoryByNodeId = new Map(graph.nodes.map((node) => [node.id, node.category]));
  return graph.edges.find((edge) => {
    const sourceCategory = categoryByNodeId.get(edge.source);
    const targetCategory = categoryByNodeId.get(edge.target);
    return (sourceCategory === from && targetCategory === to) || (sourceCategory === to && targetCategory === from);
  });
}

// "32GB(16Gx2)" 같은 킷 상품은 스틱 2개를 차지한다. moduleCount 없는 상품은 단품(1)으로 본다.
function itemStickCount(item: QuoteDraftItem): number {
  const moduleCount = Number(item.attributes?.moduleCount);
  return item.quantity * (Number.isFinite(moduleCount) && moduleCount >= 1 ? moduleCount : 1);
}

function MiniSlotRow({ slot, items }: { slot: SlotConfig; items: QuoteDraftItem[] }) {
  const total = slot.miniSlots ?? 0;
  const fillCount = slot.miniFillBy === 'quantity'
    ? items.reduce((sum, item) => sum + itemStickCount(item), 0)
    : items.length;
  const overflow = Math.max(0, fillCount - total);

  return (
    <span className="flex items-center gap-1" aria-label={`${slot.label} 시각 슬롯 ${Math.min(fillCount, total)}/${total}`}>
      {Array.from({ length: total }).map((_, index) => (
        <span
          key={index}
          data-mini-slot-filled={index < fillCount ? 'true' : 'false'}
          className={`h-2.5 w-2.5 rounded-sm ${index < fillCount ? 'bg-brand-blue' : 'border border-dashed border-slate-300 bg-white'}`}
        />
      ))}
      {overflow > 0 ? <span className="text-[10px] font-black text-slate-500">+{overflow}</span> : null}
    </span>
  );
}
