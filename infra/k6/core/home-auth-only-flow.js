import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { authHeaders, baseUrl, credentialsForVu, requestTimeout } from '../util/general-helper.js';

const PART_CATEGORIES = ['CPU', 'GPU', 'MOTHERBOARD', 'RAM', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
const HOME_PART_LIMIT = Number(__ENV.HOME_PART_LIMIT || '5');
const AUTH_HOME_START_RATE = Number(__ENV.AUTH_HOME_START_RATE || __ENV.HOME_START_RATE || '2');
const AUTH_HOME_PEAK_RATE = Number(__ENV.AUTH_HOME_PEAK_RATE || __ENV.HOME_PEAK_RATE || '8');
const AUTH_HOME_PRE_ALLOCATED_VUS = Number(
  __ENV.AUTH_HOME_PRE_ALLOCATED_VUS || __ENV.HOME_PRE_ALLOCATED_VUS || '50'
);
const AUTH_HOME_MAX_VUS = Number(__ENV.AUTH_HOME_MAX_VUS || __ENV.HOME_MAX_VUS || '200');
const AUTH_HOME_USER_COUNT = Number(__ENV.TEST_USER_COUNT || '500');
const AUTH_HOME_P95_THRESHOLD_MS = Number(__ENV.AUTH_HOME_P95_THRESHOLD_MS || '1000');
const AUTH_HOME_RECORD_EVENTS = (__ENV.AUTH_HOME_RECORD_EVENTS || 'true') !== 'false';
const AUTH_HOME_SETUP_COOLDOWN_SECONDS = Number(__ENV.AUTH_HOME_SETUP_COOLDOWN_SECONDS || '0');

export const options = {
  setupTimeout: __ENV.AUTH_HOME_SETUP_TIMEOUT || '2m',
  scenarios: {
    authenticated_home_only: {
      executor: 'ramping-arrival-rate',
      exec: 'authenticatedHomeOnly',
      startRate: AUTH_HOME_START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: AUTH_HOME_PRE_ALLOCATED_VUS,
      maxVUs: AUTH_HOME_MAX_VUS,
      stages: authHomeStages(),
      startTime: '0s',
      env: {
        BASE_URL: __ENV.AUTH_HOME_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
        K6_FLOW: 'home',
        K6_VARIANT: __ENV.K6_VARIANT || 'auth-home-only'
      },
      tags: {
        flow: 'home',
        visitor: 'authenticated'
      }
    }
  },
  thresholds: {
    'http_req_failed{endpoint:home}': ['rate<0.01'],
    'http_req_duration{endpoint:home}': [`p(95)<${AUTH_HOME_P95_THRESHOLD_MS}`]
  }
};

export function setup() {
  const tokens = [];
  for (let accountIndex = 1; accountIndex <= AUTH_HOME_USER_COUNT; accountIndex += 1) {
    const response = http.post(`${baseUrl()}/api/auth/login`, JSON.stringify(credentialsForVu(accountIndex)), {
      headers: { 'Content-Type': 'application/json' },
      tags: authHomeTags('setup-login', { endpoint: 'setup_auth_login', visitor: 'authenticated' }),
      timeout: requestTimeout()
    });
    if (response.status !== 200) {
      throw new Error(`login failed during setup: accountIndex=${accountIndex}, status=${response.status}`);
    }
    const payload = response.json();
    if (!payload?.accessToken) {
      throw new Error(`login response has no accessToken during setup: accountIndex=${accountIndex}`);
    }
    tokens.push(payload.accessToken);
  }
  if (AUTH_HOME_SETUP_COOLDOWN_SECONDS > 0) {
    sleep(AUTH_HOME_SETUP_COOLDOWN_SECONDS);
  }
  return { tokens };
}

function authHomeStages() {
  return [
    { target: AUTH_HOME_PEAK_RATE, duration: __ENV.AUTH_HOME_RAMP_UP_DURATION || __ENV.HOME_RAMP_UP_DURATION || '30s' },
    { target: AUTH_HOME_PEAK_RATE, duration: __ENV.AUTH_HOME_STEADY_DURATION || __ENV.HOME_STEADY_DURATION || '7m' },
    { target: 1, duration: __ENV.AUTH_HOME_RAMP_DOWN_DURATION || __ENV.HOME_RAMP_DOWN_DURATION || '30s' }
  ];
}

export function authenticatedHomeOnly(data) {
  const token = tokenForIteration(data.tokens);

  const homeResponse = http.get(`${baseUrl()}/api/home`, {
    headers: authHeaders(token),
    tags: authHomeTags('authenticated-home', { endpoint: 'home', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });

  check(homeResponse, {
    'authenticated home 200': (res) => res.status === 200,
    'authenticated home has category parts': (res) => hasObjectField(res, 'categoryParts'),
    'authenticated home has recommended parts': (res) => hasObjectField(res, 'recommendedParts'),
    'authenticated home recommended parts has items': (res) => {
      const body = parseJson(res);
      return Array.isArray(body?.recommendedParts?.items);
    }
  });
  for (const category of PART_CATEGORIES) {
    check(homeResponse, {
      [`authenticated home ${category} category has items`]: (res) => {
        const body = parseJson(res);
        return Array.isArray(body?.categoryParts?.[category]);
      }
    });
  }

  if (AUTH_HOME_RECORD_EVENTS) {
    recordHomeRecommendedPartImpressions(token, homeResponse);
  }

  sleep(0.5 + Math.random());
}

function tokenForIteration(tokens) {
  if (!Array.isArray(tokens) || tokens.length === 0) {
    throw new Error('setup did not provide login tokens');
  }
  const tokenIndex = exec.scenario.iterationInTest % tokens.length;
  return tokens[tokenIndex];
}

function recordHomeRecommendedPartImpressions(token, response) {
  if (response.status !== 200) return;
  const body = parseJson(response);
  const sourceItems = body?.recommendedParts?.items ?? body?.items;
  const items = Array.isArray(sourceItems) ? sourceItems.slice(0, HOME_PART_LIMIT) : [];
  const events = [];
  for (const item of items) {
    const part = item?.part;
    if (!item?.recommendationId || !part?.id || !part?.category) continue;
    events.push({
      eventType: 'IMPRESSION',
      sourceSurface: 'HOME_RECOMMENDED_PARTS',
      recommendationId: item.recommendationId,
      partId: part.id,
      category: part.category,
      rankPosition: item.rankPosition,
      idempotencyKey: `home-impression-${item.recommendationId}`
    });
  }
  if (!events.length) return;
  const eventResponse = http.post(`${baseUrl()}/api/recommendation-events/bulk`, JSON.stringify({ events }), {
    headers: authHeaders(token),
    tags: authHomeTags('recommended-part-impression', {
      endpoint: 'recommendation_events_bulk',
      visitor: 'authenticated',
      eventType: 'IMPRESSION'
    }),
    timeout: requestTimeout()
  });
  check(eventResponse, {
    'home impressions recorded': (res) => res.status === 201 || res.status === 200
  });
}

function authHomeTags(phase, extra = {}) {
  return {
    flow: 'home',
    variant: __ENV.K6_VARIANT || 'auth-home-only',
    phase,
    ...extra
  };
}

function hasObjectField(response, field) {
  const body = parseJson(response);
  return Boolean(body && typeof body[field] === 'object' && !Array.isArray(body[field]));
}

function parseJson(response) {
  try {
    return JSON.parse(response.body || '{}');
  } catch (_) {
    return null;
  }
}
