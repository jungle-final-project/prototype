import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { keepPreviousData, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { ChevronDown } from 'lucide-react';
import { PART_CATEGORY_LABELS, type BuildGraphResolveResponse } from '../../../quote/aiSelection';
import { CompositeScoreGauge } from '../../../quote/components/CompositeScoreGauge';
import type { PerfCompareTarget } from '../../../../lib/events';
import { checkBuildPerformance, type GameFpsEvidence } from '../../../quote/quoteApi';
import { listParts } from '../../partsApi';
import type { PartCompatibility } from '../../types';
import { withObjectParticle } from './koreanParticle';

// 담긴 견적으로 FPS를 조회할 수 있는 게임·해상도 — game_fps_benchmarks 시드 커버리지 기준.
const FPS_GAMES = [
  { key: 'pubg', label: '배그', query: 'pubg' },
  { key: 'valorant', label: '발로란트', query: 'valorant' },
  { key: 'overwatch-2', label: '오버워치2', query: 'overwatch' },
  { key: 'lost-ark', label: '로스트아크', query: 'lost ark' },
  { key: 'cyberpunk-2077', label: '사이버펑크', query: 'cyberpunk' }
] as const;
const FPS_RESOLUTIONS = [
  { key: 'FHD', label: 'FHD', query: 'fhd' },
  { key: 'QHD', label: 'QHD', query: 'qhd' },
  { key: '4K', label: '4K', query: '4k' }
] as const;

// FPS 아크 게이지 스케일 상한 — 일반적인 게이밍 모니터 주사율(165Hz) 기준.
const FPS_CAP = 165;

// 담긴 견적의 성능을 셀프견적에 바로 보여준다 — resolveBuildGraph가 내려주는 compositeScore를
// 단일 1000점 종합점수로 표시한다. CPU/GPU 카테고리 내부 점수는 더 이상 사용자 대표 점수로 노출하지 않는다.
// 셀프견적 드래프트·저장 견적 어디서든 재사용할 수 있게 최소 필드(category, partId)만 받는다.
// name/currentPrice는 교체 비교(비용 대비 효과)에만 쓰는 선택 필드다 — 없으면 비용 블록만 생략된다.
type PerfItem = { category: string; partId: string; name?: string; currentPrice?: number };

// 팀장 확정 설계: 탭 없이 상시 2열 — 왼쪽 열은 종합점수 아크 + 게임 FPS 아크(게임/해상도 선택 포함) 상하 적층,
// 오른쪽 열은 "게임 성능 비교" 상시 작업창(카테고리 토글 + 후보 선택 팝오버 + 비교 결과 + 교체 담기).
// onStartComparison이 없으면(저장 견적 등) 비교 작업창 없이 왼쪽 열만 세로로 쌓인다.
export function QuotePerformancePanel({
  graph,
  items,
  comparison = null,
  onClearComparison,
  onStartComparison,
  onApplyComparison
}: {
  graph?: BuildGraphResolveResponse;
  items: PerfItem[];
  comparison?: PerfCompareTarget | null;
  onClearComparison?: () => void;
  onStartComparison?: (target: PerfCompareTarget) => void;
  onApplyComparison?: (target: PerfCompareTarget) => Promise<unknown>;
}) {
  const compositeScore = graph?.compositeScore;
  if (!compositeScore) {
    return null;
  }
  // FPS 참고범위는 GPU가 있어야 의미 있다 — CPU·GPU만 조회에 넘긴다.
  const hasGpu = items.some((item) => item.category === 'GPU');
  const perfItems = items.filter((item) => item.category === 'CPU' || item.category === 'GPU');

  return (
    <section data-testid="quote-performance-panel" className="panel p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-sm font-black text-commerce-ink">담긴 견적 성능</h2>
          <span
            data-testid="quote-performance-fit"
            className={`rounded-full border px-2 py-0.5 text-[10px] font-black ${scoreBadgeTone(compositeScore.score)}`}
          >
            {compositeScore.label}
          </span>
        </div>
        <span className="text-[10px] font-bold text-slate-400">공개 자료 기준 참고치</span>
      </div>

      <PerfPanelBody
        compositeScore={compositeScore}
        perfItems={perfItems}
        hasGpu={hasGpu}
        comparison={comparison}
        onClearComparison={onClearComparison}
        onStartComparison={onStartComparison}
        onApplyComparison={onApplyComparison}
      />
    </section>
  );
}

// 패널 본문: 게임/해상도 선택과 FPS 조회(기존·변경 조합)를 한곳에서 소유하고,
// 왼쪽 열(종합점수 아크 + FPS 아크)과 오른쪽 열(비교 작업창)에 같은 상태를 내려준다.
function PerfPanelBody({
  compositeScore,
  perfItems,
  hasGpu,
  comparison,
  onClearComparison,
  onStartComparison,
  onApplyComparison
}: {
  compositeScore: NonNullable<BuildGraphResolveResponse['compositeScore']>;
  perfItems: PerfItem[];
  hasGpu: boolean;
  comparison: PerfCompareTarget | null;
  onClearComparison?: () => void;
  onStartComparison?: (target: PerfCompareTarget) => void;
  onApplyComparison?: (target: PerfCompareTarget) => Promise<unknown>;
}) {
  const queryClient = useQueryClient();
  const [gameKey, setGameKey] = useState<string>(FPS_GAMES[0].key);
  const [resKey, setResKey] = useState<string>('QHD');
  const game = FPS_GAMES.find((g) => g.key === gameKey) ?? FPS_GAMES[0];
  const resolution = FPS_RESOLUTIONS.find((r) => r.key === resKey) ?? FPS_RESOLUTIONS[1];

  const partIds = perfItems.map((item) => item.partId).filter(Boolean);
  const partKey = useMemo(() => [...partIds].sort().join(','), [partIds]);

  // 비교 대상이 현재 견적과 어긋나면(같은 부품이거나 기존 부품이 없음) 비교를 그리지 않는다 — 상위에서 해제하지만 이중 안전망.
  const currentPart = comparison ? perfItems.find((item) => item.category === comparison.category) : undefined;
  const activeComparison = comparison && currentPart && currentPart.partId !== comparison.partId ? comparison : null;
  const comparePartIds = activeComparison
    ? perfItems.map((item) => (item.category === activeComparison.category ? activeComparison.partId : item.partId)).filter(Boolean)
    : [];
  const compareKey = useMemo(() => [...comparePartIds].sort().join(','), [comparePartIds]);

  const { data, isFetching, isError } = useQuery({
    queryKey: ['quote-fps', partKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds, game: game.query, resolution: resolution.query }),
    enabled: hasGpu && partIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });
  // 변경 조합 조회 — queryKey에 비교 대상 partId가 섞인 목록이 들어가 후보가 바뀌면 다시 조회한다.
  const compareQuery = useQuery({
    queryKey: ['quote-fps', compareKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds: comparePartIds, game: game.query, resolution: resolution.query }),
    enabled: hasGpu && Boolean(activeComparison) && comparePartIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });

  // 가장 근접한 근거(정렬 1순위)를 대표값으로 쓴다 — 서버가 exactness 순으로 정렬해 내려준다.
  const evidence: GameFpsEvidence | undefined = data?.details?.gameFpsEvidence?.[0];
  const avg = Number(evidence?.avgFps);
  const hasAvg = Number.isFinite(avg) && avg > 0;
  const low = Number(evidence?.onePercentLowFps);
  const hasLow = Number.isFinite(low) && low > 0;

  const compareEvidence: GameFpsEvidence | undefined = activeComparison
    ? compareQuery.data?.details?.gameFpsEvidence?.[0]
    : undefined;
  const compareAvg = Number(compareEvidence?.avgFps);
  const hasCompareAvg = Number.isFinite(compareAvg) && compareAvg > 0;
  const compareLow = Number(compareEvidence?.onePercentLowFps);
  const hasCompareLow = Number.isFinite(compareLow) && compareLow > 0;
  // 비교가 켜져 있는데 자료가 아직 안 왔으면 로딩, 끝내 없으면 사유를 알리고 비교를 강제하지 않는다.
  const isCompareReady = Boolean(activeComparison) && hasAvg && hasCompareAvg;
  const isCompareLoading = Boolean(activeComparison) && !isCompareReady && (isFetching || compareQuery.isFetching);

  // 아크 스윕과 중앙 숫자 카운트업이 같은 보간을 공유한다 — 값이 바뀌면 이전 값→새 값으로 rAF easeOut 스윕.
  const animatedAvg = useAnimatedNumber(hasAvg ? avg : 0);
  // 변경 조합 값: 비교 전엔 기존 값을 조용히 따라가다가, 비교가 켜지면 기존 값→새 값으로 스윕한다.
  // 진입 시퀀스: 고스트 fade-in(~300ms)이 끝난 뒤 스윕이 시작되도록 딜레이를 준다. 해제 시엔 즉시 기존 값으로 복귀 스윕.
  const animatedCompareAvg = useAnimatedNumber(
    isCompareReady ? compareAvg : hasAvg ? avg : 0,
    isCompareReady ? COMPARE_SWEEP_DELAY_MS : 0
  );
  // 비교 해제 시 파란 아크를 즉시 지우지 않고, 기존 값으로 스윕 복귀 + 페이드아웃이 끝날 때까지 잠깐 유지한다.
  const [compareLinger, setCompareLinger] = useState(false);
  const wasCompareReadyRef = useRef(isCompareReady);
  useEffect(() => {
    const wasReady = wasCompareReadyRef.current;
    wasCompareReadyRef.current = isCompareReady;
    if (isCompareReady) {
      setCompareLinger(false);
      return;
    }
    if (!wasReady || prefersReducedMotion()) return;
    setCompareLinger(true);
    const timer = window.setTimeout(() => setCompareLinger(false), SWEEP_DURATION_MS);
    return () => window.clearTimeout(timer);
  }, [isCompareReady]);

  // 교체 담기: 비교 중인 후보를 실제 견적에 반영한다(성공 시 상위에서 드래프트 갱신 → 비교 자동 해제 → 게이지 스윕).
  const [isApplying, setIsApplying] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);
  const activeComparisonPartId = activeComparison?.partId ?? null;
  useEffect(() => {
    // 비교 대상이 바뀌거나 해제되면 이전 교체 실패 안내를 지운다.
    setApplyError(null);
  }, [activeComparisonPartId]);
  const applyComparison = async () => {
    if (!activeComparison || !onApplyComparison || isApplying) return;
    setIsApplying(true);
    setApplyError(null);
    try {
      await onApplyComparison(activeComparison);
      // 교체 후에는 후보 호환 평가가 새 조합 기준으로 달라진다 — 선택기 목록을 재평가한다.
      void queryClient.invalidateQueries({ queryKey: ['parts', 'perf-compare-candidates'] });
    } catch {
      setApplyError('부품을 교체하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setIsApplying(false);
    }
  };

  const hasWorkspace = Boolean(onStartComparison);

  return (
    <div
      data-testid="quote-performance-grid"
      className={`grid gap-3 ${hasWorkspace ? 'lg:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)] lg:items-start' : ''}`}
    >
      {/* 왼쪽 열: 카드 하나 안에 종합점수 아크(위) + 게임 FPS 아크(아래) 상하 적층 — 구분은 얇은 디바이더만.
          모바일에서는 전체가 1열로 쌓인다. */}
      <div className="rounded-lg border border-commerce-line bg-white p-3">
        <div data-testid="quote-composite-score-card">
          <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-[11px]">
            <span className="font-black text-slate-600">종합 점수</span>
            <span className="font-bold text-slate-400">호환·성능·여유 종합 1000점</span>
          </div>
          <CompositeScoreGauge
            score={compositeScore}
            size="large"
            className="mx-auto"
            scoreTestId="quote-composite-score"
            gaugeTestId="quote-composite-score-gauge"
          />
          {compositeScore.requestFit ? (
            <div className={`mt-2 rounded px-2 py-1 text-[10px] font-black ${requestFitTone(compositeScore.requestFit.status)}`}>
              {requestFitLabel(compositeScore.requestFit)}
            </div>
          ) : null}
          <p className="mt-2 text-[10px] leading-relaxed text-slate-400">
            종합 점수는 공개 벤치마크·공식 스펙·호환성 검증 기반 참고값입니다 — 실제 성능이나 정확한 FPS를 보장하지 않습니다.
          </p>
        </div>

        {hasGpu ? (
          <div data-testid="quote-fps-section" className="mt-3 border-t border-commerce-line pt-3">
            <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
              <span className="text-[11px] font-black text-slate-600">게임 예상 성능 <span className="text-slate-400">(참고)</span></span>
              <div className="flex gap-0.5 rounded-md border border-commerce-line bg-slate-50 p-0.5" role="group" aria-label="해상도 선택">
                {FPS_RESOLUTIONS.map((res) => (
                  <button
                    key={res.key}
                    type="button"
                    data-testid={`fps-res-${res.key}`}
                    aria-pressed={resKey === res.key}
                    onClick={() => setResKey(res.key)}
                    className={`rounded px-2 py-0.5 text-[10px] font-black transition ${
                      resKey === res.key ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
                    }`}
                  >
                    {res.label}
                  </button>
                ))}
              </div>
            </div>

            {isFetching && !evidence ? (
              <div className="h-40 animate-pulse rounded-lg bg-slate-100" />
            ) : hasAvg ? (
              <div data-testid="fps-result">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-[11px] font-black text-slate-600">
                    {game.label} · {evidence?.resolution ?? resolution.label}
                    {presetLabel(evidence?.graphicsPreset) ? ` · ${presetLabel(evidence?.graphicsPreset)}` : ''}
                  </span>
                  {!isCompareReady ? (
                    <span className={`text-[10px] font-black ${feelTone(avg).text}`}>{feelLabel(avg)}</span>
                  ) : null}
                </div>

                {/* 곡선(반원 아크) 게이지 — 비교 시 기존은 회색 고스트, 변경은 파랑으로 겹쳐 그린다(오른쪽 작업창과 실시간 연동). */}
                <div className="mt-2">
                  <FpsArcGauge
                    avg={avg}
                    displayAvg={animatedAvg}
                    low={hasLow ? low : undefined}
                    compareActive={isCompareReady}
                    compareAvg={isCompareReady ? compareAvg : undefined}
                    compareDisplay={isCompareReady || compareLinger ? animatedCompareAvg : undefined}
                    compareLow={isCompareReady && hasCompareLow ? compareLow : undefined}
                  >
                    {isCompareReady ? (
                      <>
                        <div className="flex flex-wrap items-baseline justify-center gap-1">
                          <span data-testid="fps-avg" className="text-xl font-black text-slate-500">{Math.round(animatedAvg)}</span>
                          <span className="text-sm font-black text-slate-400">→</span>
                          {/* 변경 숫자만 카운트업 — 아크 스윕과 같은 보간을 공유한다. */}
                          <span data-testid="fps-compare-avg" className="text-3xl font-black text-brand-blue">{Math.round(animatedCompareAvg)}</span>
                          {/* 델타 배지: 스윕이 끝날 즈음 팝인. 비교 대상이 바뀌면 key로 다시 재생한다. */}
                          <span
                            key={activeComparison?.partId}
                            data-testid="fps-compare-delta"
                            className={`perf-pop-in ml-0.5 rounded-full border px-1.5 py-0.5 text-[10px] font-black ${deltaBadgeTone(percentDelta(avg, compareAvg))}`}
                          >
                            {formatSignedPercent(percentDelta(avg, compareAvg))}
                          </span>
                        </div>
                        <div className="mt-0.5 text-[10px] font-bold text-slate-400">FPS 평균 (참고)</div>
                        <div className="text-[11px] font-black">
                          <span className={feelTone(avg).text}>{feelLabel(avg)}</span>
                          {feelLabel(avg) !== feelLabel(compareAvg) ? (
                            <span className={feelTone(compareAvg).text}> → {feelLabel(compareAvg)}</span>
                          ) : null}
                        </div>
                      </>
                    ) : (
                      <>
                        <div data-testid="fps-avg" className="text-3xl font-black leading-none text-commerce-ink">{Math.round(animatedAvg)}</div>
                        <div className="mt-1 text-[10px] font-bold text-slate-400">FPS 평균 (참고)</div>
                        <div className={`text-[11px] font-black ${feelTone(avg).text}`}>{feelLabel(avg)}</div>
                      </>
                    )}
                  </FpsArcGauge>
                </div>

                <div className="mt-2 flex flex-wrap items-center justify-between gap-1.5 text-[10px]">
                  <span className="font-bold text-slate-500">
                    {isCompareReady
                      ? '눈금 표시는 순간 최저(하위 1% 평균) 위치입니다'
                      : hasLow
                        ? `최저 약 ${Math.round(low)} FPS (하위 1% 평균)`
                        : '최저값 자료 없음'}
                  </span>
                  <span className="font-bold text-slate-400">
                    {exactnessLabel(evidence?.match?.evidenceExactness)}
                    {evidence?.sourceName ? ` · ${evidence.sourceName}` : ''}
                  </span>
                </div>
              </div>
            ) : (
              <div data-testid="fps-empty" className="flex flex-col items-center justify-center rounded-lg border border-dashed border-slate-300 bg-white p-3 text-center text-[11px] font-bold text-slate-500">
                {isError ? '참고 자료를 불러오지 못했습니다.' : '이 조합의 공개 참고 자료가 아직 없어요.'}
              </div>
            )}

            {/* 다른 게임 한눈에(컴팩트): 5개 게임 평균을 나란히 — 행 클릭이 게임 선택기 역할을 겸한다(비교 중에도 동작). */}
            <GameFpsOverview
              partIds={partIds}
              partKey={partKey}
              resolution={resolution}
              selectedGameKey={game.key}
              onSelectGame={setGameKey}
            />

            <p className="mt-2 text-[10px] leading-relaxed text-slate-400">
              공개 자료 기준 참고 범위입니다 — 실제 FPS는 게임 설정·패치·드라이버에 따라 달라집니다.
            </p>
          </div>
        ) : (
          <div data-testid="fps-no-gpu" className="mt-3 rounded-lg border border-dashed border-slate-300 bg-white p-4 text-center text-[11px] font-bold text-slate-500">
            그래픽카드를 담으면 게임 예상 성능을 보여드려요.
          </div>
        )}
      </div>

      {/* 오른쪽 열: "게임 성능 비교" 상시 작업창 — 한 줄 콤보(토글+후보 선택 팝오버)는 세로로 고정되고,
          팝오버는 겹쳐 떠서 아래 비교 결과를 밀어내지 않는다. */}
      {onStartComparison ? (
        <CompareWorkspace
          perfItems={perfItems}
          activeComparison={activeComparison}
          currentPart={currentPart}
          isCompareReady={isCompareReady}
          isCompareLoading={isCompareLoading}
          baseAvg={avg}
          baseLow={hasLow ? low : undefined}
          compareAvg={compareAvg}
          compareLow={hasCompareLow ? compareLow : undefined}
          onStartComparison={onStartComparison}
          onClearComparison={onClearComparison}
          canApply={Boolean(onApplyComparison)}
          isApplying={isApplying}
          applyError={applyError}
          onApply={() => void applyComparison()}
        />
      ) : null}
    </div>
  );
}

// 교체 비교가 의미 있는 카테고리(벤치마크 근거가 있는 CPU/GPU)만 선택기에 노출한다.
const PERF_PICKER_CATEGORIES: Array<PerfCompareTarget['category']> = ['CPU', 'GPU'];

// 오른쪽 열 상시 작업창: [CPU|GPU 토글] + [후보 선택 ▾] 한 줄 콤보 위에서 후보를 고르면
// 아래에 가격·성능 변화와 FPS 범위, 교체 담기까지 이어진다. 미선택 시엔 짧은 안내 한 줄만 둔다.
function CompareWorkspace({
  perfItems,
  activeComparison,
  currentPart,
  isCompareReady,
  isCompareLoading,
  baseAvg,
  baseLow,
  compareAvg,
  compareLow,
  onStartComparison,
  onClearComparison,
  canApply,
  isApplying,
  applyError,
  onApply
}: {
  perfItems: PerfItem[];
  activeComparison: PerfCompareTarget | null;
  currentPart?: PerfItem;
  isCompareReady: boolean;
  isCompareLoading: boolean;
  baseAvg: number;
  baseLow?: number;
  compareAvg: number;
  compareLow?: number;
  onStartComparison: (target: PerfCompareTarget) => void;
  onClearComparison?: () => void;
  canApply: boolean;
  isApplying: boolean;
  applyError: string | null;
  onApply: () => void;
}) {
  const [category, setCategory] = useState<PerfCompareTarget['category']>(activeComparison?.category ?? 'GPU');
  const [isPickerOpen, setIsPickerOpen] = useState(false);
  // 콤보 한 줄(토글+선택 버튼+팝오버)을 한 단위로 본다 — 팝오버가 열린 채 카테고리를 토글해도 닫히지 않는다.
  const comboRef = useRef<HTMLDivElement | null>(null);

  // 외부(AI 변경 미리보기 이벤트 등)에서 비교가 켜지면 선택기도 그 카테고리를 따라간다.
  const comparisonCategory = activeComparison?.category;
  useEffect(() => {
    if (comparisonCategory) {
      setCategory(comparisonCategory);
    }
  }, [comparisonCategory]);

  // 팝오버는 바깥 클릭·Escape로 닫힌다 — 후보를 고르면 즉시 닫히고 아래 비교 결과로 시선이 이어진다.
  useEffect(() => {
    if (!isPickerOpen) return;
    const onPointerDown = (event: MouseEvent) => {
      if (comboRef.current && !comboRef.current.contains(event.target as Node)) {
        setIsPickerOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsPickerOpen(false);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [isPickerOpen]);

  const categoryCurrentPart = perfItems.find((item) => item.category === category);
  // 아코디언과 같은 GET /api/parts(QUOTE_DRAFT_CURRENT, 호환 정렬)를 재사용한다 — 팝오버가 열릴 때만 조회.
  // keepPreviousData를 쓰지 않는다 — 토글 직후 이전 카테고리 후보가 잠깐 남으면 엉뚱한 비교를 시작할 수 있다.
  const candidateQuery = useQuery({
    queryKey: ['parts', 'perf-compare-candidates', category],
    queryFn: () => listParts({
      category,
      page: 0,
      size: 20,
      sort: 'compatibility',
      compatibilitySource: 'QUOTE_DRAFT_CURRENT'
    }),
    enabled: isPickerOpen && Boolean(categoryCurrentPart),
    staleTime: 30_000
  });
  const candidates = candidateQuery.data?.items ?? [];

  return (
    <div data-testid="perf-compare-workspace" className="rounded-lg border border-commerce-line bg-slate-50/60 p-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="text-[11px] font-black text-slate-600">게임 성능 비교</span>
        <span className="text-[10px] font-bold text-slate-400">후보를 고르면 왼쪽 게이지와 함께 비교돼요</span>
      </div>

      {/* 한 줄 콤보: 부품 종류 토글 + 후보 선택 버튼. 팝오버는 이 줄 아래에 겹쳐 떠서 결과 영역을 밀지 않는다. */}
      <div ref={comboRef} className="flex items-center gap-2">
        <div className="flex shrink-0 gap-0.5 rounded-md border border-commerce-line bg-white p-0.5" role="group" aria-label="비교할 부품 종류 선택">
          {PERF_PICKER_CATEGORIES.map((pickerCategory) => (
            <button
              key={pickerCategory}
              type="button"
              data-testid={`perf-candidate-category-${pickerCategory}`}
              aria-pressed={category === pickerCategory}
              onClick={() => setCategory(pickerCategory)}
              className={`rounded px-2.5 py-1 text-[10px] font-black transition ${
                category === pickerCategory ? 'bg-brand-blue text-white shadow-sm' : 'text-slate-400 hover:text-slate-600'
              }`}
            >
              {PART_CATEGORY_LABELS[pickerCategory]}
            </button>
          ))}
        </div>
        <div className="relative min-w-0 flex-1">
          <button
            type="button"
            data-testid="perf-candidate-select"
            aria-expanded={isPickerOpen}
            aria-haspopup="true"
            onClick={() => setIsPickerOpen((open) => !open)}
            className="flex w-full items-center justify-between gap-2 rounded-md border border-commerce-line bg-white px-2.5 py-1.5 text-left text-[11px] font-black text-commerce-ink transition hover:border-brand-blue"
          >
            <span className={`truncate ${activeComparison ? '' : 'text-slate-400'}`}>
              {activeComparison ? activeComparison.name : '교체 후보 선택'}
            </span>
            <ChevronDown className={`h-3.5 w-3.5 shrink-0 text-slate-400 transition-transform ${isPickerOpen ? 'rotate-180' : ''}`} aria-hidden="true" />
          </button>

          {isPickerOpen ? (
            <div
              data-testid="perf-candidate-popover"
              className="perf-popover-in absolute left-0 right-0 top-full z-30 mt-1 rounded-lg border border-commerce-line bg-white p-2 shadow-xl"
            >
              {categoryCurrentPart ? (
                <div data-testid="perf-candidate-current" className="mb-1.5 truncate rounded-md bg-slate-50 px-2 py-1.5 text-[10px] font-bold text-slate-500">
                  지금 담긴 부품 · <span className="font-black text-slate-600">{categoryCurrentPart.name ?? PART_CATEGORY_LABELS[category]}</span>
                </div>
              ) : null}
              {!categoryCurrentPart ? (
                <div data-testid="perf-candidate-picker-empty" className="rounded-md border border-dashed border-slate-300 bg-white px-2.5 py-3 text-center text-[11px] font-bold text-slate-500">
                  {withObjectParticle(PART_CATEGORY_LABELS[category])} 먼저 담으면 교체 비교를 할 수 있어요.
                </div>
              ) : candidateQuery.isLoading ? (
                <div className="px-2 py-3 text-center text-[11px] font-bold text-slate-400">후보를 불러오는 중</div>
              ) : candidateQuery.isError ? (
                <div className="px-2 py-3 text-center text-[11px] font-bold text-red-500">후보를 불러오지 못했습니다</div>
              ) : candidates.length === 0 ? (
                <div className="px-2 py-3 text-center text-[11px] font-bold text-slate-400">표시할 후보가 없습니다</div>
              ) : (
                <div className="max-h-64 space-y-1 overflow-y-auto pr-1">
                  {candidates.map((part, index) => {
                    const status = part.compatibility?.status;
                    const isFail = status === 'FAIL';
                    const isCurrent = part.id === categoryCurrentPart.partId;
                    const isSelected = activeComparison?.partId === part.id;
                    return (
                      <button
                        key={part.id}
                        type="button"
                        data-testid={`perf-candidate-option-${index}`}
                        disabled={isFail || isCurrent}
                        aria-pressed={isSelected}
                        onClick={() => {
                          onStartComparison({ category, partId: part.id, name: part.name, price: part.price });
                          setIsPickerOpen(false);
                        }}
                        className={`w-full rounded-md border bg-white px-2.5 py-2 text-left text-[11px] transition disabled:cursor-not-allowed ${
                          isFail
                            ? 'border-slate-200 opacity-55'
                            : isCurrent
                              ? 'border-emerald-200 bg-emerald-50/60'
                              : isSelected
                                ? 'border-brand-blue ring-2 ring-blue-100'
                                : 'border-commerce-line hover:border-brand-blue'
                        }`}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <span className="line-clamp-2 min-w-0 font-black text-commerce-ink">{part.name}</span>
                          <span className="shrink-0 font-black text-commerce-ink">{part.price.toLocaleString()}원</span>
                        </div>
                        <div className="mt-1 flex items-center justify-between gap-2 text-[10px]">
                          <span className="truncate text-slate-500">{part.manufacturer ?? '제조사 미상'}</span>
                          <span className={`shrink-0 font-black ${candidateStatusTone(status, isCurrent, isSelected)}`}>
                            {isCurrent ? '지금 담긴 부품' : isSelected ? '비교 중' : candidateStatusLabel(part.compatibility)}
                          </span>
                        </div>
                        {isFail ? (
                          <div className="mt-1 text-[10px] font-bold text-red-500">
                            {part.compatibility?.summary || '현재 조합에는 장착할 수 없어요.'}
                          </div>
                        ) : null}
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          ) : null}
        </div>
      </div>

      {/* 콤보 아래 = 비교 결과. 미선택 시엔 짧은 안내 한 줄만 — 세로를 누르지 않는다. */}
      {activeComparison ? (
        <div className="mt-2.5 space-y-2">
          <div data-testid="fps-compare-banner" className="flex min-w-0 flex-wrap items-center gap-1 rounded-md border border-blue-100 bg-blue-50/60 px-2.5 py-1.5 text-[11px] font-bold text-slate-600">
            <span className="font-black text-brand-blue">교체 비교</span>
            <span aria-hidden="true">·</span>
            <span className="min-w-0 truncate">
              {currentPart?.name ?? '지금 담긴 부품'} → {activeComparison.name}
            </span>
          </div>

          {isCompareReady ? (
            <>
              {/* 위계 1: 비용 대비 효과 — 가격 변화 % vs 성능(FPS 평균) 변화 %. */}
              <div data-testid="cost-effect-block" className="perf-block-in rounded-lg border border-commerce-line bg-white p-2.5">
                <div className="mb-1.5 text-[11px] font-black text-slate-600">비용 대비 효과</div>
                <CostEffectBars
                  currentPrice={currentPart?.currentPrice}
                  targetPrice={activeComparison.price}
                  baseAvg={baseAvg}
                  compareAvg={compareAvg}
                />
              </div>
              {/* 위계 2: 기존/변경 FPS 범위(1% 최저 ~ 평균) 수평 바 — 두 조합의 흔들림 폭을 나란히 본다. */}
              <div data-testid="fps-range-bars" className="perf-block-in space-y-1.5 rounded-lg border border-commerce-line bg-white p-2.5" style={{ animationDelay: '120ms' }}>
                <FpsRangeBar label="기존" avg={baseAvg} low={baseLow} tone="base" />
                <FpsRangeBar label="변경" avg={compareAvg} low={compareLow} tone="changed" />
              </div>
              {/* 위계 3: 추가 비용과 예상 FPS 화살표 — 결정에 필요한 숫자 요약. */}
              <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-[10px] font-bold text-slate-500">
                <span data-testid="cost-effect-price">{priceDiffText(currentPart?.currentPrice, activeComparison.price)}</span>
                <span data-testid="cost-effect-fps">
                  예상 FPS {fpsRangeText(baseAvg, baseLow)} → {fpsRangeText(compareAvg, compareLow)}
                </span>
              </div>
            </>
          ) : isCompareLoading ? (
            <div className="h-24 animate-pulse rounded-lg bg-slate-100" />
          ) : (
            <div data-testid="fps-compare-empty" className="rounded-md border border-dashed border-slate-300 bg-white px-2.5 py-2 text-[11px] font-bold text-slate-500">
              변경 조합의 공개 참고 자료가 없어요 — 지금 담긴 조합 기준으로만 보여드려요.
            </div>
          )}

          {applyError ? (
            <div data-testid="perf-apply-error" className="rounded-md border border-red-100 bg-red-50/70 px-2.5 py-1.5 text-[11px] font-bold text-red-600">
              {applyError}
            </div>
          ) : null}

          <div className="flex flex-wrap items-center gap-1.5">
            {canApply ? (
              <button
                type="button"
                data-testid="perf-apply-replace"
                disabled={isApplying}
                onClick={onApply}
                className="rounded bg-brand-blue px-2.5 py-1.5 text-[10px] font-black text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {isApplying ? '교체해 담는 중…' : '이 제품으로 교체해 담기'}
              </button>
            ) : null}
            {onClearComparison ? (
              <button
                type="button"
                data-testid="compare-clear"
                onClick={onClearComparison}
                className="rounded border border-commerce-line bg-white px-2 py-1.5 text-[10px] font-black text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
              >
                비교 해제
              </button>
            ) : null}
          </div>
        </div>
      ) : (
        <p data-testid="perf-compare-idle" className="mt-2.5 text-[11px] font-bold leading-relaxed text-slate-400">
          후보를 고르면 지금 조합과 가격·성능 변화를 나란히 비교해 드려요.
        </p>
      )}
    </div>
  );
}

// 호환 상태 → 사용자 언어 배지(서버 라벨 우선). 원어(PASS/WARN/FAIL)는 노출하지 않는다.
function candidateStatusLabel(compatibility?: PartCompatibility | null): string {
  if (compatibility?.statusLabel) return compatibility.statusLabel;
  switch (compatibility?.status) {
    case 'PASS':
      return '호환 가능';
    case 'WARN':
      return '간섭 주의';
    case 'FAIL':
      return '장착 불가';
    default:
      return '확인 전';
  }
}

function candidateStatusTone(status: PartCompatibility['status'] | undefined, isCurrent: boolean, isSelected: boolean): string {
  if (isCurrent) return 'text-emerald-700';
  if (isSelected) return 'text-brand-blue';
  if (status === 'PASS') return 'text-emerald-700';
  if (status === 'WARN') return 'text-amber-600';
  if (status === 'FAIL') return 'text-red-600';
  return 'text-slate-400';
}

// 왼쪽 FPS 아크 아래 컴팩트 리스트: "다른 게임 한눈에" — 현재 해상도 기준 5개 게임 평균 FPS.
// 게임별 useQuery 5개 병렬(결정적 DB 조회라 부담 없음) — queryKey를 메인 조회와 공유해 캐시가 그대로 재사용된다.
// 행 클릭 = 그 게임 선택: 이 리스트가 게임 선택기 역할을 겸한다(비교 중에도 동작).
function GameFpsOverview({
  partIds,
  partKey,
  resolution,
  selectedGameKey,
  onSelectGame
}: {
  partIds: string[];
  partKey: string;
  resolution: (typeof FPS_RESOLUTIONS)[number];
  selectedGameKey: string;
  onSelectGame: (gameKey: string) => void;
}) {
  const results = useQueries({
    queries: FPS_GAMES.map((g) => ({
      queryKey: ['quote-fps', partKey, g.key, resolution.key],
      queryFn: () => checkBuildPerformance({ partIds, game: g.query, resolution: resolution.query }),
      enabled: partIds.length > 0,
      placeholderData: keepPreviousData,
      staleTime: 5 * 60 * 1000
    }))
  });

  return (
    <div data-testid="fps-game-overview" className="mt-2.5 border-t border-commerce-line pt-2.5">
      <div className="mb-1.5 flex items-center justify-between gap-2 text-[11px]">
        <span className="font-black text-slate-600">다른 게임 한눈에</span>
        <span className="font-bold text-slate-400">{resolution.label} · FPS 평균</span>
      </div>
      <div className="flex flex-col gap-1" role="group" aria-label="게임 선택">
        {FPS_GAMES.map((g, index) => {
          const query = results[index];
          // 아직 캐시도 없는 첫 로딩만 스켈레톤 — 해상도 전환 시엔 keepPreviousData로 이전 값을 유지한다.
          if (query.isPending) {
            return <div key={g.key} data-testid={`fps-game-row-${g.key}`} className="h-7 animate-pulse rounded-md bg-slate-100" />;
          }
          const rowEvidence: GameFpsEvidence | undefined = query.data?.details?.gameFpsEvidence?.[0];
          const rowAvg = Number(rowEvidence?.avgFps);
          const hasRowAvg = Number.isFinite(rowAvg) && rowAvg > 0;
          const isSelected = g.key === selectedGameKey;
          return (
            <button
              key={g.key}
              type="button"
              data-testid={`fps-game-row-${g.key}`}
              aria-pressed={isSelected}
              aria-label={hasRowAvg ? `${g.label} 평균 약 ${Math.round(rowAvg)} FPS` : `${g.label} 자료 없음`}
              onClick={() => onSelectGame(g.key)}
              className={`grid w-full grid-cols-[64px_minmax(0,1fr)_40px_10px] items-center gap-2 rounded-md border px-2 py-1 text-left transition ${
                isSelected ? 'border-brand-blue bg-blue-50/70' : 'border-transparent hover:border-commerce-line hover:bg-slate-50'
              }`}
            >
              <span
                className={`truncate text-[11px] ${
                  isSelected ? 'font-black text-commerce-ink' : hasRowAvg ? 'font-bold text-slate-600' : 'font-bold text-slate-400'
                }`}
              >
                {g.label}
              </span>
              {hasRowAvg ? (
                <>
                  <span className="relative block h-1.5 overflow-hidden rounded-full bg-slate-200/80">
                    {/* 165 스케일 미니 바 — 기존 모션 결(perf-bar-grow: 0→목표 폭 + 값 변화 transition)에
                        행별 스태거를 얹는다. reduced-motion이면 기존 규칙대로 즉시 표시. */}
                    <span
                      className={`perf-bar-grow absolute left-0 top-0 block h-full rounded-full ${feelTone(rowAvg).bar}`}
                      style={{ width: `${fpsPercent(rowAvg)}%`, animationDelay: `${index * 70}ms` }}
                    />
                  </span>
                  <span className="text-right text-[11px] font-black text-slate-700">{Math.round(rowAvg)}</span>
                  {/* 체감 색점 — 색약 대비 라벨은 행 aria-label과 게이지 쪽 체감 라벨이 담당한다. */}
                  <span className={`h-2 w-2 justify-self-center rounded-full ${feelTone(rowAvg).bar}`} aria-hidden="true" />
                </>
              ) : (
                <span className="col-span-3 text-right text-[10px] font-bold text-slate-400">자료 없음</span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

const FPS_ARC_PATH = 'M 24 112 A 86 86 0 0 1 196 112';

// FPS를 체감 경험으로 — 종합점수 게이지와 같은 시각 언어(반원 아크 + 중앙 큰 숫자)의 FPS 버전.
// CompositeScoreGauge는 홈/견적함 공용이라 손대지 않고 slot-board 로컬로 둔다.
// displayAvg/compareDisplay는 상위(PerfPanelBody)에서 rAF로 보간된 표시값이다 — 아크와 숫자가 같은 보간을 공유한다.
function FpsArcGauge({
  avg,
  displayAvg,
  low,
  compareActive,
  compareAvg,
  compareDisplay,
  compareLow,
  children
}: {
  avg: number;
  displayAvg: number;
  low?: number;
  compareActive: boolean;
  compareAvg?: number;
  compareDisplay?: number;
  compareLow?: number;
  children: ReactNode;
}) {
  const basePercent = fpsPercent(displayAvg);
  const comparePercent = compareDisplay !== undefined ? fpsPercent(compareDisplay) : null;
  const ariaLabel = compareActive && compareAvg !== undefined
    ? `FPS 평균 기존 약 ${Math.round(avg)}, 변경 약 ${Math.round(compareAvg)}`
    : `FPS 평균 약 ${Math.round(avg)}`;
  return (
    <div data-testid="fps-arc-gauge" className="mx-auto w-full max-w-[280px] text-center" aria-label={ariaLabel}>
      <div className="relative">
        <svg className="h-[150px] w-full overflow-visible" viewBox="0 0 220 132" role="img" aria-hidden="true">
          <path
            d={FPS_ARC_PATH}
            fill="none"
            className="stroke-slate-200"
            strokeWidth={18}
            strokeLinecap="butt"
            pathLength={100}
          />
          {/* 기존 조합: 비교 중엔 회색 반투명 고스트, 평소엔 체감 색 — 전환은 300ms 페이드(perf-arc-transition). */}
          <path
            d={FPS_ARC_PATH}
            fill="none"
            className={`perf-arc-transition ${compareActive ? 'stroke-slate-400 opacity-50' : feelStroke(avg)}`}
            strokeWidth={18}
            strokeLinecap="butt"
            pathLength={100}
            strokeDasharray={`${basePercent} 100`}
          />
          {/* 변경 조합: 파랑 아크를 살짝 좁게 겹쳐 위계를 준다. 해제 시엔 기존 값으로 스윕 복귀하며 페이드아웃. */}
          {comparePercent !== null ? (
            <path
              d={FPS_ARC_PATH}
              fill="none"
              className="perf-compare-arc stroke-brand-blue"
              style={{ opacity: compareActive ? 1 : 0 }}
              strokeWidth={10}
              strokeLinecap="butt"
              pathLength={100}
              strokeDasharray={`${comparePercent} 100`}
            />
          ) : null}
          {/* 1% 최저 마커: 아크 위 눈금 — 수평바 마커의 아크 버전. */}
          {low !== undefined ? <FpsArcTick value={low} className={compareActive ? 'stroke-slate-500' : 'stroke-slate-600'} /> : null}
          {compareActive && compareLow !== undefined ? <FpsArcTick value={compareLow} className="stroke-brand-blue" /> : null}
        </svg>
        <div className="absolute inset-x-0 bottom-1 px-1">{children}</div>
      </div>
      <div className="-mt-2 flex items-center justify-between px-4 text-[10px] font-bold text-slate-400" aria-hidden="true">
        <span>0</span>
        <span>{FPS_CAP}</span>
      </div>
    </div>
  );
}

function fpsPercent(fps: number) {
  return Math.min(100, Math.max(0, (fps / FPS_CAP) * 100));
}

// 값 스윕 길이(~600ms)와, 비교 진입 시 고스트 fade-in(~300ms)이 끝난 뒤 스윕을 시작하는 딜레이.
const SWEEP_DURATION_MS = 600;
const COMPARE_SWEEP_DELAY_MS = 300;

function prefersReducedMotion() {
  return typeof window === 'undefined' || window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function easeOutCubic(value: number) {
  return 1 - Math.pow(1 - value, 3);
}

// 값이 바뀌면 이전 값→새 값으로 rAF easeOut 스윕한다 — CompositeScoreGauge의 게이지 애니메이션과 같은 결.
// prefers-reduced-motion이면 즉시 반영한다. delayMs만큼 스윕 시작을 늦출 수 있다(비교 진입 시퀀스용).
function useAnimatedNumber(target: number, delayMs = 0): number {
  const safeTarget = Number.isFinite(target) ? target : 0;
  const [value, setValue] = useState(safeTarget);
  const valueRef = useRef(safeTarget);
  const frameRef = useRef<number | null>(null);
  const delayTimerRef = useRef<number | null>(null);
  const settleTimerRef = useRef<number | null>(null);

  useEffect(() => {
    if (safeTarget === valueRef.current) return;
    if (prefersReducedMotion()) {
      valueRef.current = safeTarget;
      setValue(safeTarget);
      return;
    }
    const clearSettle = () => {
      if (settleTimerRef.current !== null) window.clearTimeout(settleTimerRef.current);
      settleTimerRef.current = null;
    };
    const begin = () => {
      const from = valueRef.current;
      const startedAt = performance.now();
      const tick = (now: number) => {
        const progress = Math.min(1, (now - startedAt) / SWEEP_DURATION_MS);
        const next = from + (safeTarget - from) * easeOutCubic(progress);
        valueRef.current = next;
        setValue(next);
        if (progress < 1) {
          frameRef.current = requestAnimationFrame(tick);
        } else {
          frameRef.current = null;
          clearSettle();
        }
      };
      frameRef.current = requestAnimationFrame(tick);
      // 백그라운드 탭 등 rAF가 멈춘 환경에서도 최종값은 보장한다 — 스윕 시간이 지나면 목표값으로 스냅.
      settleTimerRef.current = window.setTimeout(() => {
        settleTimerRef.current = null;
        if (frameRef.current !== null) {
          cancelAnimationFrame(frameRef.current);
          frameRef.current = null;
        }
        valueRef.current = safeTarget;
        setValue(safeTarget);
      }, SWEEP_DURATION_MS + 100);
    };
    if (delayMs > 0) {
      delayTimerRef.current = window.setTimeout(begin, delayMs);
    } else {
      begin();
    }
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
      frameRef.current = null;
      if (delayTimerRef.current !== null) window.clearTimeout(delayTimerRef.current);
      delayTimerRef.current = null;
      clearSettle();
    };
  }, [safeTarget, delayMs]);

  return value;
}

// 아크 위 값 위치에 짧은 방사형 눈금을 그린다.
function FpsArcTick({ value, className }: { value: number; className: string }) {
  const ratio = Math.min(1, Math.max(0, value / FPS_CAP));
  const theta = Math.PI * (1 - ratio);
  const cx = 110;
  const cy = 112;
  const r = 86;
  const inner = r - 13;
  const outer = r + 13;
  const x1 = cx + inner * Math.cos(theta);
  const y1 = cy - inner * Math.sin(theta);
  const x2 = cx + outer * Math.cos(theta);
  const y2 = cy - outer * Math.sin(theta);
  return <line x1={x1} y1={y1} x2={x2} y2={y2} strokeWidth={2.5} strokeLinecap="round" className={className} />;
}

// 기존/변경 조합의 [1% 최저 ~ 평균] 범위를 수평 바로 나란히 — 최저값이 없으면 평균 단일점.
function FpsRangeBar({ label, avg, low, tone }: { label: string; avg: number; low?: number; tone: 'base' | 'changed' }) {
  const end = fpsPercent(avg);
  const start = low !== undefined ? fpsPercent(Math.min(low, avg)) : end;
  const width = Math.max(1.5, end - start);
  const barClass = tone === 'changed' ? 'bg-brand-blue' : 'bg-slate-400';
  const text = low !== undefined ? `${Math.round(low)}~${Math.round(avg)} FPS` : `약 ${Math.round(avg)} FPS`;
  return (
    <div className="grid grid-cols-[30px_1fr_88px] items-center gap-2 text-[11px] font-bold">
      <span className={tone === 'changed' ? 'font-black text-brand-blue' : 'text-slate-500'}>{label}</span>
      <div className="relative h-2.5 overflow-hidden rounded-full bg-slate-100">
        {/* 등장 시 0→목표 폭으로 자라고(기존 먼저, 변경 120ms 스태거), 값 변화는 transition으로 따라간다. */}
        <div
          className={`absolute top-0 h-full rounded-full ${barClass} perf-bar-grow${tone === 'changed' ? ' perf-bar-stagger' : ''}`}
          style={{ left: `${Math.min(start, 100 - width)}%`, width: `${width}%` }}
        />
      </div>
      <span className="text-right text-slate-600">{text}</span>
    </div>
  );
}

// 비용 대비 효과: 가격 변화 %와 성능(FPS 평균) 변화 %를 나란히 — "돈을 더 내면 얼마나 좋아지나"를 한눈에.
function CostEffectBars({
  currentPrice,
  targetPrice,
  baseAvg,
  compareAvg
}: {
  currentPrice?: number;
  targetPrice: number;
  baseAvg: number;
  compareAvg: number;
}) {
  const hasPrice = typeof currentPrice === 'number' && currentPrice > 0 && targetPrice > 0;
  const pricePercent = hasPrice ? percentDelta(currentPrice as number, targetPrice) : null;
  const perfPercent = percentDelta(baseAvg, compareAvg);
  const maxPercent = Math.max(Math.abs(pricePercent ?? 0), Math.abs(perfPercent), 1);

  return (
    <div className="space-y-1.5">
      <EffectBar
        label="가격"
        percent={pricePercent}
        maxPercent={maxPercent}
        barClass="bg-slate-400"
        textClass="text-slate-600"
      />
      <EffectBar
        label="성능"
        percent={perfPercent}
        maxPercent={maxPercent}
        barClass={perfPercent > 0 ? 'bg-emerald-500' : perfPercent < 0 ? 'bg-red-500' : 'bg-slate-300'}
        textClass={perfPercent > 0 ? 'text-emerald-600' : perfPercent < 0 ? 'text-red-600' : 'text-slate-500'}
        stagger
      />
    </div>
  );
}

// 추가 비용 요약 문구 — 기존 부품 가격이 없으면 그 사실을 밝힌다.
function priceDiffText(currentPrice: number | undefined, targetPrice: number): string {
  const hasPrice = typeof currentPrice === 'number' && currentPrice > 0 && targetPrice > 0;
  if (!hasPrice) return '기존 부품 가격 정보 없음';
  const formatter = new Intl.NumberFormat('ko-KR');
  const priceDiff = targetPrice - (currentPrice as number);
  if (priceDiff > 0) return `추가 비용 +${formatter.format(priceDiff)}원`;
  if (priceDiff < 0) return `추가 비용 -${formatter.format(Math.abs(priceDiff))}원 (절감)`;
  return '가격 차이 없음';
}

function EffectBar({
  label,
  percent,
  maxPercent,
  barClass,
  textClass,
  stagger = false
}: {
  label: string;
  percent: number | null;
  maxPercent: number;
  barClass: string;
  textClass: string;
  stagger?: boolean;
}) {
  const width = percent === null ? 0 : Math.max(3, Math.min(100, (Math.abs(percent) / maxPercent) * 100));
  return (
    <div className="grid grid-cols-[30px_1fr_48px] items-center gap-2 text-[11px] font-bold text-slate-500">
      <span>{label}</span>
      <div className="h-2.5 overflow-hidden rounded-full bg-slate-100">
        <div className={`h-full rounded-full ${barClass} perf-bar-grow${stagger ? ' perf-bar-stagger' : ''}`} style={{ width: `${width}%` }} />
      </div>
      <span className={`text-right font-black ${textClass}`}>{percent === null ? '-' : formatSignedPercent(percent)}</span>
    </div>
  );
}

function fpsRangeText(avg: number, low?: number) {
  return low !== undefined ? `${Math.round(low)}~${Math.round(avg)}` : `약 ${Math.round(avg)}`;
}

function percentDelta(from: number, to: number) {
  if (!(from > 0)) return 0;
  return Math.round(((to - from) / from) * 100);
}

function formatSignedPercent(percent: number) {
  return `${percent > 0 ? '+' : ''}${percent}%`;
}

function deltaBadgeTone(percent: number) {
  if (percent > 0) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (percent < 0) return 'border-red-200 bg-red-50 text-red-700';
  return 'border-slate-200 bg-slate-50 text-slate-500';
}

function feelLabel(fps: number): string {
  if (fps >= 100) return '매우 부드러움';
  if (fps >= 60) return '부드러움';
  if (fps >= 40) return '무난';
  if (fps >= 30) return '다소 끊김';
  return '끊김';
}

function feelTone(fps: number): { text: string; bar: string } {
  if (fps >= 100) return { text: 'text-brand-blue', bar: 'bg-brand-blue' };
  if (fps >= 60) return { text: 'text-emerald-600', bar: 'bg-emerald-500' };
  if (fps >= 40) return { text: 'text-amber-600', bar: 'bg-amber-500' };
  return { text: 'text-red-600', bar: 'bg-red-500' };
}

function feelStroke(fps: number): string {
  if (fps >= 100) return 'stroke-brand-blue';
  if (fps >= 60) return 'stroke-emerald-500';
  if (fps >= 40) return 'stroke-amber-500';
  return 'stroke-red-500';
}

// 그래픽 프리셋 → 사용자 언어(원어·소스 접두 노출 금지). 'PC_BUILDS_MEDIUM' 같은 원문에서 등급만 뽑는다.
function presetLabel(preset?: string | null): string {
  if (!preset) return '';
  const upper = preset.toUpperCase();
  if (upper.includes('ULTRA') || upper.includes('EPIC') || upper.includes('MAX')) return '최고 옵션';
  if (upper.includes('HIGH')) return '높음 옵션';
  if (upper.includes('MEDIUM') || upper.includes('MED')) return '중간 옵션';
  if (upper.includes('LOW')) return '낮음 옵션';
  return '';
}

// evidenceExactness → 사용자 언어(원어 노출 금지). 근거가 얼마나 이 견적에 가까운지.
function exactnessLabel(exactness?: string): string {
  switch (exactness) {
    case 'EXACT_PART_AND_RESOLUTION':
      return '이 부품 기준';
    case 'SAME_CLASS_AND_RESOLUTION':
      return '동급 부품 기준';
    case 'GPU_CLASS_REFERENCE':
    case 'GPU_CLASS_RESOLUTION_FALLBACK':
      return '동급 그래픽카드 기준';
    default:
      return '공개 참고 자료';
  }
}

function scoreBadgeTone(score: number) {
  if (score >= 850) return 'border-emerald-200 bg-emerald-50 text-emerald-700';
  if (score >= 600) return 'border-amber-200 bg-amber-50 text-amber-700';
  return 'border-red-200 bg-red-50 text-red-700';
}

function requestFitLabel(requestFit: NonNullable<BuildGraphResolveResponse['compositeScore']>['requestFit']) {
  if (!requestFit) return '요청 예산 정보 없음';
  const formatter = new Intl.NumberFormat('ko-KR');
  if (requestFit.status === 'OVER_BUDGET') {
    return `요청 예산 초과 · 차액 ${formatter.format(Math.abs(requestFit.priceDiff ?? 0))}원`;
  }
  if (requestFit.status === 'PASS') return '요청 예산 적합';
  if (requestFit.status === 'WARN') return '요청 예산 근접';
  return requestFit.summary || '요청 예산 정보 없음';
}

function requestFitTone(status?: string) {
  if (status === 'OVER_BUDGET') return 'bg-red-50 text-red-700';
  if (status === 'WARN') return 'bg-amber-50 text-amber-700';
  if (status === 'PASS') return 'bg-emerald-50 text-emerald-700';
  return 'bg-slate-100 text-slate-500';
}
