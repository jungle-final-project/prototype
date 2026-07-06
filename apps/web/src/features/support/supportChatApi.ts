import { API_BASE_URL, api, getToken } from '../../lib/api';
import type { SupportChatSessionDto, SupportChatSessionListDto } from './types';

export function getCurrentSupportChat(asTicketId?: string | null) {
  const query = asTicketId ? `?asTicketId=${encodeURIComponent(asTicketId)}` : '';
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/current${query}`);
}

export function getSupportChatSession(sessionId: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}`);
}

export function postSupportChatMessage(sessionId: string, content: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}

export function getAdminSupportChatSession(sessionId: string, markRead = true) {
  const query = markRead ? '' : '?markRead=false';
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}${query}`);
}

export function getAdminSupportChatSessions() {
  return api<SupportChatSessionListDto>('/api/admin/support/chat-sessions');
}

export function postAdminSupportChatMessage(sessionId: string, content: string) {
  return api<SupportChatSessionDto>(`/api/admin/support/chat-sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}

export type SupportChatSocket = {
  close: () => void;
};

type SupportChatSocketError = {
  type?: string;
  code?: string;
  message?: string;
  retryable?: boolean;
};

export function openSupportChatSocket(options: {
  mode: 'user' | 'admin';
  sessionId: string;
  onDetail: (detail: SupportChatSessionDto) => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: () => void;
  onSocketError?: (error: SupportChatSocketError) => void;
}): SupportChatSocket | null {
  const token = getToken();
  if (!token || typeof WebSocket === 'undefined') {
    return null;
  }
  const socket = new WebSocket(supportChatSocketUrl(token, options.mode, options.sessionId));
  socket.addEventListener('open', () => options.onOpen?.());
  socket.addEventListener('close', () => options.onClose?.());
  socket.addEventListener('error', () => options.onError?.());
  socket.addEventListener('message', (event) => {
    try {
      const payload = JSON.parse(String(event.data)) as { type?: string; detail?: SupportChatSessionDto } & SupportChatSocketError;
      if (payload.type === 'CHAT_UPDATED' && payload.detail) {
        options.onDetail(payload.detail);
        return;
      }
      if (payload.type === 'ERROR') {
        options.onSocketError?.(payload);
      }
    } catch {
      // Polling remains the fallback when a socket payload is malformed.
    }
  });
  return {
    close() {
      socket.close();
    }
  };
}

function supportChatSocketUrl(token: string, mode: 'user' | 'admin', sessionId: string) {
  const base = API_BASE_URL || window.location.origin;
  const url = new URL('/ws/support-chat', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('token', token);
  url.searchParams.set('mode', mode);
  url.searchParams.set('sessionId', sessionId);
  return url.toString();
}
