import { AlertTriangle, ArrowLeft, ChevronDown, History, LoaderCircle, RotateCcw } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { useState, type ReactNode } from 'react';
import { ApiError } from '../../../../lib/api';
import {
  getQuoteDraftHistoryComparison,
  listQuoteDraftHistory,
  restoreQuoteDraftHistory
} from '../../partsApi';
import type { QuoteDraftHistoryDifference, QuoteDraftHistoryEntry, QuoteDraftHistoryIssue } from '../../types';
import { FloatingQuotePanel } from './FloatingQuotePanel';
import { QuoteDraftHistoryComparisonVisual } from './QuoteDraftHistoryComparisonVisual';

type QuoteDraftHistoryPanelProps = {
  onClose: () => void;
  initialHistoryId?: string | null;
  initialMode?: 'LIST' | 'COMPARE' | 'RESTORE_CONFIRM';
  game?: string;
  resolution?: 'fhd' | 'qhd' | '4k';
};

const won = new Intl.NumberFormat('ko-KR');

export function QuoteDraftHistoryPanel({
  onClose,
  initialHistoryId,
  initialMode = 'LIST',
  game = 'pubg',
  resolution = 'qhd'
}: QuoteDraftHistoryPanelProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [restoreNotice, setRestoreNotice] = useState<string | null>(null);
  const historyQuery = useQuery({
    queryKey: ['quote-draft', 'history'],
    queryFn: listQuoteDraftHistory,
    refetchInterval: (query) => query.state.data?.items.some((entry) => (
      entry.evaluationStatus === 'PENDING' || entry.evaluationStatus === 'RUNNING'
    )) ? 1000 : false
  });
  const fallbackId = historyQuery.data?.items.find((entry) => entry.evaluationStatus === 'VALID')?.id
    ?? historyQuery.data?.items[0]?.id;
  const selectedId = initialMode === 'LIST' ? null : (initialHistoryId ?? fallbackId ?? null);
  const comparisonQuery = useQuery({
    queryKey: ['quote-draft', 'history', selectedId, 'comparison', game, resolution],
    queryFn: () => getQuoteDraftHistoryComparison(selectedId!, game, resolution),
    enabled: Boolean(selectedId)
  });
  const restoreMutation = useMutation({
    mutationFn: ({ id, confirmRisk }: { id: string; confirmRisk: boolean }) => (
      restoreQuoteDraftHistory(id, confirmRisk, crypto.randomUUID())
    ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['quote-draft', 'current'] }),
        queryClient.invalidateQueries({ queryKey: ['quote-draft', 'history'] }),
        queryClient.invalidateQueries({ queryKey: ['build-graph'] })
      ]);
      setRestoreNotice('견적을 복원했습니다. 현재 견적과 변경 기록을 갱신했습니다.');
      navigate(historyListUrl(), { replace: true });
    }
  });

  const comparison = comparisonQuery.data;
  const title = selectedId ? '현재 견적과 비교' : '변경 기록';
  const restore = () => {
    if (!selectedId || !comparison?.restorable) return;
    const warning = comparison.requiresCompatibilityConfirmation
      ? '\n\n이 기록에는 현재 기준 호환성 또는 장착 경고가 있습니다.'
      : '';
    if (!window.confirm(`현재 견적 전체를 이 시점으로 복원할까요?${warning}\n복원 직전 상태도 변경 기록에 남습니다.`)) return;
    restoreMutation.mutate({ id: selectedId, confirmRisk: comparison.requiresCompatibilityConfirmation });
  };

  return (
    <FloatingQuotePanel
      testId="quote-draft-history-panel"
      ariaLabel={title}
      title={title}
      subtitle={selectedId ? '현재 견적 → 선택 기록 복원 미리보기' : '정상 20건 · 문제 5건 · 30일 보관'}
      persistKey="quote-draft-history-panel"
      dataView={selectedId ? 'COMPARE' : 'LIST'}
      onClose={onClose}
      closeOnBackdrop={false}
      headerActions={selectedId ? (
        <button
          type="button"
          data-testid="quote-history-back"
          onClick={() => navigate(historyListUrl(), { replace: true })}
          className="flex items-center gap-1 rounded-md border border-commerce-line bg-white px-2.5 py-1.5 text-xs font-black text-slate-700 hover:border-commerce-ink"
        >
          <ArrowLeft size={13} /> 목록
        </button>
      ) : undefined}
    >
      <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
        {restoreNotice ? (
          <p
            role="status"
            data-testid="quote-history-restore-success"
            className="mb-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-800"
          >
            {restoreNotice}
          </p>
        ) : null}
        {!selectedId ? (
          <HistoryList
            loading={historyQuery.isLoading}
            error={historyQuery.isError}
            items={historyQuery.data?.items ?? []}
          />
        ) : comparisonQuery.isLoading ? (
          <PanelState>현재 견적을 선택 기록으로 복원한 결과를 평가하고 있습니다.</PanelState>
        ) : comparisonQuery.isError || !comparison ? (
          <PanelState tone="error">비교 결과를 불러오지 못했습니다.</PanelState>
        ) : (
          <>
            <QuoteDraftHistoryComparisonVisual comparison={comparison} />

            <section className="mt-4">
              <h3 className="text-xs font-black text-commerce-ink">변경된 부품</h3>
              <div className="mt-2 space-y-2">
                {comparison.differences.length === 0 ? (
                  <PanelState>현재 견적과 부품 구성이 같습니다.</PanelState>
                ) : comparison.differences.map((difference) => (
                  <DifferenceRow key={difference.category} difference={difference} />
                ))}
              </div>
            </section>

            <IssueChanges added={comparison.issueChanges.added} resolved={comparison.issueChanges.resolved} />

            {!comparison.restorable ? (
              <div className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-xs font-bold text-red-700">
                판매 중지 또는 삭제된 부품이 포함되어 전체 복원이 불가능합니다.
                <ul className="mt-1 list-disc pl-4">
                  {comparison.unavailableItems.map((item) => <li key={item.partId}>{item.name}</li>)}
                </ul>
              </div>
            ) : comparison.requiresCompatibilityConfirmation ? (
              <div className="mt-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs font-bold text-amber-800">
                현재 평가 기준 호환성 또는 장착 경고가 있습니다. 복원 전에 경고를 다시 확인합니다.
              </div>
            ) : null}

            <p className="mt-4 text-[11px] leading-5 text-slate-500">
              가격은 기록 당시 단가이며, 종합점수·호환성·FPS는 현재 카탈로그와 평가 기준으로 다시 계산됩니다.
            </p>
            {restoreMutation.isError ? (
              <p role="alert" className="mt-2 text-xs font-bold text-red-600">{restoreError(restoreMutation.error)}</p>
            ) : null}
            <button
              type="button"
              data-testid="quote-history-restore"
              disabled={!comparison.restorable || restoreMutation.isPending}
              onClick={restore}
              className="mt-3 flex w-full items-center justify-center gap-2 rounded-md bg-commerce-ink px-3 py-2.5 text-xs font-black text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <RotateCcw size={14} /> {restoreMutation.isPending ? '복원 중' : '이 시점으로 전체 복원'}
            </button>
          </>
        )}
      </div>
    </FloatingQuotePanel>
  );
}

function HistoryList({ loading, error, items }: { loading: boolean; error: boolean; items: Awaited<ReturnType<typeof listQuoteDraftHistory>>['items'] }) {
  if (loading) return <PanelState>변경 기록을 불러오고 있습니다.</PanelState>;
  if (error) return <PanelState tone="error">변경 기록을 불러오지 못했습니다.</PanelState>;
  if (items.length === 0) return <PanelState>아직 변경 기록이 없습니다. 부품을 변경하면 직전 상태가 자동으로 남습니다.</PanelState>;
  const valid = items.filter((entry) => entry.evaluationStatus === 'VALID');
  const invalid = items.filter((entry) => entry.evaluationStatus === 'INVALID');
  const pending = items.filter((entry) => entry.evaluationStatus === 'PENDING' || entry.evaluationStatus === 'RUNNING');
  const unavailable = items.filter((entry) => entry.evaluationStatus === 'UNAVAILABLE');
  return (
    <div className="space-y-3">
      {valid.length > 0 ? (
        <HistoryGroup title={`정상 견적 기록 ${valid.length}건`} entries={valid} />
      ) : null}
      {pending.length > 0 ? (
        <section data-testid="quote-history-pending-group">
          <div className="mb-2 flex items-center gap-1.5 text-[11px] font-black text-slate-500">
            <LoaderCircle size={13} className="animate-spin" /> 평가 중인 기록 {pending.length}건
          </div>
          <HistoryEntries entries={pending} />
        </section>
      ) : null}
      {invalid.length > 0 ? (
        <CollapsibleHistoryGroup
          testId="quote-history-problem-group"
          title={`호환 문제 기록 ${invalid.length}건`}
          entries={invalid}
          tone="danger"
        />
      ) : null}
      {unavailable.length > 0 ? (
        <CollapsibleHistoryGroup
          testId="quote-history-unavailable-group"
          title={`평가 자료가 없는 기록 ${unavailable.length}건`}
          entries={unavailable}
          tone="muted"
        />
      ) : null}
    </div>
  );
}

function HistoryGroup({ title, entries }: { title: string; entries: QuoteDraftHistoryEntry[] }) {
  return (
    <section data-testid="quote-history-valid-group">
      <h3 className="mb-2 text-[11px] font-black text-commerce-ink">{title}</h3>
      <HistoryEntries entries={entries} />
    </section>
  );
}

function CollapsibleHistoryGroup({
  testId,
  title,
  entries,
  tone
}: {
  testId: string;
  title: string;
  entries: QuoteDraftHistoryEntry[];
  tone: 'danger' | 'muted';
}) {
  return (
    <details data-testid={testId} className={`group rounded-md border ${tone === 'danger' ? 'border-red-200 bg-red-50/60' : 'border-slate-200 bg-slate-50'}`}>
      <summary className={`flex cursor-pointer list-none items-center justify-between gap-2 px-3 py-2.5 text-[11px] font-black ${tone === 'danger' ? 'text-red-700' : 'text-slate-600'}`}>
        <span className="flex items-center gap-1.5">
          {tone === 'danger' ? <AlertTriangle size={13} /> : <History size={13} />}
          {title}
        </span>
        <ChevronDown size={14} className="transition group-open:rotate-180" />
      </summary>
      <div className="border-t border-inherit p-2">
        <HistoryEntries entries={entries} />
      </div>
    </details>
  );
}

function HistoryEntries({ entries }: { entries: QuoteDraftHistoryEntry[] }) {
  return (
    <ol className="space-y-2">
      {entries.map((entry) => (
        <li key={entry.id}>
          <Link
            data-testid="quote-history-entry"
            to={historyCompareUrl(entry.id)}
            className="block rounded-md border border-commerce-line bg-white p-3 transition hover:border-brand-blue hover:bg-blue-50/30"
          >
            <div className="flex items-start justify-between gap-3">
              <span className="text-xs font-black text-commerce-ink">{entry.actionLabel}</span>
              <HistoryEvaluationBadge entry={entry} />
            </div>
            <div className="mt-1 flex items-center justify-between gap-2 text-[11px] font-bold text-slate-500">
              <span>당시 예상가 {won.format(entry.totalPrice)}원 · {entry.itemCount}개</span>
              <time className="shrink-0 text-[10px] text-slate-400">{formatTime(entry.createdAt)}</time>
            </div>
          </Link>
        </li>
      ))}
    </ol>
  );
}

function HistoryEvaluationBadge({ entry }: { entry: QuoteDraftHistoryEntry }) {
  if (entry.evaluationStatus === 'VALID' && entry.score !== null && entry.score !== undefined) {
    return <span className="shrink-0 rounded-full bg-blue-50 px-2 py-1 text-[10px] font-black text-brand-blue">{entry.score}점</span>;
  }
  if (entry.evaluationStatus === 'INVALID') {
    return <span className="shrink-0 rounded-full bg-red-100 px-2 py-1 text-[10px] font-black text-red-700">0점 · 호환 문제</span>;
  }
  if (entry.evaluationStatus === 'UNAVAILABLE') {
    return <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-[10px] font-black text-slate-500">평가 자료 없음</span>;
  }
  return <span className="shrink-0 rounded-full bg-slate-100 px-2 py-1 text-[10px] font-black text-slate-500">평가 중</span>;
}

function DifferenceRow({ difference }: { difference: QuoteDraftHistoryDifference }) {
  return (
    <div className="rounded-md border border-commerce-line bg-slate-50 p-2.5">
      <div className="text-[11px] font-black text-slate-700">{difference.categoryLabel} · {changeLabel(difference.changeType)}</div>
      <div className="mt-1 grid gap-1 text-[11px] text-slate-600 sm:grid-cols-2">
        <div><span className="font-bold text-slate-400">현재</span> {partSummary(difference.beforeItems)}</div>
        <div><span className="font-bold text-slate-400">복원 후</span> {partSummary(difference.afterItems)}</div>
      </div>
    </div>
  );
}

function IssueChanges({ added, resolved }: { added: QuoteDraftHistoryIssue[]; resolved: QuoteDraftHistoryIssue[] }) {
  if (added.length === 0 && resolved.length === 0) return null;
  return (
    <section className="mt-4 grid gap-2 sm:grid-cols-2">
      <IssueList title="복원 후 새로 생기는 경고" tone="red" issues={added} />
      <IssueList title="복원 후 해결되는 경고" tone="green" issues={resolved} />
    </section>
  );
}

function IssueList({ title, tone, issues }: { title: string; tone: 'red' | 'green'; issues: QuoteDraftHistoryIssue[] }) {
  if (issues.length === 0) return null;
  return (
    <div className={`rounded-md border p-2.5 ${tone === 'red' ? 'border-red-200 bg-red-50' : 'border-emerald-200 bg-emerald-50'}`}>
      <h3 className={`text-[11px] font-black ${tone === 'red' ? 'text-red-700' : 'text-emerald-700'}`}>{title}</h3>
      <ul className="mt-1 space-y-1 text-[10px] font-bold text-slate-600">
        {issues.map((issue, index) => <li key={`${issue.code ?? issue.title}-${index}`}>{issue.title ?? issue.description}</li>)}
      </ul>
    </div>
  );
}

function PanelState({ children, tone = 'normal' }: { children: ReactNode; tone?: 'normal' | 'error' }) {
  return <div className={`rounded-md border border-dashed p-5 text-center text-xs font-bold ${tone === 'error' ? 'border-red-200 text-red-600' : 'border-slate-300 text-slate-500'}`}>{children}</div>;
}

function partSummary(items: Array<{ name: string; quantity: number }>) { return items.length ? items.map((item) => `${item.name}${item.quantity > 1 ? ` ×${item.quantity}` : ''}`).join(', ') : '없음'; }
function changeLabel(value: string) { return ({ ADDED: '추가', REMOVED: '제거', REPLACED: '교체', QUANTITY_CHANGED: '수량 변경' } as Record<string, string>)[value] ?? value; }
function formatTime(value: string) { return new Intl.DateTimeFormat('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); }
function historyListUrl() { const url = new URL(window.location.href); url.searchParams.set('panel', 'history'); url.searchParams.delete('historyId'); url.searchParams.delete('historyMode'); return `${url.pathname}${url.search}`; }
function historyCompareUrl(id: string) { const url = new URL(window.location.href); url.searchParams.set('panel', 'history'); url.searchParams.set('historyId', id); url.searchParams.set('historyMode', 'COMPARE'); return `${url.pathname}${url.search}`; }
function restoreError(error: Error) { return error instanceof ApiError ? error.message : '과거 견적을 복원하지 못했습니다.'; }
