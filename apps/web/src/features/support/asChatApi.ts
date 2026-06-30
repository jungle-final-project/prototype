import { api } from '../../lib/api';

export const AS_CHAT_DEFAULT_TICKET_ID = '00000000-0000-4000-8000-000000006001';

export type AsChatTicket = {
  id: string;
  status: string;
  symptom: string;
  logSummary?: string;
  causeCandidates?: unknown[];
  upgradeCandidates?: unknown[];
  createdAt?: string;
};

export type AsChatMessage = {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
  structuredPayload?: Record<string, unknown>;
  agentSessionId?: string | null;
  createdAt?: string;
};

export type AsChatCauseCandidate = {
  label?: string;
  confidence?: string;
  reason?: string;
};

export type AsChatNextAction = {
  label?: string;
  priority?: string;
  instruction?: string;
};

export type AsChatEvidence = {
  id: string;
  sourceId: string;
  summary: string;
  chunkText?: string;
  score?: number | string;
  metadata?: Record<string, unknown>;
};

export type AsChatToolResult = {
  id: string;
  toolName: string;
  status: string;
  confidence: string;
  summary: string;
  resultPayload?: Record<string, unknown>;
};

export type AsChatResponse = {
  sessionId?: string | null;
  asTicketId: string;
  ticket: AsChatTicket;
  model: string;
  agentSessionId?: string;
  messages: AsChatMessage[];
  assistantMessage?: string;
  causeCandidates?: AsChatCauseCandidate[];
  nextActions?: AsChatNextAction[];
  escalation?: { required?: boolean; reason?: string };
  ticketDraft?: { symptomSummary?: string; recommendedLogRequest?: string };
  evidence: AsChatEvidence[];
  toolResults: AsChatToolResult[];
};

export function getAsChat(asTicketId: string) {
  return api<AsChatResponse>(`/api/ai/as-chat?asTicketId=${encodeURIComponent(asTicketId)}`);
}

export function sendAsChat(asTicketId: string, message: string) {
  return api<AsChatResponse>('/api/ai/as-chat', {
    method: 'POST',
    body: JSON.stringify({ asTicketId, message })
  });
}
