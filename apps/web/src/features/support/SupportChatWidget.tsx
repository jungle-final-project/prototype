import { FormEvent, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import { LifeBuoy, MessageCircle, Send, X } from 'lucide-react';
import { getCachedAuthUser, getToken } from '../../lib/api';
import { getCurrentUser } from '../auth/authApi';
import { getCurrentSupportChat, getSupportChatSession, openSupportChatSocket, postSupportChatMessage, type SupportChatSocket } from './supportChatApi';
import type { SupportChatMessage, SupportChatSessionDto } from './types';

// 소켓 미연결 시 활성 폴링 간격. 소켓 연결 시에도 완전히 끄지 않고 낮은 빈도로 유지해,
// 다중 인스턴스에서 broadcast가 도달하지 않아도 상대 메시지를 놓치지 않게 한다.
const ACTIVE_POLL_MS = 5000;
const SOCKET_FALLBACK_POLL_MS = 15000;

export function SupportChatWidget() {
  const location = useLocation();
  const queryClient = useQueryClient();
  const socketRef = useRef<SupportChatSocket | null>(null);
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [socketConnected, setSocketConnected] = useState(false);
  const hidden = shouldHideSupportChat(location.pathname);
  const hasToken = Boolean(getToken());
  const cachedRole = cachedUserRole();
  const roleQuery = useQuery({
    queryKey: ['support-chat', 'current-user-role'],
    queryFn: getCurrentUser,
    enabled: hasToken && !hidden && cachedRole == null,
    staleTime: 30_000,
    retry: false
  });
  const currentRole = cachedRole ?? roleQuery.data?.role ?? null;
  const canUseUserChat = currentRole === 'USER';
  const routeTicketId = supportTicketIdFromPath(location.pathname);

  const currentQuery = useQuery({
    queryKey: ['support-chat', 'current', routeTicketId ?? 'latest'],
    queryFn: () => getCurrentSupportChat(routeTicketId),
    enabled: hasToken && !hidden && canUseUserChat,
    refetchInterval: open ? false : socketConnected ? SOCKET_FALLBACK_POLL_MS : ACTIVE_POLL_MS
  });

  const sessionId = currentQuery.data?.contact?.id ?? null;
  const detailQuery = useQuery({
    queryKey: ['support-chat', sessionId],
    queryFn: () => getSupportChatSession(sessionId as string),
    enabled: Boolean(open && sessionId),
    refetchInterval: open && sessionId ? (socketConnected ? SOCKET_FALLBACK_POLL_MS : ACTIVE_POLL_MS) : false
  });

  const sendMutation = useMutation({
    mutationFn: () => postSupportChatMessage(sessionId as string, message.trim()),
    onSuccess: (detail) => {
      setMessage('');
      updateChatCache(detail);
    }
  });

  const activeChat = detailQuery.data ?? currentQuery.data;
  const contact = activeChat?.contact ?? null;
  const unreadCount = currentQuery.data?.contact?.userUnreadCount ?? 0;
  const canSend = Boolean(contact?.canSendMessage && message.trim() && !sendMutation.isPending);

  useEffect(() => {
    socketRef.current?.close();
    socketRef.current = null;
    setSocketConnected(false);
    if (!open || !sessionId) {
      return undefined;
    }
    const socket = openSupportChatSocket({
      mode: 'user',
      sessionId,
      onOpen: () => setSocketConnected(true),
      onClose: () => setSocketConnected(false),
      onError: () => setSocketConnected(false),
      onDetail: updateChatCache
    });
    socketRef.current = socket;
    return () => {
      socket?.close();
      socketRef.current = null;
      setSocketConnected(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, sessionId]);

  function updateChatCache(detail: SupportChatSessionDto) {
    queryClient.setQueryData(['support-chat', 'current', routeTicketId ?? 'latest'], detail);
    if (detail.contact?.id) {
      queryClient.setQueryData(['support-chat', detail.contact.id], detail);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!canSend || !sessionId) return;
    const outgoing = message.trim();
    if (socketRef.current?.sendMessage(outgoing)) {
      setMessage('');
      return;
    }
    sendMutation.mutate();
  }

  if (hidden || !hasToken || !canUseUserChat) {
    return null;
  }

  if (!open) {
    return (
      <button
        type="button"
        aria-label="상담방 열기"
        onClick={() => setOpen(true)}
        className="fixed bottom-5 left-5 z-50 grid h-14 w-14 place-items-center rounded-2xl border border-emerald-900 bg-emerald-700 text-white shadow-2xl transition hover:-translate-y-0.5 hover:bg-emerald-800 focus:outline-none focus:ring-4 focus:ring-emerald-100"
      >
        <MessageCircle size={24} />
        {unreadCount > 0 ? (
          <span className="absolute -right-1 -top-1 grid h-6 min-w-6 place-items-center rounded-full border-2 border-white bg-rose-600 px-1 text-[11px] font-black">
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        ) : null}
      </button>
    );
  }

  return (
    <section className="fixed bottom-4 left-4 z-50 flex h-[min(620px,calc(100vh-2rem))] w-[min(390px,calc(100vw-2rem))] flex-col overflow-hidden rounded-lg border border-slate-200 bg-white shadow-2xl">
      <div className="flex items-center justify-between border-b border-slate-200 bg-slate-950 px-4 py-3 text-white">
        <div className="flex min-w-0 items-center gap-3">
          <div className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-emerald-500 text-slate-950">
            <LifeBuoy size={20} />
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-sm font-black">PC Agent 상담방</h2>
            <p className="truncate text-xs text-slate-300">{contact?.title ?? 'AS 티켓 기반 상담'}</p>
          </div>
        </div>
        <button
          type="button"
          aria-label="상담방 닫기"
          onClick={() => setOpen(false)}
          className="grid h-9 w-9 place-items-center rounded-md border border-white/15 text-white transition hover:bg-white/10 focus:outline-none focus:ring-4 focus:ring-white/20"
        >
          <X size={17} />
        </button>
      </div>

      {contact ? (
        <>
          <div className="border-b border-slate-200 bg-slate-50 px-4 py-3 text-xs text-slate-600">
            <div className="truncate font-bold text-slate-900">{contact.symptom ?? 'AS 상담'}</div>
            <div className="mt-1 flex items-center justify-between gap-2">
              <span>티켓 {shortId(contact.asTicketId)}</span>
              <span>{socketConnected ? '실시간 연결' : '자동 새로고침'}</span>
            </div>
          </div>
          <div className="flex-1 overflow-y-auto bg-slate-50 p-4">
            <div className="space-y-3">
              {(activeChat?.messages ?? []).map((item) => (
                <SupportChatBubble key={item.id} message={item} />
              ))}
            </div>
          </div>
          <form onSubmit={submit} className="border-t border-slate-200 bg-white p-3">
            {contact.canSendMessage ? (
              <div className="flex gap-2">
                <input
                  className="h-11 min-w-0 flex-1 rounded-md border border-slate-300 px-3 text-sm focus:border-emerald-600 focus:outline-none focus:ring-4 focus:ring-emerald-100"
                  placeholder="메시지를 입력하세요"
                  value={message}
                  onChange={(event) => setMessage(event.target.value)}
                />
                <button
                  disabled={!canSend}
                  className="grid h-11 w-11 place-items-center rounded-md bg-emerald-700 text-white transition hover:bg-emerald-800 disabled:cursor-not-allowed disabled:bg-slate-400"
                  aria-label="전송"
                >
                  <Send size={18} />
                </button>
              </div>
            ) : (
              <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
                종료된 AS 티켓 상담방입니다.
              </div>
            )}
          </form>
        </>
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center gap-4 bg-slate-50 p-6 text-center">
          <div className="grid h-12 w-12 place-items-center rounded-lg bg-white text-emerald-700 shadow-sm">
            <LifeBuoy size={24} />
          </div>
          <div>
            <h3 className="text-base font-black text-slate-950">AS 티켓이 필요합니다.</h3>
            <p className="mt-2 text-sm leading-6 text-slate-600">
              상담방은 AS 접수 후 자동으로 생성됩니다. PC Agent 로그와 증상을 접수하면 담당자와 대화할 수 있습니다.
            </p>
          </div>
          <Link to={activeChat?.supportNewPath ?? '/support/new'} className="rounded-md bg-emerald-700 px-4 py-3 text-sm font-bold text-white hover:bg-emerald-800">
            AS 접수로 이동
          </Link>
        </div>
      )}
    </section>
  );
}

function SupportChatBubble({ message }: { message: SupportChatMessage }) {
  const isUser = message.role === 'USER';
  const isSystem = message.role === 'SYSTEM';
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[78%] rounded-md px-3 py-2 text-sm leading-6 shadow-sm ${
        isUser
          ? 'bg-emerald-700 text-white'
          : isSystem
            ? 'border border-slate-200 bg-white text-slate-600'
            : 'border border-slate-200 bg-white text-slate-900'
      }`}>
        <div className="mb-1 text-[11px] font-bold opacity-75">
          {isSystem ? '시스템' : isUser ? '나' : message.senderName ?? '상담원'}
        </div>
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
      </div>
    </div>
  );
}

function shouldHideSupportChat(pathname: string) {
  return pathname === '/login'
    || pathname === '/signup'
    || pathname.startsWith('/admin')
    || pathname === '/support/new';
}

function cachedUserRole() {
  const user = getCachedAuthUser();
  if (!user || typeof user !== 'object') {
    return null;
  }
  const role = (user as { role?: unknown }).role;
  return role === 'USER' || role === 'ADMIN' ? role : null;
}

function supportTicketIdFromPath(pathname: string) {
  const match = pathname.match(/^\/support\/([^/]+)$/);
  if (!match || match[1] === 'new' || match[1] === 'ai-chat') {
    return null;
  }
  return match[1];
}

function shortId(id: string) {
  return id.length <= 12 ? id : `${id.slice(0, 8)}...${id.slice(-4)}`;
}
