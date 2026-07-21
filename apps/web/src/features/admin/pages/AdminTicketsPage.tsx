import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { formatSeoulDateTime } from '../../../lib/dateTime';
import { deleteAdminTicket, getAdminTickets } from '../adminApi';
import type { AdminAsTicket } from '../adminApi';

export function AdminTicketsPage() {
  const queryClient = useQueryClient();
  const { data, isError, isLoading } = useQuery({
    queryKey: ['admin-as-tickets'],
    queryFn: getAdminTickets
  });

  const deleteMutation = useMutation({
    mutationFn: deleteAdminTicket,
    onSuccess: async (_, ticketId) => {
      queryClient.removeQueries({ queryKey: ['admin-as-ticket', ticketId] });
      await queryClient.invalidateQueries({ queryKey: ['admin-as-tickets'] });
    }
  });

  const tickets = data?.items ?? [];
  const ticketRows = tickets.map((ticket) => ({
    '티켓': <Link className="whitespace-nowrap font-bold text-brand-blue" to={`/admin/as-tickets/${ticket.id}`}>{shortId(ticket.id)}</Link>,
    '상태': <StatusBadge status={ticket.status} />,
    '검토': ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-',
    '결정': ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : '-',
    'Agent 진단': <span className="line-clamp-2 min-w-48 text-xs leading-5 text-slate-600">{agentDiagnosisSummary(ticket)}</span>,
    '추천 서비스': <span className="inline-block whitespace-nowrap">{recommendedSupportLabel(ticket)}</span>,
    '증상': (
      <Link
        className="line-clamp-2 block min-w-28 max-w-36 text-sm font-bold leading-5 text-slate-800 hover:text-brand-blue"
        title={ticketSymptomText(ticket)}
        to={`/admin/as-tickets/${ticket.id}`}
      >
        {symptomSummary(ticket)}
      </Link>
    ),
    '사용자': userLabel(ticket),
    '접수 시간': <span className="whitespace-nowrap">{formatDateTime(ticket.createdAt)}</span>,
    '담당자': <span className="whitespace-nowrap">{ticket.assignedAdminId ? shortId(ticket.assignedAdminId) : '미배정'}</span>,
    '관리': (
      <button
        type="button"
        aria-label={`AS 티켓 ${ticket.id} 삭제`}
        title="티켓 삭제"
        disabled={deleteMutation.isPending}
        onClick={() => {
          const confirmed = window.confirm(
            `AS 티켓 ${shortId(ticket.id)}을 삭제할까요? 연결된 상담방과 진행 중 지원은 종료되며 원본 로그와 감사 기록은 보존됩니다.`
          );
          if (confirmed) {
            deleteMutation.mutate(ticket.id);
          }
        }}
        className="inline-flex h-9 w-9 items-center justify-center rounded border border-red-200 text-red-700 transition hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <Trash2 aria-hidden="true" size={16} />
      </button>
    )
  }));

  const selected = tickets[0];

  return (
    <AdminShell title="AS 티켓 관리">
      <div className="space-y-5">
        <div data-testid="admin-as-ticket-list-panel">
          <Panel title="처리할 AS 티켓" subtitle="사용자 증상과 PCAgent 로그가 접수된 티켓을 확인합니다.">
            {isLoading ? <StateMessage type="info" title="AS 티켓 로딩 중" body="관리자 AS 티켓 목록을 불러오고 있습니다." /> : null}
            {isError ? <StateMessage type="warn" title="AS 티켓 조회 실패" body="AS 티켓 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
            {deleteMutation.isError ? (
              <StateMessage
                type="warn"
                title="AS 티켓 삭제 실패"
                body={deleteMutation.error instanceof Error ? deleteMutation.error.message : 'AS 티켓을 삭제하지 못했습니다.'}
              />
            ) : null}
            {!isLoading && !isError && ticketRows.length === 0 ? (
              <StateMessage type="info" title="AS 티켓 없음" body="표시할 관리자 AS 티켓이 없습니다." />
            ) : null}
            {!isLoading && !isError && ticketRows.length > 0 ? (
              <DataTable
                columns={['티켓', '상태', '검토', '결정', 'Agent 진단', '추천 서비스', '증상', '사용자', '접수 시간', '담당자', '관리']}
                rows={ticketRows}
                minWidth={1320}
                nowrapColumns={['티켓', '상태', '검토', '결정', '추천 서비스', '접수 시간', '담당자', '관리']}
              />
            ) : null}
          </Panel>
        </div>

        <div data-testid="admin-as-ticket-summary-panel">
          <Panel title="최근 티켓 요약" subtitle="가장 최근 접수의 상태와 지원 방향을 빠르게 확인합니다.">
            {selected ? (
              <article className="rounded-lg border border-slate-200 bg-white p-4">
                <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <p className="text-xs font-bold text-slate-500">최근 접수 · {formatDateTime(selected.createdAt)}</p>
                    <p className="mt-1 truncate text-lg font-black text-slate-950" title={ticketSymptomText(selected)}>
                      {symptomSummary(selected)}
                    </p>
                    <p className="mt-1 text-xs font-semibold text-slate-500">티켓 {shortId(selected.id)}</p>
                  </div>
                  <Link
                    to={`/admin/as-tickets/${selected.id}`}
                    className="inline-flex h-9 shrink-0 items-center justify-center rounded border border-brand-blue px-3 text-sm font-bold text-brand-blue transition hover:bg-blue-50"
                  >
                    상세 보기
                  </Link>
                </div>

                <div className="mt-3 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
                  <StatusBadge status={selected.status} />
                  {selected.analysisStatus ? <StatusBadge status={selected.analysisStatus} /> : null}
                  {selected.reviewStatus ? <StatusBadge status={selected.reviewStatus} /> : null}
                  {selected.supportDecision ? <StatusBadge status={selected.supportDecision} /> : null}
                </div>

                <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-3">
                  <div className="rounded-md bg-slate-50 px-3 py-2">
                    <dt className="text-xs font-bold text-slate-500">추천 지원</dt>
                    <dd className="mt-1 font-bold text-slate-900">{recommendedSupportLabel(selected)}</dd>
                  </div>
                  <div className="rounded-md bg-slate-50 px-3 py-2">
                    <dt className="text-xs font-bold text-slate-500">사용자</dt>
                    <dd className="mt-1 truncate font-bold text-slate-900" title={userLabel(selected)}>{userLabel(selected)}</dd>
                  </div>
                  <div className="rounded-md bg-slate-50 px-3 py-2">
                    <dt className="text-xs font-bold text-slate-500">담당자</dt>
                    <dd className="mt-1 font-bold text-slate-900">{selected.assignedAdminId ? shortId(selected.assignedAdminId) : '미배정'}</dd>
                  </div>
                </dl>
              </article>
            ) : (
              <StateMessage type="info" title="선택 티켓 없음" body="티켓이 생성되면 최근 항목 요약이 표시됩니다." />
            )}
          </Panel>
        </div>
      </div>
    </AdminShell>
  );
}

function userLabel(ticket: AdminAsTicket) {
  return ticket.userEmail ?? ticket.userName ?? ticket.userId ?? '-';
}

function agentDiagnosisSummary(ticket: AdminAsTicket) {
  return ticket.diagnosisSummary ?? ticket.logSummaryText ?? firstLine(ticket.symptom);
}

function ticketSymptomText(ticket: AdminAsTicket) {
  return ticket.title?.trim() || firstLine(ticket.symptom);
}

function symptomSummary(ticket: AdminAsTicket) {
  const source = [ticket.title, ticket.symptom, ticket.diagnosisSummary, ticket.logSummaryText]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();

  if (source.includes('problem code 43') || source.includes('그래픽 장치 오류') || source.includes('검은 화면')) return '그래픽 장치 오류';
  if (source.includes('frame drop') || source.includes('프레임 저하') || source.includes('프레임 급락')) return '게임 프레임 저하';
  if (source.includes('warning') || source.includes('경고')) return '견적 경고 문의';
  if (source.includes('온도') || source.includes('과열') || source.includes('thermal')) return '발열·온도 이상';
  if (source.includes('부팅')) return '부팅 문제';
  if (source.includes('전원') || source.includes('power')) return '전원 문제';

  const value = ticketSymptomText(ticket);
  return value.length > 18 ? `${value.slice(0, 18)}…` : value;
}

function recommendedSupportLabel(ticket: AdminAsTicket) {
  const routing = objectValue(ticket.supportRouting);
  const explicitLabel = textValue(routing?.recommendedServiceLabel);
  if (explicitLabel) {
    return explicitLabel;
  }
  const service = textValue(routing?.recommendedService);
  if (service) {
    return supportServiceLabel(service);
  }
  return serviceLabelForDecision(ticket.supportDecision);
}

function supportServiceLabel(service: string) {
  switch (service) {
    case 'REMOTE_SUPPORT':
      return '원격지원 신청';
    case 'VISIT_SUPPORT':
      return '방문지원 신청';
    case 'DIAGNOSIS_ONLY':
      return '우선 진단만 받기';
    default:
      return service;
  }
}

function serviceLabelForDecision(decision?: string | null) {
  switch (decision) {
    case 'REMOTE_POSSIBLE':
      return '원격지원 신청';
    case 'VISIT_REQUIRED':
    case 'REPAIR_OR_REPLACE':
      return '방문지원 신청';
    case 'UNSUPPORTED':
      return '지원 범위 밖';
    default:
      return '우선 진단만 받기';
  }
}

function objectValue(value: unknown) {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
}

function textValue(value: unknown) {
  if (typeof value === 'string') {
    return value.trim() || null;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return null;
}

function firstLine(value: string) {
  return value.split('\n').find((line) => line.trim())?.trim() ?? value;
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return formatSeoulDateTime(value);
}
