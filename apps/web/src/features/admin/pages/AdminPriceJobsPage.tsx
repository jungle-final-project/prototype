import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { listPriceJobs, runPriceJob } from '../adminApi';

export function AdminPriceJobsPage() {
  const queryClient = useQueryClient();
  const jobsQuery = useQuery({
    queryKey: ['admin-price-jobs'],
    queryFn: listPriceJobs,
    refetchInterval: (query) => hasActiveJob(query.state.data?.items ?? []) ? 2000 : false
  });
  const runMutation = useMutation({
    mutationFn: runPriceJob,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-price-jobs'] })
  });

  const jobs = jobsQuery.data?.items ?? [];
  const rows = jobs.map((job) => ({
    id: shortId(job.id),
    status: <StatusBadge status={job.status} />,
    requestedBy: shortId(job.requestedBy ?? '-'),
    startedAt: formatDateTime(job.startedAt),
    finishedAt: formatDateTime(job.finishedAt),
    errorSummary: job.errorSummary ?? '-',
    createdAt: formatDateTime(job.createdAt)
  }));

  return (
    <AdminShell title="가격 Job 관리자">
      <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <Panel title="가격 수집 작업" subtitle="price_jobs와 RabbitMQ worker 기준 상태">
          {jobsQuery.isLoading ? (
            <StateMessage type="info" title="가격 Job 로딩 중" body="가격 Job 목록을 조회하고 있습니다." />
          ) : jobsQuery.isError ? (
            <StateMessage type="warn" title="가격 Job 조회 실패" body="GET /api/admin/price-jobs 응답을 불러오지 못했습니다." />
          ) : rows.length ? (
            <DataTable columns={['id', 'status', 'requestedBy', 'startedAt', 'finishedAt', 'errorSummary', 'createdAt']} rows={rows} />
          ) : (
            <StateMessage type="info" title="가격 Job 없음" body="수동 실행 버튼으로 첫 가격 Job을 생성할 수 있습니다." />
          )}
        </Panel>
        <Panel title="실행 정책">
          <StateMessage type="info" title="RabbitMQ worker 실행" body="실행 요청은 QUEUED job을 만들고 worker가 RUNNING, SUCCEEDED 또는 FAILED로 전이합니다. 네이버 쇼핑 API와 다나와 제한 크롤링 키가 없어도 seed/current price 기준 상태 전이는 확인할 수 있습니다." />
          <button disabled={runMutation.isPending || hasActiveJob(jobs)} onClick={() => runMutation.mutate()} className="mt-5 w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:bg-slate-400">
            {hasActiveJob(jobs) ? '실행 중인 Job 있음' : runMutation.isPending ? '실행 요청 중' : '가격 Job 실행'}
          </button>
          {runMutation.isSuccess ? <StateMessage type="success" title="실행 요청 완료" body="가격 Job을 큐에 등록했습니다." /> : null}
          {runMutation.isError ? <StateMessage type="warn" title="실행 요청 실패" body="이미 실행 중인 Job이 있거나 관리자 권한을 확인해야 합니다." /> : null}
        </Panel>
      </div>
    </AdminShell>
  );
}

function hasActiveJob(jobs: Array<{ status: string }>) {
  return jobs.some((job) => job.status === 'QUEUED' || job.status === 'RUNNING');
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
