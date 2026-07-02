import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage } from '../../../components/ui';
import { getRagEvidence } from '../adminApi';
import type { RagEvidenceDetail } from '../adminApi';

export function RagEvidenceAdminPage() {
  const { id } = useParams();
  const {
    data: evidence,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-rag-evidence', id],
    queryFn: () => getRagEvidence(id ?? ''),
    enabled: Boolean(id)
  });

  if (!id) {
    return (
      <AdminShell title="RAG Evidence 상세">
        <StateMessage type="info" title="RAG 근거를 선택하세요" body="Agent 세션 상세에서 RAG evidence 항목을 선택해야 합니다." />
      </AdminShell>
    );
  }

  if (isLoading) {
    return (
      <AdminShell title="RAG Evidence 상세">
        <StateMessage type="info" title="RAG 근거 로딩 중" body="RAG evidence chunk와 metadata를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !evidence) {
    return (
      <AdminShell title="RAG Evidence 상세">
        <StateMessage type="warn" title="RAG 근거 조회 실패" body="관리자 RAG evidence 상세 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="RAG Evidence 상세">
      <div className="grid grid-cols-[1fr_420px] gap-5">
        <Panel title="근거 문서" subtitle={`${evidence.sourceId} / ${evidence.id}`}>
          <DataTable columns={['필드', '값']} rows={detailRows(evidence)} />
        </Panel>
        <Panel title="요약">
          <StateMessage type="info" title={formatScore(evidence.score)} body={evidence.summary} />
        </Panel>
        <Panel title="근거 Chunk">
          <div className="min-h-[220px] rounded border border-slate-200 bg-white p-4 text-sm leading-7 text-slate-700">
            {evidence.chunkText ?? '관리자 응답에 chunkText가 없습니다.'}
          </div>
        </Panel>
        <Panel title="Metadata JSON">
          <JsonBlock value={evidence.metadata ?? {}} />
        </Panel>
      </div>
    </AdminShell>
  );
}

function detailRows(evidence: RagEvidenceDetail) {
  return [
    { 필드: 'evidenceId', 값: evidence.id },
    { 필드: 'sourceId', 값: evidence.sourceId },
    { 필드: 'score', 값: formatScore(evidence.score) },
    {
      필드: 'agentSessionId',
      값: evidence.agentSessionId
        ? <Link className="font-bold text-brand-blue" to={`/admin/agent-sessions/${evidence.agentSessionId}`}>{evidence.agentSessionId}</Link>
        : '-'
    },
    { 필드: 'summary', 값: evidence.summary }
  ];
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-[420px] overflow-auto rounded bg-slate-950 p-5 font-mono text-xs leading-6 text-slate-200">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

function formatScore(value?: string | number | null) {
  return value == null ? 'score 없음' : `score ${value}`;
}
