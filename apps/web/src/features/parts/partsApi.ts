import { api } from '../../lib/api';
import type { AiBuildItem } from '../quote/aiSelection';
import type {
  CompatiblePartCandidateRequest,
  CompatiblePartCandidateResponse,
  HomeRecommendedPartsResponse,
  PartPage,
  PartPriceHistory,
  PartPriceHistoryParams,
  PublicHomeResponse,
  PartSearchParams,
  PartRow,
  QuoteDraft,
  QuoteDraftHistoryComparison,
  QuoteDraftHistoryList,
  RecommendationEventBulkAcceptedResponse,
  RecommendationEventBulkRequest,
  RecommendationEventRequest
} from './types';

export function listParts(params: PartSearchParams = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return api<PartPage>(`/api/parts${query ? `?${query}` : ''}`);
}

export function listHomeRecommendedParts(limit = 4) {
  return api<HomeRecommendedPartsResponse>(`/api/recommendations/home-parts?limit=${limit}`);
}
export function getPublicHome() {
  return api<PublicHomeResponse>('/api/public/home');
}

export function getHome() {
  return api<PublicHomeResponse>('/api/home');
}

export function recordRecommendationEvent(payload: RecommendationEventRequest) {
  return api('/api/recommendation-events', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function recordRecommendationEventsBulk(payload: RecommendationEventBulkRequest) {
  return api('/api/recommendation-events/bulk', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function queueRecommendationEventsBulk(payload: RecommendationEventBulkRequest) {
  return api<RecommendationEventBulkAcceptedResponse>('/api/recommendation-events/bulk/async', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getPart(partId: string) {
  return api<PartRow>(`/api/parts/${partId}`);
}

export function listCompatiblePartCandidates(payload: CompatiblePartCandidateRequest) {
  return api<CompatiblePartCandidateResponse>('/api/parts/compatible-candidates', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function getPartPriceHistory(partId: string, params: PartPriceHistoryParams = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  });
  const query = search.toString();
  return api<PartPriceHistory>(`/api/parts/${partId}/price-history${query ? `?${query}` : ''}`);
}

export function getCurrentQuoteDraft() {
  return api<QuoteDraft>('/api/quote-drafts/current');
}

function draftChangeHeaders(changeGroup?: string) {
  return changeGroup ? { 'X-Quote-Draft-Change-Group': changeGroup } : undefined;
}

export function putQuoteDraftItem(partId: string, quantity: number, changeGroup?: string) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'PUT',
    headers: draftChangeHeaders(changeGroup),
    body: JSON.stringify({ quantity })
  });
}

export function patchQuoteDraftItem(partId: string, quantity: number, changeGroup?: string) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'PATCH',
    headers: draftChangeHeaders(changeGroup),
    body: JSON.stringify({ quantity })
  });
}

export function deleteQuoteDraftItem(partId: string, changeGroup?: string) {
  return api<QuoteDraft>(`/api/quote-drafts/current/items/${partId}`, {
    method: 'DELETE',
    headers: draftChangeHeaders(changeGroup)
  });
}

export function applyAiBuildToQuoteDraft(payload: {
  buildId?: string;
  items: Array<Pick<AiBuildItem, 'partId' | 'category' | 'quantity'>>;
  conflictPolicy: 'REPLACE';
}, changeGroup?: string) {
  return api<QuoteDraft>('/api/quote-drafts/current/apply-ai-build', {
    method: 'PUT',
    headers: draftChangeHeaders(changeGroup),
    body: JSON.stringify(payload)
  });
}

export function listQuoteDraftHistory() {
  return api<QuoteDraftHistoryList>('/api/quote-drafts/current/history');
}

export function getQuoteDraftHistoryComparison(
  historyId: string,
  game = 'pubg',
  resolution: 'fhd' | 'qhd' | '4k' = 'qhd'
) {
  const search = new URLSearchParams({ game, resolution });
  return api<QuoteDraftHistoryComparison>(
    `/api/quote-drafts/current/history/${historyId}/comparison?${search.toString()}`
  );
}

export function restoreQuoteDraftHistory(
  historyId: string,
  confirmCompatibilityRisk: boolean,
  changeGroup?: string
) {
  return api<QuoteDraft>(`/api/quote-drafts/current/history/${historyId}/restore`, {
    method: 'POST',
    headers: draftChangeHeaders(changeGroup),
    body: JSON.stringify({ confirmCompatibilityRisk })
  });
}

export function runToolCheck(tool: 'compatibility' | 'power' | 'size' | 'performance' | 'price', payload: unknown) {
  return api(`/api/tools/${tool}/check`, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function createPriceAlert(partId: string, targetPrice: number) {
  return api('/api/price-alerts', {
    method: 'POST',
    body: JSON.stringify({ partId, targetPrice })
  });
}

export type BuildGraphAnchorPoint = { x: number; y: number };

export type BuildGraphAnchor = {
  card: BuildGraphAnchorPoint;
  part: BuildGraphAnchorPoint;
};

export type BuildGraphLayoutDefault = {
  layoutKey: string;
  source?: string;
  positions: Record<string, BuildGraphAnchorPoint>;
  anchors?: Record<string, BuildGraphAnchor>;
  updatedAt?: string | null;
};

export function getBuildGraphLayoutDefault() {
  return api<BuildGraphLayoutDefault>('/api/build-graph-layouts/default');
}
