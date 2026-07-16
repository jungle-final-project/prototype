# k6 사용자 플로우

`core/self-quote-flow.js`는 캐시를 끈 API와 Caffeine을 켠 API를 분리하여 같은 사용자 흐름을 비교합니다.

```text
후보 조회 -> 부품 선택 -> quote draft 반영 -> Build Graph/Tool 검증
```

각 VU는 독립된 테스트 계정으로 CPU부터 COOLER까지 8개 부품을 순서대로 선택합니다. 따라서 테스트 계정을 최대 VU 수 이상 준비해야 합니다.

## 비교 서버 실행

```powershell
docker compose -f compose.yaml -f infra/k6/compose.cache-comparison.yaml up --build -d api-cache-none api-cache-caffeine
```

- `http://localhost:8081`: `SPRING_CACHE_TYPE=none`
- `http://localhost:8082`: `SPRING_CACHE_TYPE=caffeine`

두 서버는 같은 PostgreSQL을 사용하므로 동일한 테스트 계정으로 로그인할 수 있습니다.

## 시나리오 실행과 리포트

```powershell
k6 run --out json=infra/k6/results/self-quote.ndjson -e TEST_USER_EMAIL_TEMPLATE=k6-user-{vu}@example.com -e TEST_USER_PASSWORD=passw0rd! infra/k6/core/self-quote-flow.js
node infra/k6/scripts/build-k6-report.js infra/k6/results/self-quote.ndjson infra/k6/reports/self-quote-report.html
```

계정별 비밀번호가 다르면 `TEST_USERS_JSON=[{"email":"k6-user-1@example.com","password":"..."}]` 형식으로 전달합니다.

- `NONE_BASE_URL`, `CAFFEINE_BASE_URL`: 비교할 API 주소
- `TEST_USER_COUNT`: 두 시나리오가 함께 재사용할 테스트 계정 수(기본 500)
- `REQUEST_TIMEOUT`: 요청별 타임아웃
- `K6_BUCKET_MS`, `K6_VARIANTS`: HTML 리포트 버킷과 cache 필터
