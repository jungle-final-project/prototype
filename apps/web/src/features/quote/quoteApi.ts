import { api } from '../../lib/api';

export function parseRequirements(message: string, budget?: number) {
  return api('/api/requirements/parse', {
    method: 'POST',
    body: JSON.stringify({ message, budget })
  });
}

export function recommendBuild(requirementId: string) {
  return api('/api/builds/recommend', {
    method: 'POST',
    body: JSON.stringify({ requirementId })
  });
}

export function getBuild(buildId: string) {
  return api(`/api/builds/${buildId}`);
}

export function getBuildHistory() {
  return api('/api/builds/history');
}

export function changePart(buildId: string, category: string, partId: string) {
  return api(`/api/builds/${buildId}/change-part`, {
    method: 'POST',
    body: JSON.stringify({ category, partId })
  });
}
