import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { listAdminAssemblyRequests } from '../../parts/assemblyApi';
import { getAdminDashboard } from '../adminApi';

function countLabel(value: number | null | undefined) {
  return `${value ?? 0}건`;
}

function wonLabel(value: number | null | undefined) {
  return `₩${(value ?? 0).toLocaleString()}`;
}

function compactWonLabel(value: number | null | undefined) {
  const amount = value ?? 0;
  if (amount >= 100_000_000) {
    return `₩${Math.round(amount / 10_000_000) / 10}억`;
  }
  if (amount >= 10_000) {
    return `₩${Math.round(amount / 1_000) / 10}만`;
  }
  return wonLabel(amount);
}

function dateLabel(value: string | null | undefined) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

function orderStatusCount(items: Array<{ status: string; count: number }> | undefined, status: string) {
  return items?.find((item) => item.status === status)?.count ?? 0;
}

const ORDER_STATUS_COLORS: Record<string, string> = {
  PENDING: '#de6c2d',
  IN_PROGRESS: '#2563eb',
  COMPLETED: '#16a34a',
  CANCELLED: '#ef3f3f'
};

export function AdminDashboardPage() {
  const { data: dashboard, isError, isLoading } = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: getAdminDashboard
  });
  const recentAssemblyRequestsQuery = useQuery({
    queryKey: ['admin-assembly-requests', 'dashboard-recent'],
    queryFn: () => listAdminAssemblyRequests({ page: 0, size: 5 }),
    enabled: Boolean(dashboard),
    retry: false
  });
  if (isLoading) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="info" title="대시보드 로딩 중" body="운영 지표를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !dashboard) {
    return (
      <AdminShell title="운영 대시보드">
        <StateMessage type="warn" title="대시보드 조회 실패" body="관리자 대시보드 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  const generatedAt = dashboard.generatedAt ?? '갱신 시간 없음';
  const dashboardExportRows = [
    { metric: 'agentRunning', value: dashboard.agentRunning, generatedAt },
    { metric: 'openTickets', value: dashboard.openTickets, generatedAt },
    { metric: 'todayRevenue', value: dashboard.todayRevenue, generatedAt },
    { metric: 'weekRevenue', value: dashboard.weekRevenue, generatedAt },
    { metric: 'degraded', value: dashboard.degraded, generatedAt },
    ...(dashboard.revenueTrend ?? []).map((item) => ({
      metric: `revenue:${item.date}`,
      value: item.revenue,
      generatedAt
    })),
    ...(dashboard.orderStatus ?? []).map((item) => ({
      metric: `order:${item.status}`,
      value: item.count,
      generatedAt
    }))
  ];
  const pendingOrderCount = orderStatusCount(dashboard.orderStatus, 'PENDING');
  const inProgressOrderCount = orderStatusCount(dashboard.orderStatus, 'IN_PROGRESS');
  const quickActions = [
    {
      title: '조립 요청 관리',
      description: '요청 상태와 기사 제안을 확인합니다.',
      to: '/admin/assembly',
      meta: `처리 대기 ${pendingOrderCount.toLocaleString()}건`
    },
    {
      title: '기사/제안 운영',
      description: '기사 승인, 제안 추가와 상태 보정을 진행합니다.',
      to: '/admin/assembly',
      meta: `진행 중 ${inProgressOrderCount.toLocaleString()}건`
    },
    {
      title: '부품/가격 관리',
      description: '부품 데이터와 가격 수집 상태를 점검합니다.',
      to: '/admin/parts',
      meta: dashboard.priceJobsRunning > 0 ? '가격 작업 실행 중' : '가격 작업 대기 없음'
    },
    {
      title: 'AS 티켓 확인',
      description: '미해결 티켓과 사용자 문의를 확인합니다.',
      to: '/admin/as-tickets',
      meta: `미해결 ${dashboard.openTickets.toLocaleString()}건`
    }
  ];
  const recentAssemblyRows = (recentAssemblyRequestsQuery.data?.items ?? []).map((item) => ({
    요청번호: <Link className="font-black text-commerce-ink hover:text-[#de6c2d]" to="/admin/assembly">{item.requestNo}</Link>,
    상태: <StatusBadge status={item.status} />,
    '지역/일정': `${item.region} · ${item.preferredDate}`,
    금액: wonLabel(item.finalPrice ?? item.estimatedPartsPrice),
    생성: dateLabel(item.createdAt),
    이동: <Link className="font-bold text-[#de6c2d] hover:text-[#c45c22]" to="/admin/assembly">관리</Link>
  }));

  return (
    <AdminShell title="운영 대시보드" exportRows={dashboardExportRows} exportFileName="admin-dashboard.csv">
      {dashboard.degraded ? (
        <div className="mb-4">
          <StateMessage
            type="warn"
            title="운영 상태 주의"
            body={`일부 운영 지표가 주의 상태입니다. 마지막 갱신: ${generatedAt}`}
          />
        </div>
      ) : null}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="진행 중 Agent" value={countLabel(dashboard.agentRunning)} tone="orange" />
        <MetricCard label="미해결 AS" value={countLabel(dashboard.openTickets)} tone="orange" />
        <MetricCard label="오늘 매출" value={wonLabel(dashboard.todayRevenue)} tone="point" />
        <MetricCard label="이번 주 매출" value={wonLabel(dashboard.weekRevenue)} tone="point" />
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
        <Panel title="매출 추이" subtitle="최근 7일 결제 완료 금액">
          <RevenueTrendChart items={dashboard.revenueTrend ?? []} />
        </Panel>
        <Panel title="주문 현황" subtitle="조립 요청 상태 기준">
          <OrderStatusChart items={dashboard.orderStatus ?? []} />
        </Panel>
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-[600px_minmax(0,1fr)]">
        <Panel title="빠른 작업" subtitle="자주 확인하는 운영 화면으로 바로 이동">
          <div className="divide-y divide-commerce-line rounded-md border border-commerce-line bg-white">
            {quickActions.map((action) => (
              <Link
                key={action.title}
                to={action.to}
                className="group block px-4 py-3 transition duration-150 ease-out hover:bg-[#fff7f2] focus:outline-none focus:ring-2 focus:ring-[#de6c2d]/30"
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-sm font-black text-commerce-ink group-hover:text-[#de6c2d]">{action.title}</div>
                    <p className="mt-1 text-xs leading-5 text-slate-500">{action.description}</p>
                  </div>
                  <span className="shrink-0 rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-black text-slate-600 group-hover:bg-[#fde7d9] group-hover:text-[#9f4218]">
                    {action.meta}
                  </span>
                </div>
              </Link>
            ))}
          </div>
        </Panel>
        <Panel
          title="최근 조립 요청"
          subtitle="최근 접수된 조립 중개 요청"
          action={<Link className="text-xs font-black text-[#de6c2d] hover:text-[#c45c22]" to="/admin/assembly">전체 보기</Link>}
        >
          {recentAssemblyRequestsQuery.isLoading ? (
            <StateMessage type="info" title="조립 요청 로딩 중" body="최근 조립 요청을 불러오고 있습니다." />
          ) : recentAssemblyRequestsQuery.isError ? (
            <StateMessage type="warn" title="조립 요청 조회 실패" body="최근 조립 요청을 불러오지 못했습니다." />
          ) : recentAssemblyRows.length > 0 ? (
            <DataTable columns={['요청번호', '상태', '지역/일정', '금액', '생성', '이동']} rows={recentAssemblyRows} />
          ) : (
            <StateMessage type="info" title="조립 요청 없음" body="표시할 최근 조립 요청이 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}

function RevenueTrendChart({ items }: { items: Array<{ date: string; label: string; revenue: number }> }) {
  const maxRevenue = Math.max(...items.map((item) => item.revenue), 0);
  if (items.length === 0) {
    return <StateMessage type="info" title="매출 데이터 없음" body="결제 완료 이력이 쌓이면 최근 7일 매출 추이가 표시됩니다." />;
  }

  return (
    <div>
      <div className="mb-3 flex items-center justify-between text-xs font-bold text-slate-500">
        <span>0원</span>
        <span>{compactWonLabel(maxRevenue)}</span>
      </div>
      <div className="flex h-64 items-end gap-2 rounded-md border border-slate-100 bg-slate-50/60 px-3 pb-3 pt-6 sm:gap-4 sm:px-5">
        {items.map((item) => {
          const height = maxRevenue > 0 ? Math.max((item.revenue / maxRevenue) * 100, item.revenue > 0 ? 8 : 2) : 2;
          return (
            <div key={item.date} className="flex h-full min-w-0 flex-1 flex-col justify-end">
              <div className="group relative flex min-h-0 flex-1 items-end justify-center">
                <div
                  className="w-full max-w-14 rounded-t-md bg-[#de6c2d] shadow-sm transition duration-200 ease-out group-hover:bg-[#c45c22]"
                  style={{ height: `${height}%` }}
                  aria-label={`${item.label} 매출 ${wonLabel(item.revenue)}`}
                  title={`${item.label} · ${wonLabel(item.revenue)}`}
                />
                <span className="pointer-events-none absolute -top-5 hidden whitespace-nowrap rounded bg-slate-900 px-2 py-1 text-[11px] font-bold text-white shadow-sm group-hover:block">
                  {compactWonLabel(item.revenue)}
                </span>
              </div>
              <div className="mt-2 truncate text-center text-[11px] font-bold text-slate-500">{item.label}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function OrderStatusChart({ items }: { items: Array<{ status: string; label: string; count: number }> }) {
  const total = items.reduce((sum, item) => sum + item.count, 0);
  const radius = 42;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;

  return (
    <div className="grid gap-4 sm:grid-cols-[150px_minmax(0,1fr)] xl:grid-cols-1">
      <div className="relative mx-auto h-40 w-40">
        <svg viewBox="0 0 120 120" className="h-40 w-40 -rotate-90" role="img" aria-label={`주문 현황 총 ${total}건`}>
          <circle cx="60" cy="60" r={radius} fill="none" stroke="#e4e7ec" strokeWidth="16" />
          {total > 0
            ? items.map((item) => {
              const length = (item.count / total) * circumference;
              const dashOffset = -offset;
              offset += length;
              return (
                <circle
                  key={item.status}
                  cx="60"
                  cy="60"
                  r={radius}
                  fill="none"
                  stroke={ORDER_STATUS_COLORS[item.status] ?? '#64748b'}
                  strokeWidth="16"
                  strokeDasharray={`${length} ${circumference - length}`}
                  strokeDashoffset={dashOffset}
                  strokeLinecap="round"
                />
              );
            })
            : null}
        </svg>
        <div className="absolute inset-0 grid place-items-center text-center">
          <div>
            <div className="text-2xl font-black text-commerce-ink">{countLabel(total)}</div>
            <div className="mt-1 text-[11px] font-bold text-slate-500">전체 주문</div>
          </div>
        </div>
      </div>
      <div className="space-y-2">
        {items.map((item) => (
          <div key={item.status} className="flex items-center justify-between gap-3 rounded-md border border-slate-100 bg-slate-50 px-3 py-2">
            <div className="flex min-w-0 items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: ORDER_STATUS_COLORS[item.status] ?? '#64748b' }} />
              <span className="truncate text-xs font-bold text-slate-600">{item.label}</span>
            </div>
            <span className="text-xs font-black text-commerce-ink">{countLabel(item.count)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
