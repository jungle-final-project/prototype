import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';

const smokeRows = [
  { target: '/api/health', purpose: 'API와 DB 연결 smoke', status: <StatusBadge status="ACTIVE" /> },
  { target: '/api/parts', purpose: '부품 조회 smoke', status: <StatusBadge status="ACTIVE" /> },
  { target: '/api/builds/recommend', purpose: '추천 흐름 mock smoke', status: <StatusBadge status="WARN" /> }
];

const loadPlanRows = [
  { phase: '2주차', target: '동시 300명', metric: '비LLM API p95 500ms 이하' },
  { phase: '4주차', target: '동시 1,000명', metric: '에러율 1% 이하' },
  { phase: 'LLM 제한 검증', target: '100~300건', metric: '비용과 대기시간 측정' }
];

export function AdminLoadTestsPage() {
  return (
    <AdminShell title="부하 테스트">
      <div className="grid grid-cols-[1fr_360px] gap-5">
        <Panel title="k6 Smoke 대상" subtitle="PR 전 빠르게 확인할 최소 endpoint">
          <DataTable columns={['target', 'purpose', 'status']} rows={smokeRows} />
        </Panel>
        <Panel title="리포트 상태">
          <StateMessage type="warn" title="자동 리포트 미연동" body="현재는 smoke/부하 시나리오 분리 전 단계입니다. 실제 k6 리포트 연결은 인프라 검증 범위에서 확정합니다." />
        </Panel>
        <Panel title="부하 검증 계획" className="col-span-2">
          <DataTable columns={['phase', 'target', 'metric']} rows={loadPlanRows} />
        </Panel>
      </div>
    </AdminShell>
  );
}
