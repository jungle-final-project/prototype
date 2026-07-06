import { api } from '../../lib/api';
import type { AgentActivationTokenDto, AgentLogUploadDto, AsRagAnalysisDto, AsTicketDraftDto, AsTicketDto, SupportChatSessionDto } from './types';

export type UploadAgentLogMetadata = {
  rangeStartedAt?: string;
  rangeEndedAt?: string;
  incidentId?: string;
  triggerType?: string;
  symptomType?: string;
  detectedAt?: string;
  selectedByUser?: boolean;
  consentId?: string;
};

export type RemoteSupportRequestCreateRequest = {
  reason: string;
  contactPhone?: string;
};

export type SupportFeedbackRequest = {
  rating: number;
  comment?: string;
};

export function uploadAgentLog(rangeMinutes: number, consentAccepted: boolean, file: File, metadata: UploadAgentLogMetadata = {}) {
  const body = new FormData();
  body.append('file', file);
  body.append('rangeMinutes', String(rangeMinutes));
  body.append('consentAccepted', String(consentAccepted));
  Object.entries(metadata).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      body.append(key, String(value));
    }
  });

  return api<AgentLogUploadDto>('/api/agent-logs/upload', {
    method: 'POST',
    body
  });
}

export function issueAgentActivationToken() {
  return api<AgentActivationTokenDto>('/api/users/me/agent-activation-token', {
    method: 'POST',
    body: JSON.stringify({})
  });
}

export function previewAgentLogRag(rangeMinutes: number, file: File) {
  const body = new FormData();
  body.append('file', file);
  body.append('rangeMinutes', String(rangeMinutes));

  return api<AsRagAnalysisDto>('/api/agent-logs/as-rag-preview', {
    method: 'POST',
    body
  });
}

export function createSupportTicket(symptom: string, logUploadId: string) {
  return api<AsTicketDto>('/api/as-tickets', {
    method: 'POST',
    body: JSON.stringify({ symptom, logUploadId })
  });
}

export function getSupportDraft(draftId: string) {
  return api<AsTicketDraftDto>(`/api/as-ticket-drafts/${draftId}`);
}

export function getSupportTicket(ticketId: string) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}`);
}

export function requestRemoteSupport(ticketId: string, request: RemoteSupportRequestCreateRequest) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}/remote-support-requests`, {
    method: 'POST',
    body: JSON.stringify(request)
  });
}

export function submitSupportFeedback(ticketId: string, request: SupportFeedbackRequest) {
  return api<AsTicketDto>(`/api/as-tickets/${ticketId}/feedback`, {
    method: 'POST',
    body: JSON.stringify(request)
  });
}

export function getCurrentSupportChat() {
  return api<SupportChatSessionDto>('/api/support/chat-sessions/current');
}

export function createSupportChat(supportRequestType: 'REMOTE' | 'VISIT' | 'DIAGNOSIS_ONLY', message?: string) {
  return api<SupportChatSessionDto>('/api/support/chat-sessions', {
    method: 'POST',
    body: JSON.stringify({ supportRequestType, message })
  });
}

export function getSupportChatMessages(sessionId: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}/messages`);
}

export function postSupportChatMessage(sessionId: string, content: string) {
  return api<SupportChatSessionDto>(`/api/support/chat-sessions/${sessionId}/messages`, {
    method: 'POST',
    body: JSON.stringify({ content })
  });
}
