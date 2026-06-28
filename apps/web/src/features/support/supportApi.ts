import { api } from '../../lib/api';

export function uploadAgentLog(rangeMinutes: number, consentAccepted: boolean, file?: Blob) {
  const body = new FormData();
  body.append('file', file ?? new Blob(['{"metric":"seed"}\n'], { type: 'application/x-ndjson' }), 'agent-log.jsonl');
  body.append('rangeMinutes', String(rangeMinutes));
  body.append('consentAccepted', String(consentAccepted));

  return api('/api/agent-logs/upload', {
    method: 'POST',
    body
  });
}

export function createSupportTicket(symptom: string, logUploadId: string) {
  return api('/api/as-tickets', {
    method: 'POST',
    body: JSON.stringify({ symptom, logUploadId })
  });
}

export function getSupportTicket(ticketId: string) {
  return api(`/api/as-tickets/${ticketId}`);
}
