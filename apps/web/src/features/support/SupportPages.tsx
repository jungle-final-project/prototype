import { ChangeEvent, FormEvent, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../components/ui';
import { createSupportTicket, getSupportTicket, uploadAgentLog } from './supportApi';
import type { AsTicketDto, CauseCandidate } from './types';

type SubmitState = 'default' | 'consent_required' | 'uploading' | 'upload_error' | 'ticket_created';

export function SupportNewPage() {
  const navigate = useNavigate();
  const [symptomTitle, setSymptomTitle] = useState('');
  const [symptomDetail, setSymptomDetail] = useState('');
  const [consentAccepted, setConsentAccepted] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [logPreview, setLogPreview] = useState('');
  const [submitState, setSubmitState] = useState<SubmitState>('default');
  const [error, setError] = useState('');

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0] ?? null;
    setError('');
    setSelectedFile(null);
    setLogPreview('');

    if (!file) return;

    const lowerName = file.name.toLowerCase();
    if (!lowerName.endsWith('.jsonl') && !lowerName.endsWith('.ndjson')) {
      setSubmitState('upload_error');
      setError('JSONL 또는 NDJSON 로그 파일만 업로드할 수 있습니다.');
      return;
    }

    setSelectedFile(file);
    file.text()
      .then((text) => {
        const lines = text.split(/\r?\n/).filter(Boolean).slice(0, 8);
        setLogPreview(lines.join('\n') || '선택한 파일에 표시할 로그 라인이 없습니다.');
      })
      .catch(() => setLogPreview('로그 미리보기를 읽지 못했습니다. 파일은 그대로 제출할 수 있습니다.'));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');

    const title = symptomTitle.trim();
    const detail = symptomDetail.trim();
    if (!title || !detail) {
      setSubmitState('upload_error');
      setError('증상 제목과 상세 내용을 모두 입력해 주세요.');
      return;
    }
    if (!selectedFile) {
      setSubmitState('upload_error');
      setError('최근 30분 PC Agent JSONL 로그 파일을 선택해 주세요.');
      return;
    }
    if (!consentAccepted) {
      setSubmitState('consent_required');
      setError('로그 업로드와 30일 보관에 동의해야 AS를 접수할 수 있습니다.');
      return;
    }

    try {
      setSubmitState('uploading');
      const uploadedLog = await uploadAgentLog(30, consentAccepted, selectedFile);
      const symptom = `${title}\n\n${detail}`;
      const ticket = await createSupportTicket(symptom, uploadedLog.id);
      setSubmitState('ticket_created');
      navigate(`/support/${ticket.id}`);
    } catch {
      setSubmitState('upload_error');
      setError('로그 업로드 또는 AS 티켓 생성에 실패했습니다. 백엔드 실행 상태를 확인해 주세요.');
    }
  }

  const isUploading = submitState === 'uploading';

  return (
    <Screen>
      <form onSubmit={submit} className="grid grid-cols-[minmax(0,1fr)_360px] gap-5">
        <Panel title="AS 접수 / 로그 업로드" subtitle="최근 30분 JSONL 로그 업로드 동의 후 티켓을 생성합니다.">
          <div className="space-y-4">
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 제목</label>
              <input
                className="h-11 w-full rounded border border-slate-300 px-3 text-sm"
                placeholder="예: 게임 중 프레임 드랍"
                value={symptomTitle}
                onChange={(event) => setSymptomTitle(event.target.value)}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">증상 상세</label>
              <textarea
                className="h-36 w-full rounded border border-slate-300 p-4 text-sm"
                placeholder="언제부터 발생했는지, 어떤 작업 중 재현되는지 입력해 주세요."
                value={symptomDetail}
                onChange={(event) => setSymptomDetail(event.target.value)}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-bold text-slate-600">최근 30분 로그 파일</label>
              <input
                className="block w-full rounded border border-slate-300 p-3 text-sm file:mr-4 file:rounded file:border-0 file:bg-brand-blue file:px-4 file:py-2 file:text-sm file:font-bold file:text-white"
                type="file"
                accept=".jsonl,.ndjson,application/x-ndjson,application/json,text/plain"
                onChange={handleFileChange}
              />
              {selectedFile ? <p className="mt-2 text-xs text-slate-500">{selectedFile.name} · {selectedFile.size.toLocaleString()} bytes</p> : null}
            </div>
            <div className="min-h-32 rounded bg-slate-900 p-4 font-mono text-xs leading-6 text-slate-200">
              {logPreview ? <pre className="whitespace-pre-wrap">{logPreview}</pre> : 'PC Agent JSONL 로그 미리보기가 여기에 표시됩니다.'}
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={consentAccepted} onChange={(event) => setConsentAccepted(event.target.checked)} />
              최근 30분 로그 업로드와 30일 보관 후 삭제 정책에 동의합니다.
            </label>
            {error ? <StateMessage type="warn" title="AS 접수 확인 필요" body={error} /> : null}
            {submitState === 'ticket_created' ? <StateMessage type="success" title="AS 티켓 생성 완료" body="생성된 티켓 상세 화면으로 이동합니다." /> : null}
            <button disabled={isUploading} className="rounded bg-brand-blue px-5 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
              {isUploading ? '업로드 중...' : 'AS 접수하기'}
            </button>
          </div>
        </Panel>
        <Panel title="접수 상태">
          {submitState === 'default' ? <StateMessage type="info" title="로그 제출 준비" body="증상과 최근 30분 JSONL 로그를 함께 제출하면 AS 티켓이 생성됩니다." /> : null}
          {submitState === 'consent_required' ? <StateMessage type="warn" title="동의 필요" body="PC Agent 로그에는 사용 환경 정보가 포함될 수 있어 업로드 동의가 필요합니다." /> : null}
          {submitState === 'uploading' ? <StateMessage type="info" title="업로드 중" body="로그 업로드 후 AS 티켓 생성 API를 순서대로 호출하고 있습니다." /> : null}
          {submitState === 'upload_error' ? <StateMessage type="warn" title="접수 실패" body={error || '입력값과 백엔드 실행 상태를 확인해 주세요.'} /> : null}
          {submitState === 'ticket_created' ? <StateMessage type="success" title="접수 완료" body="사용자 티켓 상세 화면에서 상태를 확인할 수 있습니다." /> : null}
          <div className="mt-5 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">POST /api/agent-logs/upload</div>
          <div className="mt-2 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">POST /api/as-tickets</div>
        </Panel>
      </form>
    </Screen>
  );
}

export function SupportTicketPage() {
  const { ticketId = '00000000-0000-4000-8000-000000006001' } = useParams();
  const { data: ticket, isError, isLoading } = useQuery({
    queryKey: ['support-ticket', ticketId],
    queryFn: () => getSupportTicket(ticketId)
  });

  if (isLoading) {
    return (
      <Screen>
        <StateMessage type="info" title="AS 티켓 조회 중" body="티켓 상태와 로그 요약 정보를 불러오고 있습니다." />
      </Screen>
    );
  }

  if (isError || !ticket) {
    return (
      <Screen>
        <StateMessage type="warn" title="AS 티켓 조회 실패" body="GET /api/as-tickets/{id} 응답을 불러오지 못했습니다." />
      </Screen>
    );
  }

  return (
    <Screen>
      <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
        <Panel title={`AS 티켓 #${shortTicketId(ticket.id)}`} subtitle="사용자와 담당자가 함께 보는 상세 화면">
          <div className="mb-4 flex flex-wrap gap-2">
            <StatusBadge status={ticket.status} />
            <span className="rounded-full border border-emerald-200 bg-emerald-50 px-2 py-1 text-[11px] font-bold text-emerald-700">Agent 원인 후보 생성 {ticket.causeCandidates.length ? '완료' : '대기'}</span>
          </div>
          <DataTable columns={['시간', '주체', '내용']} rows={ticketTimeline(ticket)} />
          <div className="mt-4">
            <label className="mb-1 block text-xs font-bold text-slate-600">답변 입력</label>
            <div className="flex gap-3">
              <input disabled className="h-11 flex-1 rounded border border-slate-300 px-3 text-sm text-slate-400" placeholder="이번 PR에서는 답변 등록을 구현하지 않습니다." />
              <button disabled className="rounded bg-slate-300 px-5 py-3 text-sm font-bold text-white">답변 등록</button>
            </div>
          </div>
        </Panel>
        <Panel title="로그 요약 / 추천 조치" subtitle="관리자와 동일한 근거 일부 노출">
          <DataTable columns={['원인 후보', '근거', '신뢰도']} rows={causeRows(ticket.causeCandidates)} />
          <p className="mt-5 text-sm leading-6 text-slate-700">
            다음 조치: 로그 요약과 원인 후보를 확인한 뒤 담당자가 추가 확인을 요청할 수 있습니다.
          </p>
          <div className="mt-6 flex gap-3">
            <button disabled className="rounded border border-slate-300 px-4 py-3 text-sm font-bold text-slate-400">로그 다시 업로드</button>
            <button disabled className="rounded bg-slate-300 px-4 py-3 text-sm font-bold text-white">티켓 종료 요청</button>
          </div>
          <div className="mt-8 rounded border border-blue-100 bg-blue-50 px-3 py-2 text-center text-xs font-bold text-brand-blue">GET /api/as-tickets/{'{id}'}</div>
          <Link to="/support/new" className="mt-5 block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold">새 AS 접수</Link>
        </Panel>
      </div>
    </Screen>
  );
}

function ticketTimeline(ticket: AsTicketDto) {
  return [
    {
      시간: formatTime(ticket.createdAt),
      주체: '사용자',
      내용: ticket.symptom
    },
    {
      시간: formatTime(ticket.createdAt),
      주체: 'Agent',
      내용: ticket.causeCandidates.length ? '로그 기반 원인 후보 생성 완료' : '원인 후보 생성 대기'
    },
    {
      시간: '-',
      주체: '상담원',
      내용: ticket.assignedAdminId ? '담당자 배정 완료' : '담당자 배정 대기'
    }
  ];
}

function causeRows(candidates: CauseCandidate[]) {
  if (!candidates.length) {
    return [{ '원인 후보': '분석 대기', 근거: '티켓 접수 완료', 신뢰도: <StatusBadge status="LOW" /> }];
  }
  return candidates.map((candidate) => ({
    '원인 후보': candidate.label ?? candidate.code ?? '원인 후보',
    근거: candidate.evidenceIds?.length ? candidate.evidenceIds.join(', ') : '로그 요약 기반',
    신뢰도: <StatusBadge status={candidate.confidence ?? 'MEDIUM'} />
  }));
}

function shortTicketId(id: string) {
  const parts = id.split('-');
  const lastPart = parts[parts.length - 1];
  return lastPart ? lastPart.replace(/^0+/, '') || lastPart : id;
}

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}
