import { api } from '../../lib/api';
import type { AiBuildChatRequest, AiBuildChatResponse } from './aiSelection';
import type { BuildSummary, ChangePartResponse, ParseRequirementPayload, ParsedRequirement, RecommendBuildResponse } from './types';

export function parseRequirements(payload: ParseRequirementPayload) {
  return api<ParsedRequirement>('/api/requirements/parse', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function recommendBuild(requirementId: string, answers: Record<string, string> = {}) {
  return api<RecommendBuildResponse>('/api/builds/recommend', {
    method: 'POST',
    body: JSON.stringify({ requirementId, answers })
  });
}

export function getBuild(buildId: string) {
  return api<BuildSummary>(`/api/builds/${buildId}`);
}

export function getBuildHistory() {
  return api<{ items: BuildSummary[] }>('/api/builds/history');
}

export function changePart(buildId: string, category: string, partId: string) {
  return api<ChangePartResponse>(`/api/builds/${buildId}/change-part`, {
    method: 'POST',
    body: JSON.stringify({ category, partId })
  });
}

export function buildChat(payload: AiBuildChatRequest) {
  return api<AiBuildChatResponse>('/api/ai/build-chat', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}
