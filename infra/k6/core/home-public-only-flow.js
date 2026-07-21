import http from 'k6/http';
import { check, sleep } from 'k6';
import { baseUrl, requestTimeout } from '../util/general-helper.js';

const PUBLIC_HOME_START_RATE = Number(__ENV.PUBLIC_HOME_START_RATE || __ENV.HOME_START_RATE || '2');
const PUBLIC_HOME_PEAK_RATE = Number(__ENV.PUBLIC_HOME_PEAK_RATE || __ENV.HOME_PEAK_RATE || '8');
const PUBLIC_HOME_PRE_ALLOCATED_VUS = Number(
  __ENV.PUBLIC_HOME_PRE_ALLOCATED_VUS || __ENV.HOME_PRE_ALLOCATED_VUS || '50'
);
const PUBLIC_HOME_MAX_VUS = Number(__ENV.PUBLIC_HOME_MAX_VUS || __ENV.HOME_MAX_VUS || '200');
const PUBLIC_HOME_P95_THRESHOLD_MS = Number(__ENV.PUBLIC_HOME_P95_THRESHOLD_MS || '1000');

export const options = {
  scenarios: {
    public_home_only: {
      executor: 'ramping-arrival-rate',
      exec: 'publicHomeOnly',
      startRate: PUBLIC_HOME_START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: PUBLIC_HOME_PRE_ALLOCATED_VUS,
      maxVUs: PUBLIC_HOME_MAX_VUS,
      stages: publicHomeStages(),
      startTime: '0s',
      env: {
        BASE_URL: __ENV.PUBLIC_HOME_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
        K6_FLOW: 'home',
        K6_VARIANT: __ENV.K6_VARIANT || 'public-home-only'
      },
      tags: {
        flow: 'home',
        visitor: 'public'
      }
    }
  },
  thresholds: {
    'http_req_failed{endpoint:public_home}': ['rate<0.01'],
    'http_req_duration{endpoint:public_home}': [`p(95)<${PUBLIC_HOME_P95_THRESHOLD_MS}`]
  }
};

function publicHomeStages() {
  return [
    { target: PUBLIC_HOME_PEAK_RATE, duration: __ENV.PUBLIC_HOME_RAMP_UP_DURATION || __ENV.HOME_RAMP_UP_DURATION || '5s' },
    { target: PUBLIC_HOME_PEAK_RATE, duration: __ENV.PUBLIC_HOME_STEADY_DURATION || __ENV.HOME_STEADY_DURATION || '20s' },
    { target: 1, duration: __ENV.PUBLIC_HOME_RAMP_DOWN_DURATION || __ENV.HOME_RAMP_DOWN_DURATION || '5s' }
  ];
}

export function publicHomeOnly() {
  const response = http.get(`${baseUrl()}/api/public/home`, {
    tags: publicHomeTags('public-home', { endpoint: 'public_home', visitor: 'public' }),
    timeout: requestTimeout()
  });

  check(response, {
    'public home 200': (res) => res.status === 200,
    'public home has category parts': (res) => hasObjectField(res, 'categoryParts'),
    'public home has recommended parts': (res) => hasObjectField(res, 'recommendedParts')
  });

  sleep(0.5 + Math.random());
}

function publicHomeTags(phase, extra = {}) {
  return {
    flow: 'home',
    variant: __ENV.K6_VARIANT || 'public-home-only',
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
