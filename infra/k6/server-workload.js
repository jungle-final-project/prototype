import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';

const TEST_TYPE = (__ENV.TEST_TYPE || 'load').toLowerCase();
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:18082';
const USER_EMAIL = __ENV.USER_EMAIL || 'user@example.com';
const USER_PASSWORD = __ENV.USER_PASSWORD || 'passw0rd!';
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || '0.2');
const SUMMARY_PATH = __ENV.SUMMARY_PATH || `/work/infra/k6/reports/server-${TEST_TYPE}-summary.json`;
const SOAK_WINDOW_MINUTES = Number(__ENV.SOAK_WINDOW_MINUTES || '5');
const SOAK_WINDOW_COUNT = Number(__ENV.SOAK_WINDOW_COUNT || '12');

const contractErrors = new Rate('contract_errors');
const soakWindowDurations = [];
const soakWindowFailures = [];
for (let index = 0; index < SOAK_WINDOW_COUNT; index += 1) {
  const suffix = String(index + 1).padStart(2, '0');
  soakWindowDurations.push(new Trend(`soak_window_${suffix}_duration`, true));
  soakWindowFailures.push(new Rate(`soak_window_${suffix}_failures`));
}

const profiles = {
  load: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '15s', target: 20 },
      { duration: '60s', target: 20 },
      { duration: '15s', target: 0 },
    ],
    gracefulRampDown: '10s',
  },
  stress: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '15s', target: 20 },
      { duration: '30s', target: 40 },
      { duration: '30s', target: 60 },
      { duration: '30s', target: 80 },
      { duration: '15s', target: 0 },
    ],
    gracefulRampDown: '10s',
  },
  spike: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: 10 },
      { duration: '5s', target: 100 },
      { duration: '30s', target: 100 },
      { duration: '15s', target: 0 },
    ],
    gracefulRampDown: '10s',
  },
  soak: {
    executor: 'constant-vus',
    vus: Number(__ENV.SOAK_VUS || '20'),
    duration: __ENV.SOAK_DURATION || '5m',
    gracefulStop: '10s',
  },
  capacity: {
    executor: 'ramping-arrival-rate',
    startRate: 50,
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 400,
    stages: [
      { duration: '20s', target: 50 },
      { duration: '30s', target: 100 },
      { duration: '30s', target: 200 },
      { duration: '30s', target: 300 },
      { duration: '30s', target: 400 },
      { duration: '20s', target: 50 },
    ],
    gracefulStop: '10s',
  },
};

if (!profiles[TEST_TYPE]) {
  throw new Error(`Unsupported TEST_TYPE=${TEST_TYPE}`);
}

export const options = {
  scenarios: { [TEST_TYPE]: profiles[TEST_TYPE] },
  thresholds: {
    checks: ['rate>0.99'],
    contract_errors: ['rate<0.01'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500'],
    'http_req_duration{endpoint:health}': ['p(95)<300'],
    'http_req_duration{endpoint:parts}': ['p(95)<800'],
    'http_req_duration{endpoint:auth}': ['p(95)<1200'],
    'http_req_duration{endpoint:ai_fast}': ['p(95)<800'],
    'http_req_duration{endpoint:home_recommendations}': ['p(95)<1500'],
    'http_req_duration{endpoint:quote_draft}': ['p(95)<1000'],
    'http_req_duration{endpoint:build_history}': ['p(95)<1000'],
    'http_req_duration{endpoint:price_alerts}': ['p(95)<1000'],
    'http_req_duration{endpoint:assembly_requests}': ['p(95)<1000'],
  },
  userAgent: `BuildGraph-k6/${TEST_TYPE}`,
  noConnectionReuse: false,
};

export function setup() {
  const response = login();
  if (response.status !== 200) {
    fail(`setup login failed: HTTP ${response.status}`);
  }
  const payload = response.json();
  if (!payload || !payload.accessToken) {
    fail('setup login response did not contain accessToken');
  }
  return { accessToken: payload.accessToken };
}

export default function (data) {
  const roll = Math.random();
  if (roll < 0.05) {
    exerciseLogin();
  } else if (roll < 0.15) {
    exerciseHealth();
  } else if (roll < 0.40) {
    exerciseParts(data.accessToken);
  } else if (roll < 0.52) {
    exerciseHomeRecommendations(data.accessToken);
  } else if (roll < 0.62) {
    exerciseDraft(data.accessToken);
  } else if (roll < 0.70) {
    exerciseBuildHistory(data.accessToken);
  } else if (roll < 0.77) {
    exercisePriceAlerts(data.accessToken);
  } else if (roll < 0.92) {
    exerciseAssemblyRequests(data.accessToken);
  } else {
    exerciseFastAi(data.accessToken);
  }
  if (THINK_TIME_SECONDS > 0) {
    sleep(THINK_TIME_SECONDS * (0.75 + Math.random() * 0.5));
  }
}

function login() {
  return http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    email: USER_EMAIL,
    password: USER_PASSWORD,
  }), jsonParams('auth'));
}

function exerciseLogin() {
  const response = login();
  verify(response, 'auth', (body) => Boolean(body.accessToken));
}

function exerciseHealth() {
  const response = http.get(`${BASE_URL}/api/health`, taggedParams('health'));
  verify(response, 'health', (body) => body.status === 'UP' && body.database === 'UP');
}

function exerciseParts(token) {
  const category = ['CPU', 'GPU', 'RAM', 'STORAGE', 'PSU', 'CASE'][Math.floor(Math.random() * 6)];
  const response = http.get(
    `${BASE_URL}/api/parts?category=${category}&page=0&size=20`,
    authParams(token, 'parts'),
  );
  verify(response, 'parts', (body) => Array.isArray(body.items));
}

function exerciseHomeRecommendations(token) {
  const response = http.get(
    `${BASE_URL}/api/recommendations/home-parts?limit=8`,
    authParams(token, 'home_recommendations'),
  );
  verify(response, 'home_recommendations', (body) => Array.isArray(body.items));
}

function exerciseDraft(token) {
  const response = http.get(`${BASE_URL}/api/quote-drafts/current`, authParams(token, 'quote_draft'));
  verify(response, 'quote_draft', (body) => typeof body.status === 'string');
}

function exerciseBuildHistory(token) {
  const response = http.get(`${BASE_URL}/api/builds/history?page=0&size=20`, authParams(token, 'build_history'));
  verify(response, 'build_history', (body) => Array.isArray(body.items));
}

function exercisePriceAlerts(token) {
  const response = http.get(`${BASE_URL}/api/price-alerts?page=0&size=20`, authParams(token, 'price_alerts'));
  verify(response, 'price_alerts', (body) => Array.isArray(body.items));
}

function exerciseAssemblyRequests(token) {
  const response = http.get(`${BASE_URL}/api/assembly-requests`, authParams(token, 'assembly_requests'));
  verify(response, 'assembly_requests', (body) => Array.isArray(body.items));
}

function exerciseFastAi(token) {
  const response = http.post(`${BASE_URL}/api/ai/build-chat`, JSON.stringify({
    message: '램 위치가 어디 있어?',
    uiContext: { surface: 'SELF_QUOTE', capabilities: ['BOARD_PART_FOCUS'] },
  }), authJsonParams(token, 'ai_fast'));
  verify(response, 'ai_fast', (body) => (
    body.boardFocus
    && body.boardFocus.type === 'PART_LOCATION'
    && Array.isArray(body.boardFocus.categories)
    && body.boardFocus.categories.includes('RAM')
  ));
}

function verify(response, endpoint, contract) {
  let body = null;
  try {
    body = response.json();
  } catch (_) {
    body = null;
  }
  const ok = check(response, {
    [`${endpoint}: HTTP 200`]: (res) => res.status === 200,
    [`${endpoint}: response contract`]: () => Boolean(body && contract(body)),
  });
  contractErrors.add(!ok, { endpoint });
  recordSoakWindow(response, ok);
}

function recordSoakWindow(response, ok) {
  if (TEST_TYPE !== 'soak' || SOAK_WINDOW_MINUTES <= 0 || SOAK_WINDOW_COUNT <= 0) {
    return;
  }
  const elapsedMs = exec.instance.currentTestRunDuration;
  const windowMs = SOAK_WINDOW_MINUTES * 60 * 1000;
  const index = Math.min(Math.floor(elapsedMs / windowMs), SOAK_WINDOW_COUNT - 1);
  soakWindowDurations[index].add(response.timings.duration);
  soakWindowFailures[index].add(!ok || response.status !== 200);
}

function taggedParams(endpoint) {
  return { tags: { endpoint }, timeout: '10s' };
}

function jsonParams(endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: { 'Content-Type': 'application/json' },
  };
}

function authParams(token, endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: { Authorization: `Bearer ${token}` },
  };
}

function authJsonParams(token, endpoint) {
  return {
    ...taggedParams(endpoint),
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      'X-BuildGraph-AI-Profile': 'BUILD_CHAT_54_MINI_FAST',
    },
  };
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration && data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed && data.metrics.http_req_failed.values;
  const requests = data.metrics.http_reqs && data.metrics.http_reqs.values;
  const checks = data.metrics.checks && data.metrics.checks.values;
  const text = [
    `BuildGraph server ${TEST_TYPE} test`,
    `requests=${requests ? requests.count : 0}`,
    `requestRate=${requests ? requests.rate.toFixed(2) : 0}/s`,
    `avg=${duration ? duration.avg.toFixed(2) : 0}ms`,
    `p95=${duration ? duration['p(95)'].toFixed(2) : 0}ms`,
    `max=${duration ? duration.max.toFixed(2) : 0}ms`,
    `failedRate=${failed ? failed.rate.toFixed(4) : 0}`,
    `checkRate=${checks ? checks.rate.toFixed(4) : 0}`,
    '',
  ].join('\n');
  return {
    stdout: text,
    [SUMMARY_PATH]: JSON.stringify(data, null, 2),
  };
}
