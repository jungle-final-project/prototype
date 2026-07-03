import { FormEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BellRing, CheckCircle2, FileText, PencilLine, Save, Target } from 'lucide-react';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { createQuotePriceAlert, getBuildHistory, getPriceAlerts, type PriceAlert } from '../quoteApi';
import type { BuildItem, BuildSummary } from '../types';

type AlertInputMode = 'saved-part' | 'manual';

type SavedPartOption = {
  partId: string;
  label: string;
  category: string;
  buildName: string;
  price: number;
};

export function MyQuotesPage() {
  const queryClient = useQueryClient();
  const [partId, setPartId] = useState('');
  const [selectedSavedPartId, setSelectedSavedPartId] = useState('');
  const [targetPrice, setTargetPrice] = useState('850000');
  const [alertInputMode, setAlertInputMode] = useState<AlertInputMode>('saved-part');
  const alertFormRef = useRef<HTMLDivElement | null>(null);

  const buildsQuery = useQuery({ queryKey: ['build-history'], queryFn: getBuildHistory });
  const alertsQuery = useQuery({ queryKey: ['price-alerts'], queryFn: getPriceAlerts });

  const builds = buildsQuery.data?.items ?? [];
  const alerts = alertsQuery.data?.items ?? [];
  const savedPartOptions = useMemo(() => collectSavedPartOptions(builds), [builds]);
  const effectiveAlertInputMode: AlertInputMode = savedPartOptions.length === 0 ? 'manual' : alertInputMode;
  const selectedSavedPart = savedPartOptions.find((option) => option.partId === selectedSavedPartId);
  const selectedPartIdForSubmit = effectiveAlertInputMode === 'saved-part' ? selectedSavedPartId : partId.trim();
  const targetPriceNumber = Number(targetPrice.replace(/,/g, ''));
  const achievedAlertCount = alerts.filter((alert) => isPriceTargetAchieved(alert)).length;
  const nearestAlert = useMemo(() => findNearestAlert(alerts), [alerts]);

  useEffect(() => {
    if (!selectedSavedPartId && savedPartOptions[0]) {
      setSelectedSavedPartId(savedPartOptions[0].partId);
    }
  }, [savedPartOptions, selectedSavedPartId]);

  const createAlertMutation = useMutation({
    mutationFn: () => createQuotePriceAlert(selectedPartIdForSubmit, targetPriceNumber),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['price-alerts'] })
  });

  function submitAlert(event: FormEvent) {
    event.preventDefault();
    if (!selectedPartIdForSubmit || !targetPriceNumber) {
      return;
    }
    createAlertMutation.mutate();
  }

  function selectBuildPartForAlert(build: BuildSummary) {
    const firstPartId = resolvePartId(build.items?.[0]);
    if (!firstPartId) return;
    setAlertInputMode('saved-part');
    setSelectedSavedPartId(firstPartId);
    alertFormRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  return (
    <Screen>
      <div className="space-y-5">
        <section className="rounded-md border border-commerce-line bg-white p-5 shadow-product">
          <div className="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
            <div>
              <p className="text-xs font-black tracking-wide text-brand-blue">견적 데스크</p>
              <h1 className="mt-1 text-2xl font-black tracking-tight text-commerce-ink">내 견적함 / 목표가 알림</h1>
              <p className="mt-2 max-w-3xl break-keep text-sm leading-6 text-slate-600">
                저장한 견적을 확인하고, 관심 부품의 현재가가 목표가에 가까워지는지 한 화면에서 추적합니다.
              </p>
            </div>
            <div className="grid gap-3 sm:grid-cols-3 xl:min-w-[520px]">
              <SummaryMetric testId="my-quotes-build-count" icon={<FileText size={17} />} label="저장 견적" value={`${builds.length}개`} />
              <SummaryMetric testId="my-quotes-alert-count" icon={<BellRing size={17} />} label="목표가 알림" value={`${alerts.length}개`} />
              <SummaryMetric testId="my-quotes-achieved-count" icon={<CheckCircle2 size={17} />} label="목표 달성" value={`${achievedAlertCount}개`} tone="success" />
            </div>
          </div>
        </section>

        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
          <Panel
            title="저장 견적"
            subtitle="상세 확인, 부품 변경, 목표가 알림 등록까지 바로 이어집니다."
            action={<Link to="/requirements/new" className="rounded-md bg-brand-blue px-3 py-2 text-xs font-black text-white hover:bg-blue-700">AI 견적 시작</Link>}
          >
            {buildsQuery.isLoading ? (
              <SavedBuildSkeleton />
            ) : buildsQuery.isError ? (
              <StateMessage type="warn" title="견적 조회 실패" body="저장된 견적을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요." />
            ) : builds.length ? (
              <div className="space-y-3">
                {builds.map((build) => (
                  <SavedBuildCard key={build.id} build={build} onAlertSelect={selectBuildPartForAlert} />
                ))}
              </div>
            ) : (
              <div className="space-y-3">
                <StateMessage type="info" title="저장된 견적 없음" body="AI 추천 또는 셀프 견적으로 조합을 만든 뒤 저장하면 이곳에서 다시 확인할 수 있습니다." />
                <div className="flex flex-wrap gap-2">
                  <Link to="/requirements/new" className="rounded-md bg-brand-blue px-4 py-2.5 text-sm font-bold text-white hover:bg-blue-700">AI 견적 시작</Link>
                  <Link to="/self-quote" className="rounded-md border border-slate-300 px-4 py-2.5 text-sm font-bold text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">셀프 견적 시작</Link>
                </div>
              </div>
            )}
          </Panel>

          <div ref={alertFormRef} className="xl:sticky xl:top-5 xl:self-start">
            <Panel title="목표가 알림 등록" subtitle="저장 견적의 부품을 선택하거나 부품 ID를 직접 입력할 수 있습니다.">
              <form onSubmit={submitAlert} className="space-y-4">
                <div className="grid grid-cols-2 gap-2 rounded-md bg-slate-100 p-1">
                  <button
                    type="button"
                    onClick={() => setAlertInputMode('saved-part')}
                    disabled={savedPartOptions.length === 0}
                    className={`min-h-9 rounded px-3 text-xs font-black transition disabled:cursor-not-allowed disabled:text-slate-400 ${
                      effectiveAlertInputMode === 'saved-part'
                        ? 'bg-white text-brand-blue shadow-sm'
                        : 'text-slate-600 hover:text-commerce-ink'
                    }`}
                  >
                    저장 부품 선택
                  </button>
                  <button
                    type="button"
                    onClick={() => setAlertInputMode('manual')}
                    className={`min-h-9 rounded px-3 text-xs font-black transition ${
                      effectiveAlertInputMode === 'manual'
                        ? 'bg-white text-brand-blue shadow-sm'
                        : 'text-slate-600 hover:text-commerce-ink'
                    }`}
                  >
                    직접 입력
                  </button>
                </div>

                {effectiveAlertInputMode === 'saved-part' ? (
                  <div>
                    <label htmlFor="quote-alert-saved-part" className="mb-1 block text-xs font-black text-slate-600">저장 견적 부품</label>
                    <select
                      id="quote-alert-saved-part"
                      className="h-11 w-full rounded-md border border-slate-300 bg-white px-3 text-sm font-bold text-commerce-ink focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
                      value={selectedSavedPartId}
                      onChange={(event) => setSelectedSavedPartId(event.target.value)}
                    >
                      {savedPartOptions.map((option) => (
                        <option key={option.partId} value={option.partId}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                    {selectedSavedPart ? (
                      <p className="mt-2 break-keep text-xs leading-5 text-slate-500">
                        {selectedSavedPart.buildName} · 현재 저장가 {selectedSavedPart.price.toLocaleString()}원
                      </p>
                    ) : null}
                  </div>
                ) : (
                  <div>
                    <label htmlFor="quote-alert-part-id" className="mb-1 block text-xs font-black text-slate-600">부품 ID 직접 입력</label>
                    <input
                      id="quote-alert-part-id"
                      className="h-11 w-full rounded-md border border-slate-300 px-3 text-sm font-bold text-commerce-ink focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
                      value={partId}
                      onChange={(event) => setPartId(event.target.value)}
                      placeholder="part-public-id"
                    />
                  </div>
                )}

                <div>
                  <label htmlFor="quote-alert-target-price" className="mb-1 block text-xs font-black text-slate-600">목표가</label>
                  <input
                    id="quote-alert-target-price"
                    className="h-11 w-full rounded-md border border-slate-300 px-3 text-sm font-bold text-commerce-ink focus:border-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
                    inputMode="numeric"
                    value={targetPrice}
                    onChange={(event) => setTargetPrice(event.target.value)}
                  />
                </div>

                <button
                  disabled={createAlertMutation.isPending || !selectedPartIdForSubmit || !targetPriceNumber}
                  className="flex w-full min-h-11 items-center justify-center rounded-md bg-brand-blue px-4 py-3 text-sm font-black text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-400"
                >
                  <Save className="mr-1.5 inline" size={15} /> {createAlertMutation.isPending ? '등록 중' : '알림 등록'}
                </button>

                {nearestAlert ? (
                  <div className="rounded-md border border-blue-100 bg-blue-50 px-3 py-2">
                    <div className="text-xs font-black text-slate-500">가장 가까운 목표</div>
                    <div className="mt-1 text-sm font-black text-commerce-ink">{nearestAlert.partName}</div>
                    <div className="mt-1 text-xs font-bold text-brand-blue">{priceAlertDeltaText(nearestAlert)}</div>
                  </div>
                ) : null}

                {createAlertMutation.isSuccess ? <StateMessage type="success" title="알림 등록 완료" body="목표가 알림 목록에 반영했습니다." /> : null}
                {createAlertMutation.isError ? <StateMessage type="warn" title="알림 등록 실패" body="이미 같은 목표가 알림이 있거나 부품 ID가 유효하지 않습니다." /> : null}
              </form>
            </Panel>
          </div>

          <Panel title="목표가 알림" subtitle="현재가가 목표가에 얼마나 가까운지 차액과 진행률로 확인합니다." className="xl:col-span-2">
            {alertsQuery.isLoading ? (
              <AlertSkeleton />
            ) : alertsQuery.isError ? (
              <StateMessage type="warn" title="알림 조회 실패" body="등록된 목표가 알림을 불러오지 못했습니다. 잠시 후 다시 확인해 주세요." />
            ) : alerts.length ? (
              <div className="grid gap-3 lg:grid-cols-2">
                {alerts.map((alert) => (
                  <PriceAlertRow key={`${alert.partId}-${alert.targetPrice}`} alert={alert} />
                ))}
              </div>
            ) : (
              <StateMessage type="info" title="등록된 알림 없음" body="저장 견적의 관심 부품을 선택하고 목표가를 등록해 보세요." />
            )}
          </Panel>
        </div>
      </div>
    </Screen>
  );
}

function SummaryMetric({
  icon,
  label,
  value,
  testId,
  tone = 'default'
}: {
  icon: ReactNode;
  label: string;
  value: string;
  testId: string;
  tone?: 'default' | 'success';
}) {
  return (
    <div data-testid={testId} className={`rounded-md border px-3 py-3 ${
      tone === 'success'
        ? 'border-emerald-100 bg-emerald-50'
        : 'border-slate-200 bg-slate-50'
    }`}
    >
      <div className="flex items-center gap-2 text-xs font-black text-slate-500">
        <span className={tone === 'success' ? 'text-emerald-600' : 'text-brand-blue'}>{icon}</span>
        {label}
      </div>
      <div className="mt-1 text-xl font-black text-commerce-ink">{value}</div>
    </div>
  );
}

function ConfidencePill({ confidence }: { confidence: string }) {
  const value = confidence.toUpperCase();
  const label = value === 'HIGH' ? '근거 높음' : value === 'MEDIUM' ? '근거 보통' : value === 'LOW' ? '근거 낮음' : confidence;
  const className = value === 'HIGH'
    ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
    : value === 'MEDIUM'
      ? 'border-orange-200 bg-orange-50 text-orange-700'
      : 'border-slate-200 bg-slate-100 text-slate-600';

  return <span className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-black ${className}`}>{label}</span>;
}

function AlertStatusPill({ alert }: { alert: PriceAlert }) {
  const achieved = isPriceTargetAchieved(alert);
  return (
    <span className={`inline-flex rounded-full border px-2 py-1 text-[11px] font-black ${
      achieved
        ? 'border-emerald-200 bg-emerald-100 text-emerald-700'
        : 'border-blue-200 bg-blue-50 text-brand-blue'
    }`}
    >
      {achieved ? '목표 도달' : '추적 중'}
    </span>
  );
}

function SavedBuildCard({ build, onAlertSelect }: { build: BuildSummary; onAlertSelect: (build: BuildSummary) => void }) {
  const mainItems = (build.items ?? []).slice(0, 4);
  const hasAlertablePart = Boolean(resolvePartId(build.items?.[0]));

  return (
    <article data-testid={`saved-build-card-${build.id}`} className="rounded-md border border-slate-200 bg-white p-4 transition hover:border-blue-200 hover:shadow-product">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded bg-blue-50 px-2 py-1 text-[11px] font-black text-brand-blue">{build.recommendedFor ?? '저장 견적'}</span>
            <ConfidencePill confidence={build.confidence} />
            {build.warnings?.length ? <span className="rounded bg-orange-50 px-2 py-1 text-[11px] font-black text-orange-700">주의 {build.warnings.length}건</span> : null}
          </div>
          <h2 className="mt-2 text-lg font-black leading-6 text-commerce-ink">{build.name}</h2>
          <p className="mt-1 line-clamp-2 break-keep text-sm leading-6 text-slate-600">
            {build.summary ?? '내부 자산과 저장된 현재가 기준으로 구성한 견적입니다.'}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            {mainItems.map((item) => (
              <span key={`${build.id}-${item.category}-${resolvePartId(item) ?? item.name}`} className="rounded-md border border-slate-200 bg-slate-50 px-2 py-1 text-xs font-bold text-slate-600" title={item.name}>
                {labelForCategory(item.category)} · {item.name}
              </span>
            ))}
          </div>
        </div>
        <div className="shrink-0 lg:text-right">
          <div className="text-xs font-black text-slate-500">견적 합계</div>
          <div className="mt-1 text-2xl font-black text-brand-blue">{build.totalPrice.toLocaleString()}원</div>
          <div className="mt-1 text-xs font-semibold text-slate-500">{formatDateTime(build.createdAt)}</div>
        </div>
      </div>
      <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
        <Link to={`/builds/${build.id}`} className="inline-flex min-h-9 items-center gap-1.5 rounded-md bg-brand-blue px-3 text-xs font-black text-white hover:bg-blue-700">
          <FileText size={14} /> 견적 상세
        </Link>
        <Link to={`/builds/${build.id}/change-part`} className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 text-xs font-black text-slate-700 hover:border-commerce-ink hover:text-commerce-ink">
          <PencilLine size={14} /> 부품 변경
        </Link>
        <button
          type="button"
          disabled={!hasAlertablePart}
          onClick={() => onAlertSelect(build)}
          className="inline-flex min-h-9 items-center gap-1.5 rounded-md border border-blue-100 bg-blue-50 px-3 text-xs font-black text-brand-blue hover:border-blue-200 disabled:cursor-not-allowed disabled:border-slate-200 disabled:bg-slate-50 disabled:text-slate-400"
        >
          <Target size={14} /> 목표가 등록
        </button>
      </div>
    </article>
  );
}

function PriceAlertRow({ alert }: { alert: PriceAlert }) {
  const achieved = isPriceTargetAchieved(alert);
  const progress = alert.currentPrice > 0
    ? Math.min(100, Math.max(8, (alert.targetPrice / alert.currentPrice) * 100))
    : 0;

  return (
    <article data-testid={`price-alert-row-${alert.partId}`} className={`rounded-md border p-4 ${
      achieved
        ? 'border-emerald-200 bg-emerald-50'
        : 'border-slate-200 bg-white'
    }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <AlertStatusPill alert={alert} />
            <span className={`rounded px-2 py-1 text-[11px] font-black ${
              achieved ? 'bg-emerald-100 text-emerald-700' : 'bg-blue-50 text-brand-blue'
            }`}
            >
              {priceAlertDeltaText(alert)}
            </span>
          </div>
          <h3 className="mt-2 truncate text-base font-black text-commerce-ink" title={alert.partName}>{alert.partName}</h3>
          <p className="mt-1 text-xs font-semibold text-slate-500">등록일 {formatDateTime(alert.createdAt)}</p>
        </div>
        <div className="shrink-0 text-right">
          <div className="text-xs font-black text-slate-500">현재가</div>
          <div className="mt-1 text-lg font-black text-brand-blue">{alert.currentPrice.toLocaleString()}원</div>
        </div>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3 text-sm">
        <div className="rounded-md bg-white/70 px-3 py-2">
          <div className="text-xs font-black text-slate-500">목표가</div>
          <div className="mt-1 font-black text-commerce-ink">{alert.targetPrice.toLocaleString()}원</div>
        </div>
        <div className="rounded-md bg-white/70 px-3 py-2">
          <div className="text-xs font-black text-slate-500">차액</div>
          <div className={`mt-1 font-black ${achieved ? 'text-emerald-700' : 'text-commerce-ink'}`}>{formatPriceDelta(alert)}</div>
        </div>
      </div>
      <div className="mt-4 h-2 overflow-hidden rounded-full bg-slate-100">
        <div className={`h-full rounded-full ${achieved ? 'bg-emerald-500' : 'bg-brand-blue'}`} style={{ width: `${progress}%` }} />
      </div>
    </article>
  );
}

function SavedBuildSkeleton() {
  return (
    <div className="space-y-3">
      {[0, 1].map((index) => (
        <div key={index} className="h-36 animate-pulse rounded-md border border-slate-200 bg-slate-50" />
      ))}
    </div>
  );
}

function AlertSkeleton() {
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      {[0, 1].map((index) => (
        <div key={index} className="h-40 animate-pulse rounded-md border border-slate-200 bg-slate-50" />
      ))}
    </div>
  );
}

function collectSavedPartOptions(builds: BuildSummary[]): SavedPartOption[] {
  const seen = new Set<string>();
  const options: SavedPartOption[] = [];

  for (const build of builds) {
    for (const item of build.items ?? []) {
      const partId = resolvePartId(item);
      if (!partId || seen.has(partId)) continue;
      seen.add(partId);
      options.push({
        partId,
        label: `${labelForCategory(item.category)} · ${item.name}`,
        category: item.category,
        buildName: build.name,
        price: item.price
      });
    }
  }

  return options;
}

function resolvePartId(item?: BuildItem) {
  return item?.partId ?? item?.id ?? '';
}

function isPriceTargetAchieved(alert: PriceAlert) {
  return alert.currentPrice <= alert.targetPrice || alert.status.toUpperCase() === 'TRIGGERED';
}

function findNearestAlert(alerts: PriceAlert[]) {
  return [...alerts]
    .filter((alert) => !isPriceTargetAchieved(alert))
    .sort((a, b) => Math.abs(a.currentPrice - a.targetPrice) - Math.abs(b.currentPrice - b.targetPrice))[0]
    ?? alerts.find(isPriceTargetAchieved);
}

function priceAlertDeltaText(alert: PriceAlert) {
  if (isPriceTargetAchieved(alert)) {
    return '목표 달성';
  }
  return `목표까지 ${(alert.currentPrice - alert.targetPrice).toLocaleString()}원`;
}

function formatPriceDelta(alert: PriceAlert) {
  const delta = alert.currentPrice - alert.targetPrice;
  if (delta === 0) {
    return '0원';
  }
  if (delta < 0) {
    return `${Math.abs(delta).toLocaleString()}원 낮음`;
  }
  return `${delta.toLocaleString()}원 높음`;
}

function labelForCategory(category: string) {
  switch (category) {
    case 'MOTHERBOARD':
      return '메인보드';
    case 'STORAGE':
      return 'SSD';
    case 'PSU':
      return '파워';
    case 'CASE':
      return '케이스';
    case 'COOLER':
      return '쿨러';
    default:
      return category;
  }
}

function formatDateTime(value?: string) {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value.replace('T', ' ').slice(0, 19);
  }
  return date.toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}
