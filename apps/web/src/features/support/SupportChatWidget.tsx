import { FormEvent, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MessageCircle, Minus, Send, X } from 'lucide-react';
import { getCurrentSupportChat, getSupportChatMessages, postSupportChatMessage } from './supportApi';
import { openSupportChatSocket, SupportChatSocket } from './supportChatSocket';
import type { SupportChatMessage, SupportChatSessionDto } from './types';

function timeLabel(value?: string | null) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

export function SupportChatWidget() {
  const queryClient = useQueryClient();
  const messagesEndRef = useRef<HTMLDivElement | null>(null);
  const socketRef = useRef<SupportChatSocket | null>(null);
  const [open, setOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [socketConnected, setSocketConnected] = useState(false);

  const currentQuery = useQuery({
    queryKey: ['support-chat', 'current'],
    queryFn: getCurrentSupportChat,
    refetchInterval: socketConnected ? false : 5000,
    retry: false
  });
  const sessionId = currentQuery.data?.contact?.id ?? null;
  const messagesQuery = useQuery({
    queryKey: ['support-chat', sessionId, 'messages'],
    queryFn: () => getSupportChatMessages(sessionId as string),
    enabled: Boolean(sessionId) && open,
    refetchInterval: open && !socketConnected ? 5000 : false,
    retry: false
  });
  const session = messagesQuery.data ?? currentQuery.data;
  const contact = session?.contact ?? null;
  const messages = session?.messages ?? [];

  const sendMutation = useMutation({
    mutationFn: () => postSupportChatMessage(sessionId as string, message),
    onSuccess: () => {
      setMessage('');
      queryClient.invalidateQueries({ queryKey: ['support-chat', 'current'] });
      queryClient.invalidateQueries({ queryKey: ['support-chat', sessionId, 'messages'] });
    }
  });

  useEffect(() => {
    if (!sessionId) {
      socketRef.current?.close();
      socketRef.current = null;
      setSocketConnected(false);
      return;
    }
    socketRef.current?.close();
    socketRef.current = openSupportChatSocket<SupportChatSessionDto>({
      mode: 'user',
      sessionId,
      onOpen: () => setSocketConnected(true),
      onClose: () => setSocketConnected(false),
      onError: () => setSocketConnected(false),
      onDetail: (detail) => {
        queryClient.setQueryData(['support-chat', 'current'], detail);
        queryClient.setQueryData(['support-chat', sessionId, 'messages'], detail);
      }
    });
    return () => {
      socketRef.current?.close();
      socketRef.current = null;
      setSocketConnected(false);
    };
  }, [queryClient, sessionId]);

  useEffect(() => {
    if (!open) return;
    messagesEndRef.current?.scrollIntoView({ block: 'end' });
  }, [open, messages.length]);

  if (!contact || contact.status === 'ARCHIVED') {
    return null;
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    if (!sessionId || !message.trim() || sendMutation.isPending) return;
    if (socketRef.current?.sendMessage(message.trim())) {
      setMessage('');
      return;
    }
    sendMutation.mutate();
  }

  return (
    <div className="fixed bottom-6 left-7 z-50">
      {open ? (
        <section className="mb-5 w-[min(calc(100vw-3.5rem),440px)] overflow-hidden rounded-xl border border-slate-200 bg-white shadow-2xl">
          <div className="flex h-14 items-center justify-between bg-gradient-to-r from-teal-700 to-teal-600 px-5 text-white">
            <h2 className="text-base font-black">PC Agent 상담</h2>
            <div className="flex gap-1">
              <button type="button" aria-label="상담창 접기" onClick={() => setOpen(false)} className="grid h-8 w-8 place-items-center rounded-md hover:bg-white/10">
                <Minus size={18} />
              </button>
              <button type="button" aria-label="상담창 닫기" onClick={() => setOpen(false)} className="grid h-8 w-8 place-items-center rounded-md hover:bg-white/10">
                <X size={19} />
              </button>
            </div>
          </div>
          <div className="max-h-[58vh] min-h-[320px] space-y-4 overflow-y-auto bg-white p-4">
            {messages.map((item) => (
              <SupportChatBubble key={item.id} message={item} />
            ))}
            {messages.length === 0 ? (
              <div className="rounded-lg bg-slate-50 p-4 text-sm font-semibold text-slate-500">상담 메시지를 불러오는 중입니다.</div>
            ) : null}
            <div ref={messagesEndRef} />
          </div>
          <form onSubmit={submit} className="flex gap-2 border-t border-slate-200 bg-white p-4">
            <input
              value={message}
              onChange={(event) => setMessage(event.target.value)}
              placeholder="메시지를 입력하세요"
              className="h-11 min-w-0 flex-1 rounded-md border border-slate-200 px-3 text-sm outline-none focus:border-teal-600"
            />
            <button
              type="submit"
              disabled={!message.trim() || sendMutation.isPending}
              className="flex h-11 items-center gap-2 rounded-md bg-teal-700 px-4 text-sm font-bold text-white disabled:bg-slate-300"
            >
              <Send size={16} />
              전송
            </button>
          </form>
        </section>
      ) : null}

      <div className="flex items-end gap-4">
        <button
          type="button"
          aria-label="PC Agent 상담 열기"
          onClick={() => setOpen((value) => !value)}
          className="grid h-16 w-16 place-items-center rounded-full bg-gradient-to-br from-teal-700 to-teal-600 text-white shadow-2xl transition hover:-translate-y-0.5"
        >
          <MessageCircle size={30} />
        </button>
        {!open ? (
          <div className="relative mb-2 rounded-xl border border-slate-200 bg-white px-5 py-3 text-sm font-bold leading-6 text-slate-700 shadow-xl">
            <span className="absolute -left-2 bottom-4 h-4 w-4 rotate-45 border-b border-l border-slate-200 bg-white" />
            상담원과<br />약속시간을 협의하세요
          </div>
        ) : null}
      </div>
    </div>
  );
}

function SupportChatBubble({ message }: { message: SupportChatMessage }) {
  const isUser = message.role === 'USER';
  const isSystem = message.role === 'SYSTEM';
  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`max-w-[78%] ${isUser ? 'text-right' : 'text-left'}`}>
        <div className="mb-1 text-xs font-bold text-slate-500">
          {isSystem ? 'PC Agent · 시스템' : isUser ? timeLabel(message.createdAt) : `상담원 이지원 · ${timeLabel(message.createdAt)}`}
        </div>
        <div className={`rounded-lg px-4 py-3 text-sm leading-6 ${isUser ? 'bg-teal-50 text-slate-800' : isSystem ? 'bg-slate-100 text-slate-600' : 'bg-slate-100 text-slate-800'}`}>
          {message.content}
        </div>
      </div>
    </div>
  );
}
