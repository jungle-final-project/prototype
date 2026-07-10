import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { BuildGraphResolveResponse } from '../../../quote/aiSelection';
import { CompositeScoreGauge } from '../../../quote/components/CompositeScoreGauge';
import type { PerfCompareTarget } from '../../../../lib/events';
import { checkBuildPerformance, type GameFpsEvidence } from '../../../quote/quoteApi';

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

type PerfTab = 'composite' | 'game';

// 종합 점수(단일값)와 게임 성능(비교 가능)을 한 카드의 모드 탭으로 통합한다.
// comparison이 없으면 기존과 동일하게 동작한다 — MyQuotesPage 등 다른 사용처는 탭만 추가된 형태.
export function QuotePerformancePanel({
  graph,
  items,
  comparison = null,
  onClearComparison
}: {
  graph?: BuildGraphResolveResponse;
  items: PerfItem[];
  comparison?: PerfCompareTarget | null;
  onClearComparison?: () => void;
}) {
  const [tab, setTab] = useState<PerfTab>('composite');

  // 비교가 켜지면 비교 UI가 있는 게임 성능 탭으로 자동 전환한다(종합점수는 비교하지 않는다).
  useEffect(() => {
    if (comparison) {
      setTab('game');
    }
  }, [comparison]);

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
        <span className="text-[10px] font-bold text-slate-400">
          {tab === 'composite' ? '호환·성능·여유 종합 1000점' : '공개 자료 기준 참고치'}
        </span>
      </div>

      {/* 모드 탭: 종합 점수(단일값) / 게임 성능(게임·해상도별 FPS + 교체 비교). */}
      <div className="mb-3 flex gap-1 rounded-lg border border-commerce-line bg-slate-50 p-1" role="tablist" aria-label="성능 보기 선택">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'composite'}
          data-testid="perf-tab-composite"
          onClick={() => setTab('composite')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-black transition ${
            tab === 'composite' ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
          }`}
        >
          종합 점수
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'game'}
          data-testid="perf-tab-game"
          onClick={() => setTab('game')}
          className={`flex-1 rounded-md px-3 py-1.5 text-xs font-black transition ${
            tab === 'game' ? 'bg-white text-commerce-ink shadow-sm' : 'text-slate-400 hover:text-slate-600'
          }`}
        >
          게임 성능
        </button>
      </div>

      {/* 탭 전환 모션: key 교체로 콘텐츠만 가볍게 페이드인한다(150ms) — 과한 슬라이드는 쓰지 않는다. */}
      <div data-testid="quote-performance-grid" key={tab} className="perf-tab-fade-in">
        {tab === 'composite' ? (
          <>
            <div data-testid="quote-composite-score-card" className="mx-auto max-w-sm rounded-lg border border-commerce-line bg-slate-50/70 p-3">
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
            </div>
            <p className="mt-3 text-[10px] leading-relaxed text-slate-400">
              종합 점수는 공개 벤치마크·공식 스펙·호환성 검증 기반 참고값입니다 — 실제 성능이나 정확한 FPS를 보장하지 않습니다.
            </p>
          </>
        ) : hasGpu ? (
          <GameFpsSection items={perfItems} comparison={comparison} onClearComparison={onClearComparison} />
        ) : (
          <div data-testid="fps-no-gpu" className="rounded-lg border border-dashed border-slate-300 bg-white p-4 text-center text-[11px] font-bold text-slate-500">
            그래픽카드를 담으면 게임 예상 성능을 보여드려요.
          </div>
        )}
      </div>
    </section>
  );
}

// 게임별 FPS 참고범위: 게임·해상도를 고르면 담긴 CPU/GPU 기준 공개 자료 FPS를 조회한다(기존 툴 엔드포인트).
// comparison이 있으면 같은 엔드포인트를 후보 부품으로 치환한 목록으로 한 번 더 조회해 "기존 vs 변경"을 겹쳐 보여준다.
function GameFpsSection({
  items,
  comparison,
  onClearComparison
}: {
  items: PerfItem[];
  comparison: PerfCompareTarget | null;
  onClearComparison?: () => void;
}) {
  const [gameKey, setGameKey] = useState<string>(FPS_GAMES[0].key);
  const [resKey, setResKey] = useState<string>('QHD');
  const game = FPS_GAMES.find((g) => g.key === gameKey) ?? FPS_GAMES[0];
  const resolution = FPS_RESOLUTIONS.find((r) => r.key === resKey) ?? FPS_RESOLUTIONS[1];

  const partIds = items.map((item) => item.partId).filter(Boolean);
  const partKey = useMemo(() => [...partIds].sort().join(','), [partIds]);

  // 비교 대상이 현재 견적과 어긋나면(같은 부품이거나 기존 부품이 없음) 비교를 그리지 않는다 — 상위에서 해제하지만 이중 안전망.
  const currentPart = comparison ? items.find((item) => item.category === comparison.category) : undefined;
  const activeComparison = comparison && currentPart && currentPart.partId !== comparison.partId ? comparison : null;
  const comparePartIds = activeComparison
    ? items.map((item) => (item.category === activeComparison.category ? activeComparison.partId : item.partId)).filter(Boolean)
    : [];
  const compareKey = useMemo(() => [...comparePartIds].sort().join(','), [comparePartIds]);

  const { data, isFetching, isError } = useQuery({
    queryKey: ['quote-fps', partKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds, game: game.query, resolution: resolution.query }),
    enabled: partIds.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 5 * 60 * 1000
  });
  // 변경 조합 조회 — queryKey에 비교 대상 partId가 섞인 목록이 들어가 후보가 바뀌면 다시 조회한다.
  const compareQuery = useQuery({
    queryKey: ['quote-fps', compareKey, game.key, resolution.key],
    queryFn: () => checkBuildPerformance({ partIds: comparePartIds, game: game.query, resolution: resolution.query }),
    enabled: Boolean(activeComparison) && comparePartIds.length > 0,
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
  // 비교가 켜져 있는데 변경 조합 자료가 없으면 비교를 강제하지 않는다 — 기존만 보여주고 사유를 알린다.
  const isCompareReady = Boolean(activeComparison) && hasAvg && hasCompareAvg;
  const isCompareEmpty = Boolean(activeComparison) && !hasCompareAvg && (compareQuery.isError || compareQuery.data !== undefined);

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

  return (
    <div data-testid="quote-fps-section" className="rounded-lg border border-commerce-line bg-white p-3">
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

      <div className="mb-2.5 flex flex-wrap gap-1.5">
        {FPS_GAMES.map((g) => (
          <button
            key={g.key}
            type="button"
            data-testid={`fps-game-${g.key}`}
            aria-pressed={gameKey === g.key}
            onClick={() => setGameKey(g.key)}
            className={`rounded-full border px-2.5 py-1 text-[11px] font-black transition ${
              gameKey === g.key
                ? 'border-brand-blue bg-brand-blue text-white'
                : 'border-commerce-line bg-white text-slate-600 hover:border-brand-blue'
            }`}
          >
            {g.label}
          </button>
        ))}
      </div>

      {activeComparison ? (
        <div data-testid="fps-compare-banner" className="mb-2.5 flex flex-wrap items-center justify-between gap-2 rounded-md border border-blue-100 bg-blue-50/60 px-2.5 py-1.5">
          <span className="min-w-0 text-[11px] font-bold text-slate-600">
            <span className="font-black text-brand-blue">교체 비교</span>
            {' · '}
            {currentPart?.name ?? '지금 담긴 부품'} → {activeComparison.name}
          </span>
          {onClearComparison ? (
            <button
              type="button"
              data-testid="compare-clear"
              onClick={onClearComparison}
              className="shrink-0 rounded border border-commerce-line bg-white px-2 py-1 text-[10px] font-black text-slate-600 transition hover:border-commerce-ink hover:text-commerce-ink"
            >
              기존 견적만 보기
            </button>
          ) : null}
        </div>
      ) : null}

      {isCompareEmpty ? (
        <div data-testid="fps-compare-empty" className="mb-2.5 rounded-md border border-dashed border-slate-300 bg-slate-50/60 px-2.5 py-2 text-[11px] font-bold text-slate-500">
          변경 조합의 공개 참고 자료가 없어요 — 지금 담긴 조합 기준으로만 보여드려요.
        </div>
      ) : null}

      {isFetching && !evidence ? (
        <div className="h-40 animate-pulse rounded-lg bg-slate-100" />
      ) : hasAvg ? (
        <div data-testid="fps-result" className="rounded-lg border border-commerce-line bg-slate-50/60 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-[11px] font-black text-slate-600">
              {game.label} · {evidence?.resolution ?? resolution.label}
              {presetLabel(evidence?.graphicsPreset) ? ` · ${presetLabel(evidence?.graphicsPreset)}` : ''}
            </span>
            {!isCompareReady ? (
              <span className={`text-[10px] font-black ${feelTone(avg).text}`}>{feelLabel(avg)}</span>
            ) : null}
          </div>

          <div className={isCompareReady ? 'mt-2 grid gap-3 lg:grid-cols-[minmax(240px,320px)_minmax(0,1fr)] lg:items-center' : 'mt-2'}>
            {/* 위계 1: 곡선(반원 아크) 게이지 — 비교 시 기존은 회색 고스트, 변경은 파랑으로 겹쳐 그린다. */}
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

            {isCompareReady && activeComparison ? (
              <div className="space-y-2.5">
                {/* 위계 2: 기존/변경 FPS 범위(1% 최저 ~ 평균) 수평 바 — 두 조합의 흔들림 폭을 나란히 본다. */}
                <div data-testid="fps-range-bars" className="perf-block-in space-y-1.5 rounded-lg border border-commerce-line bg-white p-2.5">
                  <FpsRangeBar label="기존" avg={avg} low={hasLow ? low : undefined} tone="base" />
                  <FpsRangeBar label="변경" avg={compareAvg} low={hasCompareLow ? compareLow : undefined} tone="changed" />
                </div>
                {/* 위계 3: 비용 대비 효과 — 가격 변화 vs 성능 변화. */}
                <CostEffectBlock
                  currentPrice={currentPart?.currentPrice}
                  targetPrice={activeComparison.price}
                  baseAvg={avg}
                  baseLow={hasLow ? low : undefined}
                  compareAvg={compareAvg}
                  compareLow={hasCompareLow ? compareLow : undefined}
                />
              </div>
            ) : null}
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
        <div data-testid="fps-empty" className="rounded-lg border border-dashed border-slate-300 bg-white p-3 text-center text-[11px] font-bold text-slate-500">
          {isError ? '참고 자료를 불러오지 못했습니다.' : '이 조합의 공개 참고 자료가 아직 없어요.'}
        </div>
      )}

      <p className="mt-2 text-[10px] leading-relaxed text-slate-400">
        공개 자료 기준 참고 범위입니다 — 실제 FPS는 게임 설정·패치·드라이버에 따라 달라집니다.
      </p>
    </div>
  );
}

const FPS_ARC_PATH = 'M 24 112 A 86 86 0 0 1 196 112';

// FPS를 체감 경험으로 — 종합점수 게이지와 같은 시각 언어(반원 아크 + 중앙 큰 숫자)의 FPS 버전.
// CompositeScoreGauge는 홈/견적함 공용이라 손대지 않고 slot-board 로컬로 둔다.
// displayAvg/compareDisplay는 상위(GameFpsSection)에서 rAF로 보간된 표시값이다 — 아크와 숫자가 같은 보간을 공유한다.
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
function CostEffectBlock({
  currentPrice,
  targetPrice,
  baseAvg,
  baseLow,
  compareAvg,
  compareLow
}: {
  currentPrice?: number;
  targetPrice: number;
  baseAvg: number;
  baseLow?: number;
  compareAvg: number;
  compareLow?: number;
}) {
  const hasPrice = typeof currentPrice === 'number' && currentPrice > 0 && targetPrice > 0;
  const priceDiff = hasPrice ? targetPrice - (currentPrice as number) : null;
  const pricePercent = hasPrice ? percentDelta(currentPrice as number, targetPrice) : null;
  const perfPercent = percentDelta(baseAvg, compareAvg);
  const maxPercent = Math.max(Math.abs(pricePercent ?? 0), Math.abs(perfPercent), 1);
  const formatter = new Intl.NumberFormat('ko-KR');

  // 블록 자체는 fade/slide-in — 범위 바(120ms 뒤)보다 한 박자 늦게 떠올라 위→아래로 읽힌다.
  return (
    <details data-testid="cost-effect-block" open className="perf-block-in rounded-lg border border-commerce-line bg-white p-2.5" style={{ animationDelay: '120ms' }}>
      <summary className="cursor-pointer select-none text-[11px] font-black text-slate-600">비용 대비 효과</summary>
      <div className="mt-2 space-y-1.5">
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
      <div className="mt-2 flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-[10px] font-bold text-slate-500">
        <span data-testid="cost-effect-price">
          {priceDiff === null
            ? '기존 부품 가격 정보 없음'
            : priceDiff > 0
              ? `추가 비용 +${formatter.format(priceDiff)}원`
              : priceDiff < 0
                ? `추가 비용 -${formatter.format(Math.abs(priceDiff))}원 (절감)`
                : '가격 차이 없음'}
        </span>
        <span data-testid="cost-effect-fps">
          예상 FPS {fpsRangeText(baseAvg, baseLow)} → {fpsRangeText(compareAvg, compareLow)}
        </span>
      </div>
    </details>
  );
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
