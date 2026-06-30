import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, MetricCard, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminDashboard, getRecentAdminAuditLogs } from '../adminApi';

function countLabel(value: number | null | undefined) {
  return `${value ?? 0}건`;
}

const agentSessionId = '00000000-0000-4000-8000-000000003001';

export function AdminDashboardPage() {
  const { data: dashboard, isError, isLoading } = useQuery({
    queryKey: ['admin-dashboard'],
    queryFn: getAdminDashboard
  });
  const auditLogsQuery = useQuery({
    queryKey: ['admin-audit-logs-recent'],
    queryFn: getRecentAdminAuditLogs,
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

  const statusLabel = dashboard.degraded ? '주의' : '정상';
  const generatedAt = dashboard.generatedAt ?? '갱신 시간 없음';
  const auditLogRows = (auditLogsQuery.data?.items ?? []).map((item) => ({
    action: item.action ?? 'UNKNOWN',
    targetType: item.targetType ?? 'unknown',
    targetId: item.targetId ?? '-',
    createdAt: item.createdAt ?? '시간 없음'
  }));
  const operatingTasks = [
    {
      작업: '가격 Job',
      상태: <StatusBadge status={dashboard.priceJobsRunning > 0 ? 'RUNNING' : 'READY'} />,
      owner: '2번',
      이동: <Link className="font-bold text-brand-blue" to="/admin/parts">부품/가격</Link>
    },
    {
      작업: 'Mailpit',
      상태: <StatusBadge status="READY" />,
      owner: '5번',
      이동: 'Infra'
    },
    {
      작업: 'Mock Worker',
      상태: <StatusBadge status="READY" />,
      owner: '3번/5번',
      이동: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${agentSessionId}`}>Agent/RAG</Link>
    },
    {
      작업: 'k6 Smoke',
      상태: <StatusBadge status="TODO" />,
      owner: '5번',
      이동: '리포트'
    }
  ];
  const adminTodos = [
    {
      영역: '부품/가격',
      owner: '2번',
      처리: <Link className="font-bold text-brand-blue" to="/admin/parts">요약 확인</Link>
    },
    {
      영역: 'Agent/RAG',
      owner: '3번',
      처리: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${agentSessionId}`}>세션 확인</Link>
    },
    {
      영역: 'AS 티켓',
      owner: '4번',
      처리: <Link className="font-bold text-brand-blue" to="/admin/as-tickets">티켓 확인</Link>
    },
    {
      영역: '공통 권한',
      owner: '5번',
      처리: 'guard/API 계약 검토'
    }
  ];

  return (
    <AdminShell title="운영 대시보드">
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
        <MetricCard label="실행 중 Price Job" value={countLabel(dashboard.priceJobsRunning)} tone="blue" />
        <MetricCard label="운영 상태" value={statusLabel} tone={dashboard.degraded ? 'orange' : 'green'} />
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-[1fr_420px]">
        <Panel title="최근 Agent 세션 요약">
          <DataTable columns={['세션', '상태', '요약', '이동']} rows={[
            {
              세션: agentSessionId,
              상태: <StatusBadge status="PASS" />,
              요약: '추천 근거 생성 완료',
              이동: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${agentSessionId}`}>상세</Link>
            },
            {
              세션: 'session-1002',
              상태: <StatusBadge status="WARN" />,
              요약: '도메인 owner 확인 필요',
              이동: '대기'
            }
          ]} />
        </Panel>
        <Panel title="운영 상태">
          <StateMessage
            type={dashboard.degraded ? 'warn' : 'success'}
            title={dashboard.degraded ? '운영 지표 확인 필요' : '운영 상태 정상'}
            body={`마지막 갱신: ${generatedAt}`}
          />
        </Panel>
      </div>
      <div className="mt-5 grid grid-cols-1 gap-5 xl:grid-cols-2">
        <Panel title="운영 작업">
          <DataTable columns={['작업', '상태', 'owner', '이동']} rows={operatingTasks} />
        </Panel>
        <Panel title="관리자 할 일">
          <DataTable columns={['영역', 'owner', '처리']} rows={adminTodos} />
        </Panel>
      </div>
      <div className="mt-5">
        <Panel title="최근 관리자 작업" subtitle="admin_audit_logs 기준 최근 작업">
          {auditLogsQuery.isLoading ? (
            <StateMessage type="info" title="감사 로그 로딩 중" body="최근 관리자 작업을 불러오고 있습니다." />
          ) : auditLogsQuery.isError ? (
            <StateMessage type="warn" title="감사 로그 조회 실패" body="최근 관리자 작업을 불러오지 못했습니다." />
          ) : auditLogRows.length > 0 ? (
            <DataTable columns={['action', 'targetType', 'targetId', 'createdAt']} rows={auditLogRows} />
          ) : (
            <StateMessage type="info" title="감사 로그 없음" body="표시할 최근 관리자 작업이 없습니다." />
          )}
        </Panel>
      </div>
    </AdminShell>
  );
}
