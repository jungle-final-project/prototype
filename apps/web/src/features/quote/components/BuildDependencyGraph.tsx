import { useMemo, useState } from 'react';
import {
  Background,
  Controls,
  MarkerType,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { AlertTriangle, CheckCircle2, GitBranch, Info, Maximize2 } from 'lucide-react';
import {
  PART_CATEGORY_LABELS,
  type BuildGraphEdge,
  type BuildGraphResolveResponse,
  type BuildGraphStatus,
  type PartCategory
} from '../aiSelection';

type BuildDependencyGraphProps = {
  graph?: BuildGraphResolveResponse | null;
  isLoading?: boolean;
  isError?: boolean;
  title?: string;
  subtitle?: string;
  onCategorySelect?: (category: PartCategory) => void;
};

const categoryOrder = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'PSU', 'CASE', 'COOLER', 'STORAGE', 'PRICE'];
const categoryPositions: Record<string, { x: number; y: number }> = {
  CPU: { x: 40, y: 40 },
  MOTHERBOARD: { x: 300, y: 40 },
  RAM: { x: 560, y: 40 },
  GPU: { x: 40, y: 210 },
  PSU: { x: 300, y: 210 },
  CASE: { x: 560, y: 210 },
  COOLER: { x: 300, y: 380 },
  STORAGE: { x: 560, y: 380 },
  PRICE: { x: 40, y: 380 }
};

export function BuildDependencyGraph({
  graph,
  isLoading,
  isError,
  title = '견적 관계도',
  subtitle = '선택한 부품이 전력, 규격, 호환성에 주는 영향을 시각화합니다.',
  onCategorySelect
}: BuildDependencyGraphProps) {
  const [activeEdge, setActiveEdge] = useState<BuildGraphEdge | null>(null);
  const activeInsight = graph?.insights.find((insight) => insight.status !== 'PASS') ?? graph?.insights[0] ?? null;
  const { nodes, edges } = useMemo(() => toFlowElements(graph), [graph]);

  return (
    <section data-testid="build-dependency-graph" className="panel overflow-hidden">
      <div className="flex flex-col gap-4 border-b border-commerce-line bg-white px-5 py-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs font-black text-brand-blue">
            <GitBranch size={15} />
            Dependency graph
          </div>
          <h2 className="mt-1 text-xl font-black text-commerce-ink">{title}</h2>
          <p className="mt-1 max-w-3xl break-keep text-sm leading-6 text-slate-500">{graph?.summary ?? subtitle}</p>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-xs sm:min-w-[260px]">
          <GraphStat label="노드" value={graph?.nodes.length ?? 0} />
          <GraphStat label="관계" value={graph?.edges.length ?? 0} />
          <GraphStat label="주의" value={graph?.insights.filter((insight) => insight.status !== 'PASS').length ?? 0} tone="warn" />
        </div>
      </div>

      {isLoading ? (
        <div className="grid min-h-[320px] place-items-center p-6 text-sm font-bold text-slate-500">
          관계 그래프를 계산하는 중입니다.
        </div>
      ) : isError ? (
        <div className="m-5 rounded-lg border border-orange-200 bg-orange-50 p-5 text-sm font-bold text-orange-700">
          관계 그래프 API를 불러오지 못했습니다.
        </div>
      ) : !graph || graph.nodes.length === 0 ? (
        <div className="m-5 rounded-lg border border-dashed border-blue-200 bg-blue-50/70 p-6 text-center">
          <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-white text-brand-blue shadow-product">
            <GitBranch size={23} />
          </div>
          <h3 className="mt-3 text-base font-black text-commerce-ink">부품을 담으면 관계가 그려집니다</h3>
          <p className="mx-auto mt-2 max-w-xl break-keep text-sm leading-6 text-slate-500">
            CPU 소켓, RAM 규격, GPU 전력, 케이스 장착 제약처럼 서로 영향을 주는 조건을 한 화면에서 확인합니다.
          </p>
        </div>
      ) : (
        <div className="grid gap-0 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="min-h-[430px] border-b border-commerce-line bg-[linear-gradient(180deg,#f8fafc_0%,#ffffff_100%)] lg:border-b-0 lg:border-r">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              fitView
              fitViewOptions={{ padding: 0.22 }}
              minZoom={0.45}
              maxZoom={1.35}
              proOptions={{ hideAttribution: true }}
              onNodeClick={(_, node: Node) => {
                const category = node.data.category;
                if (typeof category === 'string' && isPartCategory(category)) {
                  onCategorySelect?.(category);
                }
              }}
              onEdgeClick={(_, edge: Edge) => {
                const graphEdge = graph.edges.find((item) => item.id === edge.id);
                setActiveEdge(graphEdge ?? null);
              }}
            >
              <Background color="#dbe4f0" gap={18} />
              <MiniMap pannable zoomable nodeColor={(node) => statusColor(String(node.data.status ?? 'PASS'))} />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
          <aside className="min-w-0 bg-white p-5">
            <div className="mb-4 flex items-center justify-between gap-3">
              <h3 className="text-sm font-black text-commerce-ink">영향 요약</h3>
              <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-600">
                <Maximize2 size={12} />
                Focused
              </span>
            </div>

            {activeEdge ? (
              <div className={`mb-4 rounded-lg border p-3 ${statusPanelTone(activeEdge.status)}`}>
                <div className="mb-1 flex items-center gap-2 text-xs font-black">
                  {statusIcon(activeEdge.status)}
                  선택한 관계
                </div>
                <div className="text-sm font-black text-commerce-ink">{activeEdge.label}</div>
                <p className="mt-1 break-keep text-xs leading-5 text-slate-600">{activeEdge.summary}</p>
              </div>
            ) : null}

            <div className="space-y-2">
              {graph.insights.map((insight) => (
                <article
                  key={insight.id}
                  className={`w-full rounded-lg border p-3 text-left ${statusPanelTone(insight.status)}`}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2 text-xs font-black">
                      {statusIcon(insight.status)}
                      {insight.status}
                    </div>
                    <span className="text-[11px] font-black text-slate-400">{insight.relatedNodeIds.length} nodes</span>
                  </div>
                  <div className="mt-2 text-sm font-black text-commerce-ink">{insight.title}</div>
                  <p className="mt-1 break-keep text-xs leading-5 text-slate-600">{insight.description}</p>
                </article>
              ))}
            </div>

            <div className="mt-4 rounded-lg border border-commerce-line bg-slate-50 p-3">
              <div className="mb-2 flex items-center gap-2 text-xs font-black text-slate-700">
                <Info size={14} />
                그래프 읽는 법
              </div>
              <p className="break-keep text-xs leading-5 text-slate-500">
                노드는 부품과 제약이고, 선은 선택이 영향을 주는 관계입니다. 노란 선은 확인 필요, 빨간 선은 교체 후보를 먼저 봐야 하는 관계입니다.
              </p>
            </div>
          </aside>
        </div>
      )}
    </section>
  );
}

function toFlowElements(graph?: BuildGraphResolveResponse | null): { nodes: Node[]; edges: Edge[] } {
  if (!graph) return { nodes: [], edges: [] };
  const focusNodeIds = new Set(graph.focusNodeIds);
  const nodes = graph.nodes.map((node, index) => {
    const category = String(node.category ?? node.id).toUpperCase();
    const basePosition = categoryPositions[category] ?? {
      x: 40 + (index % 3) * 260,
      y: 40 + Math.floor(index / 3) * 170
    };
    return {
      id: node.id,
      position: basePosition,
      data: {
        label: nodeLabel(node),
        category: node.category,
        status: node.status
      },
      className: focusNodeIds.has(node.id) ? 'buildgraph-flow-node buildgraph-flow-node--focus' : 'buildgraph-flow-node',
      style: nodeStyle(node.status, node.type === 'CONSTRAINT')
    } satisfies Node;
  });
  const edges = graph.edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    type: 'smoothstep',
    animated: edge.status !== 'PASS',
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: statusColor(edge.status)
    },
    style: {
      stroke: statusColor(edge.status),
      strokeWidth: edge.status === 'PASS' ? 2 : 3
    },
    labelStyle: {
      fill: statusColor(edge.status),
      fontSize: 12,
      fontWeight: 800
    },
    labelBgStyle: {
      fill: '#ffffff',
      fillOpacity: 0.92
    }
  } satisfies Edge));
  return { nodes, edges };
}

function nodeLabel(node: BuildGraphResolveResponse['nodes'][number]) {
  const category = typeof node.category === 'string' && isPartCategory(node.category)
    ? PART_CATEGORY_LABELS[node.category]
    : node.category;
  return (
    <div className="min-w-[124px] max-w-[172px]">
      <div className="mb-1 flex items-center justify-between gap-2">
        <span className="truncate text-[11px] font-black text-slate-500">{category ?? node.type}</span>
        <span className={`rounded px-1.5 py-0.5 text-[10px] font-black ${statusBadgeTone(node.status)}`}>{node.status}</span>
      </div>
      <div className="line-clamp-2 text-xs font-black leading-4 text-commerce-ink">{node.label}</div>
      {node.detail ? <div className="mt-1 line-clamp-2 text-[10px] leading-4 text-slate-500">{node.detail}</div> : null}
    </div>
  );
}

function nodeStyle(status: BuildGraphStatus, constraint: boolean) {
  const base = {
    borderRadius: 10,
    borderWidth: 1,
    borderStyle: 'solid',
    padding: 0,
    width: 184,
    boxShadow: '0 10px 26px rgba(15, 23, 42, 0.08)'
  };
  if (constraint) {
    return {
      ...base,
      background: '#f8fafc',
      borderColor: status === 'PASS' ? '#cbd5e1' : status === 'WARN' ? '#f59e0b' : '#ef4444'
    };
  }
  return {
    ...base,
    background: '#ffffff',
    borderColor: status === 'PASS' ? '#dbeafe' : status === 'WARN' ? '#f59e0b' : '#ef4444'
  };
}

function GraphStat({ label, value, tone = 'default' }: { label: string; value: number; tone?: 'default' | 'warn' }) {
  return (
    <div className="rounded-md border border-commerce-line bg-slate-50 p-2">
      <div className={`font-black ${tone === 'warn' && value > 0 ? 'text-amber-600' : 'text-commerce-ink'}`}>{value}</div>
      <div className="mt-0.5 text-slate-500">{label}</div>
    </div>
  );
}

function statusIcon(status: BuildGraphStatus) {
  if (status === 'PASS') return <CheckCircle2 size={14} className="text-commerce-green" />;
  if (status === 'WARN') return <AlertTriangle size={14} className="text-amber-600" />;
  return <AlertTriangle size={14} className="text-red-600" />;
}

function statusColor(status: string) {
  if (status === 'FAIL') return '#dc2626';
  if (status === 'WARN') return '#d97706';
  return '#2563eb';
}

function statusPanelTone(status: BuildGraphStatus) {
  if (status === 'FAIL') return 'border-red-200 bg-red-50';
  if (status === 'WARN') return 'border-amber-200 bg-amber-50';
  return 'border-blue-100 bg-blue-50';
}

function statusBadgeTone(status: BuildGraphStatus) {
  if (status === 'FAIL') return 'bg-red-100 text-red-700';
  if (status === 'WARN') return 'bg-amber-100 text-amber-700';
  return 'bg-emerald-50 text-emerald-700';
}

function isPartCategory(value: string): value is PartCategory {
  return Object.keys(PART_CATEGORY_LABELS).includes(value);
}
