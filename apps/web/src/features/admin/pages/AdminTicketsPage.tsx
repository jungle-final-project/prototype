import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminTickets } from '../adminApi';
import type { AdminAsTicket } from '../adminApi';

export function AdminTicketsPage() {
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-as-tickets'],
    queryFn: getAdminTickets
  });

  const tickets = data?.items ?? [];
  const ticketRows = tickets.map((ticket) => ({
    id: <Link className="font-bold text-brand-blue" to={`/admin/as-tickets/${ticket.id}`}>{shortId(ticket.id)}</Link>,
    status: <StatusBadge status={ticket.status} />,
    symptom: <Link className="font-bold text-slate-800 hover:text-brand-blue" to={`/admin/as-tickets/${ticket.id}`}>{ticket.title ?? ticket.symptom}</Link>,
    user: userLabel(ticket),
    createdAt: formatDateTime(ticket.createdAt)
  }));

  return (
    <AdminShell title="AS 티켓 관리자">
      <Panel title="티켓 큐" subtitle="GET /api/admin/as-tickets">
        {isLoading ? <StateMessage type="info" title="AS 티켓 로딩 중" body="관리자 AS 티켓 목록을 불러오고 있습니다." /> : null}
        {isError ? <StateMessage type="warn" title="AS 티켓 조회 실패" body="GET /api/admin/as-tickets 응답을 불러오지 못했습니다." /> : null}
        {!isLoading && !isError && ticketRows.length === 0 ? (
          <StateMessage type="info" title="AS 티켓 없음" body="표시할 관리자 AS 티켓이 없습니다." />
        ) : null}
        {!isLoading && !isError && ticketRows.length > 0 ? (
          <DataTable columns={['id', 'status', 'symptom', 'user', 'createdAt']} rows={ticketRows} />
        ) : null}
      </Panel>
    </AdminShell>
  );
}

function userLabel(ticket: AdminAsTicket) {
  return ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-';
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
