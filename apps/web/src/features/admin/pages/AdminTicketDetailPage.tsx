import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminTicket, updateAdminTicket } from '../adminApi';
import type { AdminAsTicket, AdminAsTicketUpdateRequest, AsTicketStatus } from '../adminApi';

const editableStatuses: AsTicketStatus[] = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'];
const reviewStatuses = ['', 'NOT_REQUIRED', 'REQUIRED', 'IN_REVIEW', 'APPROVED', 'REJECTED'];
const supportDecisions = ['', 'SELF_SOLVABLE', 'REMOTE_POSSIBLE', 'VISIT_REQUIRED', 'NEEDS_MORE_INFO'];
const riskLevels = ['', 'LOW', 'MEDIUM', 'HIGH'];
const visitTimeSlots = ['', 'MORNING', 'AFTERNOON', 'EVENING'];

type DecisionForm = {
  assignedAdminId: string;
  adminNote: string;
  supportDecision: string;
  reviewStatus: string;
  riskLevel: string;
  autoResponseAllowed: 'UNCHANGED' | 'true' | 'false';
  remoteSupportLink: string;
  visitSupportRequired: boolean;
  visitPreferredDate: string;
  visitTimeSlot: string;
};

const emptyDecisionForm: DecisionForm = {
  assignedAdminId: '',
  adminNote: '',
  supportDecision: '',
  reviewStatus: '',
  riskLevel: '',
  autoResponseAllowed: 'UNCHANGED',
  remoteSupportLink: '',
  visitSupportRequired: false,
  visitPreferredDate: '',
  visitTimeSlot: ''
};

export function AdminTicketDetailPage() {
  const { ticketId = '' } = useParams();
  const queryClient = useQueryClient();
  const [selectedStatus, setSelectedStatus] = useState<AsTicketStatus>('OPEN');
  const [decisionForm, setDecisionForm] = useState<DecisionForm>(emptyDecisionForm);

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
    if (!ticket) {
      return;
    }
    setSelectedStatus(ticket.status);
    setDecisionForm({
      assignedAdminId: ticket.assignedAdminId ?? '',
      adminNote: ticket.adminNote ?? '',
      supportDecision: ticket.supportDecision ?? '',
      reviewStatus: ticket.reviewStatus ?? '',
      riskLevel: ticket.riskLevel ?? '',
      autoResponseAllowed: ticket.autoResponseAllowed == null ? 'UNCHANGED' : String(ticket.autoResponseAllowed) as 'true' | 'false',
      remoteSupportLink: ticket.remoteSupportLink ?? '',
      visitSupportRequired: Boolean(ticket.visitSupportRequired),
      visitPreferredDate: ticket.visitPreferredDate ?? '',
      visitTimeSlot: ticket.visitTimeSlot ?? ''
    });
  }, [ticket]);

  const updateMutation = useMutation({
    mutationFn: () => {
      if (!ticket) {
        throw new Error('AS ticket is not loaded');
      }
      return updateAdminTicket(ticketId, buildUpdateRequest(ticket, selectedStatus, decisionForm));
    },
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
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="티켓 상세 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." />
      </AdminShell>
    );
  }

  const canSave = hasFormChanges(ticket, selectedStatus, decisionForm) && !updateMutation.isPending;

  return (
    <AdminShell title="AS 티켓 상세">
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_440px]">
        <Panel title="AS 티켓 확인" subtitle={ticket.id}>
          <DataTable columns={['항목', '내용']} rows={ticketDetailRows(ticket)} />
        </Panel>
        <Panel title="지원 결정 저장" subtitle="관리자 결정은 사용자 AS 상세 화면에 바로 반영됩니다.">
          <div className="grid gap-4">
            <Field label="처리 상태" htmlFor="ticket-status">
              <select
                id="ticket-status"
                className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                value={selectedStatus}
                onChange={(event) => setSelectedStatus(event.target.value as AsTicketStatus)}
              >
                {editableStatuses.map((status) => (
                  <option key={status} value={status}>{status}</option>
                ))}
              </select>
            </Field>
            <Field label="검토 상태" htmlFor="review-status">
              <select
                id="review-status"
                className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                value={decisionForm.reviewStatus}
                onChange={(event) => setDecisionForm({ ...decisionForm, reviewStatus: event.target.value })}
              >
                {reviewStatuses.map((status) => <option key={status || 'empty'} value={status}>{status || '변경 없음'}</option>)}
              </select>
            </Field>
            <Field label="지원 결정" htmlFor="support-decision">
              <select
                id="support-decision"
                className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                value={decisionForm.supportDecision}
                onChange={(event) => setDecisionForm({ ...decisionForm, supportDecision: event.target.value })}
              >
                {supportDecisions.map((decision) => <option key={decision || 'empty'} value={decision}>{decision || '변경 없음'}</option>)}
              </select>
            </Field>
            <Field label="위험도" htmlFor="risk-level">
              <select
                id="risk-level"
                className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                value={decisionForm.riskLevel}
                onChange={(event) => setDecisionForm({ ...decisionForm, riskLevel: event.target.value })}
              >
                {riskLevels.map((riskLevel) => <option key={riskLevel || 'empty'} value={riskLevel}>{riskLevel || '변경 없음'}</option>)}
              </select>
            </Field>
            <Field label="자동 안내 허용" htmlFor="auto-response-allowed">
              <select
                id="auto-response-allowed"
                className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                value={decisionForm.autoResponseAllowed}
                onChange={(event) => setDecisionForm({ ...decisionForm, autoResponseAllowed: event.target.value as DecisionForm['autoResponseAllowed'] })}
              >
                <option value="UNCHANGED">변경 없음</option>
                <option value="true">허용</option>
                <option value="false">허용 안 함</option>
              </select>
            </Field>
            <Field label="담당 관리자 public id" htmlFor="assigned-admin-id">
              <input
                id="assigned-admin-id"
                className="w-full rounded border border-slate-300 px-3 py-3 text-sm text-slate-800"
                value={decisionForm.assignedAdminId}
                onChange={(event) => setDecisionForm({ ...decisionForm, assignedAdminId: event.target.value })}
                placeholder="예: 관리자 public UUID"
              />
            </Field>
            <Field label="원격 지원 링크" htmlFor="remote-support-link">
              <input
                id="remote-support-link"
                className="w-full rounded border border-slate-300 px-3 py-3 text-sm text-slate-800"
                value={decisionForm.remoteSupportLink}
                onChange={(event) => setDecisionForm({ ...decisionForm, remoteSupportLink: event.target.value })}
                placeholder="https://support.example/session/..."
              />
            </Field>
            <Field label="방문 지원" htmlFor="visit-support-required">
              <label className="flex items-center gap-2 text-sm font-bold text-slate-700">
                <input
                  id="visit-support-required"
                  type="checkbox"
                  checked={decisionForm.visitSupportRequired}
                  onChange={(event) => setDecisionForm({ ...decisionForm, visitSupportRequired: event.target.checked })}
                />
                방문 지원 요청
              </label>
            </Field>
            {decisionForm.visitSupportRequired ? (
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="방문 희망일" htmlFor="visit-preferred-date">
                  <input
                    id="visit-preferred-date"
                    type="date"
                    className="w-full rounded border border-slate-300 px-3 py-3 text-sm text-slate-800"
                    value={decisionForm.visitPreferredDate}
                    onChange={(event) => setDecisionForm({ ...decisionForm, visitPreferredDate: event.target.value })}
                  />
                </Field>
                <Field label="방문 시간대" htmlFor="visit-time-slot">
                  <select
                    id="visit-time-slot"
                    className="w-full rounded border border-slate-300 bg-white px-3 py-3 text-sm font-bold text-slate-800"
                    value={decisionForm.visitTimeSlot}
                    onChange={(event) => setDecisionForm({ ...decisionForm, visitTimeSlot: event.target.value })}
                  >
                    {visitTimeSlots.map((slot) => <option key={slot || 'empty'} value={slot}>{slot || '기본값'}</option>)}
                  </select>
                </Field>
              </div>
            ) : null}
            <Field label="관리자 메모" htmlFor="admin-note">
              <textarea
                id="admin-note"
                className="min-h-28 w-full rounded border border-slate-300 px-3 py-3 text-sm text-slate-800"
                value={decisionForm.adminNote}
                onChange={(event) => setDecisionForm({ ...decisionForm, adminNote: event.target.value })}
                placeholder="사용자에게 표시할 안내 메모"
              />
            </Field>
          </div>
          <button
            className="mt-5 w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-300"
            disabled={!canSave}
            onClick={() => updateMutation.mutate()}
          >
            {updateMutation.isPending ? '결정 저장 중' : '결정 저장'}
          </button>
          {updateMutation.isError ? (
            <div className="mt-4">
              <StateMessage type="warn" title="결정 저장 실패" body="허용되지 않는 상태 전이거나 입력값이 계약과 맞지 않습니다. 값을 확인한 뒤 다시 시도해 주세요." />
            </div>
          ) : null}
          {updateMutation.isSuccess ? (
            <div className="mt-4">
              <StateMessage type="success" title="결정 저장 완료" body="관리자 결정이 저장됐고 티켓 상세 캐시가 갱신됐습니다." />
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

function Field({ children, htmlFor, label }: { children: ReactNode; htmlFor: string; label: string }) {
  return (
    <label className="block text-xs font-bold text-slate-600" htmlFor={htmlFor}>
      <span className="mb-2 block">{label}</span>
      {children}
    </label>
  );
}

function buildUpdateRequest(
  ticket: AdminAsTicket,
  selectedStatus: AsTicketStatus,
  form: DecisionForm
): AdminAsTicketUpdateRequest {
  const request: AdminAsTicketUpdateRequest = {};
  if (selectedStatus !== ticket.status) {
    request.status = selectedStatus;
  }
  assignIfChanged(request, 'assignedAdminId', ticket.assignedAdminId, form.assignedAdminId);
  assignIfChanged(request, 'adminNote', ticket.adminNote, form.adminNote);
  assignIfChanged(request, 'supportDecision', ticket.supportDecision, form.supportDecision);
  assignIfChanged(request, 'reviewStatus', ticket.reviewStatus, form.reviewStatus);
  assignIfChanged(request, 'riskLevel', ticket.riskLevel, form.riskLevel);
  assignIfChanged(request, 'remoteSupportLink', ticket.remoteSupportLink, form.remoteSupportLink);
  const currentAuto = ticket.autoResponseAllowed == null ? 'UNCHANGED' : String(ticket.autoResponseAllowed);
  if (form.autoResponseAllowed !== currentAuto && form.autoResponseAllowed !== 'UNCHANGED') {
    request.autoResponseAllowed = form.autoResponseAllowed === 'true';
  }
  if (form.visitSupportRequired !== Boolean(ticket.visitSupportRequired)) {
    request.visitSupportRequired = form.visitSupportRequired;
  }
  if (form.visitSupportRequired) {
    assignIfChanged(request, 'visitPreferredDate', ticket.visitPreferredDate, form.visitPreferredDate);
    assignIfChanged(request, 'visitTimeSlot', ticket.visitTimeSlot, form.visitTimeSlot);
  }
  return request;
}

function hasFormChanges(ticket: AdminAsTicket, selectedStatus: AsTicketStatus, form: DecisionForm) {
  return Object.keys(buildUpdateRequest(ticket, selectedStatus, form)).length > 0;
}

type StringUpdateKey =
  | 'assignedAdminId'
  | 'adminNote'
  | 'supportDecision'
  | 'reviewStatus'
  | 'riskLevel'
  | 'remoteSupportLink'
  | 'visitPreferredDate'
  | 'visitTimeSlot';

function assignIfChanged(
  request: AdminAsTicketUpdateRequest,
  key: StringUpdateKey,
  currentValue: string | null | undefined,
  nextValue: string
) {
  const normalizedNext = nextValue.trim();
  if (normalizedNext && normalizedNext !== (currentValue ?? '')) {
    request[key] = normalizedNext;
  }
}

function ticketDetailRows(ticket: AdminAsTicket) {
  return [
    { 항목: '상태', 내용: <StatusBadge status={ticket.status} /> },
    { 항목: '진단 상태', 내용: ticket.analysisStatus ? <StatusBadge status={ticket.analysisStatus} /> : '-' },
    { 항목: '검토 상태', 내용: ticket.reviewStatus ? <StatusBadge status={ticket.reviewStatus} /> : '-' },
    { 항목: '지원 결정', 내용: ticket.supportDecision ? <StatusBadge status={ticket.supportDecision} /> : '-' },
    { 항목: '위험도', 내용: ticket.riskLevel ? <StatusBadge status={ticket.riskLevel} /> : '-' },
    { 항목: '제목/증상', 내용: ticket.title ?? ticket.symptom },
    { 항목: '상세 설명', 내용: ticket.description ?? ticket.detailDescription ?? '상세 설명 응답 없음' },
    { 항목: '로그 요약', 내용: logSummary(ticket) },
    { 항목: '원인 후보', 내용: formatCandidates(ticket.causeCandidates) },
    { 항목: '업그레이드 후보', 내용: formatCandidates(ticket.upgradeCandidates) },
    { 항목: '원격지원', 내용: ticket.remoteSupportLink ?? ticket.remoteSupportStatus ?? '-' },
    { 항목: '방문지원', 내용: visitSupport(ticket) },
    { 항목: '담당자', 내용: ticket.assignedAdminId ?? '-' },
    { 항목: '관리자 메모', 내용: ticket.adminNote ?? '-' },
    { 항목: '생성일', 내용: formatDateTime(ticket.createdAt) },
    { 항목: '해결일', 내용: formatDateTime(ticket.resolvedAt) }
  ];
}

function logSummary(ticket: AdminAsTicket) {
  if (ticket.logSummary) {
    return ticket.logSummary;
  }
  return ticket.logUploadId ? `업로드된 로그 있음: ${shortId(ticket.logUploadId)}` : '연결된 로그 없음';
}

function visitSupport(ticket: AdminAsTicket) {
  if (!ticket.visitSupportRequired) {
    return '-';
  }
  return `${ticket.visitSupportStatus ?? 'REQUESTED'} ${ticket.visitPreferredDate ?? ''} ${ticket.visitTimeSlot ?? ''}`.trim();
}

function formatCandidates(candidates: Record<string, unknown>[]) {
  if (!candidates.length) {
    return '-';
  }
  return candidates.map((candidate) => {
    const summary = candidate.summary ?? candidate.reason ?? candidate.name ?? candidate.title ?? candidate.label;
    return summary == null ? JSON.stringify(candidate) : String(summary);
  }).join(' / ');
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
