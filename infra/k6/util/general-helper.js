import http from 'k6/http';

export function baseUrl() {
  return __ENV.BASE_URL || 'http://localhost:8080';
}

export function authHeaders(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

export function credentialsForVu(vu) {
  const users = parseUsers();
  if (users.length > 0) {
    if (vu > users.length) {
      throw new Error(`VU ${vu}에 할당할 테스트 계정이 없습니다. TEST_USERS_JSON에 최소 ${vu}개 계정을 넣어 주세요.`);
    }
    return users[vu - 1];
  }

  const emailTemplate = __ENV.TEST_USER_EMAIL_TEMPLATE;
  const password = __ENV.TEST_USER_PASSWORD || __ENV.TEST_PASSWORD;
  if (emailTemplate && password) {
    if (!emailTemplate.includes('{vu}')) {
      throw new Error('TEST_USER_EMAIL_TEMPLATE에는 VU별 치환값 {vu}가 필요합니다.');
    }
    return { email: emailTemplate.replaceAll('{vu}', String(vu)), password };
  }

  if (vu === 1 && __ENV.TEST_EMAIL && __ENV.TEST_PASSWORD) {
    return { email: __ENV.TEST_EMAIL, password: __ENV.TEST_PASSWORD };
  }
  throw new Error('동시 VU별 독립 quote draft를 위해 TEST_USERS_JSON 또는 TEST_USER_EMAIL_TEMPLATE/TEST_USER_PASSWORD를 설정해 주세요.');
}

export function loginForVu(vu) {
  const response = http.post(`${baseUrl()}/api/auth/login`, JSON.stringify(credentialsForVu(vu)), {
    headers: { 'Content-Type': 'application/json' },
    tags: commonTags('setup-login'),
    timeout: requestTimeout(),
  });
  if (response.status !== 200) throw new Error(`로그인 실패: vu=${vu}, status=${response.status}`);
  const payload = response.json();
  if (!payload || !payload.accessToken) throw new Error(`로그인 응답에 accessToken이 없습니다: vu=${vu}`);
  return payload.accessToken;
}

export function commonTags(phase, extra = {}) {
  return { flow: 'self-quote', variant: __ENV.K6_VARIANT || 'baseline', phase, ...extra };
}

export function requestTimeout() {
  return __ENV.REQUEST_TIMEOUT || '15s';
}

function parseUsers() {
  const raw = __ENV.TEST_USERS_JSON;
  if (!raw) return [];
  let users;
  try {
    users = JSON.parse(raw);
  } catch (_) {
    throw new Error('TEST_USERS_JSON은 JSON 배열이어야 합니다.');
  }
  if (!Array.isArray(users)) throw new Error('TEST_USERS_JSON은 JSON 배열이어야 합니다.');
  return users.map((user, index) => {
    if (!user || typeof user.email !== 'string' || typeof user.password !== 'string') {
      throw new Error(`TEST_USERS_JSON[${index}]에는 email과 password가 필요합니다.`);
    }
    return { email: user.email, password: user.password };
  });
}
