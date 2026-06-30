import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminTicket, updateAdminTicket } from '../adminApi';
import type { AdminAsTicket, AsTicketStatus } from '../adminApi';

const editableStatuses: AsTicketStatus[] = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];

export function AdminTicketDetailPage() {
  const { ticketId = '' } = useParams();
  const queryClient = useQueryClient();
  const [selectedStatus, setSelectedStatus] = useState<AsTicketStatus>('OPEN');

  const {
    data: ticket,
    isError,
    isLoading
  } = useQuery({
    queryKey: ['admin-as-ticket', ticketId],
    queryFn: () => getAdminTicket(ticketId),
    enabled: Boolean(ticketId)
  });

  useEffect(() => {
    if (ticket) {
      setSelectedStatus(ticket.status);
    }
  }, [ticket]);

  const updateMutation = useMutation({
    mutationFn: () => updateAdminTicket(ticketId, { status: selectedStatus }),
    onSuccess: (updatedTicket) => {
      queryClient.setQueryData(['admin-as-ticket', ticketId], updatedTicket);
      queryClient.invalidateQueries({ queryKey: ['admin-as-tickets'] });
    }
  });

  if (isLoading) {
    return (
      <AdminShell title="AS 티켓 상세">
        <StateMessage type="info" title="AS 티켓 로딩 중" body="관리자 AS 티켓 상세 정보를 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (isError || !ticket) {
    return (
      <AdminShell title="AS 티켓 상세">
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="GET /api/admin/as-tickets/{id} 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="AS 티켓 상세">
      <div className="grid grid-cols-[1fr_440px] gap-5">
        <Panel title="티켓 / 로그 / 진단 후보" subtitle={ticket.id}>
          <DataTable columns={['field', 'value']} rows={ticketDetailRows(ticket)} />
        </Panel>
        <Panel title="관리자 조치" subtitle="PATCH /api/admin/as-tickets/{id}">
          <StateMessage type="info" title={`현재 상태: ${ticket.status}`} body="이번 화면에서는 상태 변경만 실제 API로 저장합니다." />
          <div className="mt-5">
            <label className="text-xs font-bold text-slate-600" htmlFor="ticket-status">상태</label>
            <select
              id="ticket-status"
              className="mt-2 w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
              value={selectedStatus}
              onChange={(event) => setSelectedStatus(event.target.value as AsTicketStatus)}
            >
              {editableStatuses.map((status) => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </div>
          <button
            className="mt-4 w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-300"
            disabled={updateMutation.isPending || selectedStatus === ticket.status}
            onClick={() => updateMutation.mutate()}
          >
            {updateMutation.isPending ? '상태 저장 중' : '상태 저장'}
          </button>
          {updateMutation.isError ? (
            <div className="mt-4">
              <StateMessage type="warn" title="상태 변경 실패" body="PATCH /api/admin/as-tickets/{id} 응답을 저장하지 못했습니다." />
            </div>
          ) : null}
          {updateMutation.isSuccess ? (
            <div className="mt-4">
              <StateMessage type="success" title="상태 변경 완료" body="AS 티켓 상세 화면 상태를 갱신했습니다." />
            </div>
          ) : null}
          <div className="mt-5">
            <Link className="text-sm font-bold text-brand-blue" to="/admin/as-tickets">목록으로 돌아가기</Link>
          </div>
        </Panel>
      </div>
    </AdminShell>
  );
}

function ticketDetailRows(ticket: AdminAsTicket) {
  return [
    { field: '상태', value: <StatusBadge status={ticket.status} /> },
    { field: '제목/증상', value: ticket.title ?? ticket.symptom },
    { field: '상세 설명', value: ticket.description ?? ticket.detailDescription ?? '상세 설명 응답 없음' },
    { field: '로그 요약', value: logSummary(ticket) },
    { field: '원인 후보', value: formatCandidates(ticket.causeCandidates) },
    { field: '업그레이드 후보', value: formatCandidates(ticket.upgradeCandidates) },
    { field: '담당자', value: ticket.assignedAdminId ?? '-' },
    { field: '관리자 메모', value: ticket.adminNote ?? '-' },
    { field: '생성일', value: formatDateTime(ticket.createdAt) },
    { field: '해결일', value: formatDateTime(ticket.resolvedAt) }
  ];
}

function logSummary(ticket: AdminAsTicket) {
  if (ticket.logSummary) {
    return ticket.logSummary;
  }
  return ticket.logUploadId ? `로그 업로드 연결됨: ${shortId(ticket.logUploadId)}` : '연결된 로그 없음';
}

function formatCandidates(candidates: Record<string, unknown>[]) {
  if (!candidates.length) {
    return '-';
  }
  return candidates.map((candidate) => {
    const summary = candidate.summary ?? candidate.reason ?? candidate.name ?? candidate.title;
    return summary == null ? JSON.stringify(candidate) : String(summary);
  }).join(' / ');
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
