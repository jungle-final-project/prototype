import { API_BASE_URL, getToken } from '../../lib/api';

type SupportChatSocketOptions<TDetail> = {
  mode: 'user' | 'admin';
  sessionId?: string | null;
  onDetail: (detail: TDetail) => void;
  onContactsInvalidated?: () => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: (message?: string) => void;
};

export type SupportChatSocket = {
  socket: WebSocket;
  sendMessage: (content: string) => boolean;
  close: () => void;
};

export function openSupportChatSocket<TDetail>(options: SupportChatSocketOptions<TDetail>): SupportChatSocket | null {
  const token = getToken();
  if (!token || typeof WebSocket === 'undefined') {
    return null;
  }

  const url = supportChatSocketUrl(token, options.mode, options.sessionId);
  const socket = new WebSocket(url);

  socket.addEventListener('open', () => options.onOpen?.());
  socket.addEventListener('close', () => options.onClose?.());
  socket.addEventListener('error', () => options.onError?.());
  socket.addEventListener('message', (event) => {
    try {
      const envelope = JSON.parse(String(event.data)) as { type?: string; payload?: unknown };
      if (envelope.type === 'CHAT_DETAIL') {
        options.onDetail(envelope.payload as TDetail);
      }
      if (envelope.type === 'CONTACTS_INVALIDATED') {
        options.onContactsInvalidated?.();
      }
      if (envelope.type === 'ERROR') {
        const payload = envelope.payload as { message?: string } | undefined;
        options.onError?.(payload?.message);
      }
    } catch {
      options.onError?.();
    }
  });

  return {
    socket,
    sendMessage(content: string) {
      if (socket.readyState !== WebSocket.OPEN) {
        return false;
      }
      socket.send(JSON.stringify({ type: 'MESSAGE', content }));
      return true;
    },
    close() {
      socket.close();
    }
  };
}

function supportChatSocketUrl(token: string, mode: 'user' | 'admin', sessionId?: string | null) {
  const base = API_BASE_URL || window.location.origin;
  const url = new URL('/ws/support-chat', base);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  url.searchParams.set('token', token);
  url.searchParams.set('mode', mode);
  if (sessionId) {
    url.searchParams.set('sessionId', sessionId);
  }
  return url.toString();
}
