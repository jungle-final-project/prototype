import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { authHeaders, baseUrl, loginForVu, requestTimeout } from '../util/general-helper.js';

const accessTokens = {};

const PART_CATEGORIES = ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
const HOME_PART_LIMIT = Number(__ENV.HOME_PART_LIMIT || '5');
const HOME_START_RATE = Number(__ENV.HOME_START_RATE || '2');
const HOME_PEAK_RATE = Number(__ENV.HOME_PEAK_RATE || '8');
const HOME_PRE_ALLOCATED_VUS = Number(__ENV.HOME_PRE_ALLOCATED_VUS || '50');
const HOME_MAX_VUS = Number(__ENV.HOME_MAX_VUS || '200');

export const options = {
  scenarios: {
    public_home_before_aggregate: {
      executor: 'ramping-arrival-rate',
      exec: 'publicHomeBeforeAggregate',
      startRate: HOME_START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: HOME_PRE_ALLOCATED_VUS,
      maxVUs: HOME_MAX_VUS,
      stages: homeStages(),
      startTime: '0s',
      env: {
        BASE_URL: __ENV.HOME_PUBLIC_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
        K6_FLOW: 'home-before-aggregate',
        K6_VARIANT: __ENV.K6_VARIANT || 'public-before-aggregate'
      },
      tags: {
        flow: 'home-before-aggregate',
        visitor: 'public'
      }
    },
    authenticated_home_before_aggregate: {
      executor: 'ramping-arrival-rate',
      exec: 'authenticatedHomeBeforeAggregate',
      startRate: HOME_START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: HOME_PRE_ALLOCATED_VUS,
      maxVUs: HOME_MAX_VUS,
      stages: homeStages(),
      startTime: __ENV.HOME_AUTH_START_TIME || '45s',
      env: {
        BASE_URL: __ENV.HOME_AUTH_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
        K6_FLOW: 'home-before-aggregate',
        K6_VARIANT: __ENV.K6_VARIANT || 'authenticated-before-aggregate'
      },
      tags: {
        flow: 'home-before-aggregate',
        visitor: 'authenticated'
      }
    }
  }
};

function homeStages() {
  return [
    { target: HOME_START_RATE, duration: __ENV.HOME_RAMP_UP_DURATION || '5s' },
    { target: HOME_PEAK_RATE, duration: __ENV.HOME_STEADY_DURATION || '20s' },
    { target: 1, duration: __ENV.HOME_RAMP_DOWN_DURATION || '5s' }
  ];
}

function tokenForVu() {
  const cacheKey = __ENV.CACHE_MODE || __ENV.K6_VARIANT || 'home-before-aggregate';
  if (!accessTokens[cacheKey]) {
    const accountPoolSize = Number(__ENV.TEST_USER_COUNT || '500');
    const accountIndex = ((exec.vu.idInTest - 1) % accountPoolSize) + 1;
    accessTokens[cacheKey] = loginForVu(accountIndex);
  }
  return accessTokens[cacheKey];
}

export function publicHomeBeforeAggregate() {
  const response = http.get(`${baseUrl()}/api/public/home`, {
    tags: homeTags('public-home', { endpoint: 'public_home', visitor: 'public' }),
    timeout: requestTimeout()
  });

  check(response, {
    'public home 200': (res) => res.status === 200
  });

  sleep(0.5 + Math.random());
}

export function authenticatedHomeBeforeAggregate() {
  const token = tokenForVu();

  const userResponse = getCurrentUser(token);
  check(userResponse, {
    'auth me 200': (res) => res.status === 200
  });

  const technicianResponse = getTechnicianProfile(token);
  check(technicianResponse, {
    'technician profile 200 or 204': (res) => res.status === 200 || res.status === 204
  });

  const recommendedResponse = getHomeParts(token);
  check(recommendedResponse, {
    'home parts 200': (res) => res.status === 200,
    'home parts has items': (res) => Array.isArray(parseJson(res)?.items)
  });

  for (const category of PART_CATEGORIES) {
    const partsResponse = getCategoryParts(token, category);
    check(partsResponse, {
      [`${category} parts 200`]: (res) => res.status === 200,
      [`${category} parts has items`]: (res) => Array.isArray(parseJson(res)?.items)
    });
  }

  recordHomeRecommendedPartImpressions(token, recommendedResponse);

  sleep(0.5 + Math.random());
}

function getCurrentUser(token) {
  return http.get(`${baseUrl()}/api/auth/me`, {
    headers: authHeaders(token),
    tags: homeTags('auth-me', { endpoint: 'auth_me', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });
}

function getTechnicianProfile(token) {
  return http.get(`${baseUrl()}/api/technician/profile`, {
    headers: authHeaders(token),
    tags: homeTags('technician-profile', { endpoint: 'technician_profile', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });
}

function getHomeParts(token) {
  return http.get(`${baseUrl()}/api/recommendations/home-parts?limit=${HOME_PART_LIMIT}`, {
    headers: authHeaders(token),
    tags: homeTags('home-parts', { endpoint: 'home_parts', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });
}

function getCategoryParts(token, category) {
  return http.get(`${baseUrl()}/api/parts?category=${category}&page=0&size=4&sort=price_desc`, {
    headers: authHeaders(token),
    tags: homeTags('category-parts', { endpoint: 'parts', visitor: 'authenticated', category }),
    timeout: requestTimeout()
  });
}

function recordHomeRecommendedPartImpressions(token, response) {
  if (response.status !== 200) return;

  const body = parseJson(response);
  const items = Array.isArray(body?.items) ? body.items.slice(0, HOME_PART_LIMIT) : [];
  for (const item of items) {
    const part = item?.part;
    if (!item?.recommendationId || !part?.id || !part?.category) continue;

    const eventResponse = http.post(`${baseUrl()}/api/recommendation-events`, JSON.stringify({
      eventType: 'IMPRESSION',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: item.recommendationId,
      partId: part.id,
      category: part.category,
      rankPosition: item.rankPosition,
      idempotencyKey: `home-before-aggregate-impression-${item.recommendationId}`
    }), {
      headers: authHeaders(token),
      tags: homeTags('recommended-part-impression', {
        endpoint: 'recommendation_events',
        visitor: 'authenticated',
        eventType: 'IMPRESSION'
      }),
      timeout: requestTimeout()
    });

    check(eventResponse, {
      'home impression recorded': (res) => res.status === 201 || res.status === 200
    });
  }
}

function homeTags(phase, extra = {}) {
  return {
    flow: 'home-before-aggregate',
    variant: __ENV.K6_VARIANT || __ENV.CACHE_MODE || 'before-aggregate',
    phase,
    ...extra
  };
}

function parseJson(response) {
  try {
    return JSON.parse(response.body || '{}');
  } catch (_) {
    return null;
  }
}
