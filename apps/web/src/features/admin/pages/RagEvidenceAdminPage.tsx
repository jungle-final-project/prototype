import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';

export function RagEvidenceAdminPage() {
  return (
    <AdminShell title="RAG Evidence 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="근거 문서" subtitle="3번 담당자가 pgvector 검색 결과와 source metadata를 연결할 화면">
          <DataTable columns={['필드', '값']} rows={[
            { 필드: 'evidenceId', 값: '00000000-0000-4000-8000-000000004001' },
            { 필드: 'sourceId', 값: 'psu-rule-001' },
            { 필드: 'score', 값: '0.91' },
            { 필드: 'usedBy', 값: '00000000-0000-4000-8000-000000005002' },
            { 필드: 'summary', 값: 'GPU 피크 전력과 CPU TDP 합산 후 여유율 적용' }
          ]} />
        </Panel>
        <Panel title="구현 메모">
          <StateMessage type="info" title="자동 학습 아님" body="Feedback Loop는 모델 자동 학습이 아니라 오차와 개선 후보를 관리자 화면에 기록하는 방식입니다." />
        </Panel>
      </div>
    </AdminShell>
  );
}
