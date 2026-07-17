import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { baseUrl, commonTags, credentialsForVu, requestTimeout } from '../util/general-helper.js';

const LOGIN_START_RATE = Number(__ENV.LOGIN_START_RATE || '2');
const LOGIN_PEAK_RATE = Number(__ENV.LOGIN_PEAK_RATE || '8');
const LOGIN_PRE_ALLOCATED_VUS = Number(__ENV.LOGIN_PRE_ALLOCATED_VUS || '50');
const LOGIN_MAX_VUS = Number(__ENV.LOGIN_MAX_VUS || '200');
const LOGIN_USER_COUNT = Number(__ENV.TEST_USER_COUNT || '500');
const LOGIN_P95_THRESHOLD_MS = Number(__ENV.LOGIN_P95_THRESHOLD_MS || '3000');

export const options = {
  scenarios: {
    login_only: {
      executor: 'ramping-arrival-rate',
      exec: 'loginOnly',
      startRate: LOGIN_START_RATE,
      timeUnit: '1s',
      preAllocatedVUs: LOGIN_PRE_ALLOCATED_VUS,
      maxVUs: LOGIN_MAX_VUS,
      stages: loginStages(),
      startTime: '0s',
      env: {
        BASE_URL: __ENV.LOGIN_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080',
        K6_FLOW: 'auth',
        K6_VARIANT: __ENV.K6_VARIANT || 'login'
      },
      tags: {
        flow: 'auth',
        visitor: 'authenticated'
      }
    }
  },
  thresholds: {
    'http_req_failed{endpoint:auth_login}': ['rate<0.01'],
    'http_req_duration{endpoint:auth_login}': [`p(95)<${LOGIN_P95_THRESHOLD_MS}`]
  }
};

function loginStages() {
  return [
    { target: LOGIN_START_RATE, duration: __ENV.LOGIN_RAMP_UP_DURATION || '5s' },
    { target: LOGIN_PEAK_RATE, duration: __ENV.LOGIN_STEADY_DURATION || '20s' },
    { target: 1, duration: __ENV.LOGIN_RAMP_DOWN_DURATION || '5s' }
  ];
}

export function loginOnly() {
  const accountIndex = (exec.scenario.iterationInTest % LOGIN_USER_COUNT) + 1;
  const response = http.post(`${baseUrl()}/api/auth/login`, JSON.stringify(credentialsForVu(accountIndex)), {
    headers: { 'Content-Type': 'application/json' },
    tags: commonTags('auth-login', { endpoint: 'auth_login', visitor: 'authenticated' }),
    timeout: requestTimeout()
  });

  check(response, {
    'auth login 200': (res) => res.status === 200,
    'auth login has access token': (res) => Boolean(parseJson(res)?.accessToken),
    'auth login has refresh token': (res) => Boolean(parseJson(res)?.refreshToken),
    'auth login returns minimal user': (res) => {
      const user = parseJson(res)?.user;
      return Boolean(user?.id && user?.email && user?.name && user?.role)
        && user.phoneNumber === undefined
        && user.postalCode === undefined
        && user.authProviders === undefined;
    }
  });

  sleep(0.2 + Math.random() * 0.3);
}

function parseJson(response) {
  try {
    return JSON.parse(response.body || '{}');
  } catch (_) {
    return null;
  }
}
