import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getToolInvocation } from '../adminApi';
import type { ToolInvocation } from '../adminApi';

export function ToolInvocationAdminPage() {
  const { id } = useParams();
  const {
    data: invocation,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-tool-invocation', id],
    queryFn: () => getToolInvocation(id ?? ''),
    enabled: Boolean(id)
  });

  if (!id) {
    return (
      <AdminShell title="Tool Invocation 상세">
        <StateMessage type="info" title="Tool 호출을 선택하세요" body="Agent 세션 상세에서 Tool invocation 항목을 선택해야 합니다." />
      </AdminShell>
    );
  }

  if (isLoading) {
    return (
      <AdminShell title="Tool Invocation 상세">
        <StateMessage type="info" title="Tool 호출 로딩 중" body="Tool request와 result payload를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !invocation) {
    return (
      <AdminShell title="Tool Invocation 상세">
        <StateMessage type="warn" title="Tool 호출 조회 실패" body="관리자 Tool invocation 상세 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="Tool Invocation 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="호출 상세" subtitle={`${invocation.toolName} / ${invocation.id}`}>
          <DataTable columns={['필드', '값']} rows={detailRows(invocation)} />
        </Panel>
        <Panel title="결과 요약">
          <StateMessage type={invocation.status === 'PASS' ? 'success' : 'warn'} title={`${invocation.status} / ${invocation.confidence}`} body={invocation.summary} />
        </Panel>
        <Panel title="Request Payload" className="col-span-1">
          <JsonBlock value={invocation.requestPayload ?? {}} />
        </Panel>
        <Panel title="Result Payload">
          <JsonBlock value={invocation.resultPayload ?? {}} />
        </Panel>
      </div>
    </AdminShell>
  );
}

function detailRows(invocation: ToolInvocation) {
  return [
    { 필드: 'invocationId', 값: invocation.id },
    { 필드: 'tool', 값: invocation.toolName },
    { 필드: 'status', 값: <StatusBadge status={invocation.status} /> },
    { 필드: 'confidence', 값: <StatusBadge status={invocation.confidence} /> },
    { 필드: 'latency', 값: invocation.latencyMs == null ? '-' : `${invocation.latencyMs}ms` },
    {
      필드: 'sessionId',
      값: <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${invocation.agentSessionId}`}>{invocation.agentSessionId}</Link>
    },
    { 필드: 'createdAt', 값: formatDateTime(invocation.createdAt) }
  ];
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-[420px] overflow-auto rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
