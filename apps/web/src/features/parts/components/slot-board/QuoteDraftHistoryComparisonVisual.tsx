import type { QuoteDraftHistoryComparison } from '../../types';
import { CompositeGhostArc } from './PerformanceComparisonVisuals';

const won = new Intl.NumberFormat('ko-KR');

type ComparisonSide = QuoteDraftHistoryComparison['past'];

export function QuoteDraftHistoryComparisonVisual({
  comparison
}: {
  comparison: QuoteDraftHistoryComparison;
}) {
  const currentScore = comparison.current.compositeScore;
  const restoreScore = comparison.past.compositeScore;
  const gameTitle = comparison.current.fps?.gameTitle
    ?? comparison.past.fps?.gameTitle
    ?? gameLabel(comparison.game);
  const resolution = comparison.resolution.toUpperCase();

  return (
    <section
      data-testid="quote-history-performance-comparison"
      aria-label="현재 견적을 선택한 변경 기록으로 복원했을 때의 가격 및 성능 변화"
      className="rounded-md border border-commerce-line bg-white px-3 py-3"
    >
      <div className="flex flex-wrap items-end justify-between gap-1">
        <div>
          <h3 className="text-xs font-black text-commerce-ink">가격·성능 변화</h3>
          <p className="mt-0.5 text-[10px] font-bold text-slate-400">현재 견적 → 복원 후</p>
        </div>
        <span className="text-[10px] font-black text-slate-500">{gameTitle} · {resolution}</span>
      </div>

      <div className="mt-2 grid items-center gap-3 sm:grid-cols-[minmax(150px,0.85fr)_minmax(0,1.15fr)]">
        <div className="min-w-0 border-b border-commerce-line pb-3 sm:border-b-0 sm:border-r sm:pb-0 sm:pr-3">
          {currentScore && restoreScore ? (
            <>
              <CompositeGhostArc
                baseScore={currentScore.score}
                compareScore={restoreScore.score}
                maxScore={Math.max(currentScore.maxScore, restoreScore.maxScore)}
                compareKey={comparison.history.id}
                testIdPrefix="quote-history-score"
                tone={restoreScore.score <= 0 || restoreScore.score < currentScore.score ? 'danger' : 'brand'}
                className="mx-auto max-w-[190px]"
              />
              <span className="sr-only">
                현재 종합점수 {currentScore.score} / {currentScore.maxScore}, 복원 후 종합점수 {restoreScore.score} / {restoreScore.maxScore}
              </span>
            </>
          ) : (
            <UnavailableScore past={comparison.past} current={comparison.current} />
          )}
        </div>

        <div className="min-w-0 space-y-3">
          <ComparisonBars
            testId="quote-history-price-comparison"
            label="예상가"
            currentValue={comparison.current.totalPrice}
            restoreValue={comparison.past.totalPrice}
            formatValue={(value) => `${won.format(value)}원`}
            deltaUnit="원"
            positiveIsGood={false}
          />
          {(comparison.past.fps || comparison.current.fps) ? (
            <ComparisonBars
              testId="quote-history-fps-comparison"
              label={`${gameTitle} ${resolution} 평균 FPS`}
              currentValue={fpsValue(comparison.current)}
              restoreValue={fpsValue(comparison.past)}
              formatValue={(value) => `${won.format(value)}FPS`}
              deltaUnit="FPS"
              positiveIsGood
            />
          ) : (
            <div data-testid="quote-history-fps-unavailable" className="text-[10px] font-bold text-slate-400">
              {gameTitle} {resolution} FPS 자료 없음
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function ComparisonBars({
  testId,
  label,
  currentValue,
  restoreValue,
  formatValue,
  deltaUnit,
  positiveIsGood
}: {
  testId: string;
  label: string;
  currentValue: number | null;
  restoreValue: number | null;
  formatValue: (value: number) => string;
  deltaUnit: string;
  positiveIsGood: boolean;
}) {
  const available = [currentValue, restoreValue].filter((value): value is number => typeof value === 'number');
  const max = Math.max(1, ...available);
  const delta = currentValue !== null && restoreValue !== null ? restoreValue - currentValue : null;

  return (
    <div data-testid={testId}>
      <div className="flex min-w-0 items-start justify-between gap-2">
        <span className="min-w-0 text-[10px] font-black leading-4 text-slate-600">{label}</span>
        {delta !== null ? (
          <span className={`perf-pop-in shrink-0 text-[10px] font-black ${deltaTone(delta, positiveIsGood)}`}>
            {delta > 0 ? '+' : ''}{won.format(delta)}{deltaUnit}
          </span>
        ) : null}
      </div>
      <div className="mt-1.5 space-y-1.5">
        <BarRow label="현재" value={currentValue} max={max} formatValue={formatValue} tone="current" />
        <BarRow label="복원 후" value={restoreValue} max={max} formatValue={formatValue} tone="restore" />
      </div>
    </div>
  );
}

function BarRow({
  label,
  value,
  max,
  formatValue,
  tone
}: {
  label: string;
  value: number | null;
  max: number;
  formatValue: (value: number) => string;
  tone: 'current' | 'restore';
}) {
  const width = value === null ? 0 : Math.max(3, Math.min(100, (Math.max(0, value) / max) * 100));
  return (
    <div className="grid min-w-0 grid-cols-[28px_minmax(0,1fr)] items-center gap-2">
      <span className="text-[9px] font-black text-slate-400">{label}</span>
      <div className="min-w-0">
        <div className="flex min-w-0 items-center gap-2">
          <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-slate-100">
            <div
              className={`perf-bar-grow h-full rounded-full ${tone === 'current' ? 'bg-slate-400' : 'perf-bar-stagger bg-brand-blue'}`}
              style={{ width: `${width}%` }}
            />
          </div>
          <span className={`shrink-0 text-[10px] font-black ${tone === 'current' ? 'text-slate-500' : 'text-brand-blue'}`}>
            {value === null ? '자료 없음' : formatValue(value)}
          </span>
        </div>
      </div>
    </div>
  );
}

function UnavailableScore({ past, current }: { past: ComparisonSide; current: ComparisonSide }) {
  return (
    <div data-testid="quote-history-score-unavailable" className="rounded-md bg-slate-50 p-3 text-center">
      <div className="text-[10px] font-black text-slate-400">종합점수</div>
      <div className="mt-1 text-xs font-black text-commerce-ink">
        현재 {scoreValue(current)} → 복원 후 {scoreValue(past)}
      </div>
    </div>
  );
}

function fpsValue(side: ComparisonSide) {
  return typeof side.fps?.avgFps === 'number' ? side.fps.avgFps : null;
}

function scoreValue(side: ComparisonSide) {
  return side.compositeScore ? `${side.compositeScore.score} / ${side.compositeScore.maxScore}` : '자료 없음';
}

function deltaTone(delta: number, positiveIsGood: boolean) {
  if (delta === 0) return 'text-slate-500';
  const good = positiveIsGood ? delta > 0 : delta < 0;
  return good ? 'text-emerald-600' : 'text-red-600';
}

function gameLabel(game: string) {
  const normalized = game.trim().toLowerCase();
  if (normalized === 'pubg' || normalized === '배그') return '배그';
  if (normalized === 'overwatch2') return '오버워치2';
  if (normalized === 'cyberpunk2077') return '사이버펑크 2077';
  return game;
}
