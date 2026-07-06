import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Archive, CalendarDays, Filter, Paperclip, Search, Send } from 'lucide-react';
import { AdminShell, StateMessage } from '../../../components/ui';
import {
  archiveCustomerContact,
  createCustomerContactTicket,
  CustomerContactDetail,
  CustomerContactSummary,
  getCustomerContact,
  getCustomerContacts,
  postCustomerContactMessage
} from '../adminApi';
import { openSupportChatSocket, SupportChatSocket } from '../../support/supportChatSocket';

const requestLabels: Record<string, string> = {
  REMOTE: '원격',
  VISIT: '방문',
  DIAGNOSIS_ONLY: '진단'
};

const symptomOptions = [
  { value: 'NETWORK_INTERNET', label: '네트워크 / 인터넷' },
  { value: 'DISPLAY_DRIVER', label: '화면 / 그래픽' },
  { value: 'BOOT_FAILURE', label: '부팅 문제' },
  { value: 'PERIPHERAL_PRINTER', label: '주변기기 / 프린터' },
  { value: 'SYSTEM_SLOW', label: '성능 저하' },
  { value: 'OTHER', label: '기타' }
];

function draftString(draft: Record<string, unknown> | undefined, key: string, fallback = '') {
  const value = draft?.[key];
  return typeof value === 'string' ? value : fallback;
}

function timeLabel(value?: string | null) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function supportBadge(type: string) {
  const tone = type === 'VISIT' ? 'bg-orange-100 text-orange-700' : type === 'REMOTE' ? 'bg-blue-100 text-blue-700' : 'bg-slate-100 text-slate-600';
  return <span className={`rounded px-2 py-1 text-xs font-bold ${tone}`}>{requestLabels[type] ?? type}</span>;
}

export function AdminCustomerContactsPage() {
  const queryClient = useQueryClient();
  const socketRef = useRef<SupportChatSocket | null>(null);
  const [socketConnected, setSocketConnected] = useState(false);
  const contactsQuery = useQuery({
    queryKey: ['admin-customer-contacts'],
    queryFn: getCustomerContacts,
    refetchInterval: socketConnected ? false : 5000
  });
  const contacts = contactsQuery.data?.items ?? [];
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [keyword, setKeyword] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!selectedId && contacts.length > 0) {
      setSelectedId(contacts[0].id);
    }
  }, [contacts, selectedId]);

  const detailQuery = useQuery({
    queryKey: ['admin-customer-contact', selectedId],
    queryFn: () => getCustomerContact(selectedId as string),
    enabled: Boolean(selectedId),
    refetchInterval: selectedId && !socketConnected ? 5000 : false
  });

  const selectedContact = detailQuery.data?.contact ?? contacts.find((item) => item.id === selectedId);
  const ticketDraft = useMemo(
    () => detailQuery.data?.ticketDraft ?? selectedContact?.ticketDraft ?? {},
    [detailQuery.data?.ticketDraft, selectedContact?.ticketDraft]
  );
  const [form, setForm] = useState({
    symptomType: 'NETWORK_INTERNET',
    symptomSummary: '',
    supportRequestType: 'REMOTE',
    preferredScheduleAt: '',
    adminNote: ''
  });

  useEffect(() => {
    if (!selectedContact) return;
    setForm({
      symptomType: draftString(ticketDraft, 'symptomType', 'NETWORK_INTERNET'),
      symptomSummary: draftString(ticketDraft, 'symptomSummary', selectedContact.lastMessagePreview ?? ''),
      supportRequestType: draftString(ticketDraft, 'supportRequestType', selectedContact.supportRequestType),
      preferredScheduleAt: draftString(ticketDraft, 'preferredScheduleAt', ''),
      adminNote: draftString(ticketDraft, 'adminNote', '')
    });
  }, [selectedContact?.id, ticketDraft]);

  const filteredContacts = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) return contacts;
    return contacts.filter((contact) => `${contact.userName ?? ''} ${contact.lastMessagePreview ?? ''}`.toLowerCase().includes(normalized));
  }, [contacts, keyword]);

  const messageMutation = useMutation({
    mutationFn: () => postCustomerContactMessage(selectedId as string, message),
    onSuccess: () => {
      setMessage('');
      queryClient.invalidateQueries({ queryKey: ['admin-customer-contacts'] });
      queryClient.invalidateQueries({ queryKey: ['admin-customer-contact', selectedId] });
    }
  });

  useEffect(() => {
    if (!selectedId) {
      socketRef.current?.close();
      socketRef.current = null;
      setSocketConnected(false);
      return;
    }
    socketRef.current?.close();
    socketRef.current = openSupportChatSocket<CustomerContactDetail>({
      mode: 'admin',
      sessionId: selectedId,
      onOpen: () => setSocketConnected(true),
      onClose: () => setSocketConnected(false),
      onError: () => setSocketConnected(false),
      onContactsInvalidated: () => {
        queryClient.invalidateQueries({ queryKey: ['admin-customer-contacts'] });
      },
      onDetail: (detail) => {
        queryClient.setQueryData(['admin-customer-contact', selectedId], detail);
        queryClient.invalidateQueries({ queryKey: ['admin-customer-contacts'] });
      }
    });
    return () => {
      socketRef.current?.close();
      socketRef.current = null;
      setSocketConnected(false);
    };
  }, [queryClient, selectedId]);

  const ticketMutation = useMutation({
    mutationFn: () => createCustomerContactTicket(selectedId as string, form),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-customer-contacts'] });
      queryClient.invalidateQueries({ queryKey: ['admin-customer-contact', selectedId] });
    }
  });

  const archiveMutation = useMutation({
    mutationFn: () => archiveCustomerContact(selectedId as string),
    onSuccess: () => {
      setSelectedId(null);
      queryClient.invalidateQueries({ queryKey: ['admin-customer-contacts'] });
    }
  });

  function submitMessage(event: FormEvent) {
    event.preventDefault();
    if (!selectedId || !message.trim()) return;
    if (socketRef.current?.sendMessage(message.trim())) {
      setMessage('');
      return;
    }
    messageMutation.mutate();
  }

  function submitTicket(event: FormEvent) {
    event.preventDefault();
    if (!selectedId) return;
    ticketMutation.mutate();
  }

  if (contactsQuery.isLoading) {
    return (
      <AdminShell title="고객 연락">
        <StateMessage type="info" title="상담방 로딩 중" body="관리자 고객 연락 목록을 불러오고 있습니다." />
      </AdminShell>
    );
  }

  if (contactsQuery.isError) {
    return (
      <AdminShell title="고객 연락">
        <StateMessage type="warn" title="고객 연락 조회 실패" body="관리자 상담방 API 응답을 불러오지 못했습니다." />
      </AdminShell>
    );
  }

  return (
    <AdminShell title="고객 연락">
      <div className="grid min-h-[calc(100vh-8.5rem)] grid-cols-[330px_minmax(420px,1fr)_390px] overflow-hidden rounded-lg border border-slate-200 bg-white">
        <section className="border-r border-slate-200 bg-slate-50 p-5">
          <div className="mb-4 flex gap-2">
            <label className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" size={16} />
              <input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                placeholder="고객명, 메시지 검색"
                className="h-11 w-full rounded-md border border-slate-200 bg-white pl-9 pr-3 text-sm outline-none focus:border-brand-blue"
              />
            </label>
            <button type="button" className="grid h-11 w-11 place-items-center rounded-md border border-slate-200 bg-white text-slate-600" title="필터">
              <Filter size={17} />
            </button>
          </div>
          <div className="space-y-2">
            {filteredContacts.map((contact) => (
              <ContactListItem
                key={contact.id}
                contact={contact}
                active={contact.id === selectedId}
                onClick={() => setSelectedId(contact.id)}
              />
            ))}
          </div>
          <p className="mt-5 rounded-md border border-dashed border-slate-300 bg-white p-3 text-xs leading-5 text-slate-500">
            사용자/관리자 채팅은 WebSocket으로 실시간 반영되며, 연결이 끊기면 REST 조회로 보완됩니다.
          </p>
        </section>

        <section className="flex min-w-0 flex-col">
          <div className="flex h-20 items-center justify-between border-b border-slate-200 px-6">
            <div>
              <div className="flex items-center gap-3">
                <h2 className="text-2xl font-bold text-brand-navy">{selectedContact?.userName ?? '상담방 선택'}</h2>
                {selectedContact ? <span className="text-sm font-semibold text-brand-blue">고객 정보 보기</span> : null}
              </div>
              {selectedContact ? (
                <p className="mt-1 text-sm text-slate-500">{selectedContact.userEmail}</p>
              ) : null}
            </div>
            {selectedContact?.ticketId ? (
              <Link to={`/admin/as-tickets/${selectedContact.ticketId}`} className="rounded-md border border-brand-blue px-3 py-2 text-sm font-bold text-brand-blue">
                생성 티켓 보기
              </Link>
            ) : null}
          </div>

          <div className="flex-1 space-y-5 overflow-auto bg-white p-6">
            {detailQuery.isLoading ? (
              <StateMessage type="info" title="대화 불러오는 중" body="상담 메시지를 조회하고 있습니다." />
            ) : detailQuery.data?.messages.length ? (
              detailQuery.data.messages.map((item) => {
                const isAdmin = item.role === 'ADMIN' || item.role === 'ASSISTANT';
                return (
                  <div key={item.id} className={`flex ${isAdmin ? 'justify-start' : 'justify-end'}`}>
                    <div className={`max-w-[70%] rounded-lg px-5 py-4 text-sm leading-6 ${isAdmin ? 'bg-slate-100 text-slate-800' : 'bg-teal-50 text-brand-navy'}`}>
                      <div className="mb-1 text-xs font-bold text-slate-500">{isAdmin ? '관리자(나)' : selectedContact?.userName} · {timeLabel(item.createdAt)}</div>
                      {item.content}
                    </div>
                  </div>
                );
              })
            ) : (
              <StateMessage type="info" title="메시지 없음" body="아직 표시할 상담 메시지가 없습니다." />
            )}
          </div>

          <form onSubmit={submitMessage} className="flex gap-3 border-t border-slate-200 p-5">
            <button type="button" className="grid h-12 w-12 place-items-center rounded-md border border-slate-200 text-slate-500" title="첨부">
              <Paperclip size={18} />
            </button>
            <input
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              placeholder="메시지를 입력하세요"
              className="h-12 flex-1 rounded-md border border-slate-200 px-4 text-sm outline-none focus:border-brand-blue"
            />
            <button type="submit" disabled={!message.trim() || messageMutation.isPending} className="flex h-12 items-center gap-2 rounded-md bg-brand-blue px-5 text-sm font-bold text-white disabled:bg-slate-300">
              <Send size={16} />
              전송
            </button>
          </form>
        </section>

        <aside className="border-l border-slate-200 p-6">
          <div className="mb-5 flex items-start justify-between gap-3">
            <h2 className="text-xl font-bold text-brand-navy">상담 내용 기반 접수 티켓 생성</h2>
            <button
              type="button"
              onClick={() => archiveMutation.mutate()}
              disabled={!selectedId || archiveMutation.isPending}
              className="flex items-center gap-1 rounded-md border border-red-200 px-3 py-2 text-xs font-bold text-red-600 disabled:text-slate-300"
            >
              <Archive size={14} />
              종료
            </button>
          </div>
          <form onSubmit={submitTicket} className="space-y-4">
            <label className="block text-sm font-bold text-slate-700">
              증상 유형 *
              <select
                value={form.symptomType}
                onChange={(event) => setForm((prev) => ({ ...prev, symptomType: event.target.value }))}
                className="mt-2 h-11 w-full rounded-md border border-slate-200 px-3 text-sm font-normal"
              >
                {symptomOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select>
            </label>
            <label className="block text-sm font-bold text-slate-700">
              증상 요약 *
              <textarea
                value={form.symptomSummary}
                onChange={(event) => setForm((prev) => ({ ...prev, symptomSummary: event.target.value }))}
                className="mt-2 min-h-28 w-full rounded-md border border-slate-200 p-3 text-sm font-normal leading-6"
              />
            </label>
            <div className="text-sm font-bold text-slate-700">
              지원 방식 *
              <div className="mt-3 flex gap-5 text-sm font-semibold">
                {['REMOTE', 'VISIT', 'DIAGNOSIS_ONLY'].map((type) => (
                  <label key={type} className="flex items-center gap-2">
                    <input
                      type="radio"
                      checked={form.supportRequestType === type}
                      onChange={() => setForm((prev) => ({ ...prev, supportRequestType: type }))}
                    />
                    {requestLabels[type]}
                  </label>
                ))}
              </div>
            </div>
            <label className="block text-sm font-bold text-slate-700">
              예약 희망 시간
              <div className="relative mt-2">
                <CalendarDays className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400" size={17} />
                <input
                  value={form.preferredScheduleAt}
                  onChange={(event) => setForm((prev) => ({ ...prev, preferredScheduleAt: event.target.value }))}
                  placeholder="2026-07-03 20:00 이후"
                  className="h-11 w-full rounded-md border border-slate-200 px-3 pr-10 text-sm font-normal"
                />
              </div>
            </label>
            <label className="block text-sm font-bold text-slate-700">
              관리자 메모
              <textarea
                value={form.adminNote}
                onChange={(event) => setForm((prev) => ({ ...prev, adminNote: event.target.value }))}
                placeholder="메모를 입력하세요"
                className="mt-2 min-h-28 w-full rounded-md border border-slate-200 p-3 text-sm font-normal leading-6"
              />
            </label>
            <button type="submit" disabled={!selectedId || Boolean(selectedContact?.ticketId) || ticketMutation.isPending} className="h-13 w-full rounded-md bg-brand-blue px-4 py-4 text-sm font-bold text-white disabled:bg-slate-300">
              {selectedContact?.ticketId ? '이미 티켓 생성됨' : '접수 티켓 만들기'}
            </button>
            <p className="text-xs leading-5 text-slate-500">티켓은 상담 내용을 기반으로 여기에서 생성됩니다. 채팅 메시지는 사용자 화면과 실시간으로 동기화됩니다.</p>
          </form>
        </aside>
      </div>
    </AdminShell>
  );
}

function ContactListItem({ contact, active, onClick }: { contact: CustomerContactSummary; active: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center gap-3 rounded-md border p-3 text-left transition ${active ? 'border-brand-blue bg-teal-50' : 'border-slate-200 bg-white hover:border-slate-300'}`}
    >
      <div className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-slate-200 text-sm font-bold text-slate-700">
        {(contact.userName ?? '?').slice(0, 1)}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-bold text-brand-navy">{contact.userName}</span>
          {supportBadge(contact.supportRequestType)}
          <span className="ml-auto text-xs text-slate-500">{timeLabel(contact.lastMessageAt)}</span>
        </div>
        <p className="mt-1 truncate text-sm text-slate-500">{contact.lastMessagePreview ?? '상담 메시지 없음'}</p>
      </div>
      <span className="grid h-6 min-w-6 place-items-center rounded-full bg-brand-blue px-2 text-xs font-bold text-white">{contact.adminUnreadCount ?? 0}</span>
    </button>
  );
}
