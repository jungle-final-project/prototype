import { AdminShell, DataTable, Panel, StatusBadge } from '../../../components/ui';

export function ToolInvocationAdminPage() {
  return (
    <AdminShell title="Tool Invocation 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="호출 상세" subtitle="2번/3번 담당자가 Tool request, result, evidence 저장을 연결할 화면">
          <DataTable columns={['필드', '값']} rows={[
            { 필드: 'invocationId', 값: '00000000-0000-4000-8000-000000005002' },
            { 필드: 'tool', 값: 'power' },
            { 필드: 'status', 값: <StatusBadge status="WARN" /> },
            { 필드: 'confidence', 값: <StatusBadge status="MEDIUM" /> },
            { 필드: 'latency', 값: '168ms' },
            { 필드: 'sessionId', 값: '00000000-0000-4000-8000-000000003001' }
          ]} />
        </Panel>
        <Panel title="표준 응답 형식">
          <div className="rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
            {'{'}<br />
            &nbsp;&nbsp;"status": "PASS | WARN | FAIL",<br />
            &nbsp;&nbsp;"score": 0.82,<br />
            &nbsp;&nbsp;"confidence": "LOW | MEDIUM | HIGH",<br />
            &nbsp;&nbsp;"warnings": ["피크 전력 여유율 부족"],<br />
            &nbsp;&nbsp;"evidence": ["psu-rule-001"]<br />
            {'}'}
          </div>
        </Panel>
      </div>
    </AdminShell>
  );
}
