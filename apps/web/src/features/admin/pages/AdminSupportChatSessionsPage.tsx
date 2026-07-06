import { FormEvent, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import { getAdminSupportChatSession, getAdminSupportChatSessions, postAdminSupportChatMessage } from '../../support/supportChatApi';
import type { SupportChatContact, SupportChatMessage, SupportChatSessionDto } from '../../support/types';

export function AdminSupportChatSessionsPage() {
  const queryClient = useQueryClient();
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [message, setMessage] = useState('');

  const listQuery = useQuery({
    queryKey: ['admin-support-chat-sessions'],
    queryFn: getAdminSupportChatSessions,
    refetchInterval: 5000
  });
  const rooms = listQuery.data?.items ?? [];

  useEffect(() => {
    if (!selectedSessionId && rooms[0]?.id) {
      setSelectedSessionId(rooms[0].id);
    }
  }, [rooms, selectedSessionId]);

  const detailQuery = useQuery({
    queryKey: ['admin-support-chat-session', selectedSessionId],
    queryFn: () => getAdminSupportChatSession(selectedSessionId as string),
    enabled: Boolean(selectedSessionId),
    refetchInterval: 5000
  });

  const sendMutation = useMutation({
    mutationFn: () => postAdminSupportChatMessage(selectedSessionId as string, message.trim()),
    onSuccess: (detail) => {
      setMessage('');
      cacheDetail(queryClient, detail);
      void listQuery.refetch();
    }
  });

  const selectedRoom = detailQuery.data?.contact ?? rooms.find((room) => room.id === selectedSessionId) ?? null;
  const canSend = Boolean(selectedSessionId && selectedRoom?.canSendMessage && message.trim() && !sendMutation.isPending);
  const roomRows = rooms.map((room) => ({
    선택: (
      <button
        type="button"
        onClick={() => setSelectedSessionId(room.id)}
        className={`rounded px-3 py-2 text-xs font-bold ${room.id === selectedSessionId ? 'bg-brand-blue text-white' : 'border border-slate-300 text-brand-navy'}`}
        aria-label={`${userLabel(room)} 상담방 선택`}
      >
        선택
      </button>
    ),
    사용자: userLabel(room),
    티켓: <Link to={`/admin/as-tickets/${room.asTicketId}`} className="font-bold text-brand-blue">{shortId(room.asTicketId)}</Link>,
    상태: <StatusBadge status={room.ticketStatus ?? room.status} />,
    안읽음: room.adminUnreadCount ?? 0,
    증상: room.symptom ?? '-',
    최근메시지: room.lastMessagePreview ?? '-',
    최근시각: formatDateTime(room.lastMessageAt ?? undefined)
  }));
  const exportRows = rooms.map((room) => ({
    id: room.id,
    asTicketId: room.asTicketId,
    user: userLabel(room),
    ticketStatus: room.ticketStatus ?? room.status,
    adminUnreadCount: room.adminUnreadCount ?? 0,
    symptom: room.symptom ?? '',
    lastMessageAt: formatDateTime(room.lastMessageAt ?? undefined)
  }));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSend) return;
    sendMutation.mutate();
  }

  return (
    <AdminShell title="상담방 관리" exportRows={exportRows} exportFileName="admin-support-chat-sessions.csv">
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_460px]">
        <Panel title="상담방 목록" subtitle="AS 티켓이 생성된 사용자 상담방을 확인하고 응답할 수 있습니다.">
          {listQuery.isLoading ? <StateMessage type="info" title="상담방 로딩 중" body="사용자 상담방 목록을 불러오고 있습니다." /> : null}
          {listQuery.isError ? <StateMessage type="warn" title="상담방 조회 실패" body="관리자 상담방 목록을 불러오지 못했습니다." /> : null}
          {!listQuery.isLoading && !listQuery.isError && roomRows.length === 0 ? (
            <StateMessage type="info" title="상담방 없음" body="아직 관리할 사용자 상담방이 없습니다." />
          ) : null}
          {!listQuery.isLoading && !listQuery.isError && roomRows.length > 0 ? (
            <DataTable columns={['선택', '사용자', '티켓', '상태', '안읽음', '증상', '최근메시지', '최근시각']} rows={roomRows} />
          ) : null}
        </Panel>

        <Panel title="대화 내용" subtitle={selectedRoom ? `${userLabel(selectedRoom)} · ${shortId(selectedRoom.asTicketId)}` : '상담방을 선택하세요.'}>
          {!selectedSessionId ? (
            <StateMessage type="info" title="선택된 상담방 없음" body="왼쪽 목록에서 상담방을 선택하면 대화 내용이 표시됩니다." />
          ) : null}
          {detailQuery.isLoading ? <StateMessage type="info" title="대화 로딩 중" body="상담 메시지를 불러오고 있습니다." /> : null}
          {detailQuery.isError ? <StateMessage type="warn" title="대화 조회 실패" body="상담방 상세를 불러오지 못했습니다." /> : null}
          {detailQuery.data ? (
            <>
              <div className="mb-4 rounded border border-slate-200 bg-slate-50 p-3 text-sm">
                <div className="mb-2 font-bold text-slate-900">{selectedRoom?.symptom ?? 'AS 상담'}</div>
                <div className="flex flex-wrap gap-2 text-xs text-slate-600">
                  <span>티켓 {selectedRoom ? shortId(selectedRoom.asTicketId) : '-'}</span>
                  <span>상태 {selectedRoom?.ticketStatus ?? '-'}</span>
                  <span>안읽음 {selectedRoom?.adminUnreadCount ?? 0}</span>
                </div>
              </div>
              <div className="h-[440px] overflow-y-auto rounded border border-slate-200 bg-slate-50 p-4">
                <div className="space-y-3">
                  {detailQuery.data.messages.map((item) => (
                    <AdminChatBubble key={item.id} message={item} />
                  ))}
                </div>
              </div>
              <form onSubmit={submit} className="mt-4">
                {selectedRoom?.canSendMessage === false ? (
                  <StateMessage type="info" title="종료된 상담방" body="종료된 AS 티켓 상담방에는 답변을 보낼 수 없습니다." />
                ) : (
                  <div className="flex gap-2">
                    <input
                      className="h-11 min-w-0 flex-1 rounded border border-slate-300 px-3 text-sm"
                      placeholder="관리자 답변을 입력하세요"
                      value={message}
                      onChange={(event) => setMessage(event.target.value)}
                    />
                    <button disabled={!canSend} className="rounded bg-brand-blue px-4 py-2 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-400">
                      {sendMutation.isPending ? '전송 중' : '답변 전송'}
                    </button>
                  </div>
                )}
              </form>
            </>
          ) : null}
        </Panel>
      </div>
    </AdminShell>
  );
}

function AdminChatBubble({ message }: { message: SupportChatMessage }) {
  const isAdmin = message.role === 'ADMIN';
  const isSystem = message.role === 'SYSTEM';
  return (
    <div className={`flex ${isAdmin ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[82%] rounded px-3 py-2 text-sm leading-6 shadow-sm ${
        isAdmin
          ? 'bg-brand-blue text-white'
          : isSystem
            ? 'border border-slate-200 bg-white text-slate-600'
            : 'border border-slate-200 bg-white text-slate-900'
      }`}>
        <div className="mb-1 text-[11px] font-bold opacity-75">{messageLabel(message)}</div>
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
      </div>
    </div>
  );
}

function cacheDetail(queryClient: ReturnType<typeof useQueryClient>, detail: SupportChatSessionDto) {
  if (detail.contact?.id) {
    queryClient.setQueryData(['admin-support-chat-session', detail.contact.id], detail);
  }
}

function messageLabel(message: SupportChatMessage) {
  if (message.role === 'SYSTEM') return '시스템';
  if (message.role === 'ADMIN') return message.senderName ?? '관리자';
  return message.senderName ?? '사용자';
}

function userLabel(room: SupportChatContact) {
  return room.user?.email ?? room.user?.name ?? room.user?.id ?? '-';
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}

function formatDateTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}
