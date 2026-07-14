# AWS 배포 부하 테스트 사전 공유 문서

> 기준: `origin/main` `da17653` (2026-07-12 KST, PR #146 병합 후)
> 대상: `https://d1a7gxvxxd385i.cloudfront.net`
> 목적: 첫 AWS 부하 테스트 전에 팀이 현재 한계, 측정 조건, 중단 기준, 개선 순서를 동일하게 이해하도록 한다.

## 1. 결론

현재 배포는 **CloudFront 뒤 단일 EC2의 Docker Compose**이며, API와 PostgreSQL, Redis, RabbitMQ, XGBoost scorer, Mailpit, Nginx가 같은 호스트 자원을 공유한다. 코드의 기능 완성도와 로컬 부하 결과는 양호하지만, AWS 환경에서 지속 가능한 최대 RPS는 아직 측정되지 않았다.

첫 AWS 시험에서 확인할 항목은 다음 세 가지다.

1. CloudFront를 포함한 실제 사용자 경로에서 2~80 RPS를 단계적으로 처리할 수 있는지 확인한다.
2. 어느 계층이 먼저 포화되는지 CPU, 메모리, DB 연결, 컨테이너 재시작과 함께 식별한다.
3. 같은 시나리오를 성능 개선 전후에 반복할 수 있는 기준선을 남긴다.

첫 시험은 **1시간 점진 램프**로 진행한다. 여기서 확인된 안정 처리량의 60~70%로 두 번째 1시간 Soak를 수행한다. 100 RPS 이상 Capacity와 Spike는 내부 지표를 함께 수집하는 후속 세트로 둔다.

## 2. 최신 상태에서 확인된 사실

### 배포 구조

- `compose.prod.yaml`은 7개 서비스를 단일 Docker 호스트에서 실행한다.
- 컨테이너별 CPU/메모리 limit과 API replica가 정의되어 있지 않다.
- API Dockerfile은 별도 JVM heap/GC 설정 없이 `java -jar`로 실행한다.
- HikariCP, Tomcat worker/queue, graceful shutdown의 운영값이 명시되어 있지 않다.
- 배포 workflow는 GitHub Actions에서 검증 빌드를 수행한 뒤 EC2에서 다시 `docker compose ... up --build`를 실행한다. 배포 중 EC2가 Gradle/Vite/Docker build 부하도 함께 받는다.
- 최신 `da17653`의 CI와 `Deploy EC2 Compose` workflow는 모두 성공했다. 다만 런타임 응답에 Git SHA가 없어 외부 요청만으로 실행 중인 코드를 증명할 수는 없다.
- 실제 인스턴스 유형, T3 credit mode, EBS 여유, CloudWatch alarm 구성은 AWS 콘솔 또는 SSH에서 확인한다. 리서치의 `t3.medium` 표기는 아직 서버 실측값이 아니다.

### CloudFront 실측

2026-07-12에 현재 배포의 해시 JS(`/assets/index-B0vZ5UDq.js`, 약 1.22 MB)를 연속 조회한 결과 두 번 모두 `Miss from cloudfront`였고 `Content-Encoding`도 없었다. Origin은 `Cache-Control: public, max-age=31536000, immutable`을 반환하지만 CloudFront behavior가 캐시/압축을 사용하지 않는 상태로 보인다.

- `/api/*`와 인증 응답은 `CachingDisabled`를 유지한다.
- `/assets/*`만 별도 behavior로 분리해 장기 캐시와 Gzip/Brotli를 활성화할 가치가 크다.
- 첫 AWS 기준선은 현 상태로 측정하고, CloudFront 변경 후 동일 테스트로 효과를 비교한다.

### 부하 하네스

- Load, Stress, Spike, Soak, Capacity 프로필이 존재한다.
- PR #146이 최신 main에 병합되어 VU별 access/refresh token 갱신이 동작한다.
- 조립 요청 조회가 전체 서버 혼합 부하에 포함됐다.
- 외부 OpenAI cold call은 전체 서버 부하에서 제외하고 읽기 전용 AI fast path만 사용한다.
- 원격 주소로 실행하면 현재 PowerShell resource monitor는 서버 JVM을 측정하지 못한다. EC2 측 수집기가 별도로 필요하다.

### 코드 핫패스

| 경로 | 확인 내용 | 성능 영향 |
|---|---|---|
| `GET /api/builds/history` | 목록 1회 뒤 최대 30개 견적마다 부품, 예산, Agent session, 근거 등을 개별 조회 | 실제 N+1. Hikari pool 점유와 DB p95 상승 요인 |
| `GET /api/recommendations/home-parts` | ACTIVE 부품 전체에 benchmark, price snapshot, offer 3개 LATERAL과 FPS EXISTS 수행 | 후보 증가에 따라 DB 비용 증가. scorer 상태에 따라 shadow row 쓰기도 발생 |
| `GET /api/parts` | 목록마다 최신 benchmark/price/offer LATERAL + 별도 count | 데이터와 pagination 깊이가 커지면 비용 증가 |
| `sort=compatibility` | 카테고리 전체 후보를 읽고 Java에서 후보별 Tool 평가 후 정렬·페이지 절단 | 일반 parts 부하보다 훨씬 비싸며 기존 k6 혼합에는 포함되지 않음 |
| 외부 AI | 동기 RestClient 호출이며 전체 서버 부하에서는 제외 | 별도 bulkhead, timeout, provider quota 시험 필요 |

## 3. 로컬 기준선과 해석 제한

동일 코드의 로컬 전용 QA API에서 얻은 결과다. AWS 용량 수치가 아니라, 테스트 하네스와 애플리케이션 회귀 기준선으로만 사용한다.

| 프로필 | 요청 수 | 평균 처리율 | p95 | 오류 |
|---|---:|---:|---:|---:|
| Load | 7,114 | 75.90 req/s | 60.73 ms | 0 |
| Stress | 24,686 | 197.60 req/s | 60.18 ms | 0 |
| Spike | 19,165 | 306.15 req/s | 66.37 ms | 0 |
| Capacity | 30,250 | 181.79 req/s(전체 램프 평균) | 38.46 ms | 0, dropped 0 |
| Soak 60분 | 343,042 | 91.50 req/s | 56.67 ms | 최종 계약 실패 13건 |

Soak에서는 15분 access token 만료마다 총 80회의 refresh가 확인됐다. k6 원시 `http_req_failed`에는 refresh를 유발한 최초 401도 포함되므로, 사용자 관점 최종 응답 실패와 구분해야 한다. 메모리는 초반 warming 후 후반에 평탄해 로컬 JVM 누수 신호는 없었다.

Capacity가 짧게 400 iterations/s 단계에 도달했고 dropped iteration이 없었던 것은 유효한 로컬 결과다. 하지만 해당 단계가 10분 이상 유지되지 않았고 AWS 네트워크·EC2·동일 호스트 DB 조건이 아니므로 **AWS가 400 RPS를 지원한다는 근거로 사용할 수 없다.**

## 4. 시험 시작 전 P0 체크리스트

1시간 시험 전에 팀에서 아래 항목과 담당자를 확인한다.

### 운영·복구

- [ ] 테스트 시간과 배포·데이터 migration 일정이 겹치지 않게 한다.
- [ ] AWS/서버 담당자 한 명이 전체 시간 동안 중단 권한을 가진다.
- [ ] PostgreSQL volume 또는 EC2/EBS snapshot 완료 시각을 기록한다.
- [ ] `docker compose ... restart api`와 전체 compose 복구 담당자를 정한다.
- [ ] 테스트 후 정리할 전용 사용자와 생성 데이터 범위를 정한다.
- [ ] 일반 사용자에게 영향이 적은 시간대를 확정한다.

### AWS/호스트 사실 확인

- [ ] EC2 인스턴스 유형과 vCPU/메모리를 기록한다.
- [ ] T3/T4g이면 CPU credit mode가 `unlimited`인지 확인하고 `CPUCreditBalance`, `CPUSurplusCreditBalance/Charged`를 수집한다.
- [ ] EBS 사용률과 남은 공간이 20% 이상인지 확인한다.
- [ ] EC2 system/instance status check와 자동 복구 설정을 확인한다. EC2 자동 복구는 호스트 장애용이며 애플리케이션 OOM을 복구하는 기능이 아니다.
- [ ] 현재 배포 Git SHA를 기록한다. 외부에서 SHA를 확인할 endpoint/header가 없으므로 배포 workflow와 서버 checkout을 대조한다.

### 관측

- [ ] 1분 간격 EC2 CPU, network, EBS I/O/queue, CPU credit를 수집한다.
- [ ] 1분 간격 `docker stats`, 컨테이너 restart count, host memory/swap/disk를 수집한다.
- [ ] API 로그의 4xx/5xx, timeout, DB connection 오류를 보존한다.
- [ ] 가능하면 Actuator JVM/GC/thread/Hikari 지표를 내부 경로에서 수집한다.
- [ ] k6 raw JSON, console log, manifest를 실행 ID별로 별도 보존한다.

### 테스트 데이터와 비용

- [ ] 공용 `user@example.com` 대신 전용 load-test 계정을 사용한다.
- [ ] 현재 스크립트의 반복 로그인 5%는 운영 DB에 refresh token을 대량 생성하므로 배포 전용 프로필에서 분리한다. 로그인은 setup/만료 복구와 낮은 고정률 auth 시나리오로 제한한다.
- [ ] 홈 추천 조회는 scorer 상태에 따라 `recommendation_shadow_scores`를 쓸 수 있으므로 전후 row 증가량을 기록한다.
- [ ] 외부 OpenAI cold call, 가격 수집, 이메일, 실제 조립 요청 생성은 첫 혼합 부하에서 제외한다.
- [ ] 스케줄러/가격 cron과 테스트 시간이 겹치지 않는지 확인한다.

## 5. 첫 AWS 1시간 실행안

### 트래픽 경로

- 사용자 체감 기준선은 CloudFront URL을 대상으로 한다.
- 부하 생성기는 대상 EC2가 아닌 별도 PC/인스턴스에서 실행한다.
- CloudFront와 origin 병목을 분리하려면 추후 허가된 origin 직접 테스트를 별도로 수행한다.
- arrival-rate executor를 사용해 VU가 아니라 RPS로 부하를 설명한다.

### 단계

| 구간 | 목표 부하 | 목적 |
|---|---:|---|
| 0~5분 | 2 RPS | 인증, 계약, 관측 수집 확인 |
| 5~15분 | 5 RPS | 배포 경로 기준선 |
| 15~25분 | 10 RPS | 낮은 정상 부하 |
| 25~35분 | 20 RPS | DB/pool 초기 변화 확인 |
| 35~45분 | 40 RPS | 단일 EC2 지속 부하 확인 |
| 45~55분 | 80 RPS | 첫 시험 상한, SLO 여유 확인 |
| 55~60분 | 5 RPS | 부하 제거 후 회복 확인 |

각 단계 종료 시 지표를 검토해 다음 단계 진행 여부를 결정한다. 80 RPS는 첫 시험 범위이며 최대 용량은 후속 Capacity 시험에서 산정한다.

### 첫 시험 트래픽 믹스

초기에는 운영 데이터 변경이 적은 조회 중심 혼합을 사용한다.

| 경로군 | 비중 | 비고 |
|---|---:|---|
| health | 10% | DB를 포함한 end-to-end 상태 확인 |
| parts 목록 | 30% | 일반 category/page 조회 |
| 홈 추천부품 | 5% | shadow write 증가량 별도 확인 |
| quote draft 조회 | 15% | 전용 사용자 데이터 |
| build history | 10% | N+1 관측 핵심 |
| price alerts 조회 | 5% | 읽기 전용 |
| assembly requests 조회 | 10% | PR #145/#146 경로 |
| deterministic AI fast path | 10% | OpenAI 호출 없음 |
| 로그인/refresh | 5% 이하 | 반복 로그인은 별도 저율 시나리오로 분리 |

`compatibility sort`, 조립 요청/입찰 쓰기, WebSocket, 로그 업로드, cold LLM은 비용과 부하 성격이 달라 별도 프로필로 측정한다.

## 6. 합격·중단 기준

### 잠정 합격 기준

| 요청군 | p95 | p99 |
|---|---:|---:|
| health | 100 ms | 250 ms |
| 일반 조회 | 300 ms | 800 ms |
| 인증 조회/쓰기 | 700 ms | 1,500 ms |
| 호환성/추천 | 800 ms | 1,500 ms |
| deterministic/cache-hit AI | 500 ms | 1,000 ms |

- 정상 구간 오류율 0.1% 미만, 테스트 전체 1% 미만
- `dropped_iterations = 0`
- 컨테이너 restart/OOM 0
- EC2 CPU steady state 70% 이하, 메모리 75% 이하를 목표로 한다.
- DB connection timeout 0, Hikari pending의 지속 증가 없음
- 부하 제거 후 5분 안에 latency와 CPU가 기준선 수준으로 회복

### 중단 기준

다음 중 하나가 발생하면 다음 단계로 올리지 않고 테스트를 중단한다.

- p95가 2초를 3분 연속 초과
- 최종 응답 오류율이 1%를 2분 연속 초과
- `/api/health`가 연속 2회 실패 또는 503
- 컨테이너 재시작/OOM 발생
- host memory 85% 초과 또는 swap 지속 증가
- 디스크 사용률 80% 초과
- CPU 85% 이상이 5분 지속하며 latency가 함께 악화
- DB connection 사용량 80% 초과 또는 connection timeout 발생
- 일반 사용자 영향이 확인되거나 복구 담당자가 중단을 요청

## 7. 결과 해석 기준

지속 가능 RPS는 순간 최고치가 아니라 다음 조건을 모두 만족한 마지막 10분 구간이다.

```text
SLO 통과
+ 최종 응답 오류율 통과
+ dropped iteration 0
+ 컨테이너 restart/OOM 0
+ CPU·메모리·DB connection 여유 존재
+ 다음 저부하 구간에서 정상 회복
```

| 관측 패턴 | 우선 의심 | 다음 조치 |
|---|---|---|
| CPU 높음, DB 대기 낮음 | JVM/API 연산, compatibility Tool | endpoint별 CPU profile, 후보 축소 |
| CPU 낮음, Hikari pending 증가 | 느린 SQL/lock/DB I/O | `pg_stat_statements`, 실행 계획 확인 |
| home recommendation만 악화 | 전체 후보 LATERAL/scorer write | read model·TTL cache·shadow 쓰기 분리 |
| history만 악화 | 견적별 N+1 | 한 번의 batch query/집계로 변경 |
| 메모리 단계별 증가 후 미회복 | JVM 또는 비동기 queue 누적 | heap/GC/thread dump와 executor queue 확인 |
| 401 증가 후 최종 요청 성공 | 정상 refresh 전환 | raw HTTP 실패와 사용자 실패를 분리 집계 |
| dropped iteration 증가 | injector VU 부족 또는 SUT 지연 | VU 여유와 응답시간을 함께 확인 |
| 정적 자산 origin 요청 지속 | CloudFront behavior 문제 | `/assets/*` 캐시·압축 분리 |

## 8. 성능 업그레이드 전략

### 8.1 우선순위와 기대 결과

| 단계 | 변경 묶음 | 기대 결과 | 판단 지표 |
|---|---|---|---|
| P0 | 배포 SHA·호스트·JVM·DB 관측, 전용 부하 프로필 | 결과 재현성과 병목 위치 확보 | 같은 실행 ID로 k6와 서버 지표 연결 |
| P1-A | CloudFront 캐시/압축, Nginx keepalive/timeout | 정적 전송량과 origin 연결 비용 감소 | asset cache hit, 전송 크기, Nginx upstream time |
| P1-B | JVM/컨테이너 자원 예산, Hikari/Tomcat A/B | host OOM 방지와 queue/pool 균형 | heap, GC, Hikari pending, p95/p99 |
| P1-C | CI 이미지 배포, scheduler 분리 | 배포 중 서버 CPU 급증과 중복 job 제거 | 배포 시간, EC2 CPU, job 중복 실행 수 |
| P2-A | parts/home/auth 캐시 | 반복 DB 조회 감소 | cache hit, DB QPS, endpoint p95 |
| P2-B | history N+1·LATERAL·compatibility 개선 | 쿼리 수와 CPU 비용 감소 | SQL 호출 수, total DB time, rows scanned |
| P2-C | AI bulkhead/single-flight | 외부 AI 지연이 일반 API로 전파되는 현상 차단 | AI in-flight, queue, timeout, 일반 API p95 |
| P3 | S3·RDS·ElastiCache·ALB/ECS 분리 | 단일 호스트 자원 경합 제거와 수평 확장 | task별 RPS, DB connection, 장애 복구 시간 |

### 8.2 CloudFront와 정적 전송

현재 해시 JS는 origin에서 1년 immutable header를 보내지만 CloudFront에서 연속 `Miss`가 발생하고 압축도 적용되지 않는다. 가장 먼저 효과를 확인할 수 있는 변경이다.

#### CloudFront behavior

| Path pattern | Cache policy | 전달 값 | 압축 |
|---|---|---|---|
| `/assets/*` | `CachingOptimized` 또는 동등한 장기 TTL | cookie 없음, query 없음, 최소 header | Gzip+Brotli |
| `/api/*` | `CachingDisabled` | Authorization, Origin, 필요한 cookie/query | API origin 처리 |
| `/ws/*` | `CachingDisabled` | Upgrade, Connection, Origin, 인증 정보 | 사용 안 함 |
| 기본 SPA/HTML | 짧은 TTL 또는 no-cache | 최소 값 | Gzip+Brotli |

적용 후 같은 해시 asset을 두 번 요청해 두 번째 응답의 `X-Cache: Hit from cloudfront`, `Age`, `Content-Encoding: br|gzip`을 확인한다. 현재 약 1.22 MB JS의 실제 전송 크기와 초기 화면 로딩 시간도 함께 비교한다.

정적 자산을 장기적으로 S3 + CloudFront OAC로 옮기면 Nginx와 EC2가 SPA 파일을 전송하는 역할 자체를 제거할 수 있다. 첫 개선에서는 behavior만 분리하고, S3 이전은 P3로 둔다.

### 8.3 Nginx 연결·압축·timeout

현재 `/api/`는 요청 종류와 관계없이 같은 upstream과 300초 timeout을 사용한다. 다음처럼 연결 재사용과 요청 성격별 timeout을 분리한다.

```nginx
upstream buildgraph_api {
    server api:8080;
    keepalive 32;
}

gzip on;
gzip_proxied any;
gzip_vary on;
gzip_min_length 1024;
gzip_types application/json application/javascript text/css text/plain image/svg+xml;

location /api/ai/ {
    proxy_pass http://buildgraph_api;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_connect_timeout 3s;
    proxy_read_timeout 30s;
}

location /api/ {
    proxy_pass http://buildgraph_api;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_connect_timeout 3s;
    proxy_read_timeout 10s;
}
```

WebSocket은 별도 location의 upgrade header와 장시간 timeout을 유지한다. 로그에는 `$request_time`, `$upstream_response_time`, status, request ID를 추가해 CloudFront 지연과 API 지연을 분리한다.

기대 결과는 TCP connection 재사용, JSON/CSS/JS 전송량 감소, 느린 일반 요청의 조기 식별이다. `Content-Encoding`, upstream connection 수, Nginx request time과 k6 latency로 효과를 확인한다.

### 8.4 단일 EC2 자원 예산과 JVM

현재는 7개 컨테이너가 4 GB 호스트 메모리를 제한 없이 공유한다. 실제 호스트가 `t3.medium`으로 확인되면 아래 값을 첫 자원 격리 실험의 시작점으로 사용한다.

| 서비스 | memory limit 시작값 | 목적 |
|---|---:|---|
| API | 1,280 MB | JVM heap+native/thread 공간 확보 |
| PostgreSQL | 900 MB | shared buffer와 query memory 상한 |
| XGB reranker | 400 MB | Python/model 메모리 격리 |
| RabbitMQ | 350 MB | queue process 상한 |
| Redis | 200 MB | 캐시 데이터 상한과 eviction 관측 |
| Nginx | 100 MB | 정적/프록시 전용 |
| Mailpit | 100 MB | 데모 메일 전용 |

합계 약 3.25 GB로 OS와 Docker에 약 0.75 GB를 남긴다. 실제 idle/peak 사용량을 먼저 기록하고 limit 초과 컨테이너는 같은 총예산 안에서 조정한다.

API에는 다음 JVM 시작값을 적용한다.

```text
JAVA_TOOL_OPTIONS=
  -XX:MaxRAMPercentage=70
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/tmp
  -XX:+ExitOnOutOfMemoryError
```

GC 종류는 Java 21 기본값을 유지하고 `jvm.gc.pause`, allocation rate, old generation 사용량을 기준선과 비교한다. 이 변경의 목표는 처리량 증가보다 host 전체 OOM과 DB 동반 종료를 막고 메모리 사용 경계를 명확하게 만드는 것이다.

### 8.5 Hikari, Tomcat, Java 21 virtual thread

현재 명시 설정이 없어 Hikari와 Tomcat 기본값 조합으로 동작한다. DB pool보다 HTTP worker가 훨씬 많으면 DB 대기 요청이 JVM 안에 쌓일 수 있다.

첫 설정 실험은 다음 값으로 시작한다.

```yaml
server:
  shutdown: graceful
  tomcat:
    threads:
      max: 150
      min-spare: 20
    accept-count: 100

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    hikari:
      maximum-pool-size: 15
      minimum-idle: 5
      connection-timeout: 1500
      validation-timeout: 800
```

다음 두 변형을 동일 부하로 비교한다.

1. 플랫폼 스레드 + 위 Tomcat/Hikari 설정
2. `spring.threads.virtual.enabled=true` + 동일 Hikari 상한

Virtual thread는 DB connection 수를 늘리지 않는다. 비교 기준은 전체 처리량뿐 아니라 Hikari pending, DB CPU, p95/p99, JVM thread/native memory다. Hikari 10과 15도 별도 비교해 DB가 포화되기 전의 값을 선택한다.

### 8.6 Actuator·Prometheus·서버 관측

현재 Actuator 의존성은 있지만 `health,info`만 노출하며 Prometheus registry는 없다.

1. `micrometer-registry-prometheus`를 추가한다.
2. 내부 접근 경로에서 `health,info,prometheus`를 노출한다.
3. Nginx 또는 security group으로 `/actuator/prometheus` 접근 주체를 제한한다.
4. CloudWatch agent 또는 1분 수집 스크립트로 host/container 지표를 같은 실행 ID에 저장한다.

필수 meter는 `http.server.requests`, `jvm.memory.*`, `jvm.gc.pause`, `jvm.threads.*`, `hikaricp.*`, `jdbc.connections.*`, process CPU다. k6 p95 상승 시 같은 시각의 Hikari pending·GC·CPU를 바로 대조할 수 있어야 한다.

Health endpoint는 용도를 분리한다.

- liveness: JVM process 상태, DB 비의존
- readiness: DB·필수 의존성 포함
- `/api/health`: 운영자용 종합 상태

### 8.7 캐시 전략

#### 부품 목록 캐시

- 대상: 일반 `GET /api/parts`의 category, page, size, sort, 검색 조건 결과
- 저장: Redis JSON 또는 Spring Cache
- TTL: 30~60초
- key: 요청 필터 + parts/price/benchmark/offer data version
- 무효화: 관리자 part mutation, 수동 가격 변경, 가격 refresh, offer 갱신
- 제외: draft 기반 compatibility 정렬

일반 parts DB QPS와 p95를 전후 비교한다. 캐시 hit 응답도 사용자별 데이터를 포함하지 않는지 계약 테스트로 고정한다.

#### 홈 추천부품 캐시

- 사용자와 무관한 후보 feature·fallback ranking을 30~60초 공유한다.
- key에 `limit`, part data version, benchmark/FPS version, active model version을 포함한다.
- 사용자별 shadow 기록과 이벤트 ID 생성은 캐시된 ranking 조회 뒤 분리 실행한다.
- scorer real model 활성화/은퇴와 parts/price 변경 시 version을 올린다.

이 구조는 매 요청 전체 ACTIVE 후보와 3개 LATERAL을 다시 읽는 비용을 줄이면서 사용자 행동 이벤트는 유지한다.

#### 인증 사용자 캐시

현재 보호 API는 JWT 검증 후 매 요청 `users`를 다시 조회한다. 단일 EC2에서는 Caffeine 또는 Redis에 `publicUserId → internalId/role/deleted 상태`를 30~60초 캐시한다.

- role 변경, soft delete, 계정 정지 시 즉시 무효화
- cache miss만 users query 수행
- access token 검증 자체는 계속 수행

전후 `users` SELECT QPS와 인증 endpoint p95를 비교한다.

#### AI single-flight

기존 exact/semantic cache는 유지한다. 동일 cache key의 동시 miss에서는 한 요청만 외부 AI를 호출하고 나머지는 같은 Future/Redis lock 결과를 기다리게 한다. lock에는 짧은 TTL과 owner token을 둔다. AI provider 호출 수, 동시 in-flight, cache miss latency를 비교한다.

### 8.8 DB와 코드 핫패스

#### `builds/history` N+1 제거

현재 최대 30개 build 각각에 여러 조회가 붙는다. 다음처럼 query 수를 페이지 크기와 무관한 상수로 만든다.

1. 소유 build 30개 조회
2. `build_id IN (...)`으로 모든 items 일괄 조회
3. window function 또는 `DISTINCT ON`으로 build별 최신 Agent session 일괄 조회
4. session/build ID 집합으로 evidence/tool 결과 일괄 조회
5. Java에서 build ID로 group 조립

목표는 30개 이력 기준 SQL 호출 수를 150회 이상 가능 구조에서 약 4~6회로 줄이는 것이다. API integration test에 query counter를 추가해 재발을 막는다.

#### 홈 추천 read model

단기 TTL cache 이후에도 갱신 비용이 크면 `part_recommendation_features` current table을 둔다. part 가격, 최신 benchmark, 최신 offer, FPS coverage, toolReady를 mutation 시 갱신하고 홈 요청은 이 테이블만 읽는다. 모델 score와 category diversity는 현재 서비스 로직을 유지한다.

#### 부품 최신값 read model과 인덱스

다음 후보는 운영 데이터 복제본에서 기존 `pg_indexes`와 실행 계획을 확인한 뒤 적용한다.

```sql
CREATE INDEX ... ON parts (category, status, price, id)
WHERE deleted_at IS NULL;

CREATE INDEX ... ON benchmark_summaries (part_id, created_at DESC, id DESC)
INCLUDE (summary, score)
WHERE deleted_at IS NULL;

CREATE INDEX ... ON price_snapshots (part_id, collected_at DESC, id DESC)
INCLUDE (price, source);
```

중기적으로 `part_current_price`, `part_current_benchmark`, `part_current_offer`를 유지하면 목록과 홈 추천의 LATERAL을 제거할 수 있다. 정확한 total이 필수가 아닌 화면은 `LIMIT size+1`의 `hasNext` 방식, 깊은 페이지는 keyset pagination을 적용한다.

#### compatibility 정렬

1. SQL에서 category/status/가격/필수 스펙으로 후보를 100개 이하로 제한한다.
2. 카테고리별 관련 Tool만 실행한다.
3. `draft fingerprint + category + candidate part version`으로 평가 결과를 짧게 캐시한다.
4. draft가 바뀌면 fingerprint가 달라져 자연스럽게 miss 처리한다.

일반 parts 프로필과 compatibility 프로필을 분리해 CPU time, 평가 후보 수, p95를 기록한다.

#### DB 관측과 PostgreSQL 설정

- `pg_stat_statements`로 total time, mean time, calls 상위 SQL을 수집한다.
- `EXPLAIN (ANALYZE, BUFFERS)`는 운영 데이터 복제본에서 실행한다.
- connection 수는 `API 인스턴스 수 × Hikari max + worker/admin 여유`가 DB 상한의 70~80% 안에 들도록 계산한다.
- `shared_buffers`, `work_mem`, `max_connections`는 Postgres 컨테이너 memory budget과 실제 쿼리 동시성을 기준으로 조정한다.

### 8.9 외부 AI 경로 격리

전체 API worker가 외부 OpenAI 응답을 기다리며 묶이지 않도록 AI 호출에 별도 경계를 둔다.

1. bounded executor와 전역 semaphore
2. 사용자별 동시 호출 제한
3. bounded queue와 queue 대기 시간 metric
4. connect/read/call timeout 분리
5. circuit breaker
6. 429·5xx에만 제한된 jitter retry
7. queue 포화 시 빠른 429/503과 재시도 안내
8. cache async store도 공용 ForkJoinPool 대신 전용 executor 사용

외부 AI 시험은 전체 서버 RPS와 분리해 cache hit, cache miss, 동시 1/2/4/8/16 요청으로 측정한다. 일반 API p95가 AI 동시성 증가에 따라 변하지 않는 것이 합격 기준이다.

### 8.10 배포 파이프라인과 인스턴스 선택

현재 EC2가 서비스 운영과 이미지 build를 함께 수행한다. 개선 후 흐름은 다음과 같다.

```text
GitHub Actions test/build
→ API/Web/XGB 이미지 또는 정적 산출물 생성
→ registry/S3 업로드
→ EC2는 pull + compose up
→ health 확인 후 이전 이미지 정리
```

이렇게 하면 배포 중 Gradle/Vite/Docker build가 DB와 API CPU·메모리를 경쟁하지 않는다. load test 시간에는 배포 workflow를 실행하지 않는다.

인스턴스 변경은 병목에 맞춘다.

- 메모리 포화가 먼저면 `t3.large`로 테스트 기간 임시 확대해 4 GB와 8 GB 차이를 비교한다.
- CPU credit와 CPU 포화가 먼저면 고정 성능 계열(`m7i.large` 등)을 같은 테스트로 비교한다.
- 인스턴스만 키워도 DB·Redis·API의 장애 도메인이 하나라는 점은 유지된다.

### 8.11 구조 분리 로드맵

1. 정적 SPA를 S3 + CloudFront OAC로 이전
2. PostgreSQL을 RDS PostgreSQL로 이전
3. Redis를 ElastiCache로 이전
4. API를 ALB 뒤 최소 2개 EC2 또는 ECS task로 배치
5. scheduler/worker를 web-facing API와 분리하고 단일 실행 보장
6. Mailpit을 운영 경로에서 제거하고 외부 메일 서비스 사용
7. RabbitMQ를 별도 관리형 서비스 또는 전용 호스트로 분리
8. API task 수 × Hikari pool이 DB connection 상한을 넘으면 RDS Proxy 검토

목표 구조에서는 API task 한 개 종료, rolling deployment, Redis/DB 장애를 정상 부하 중 별도 시험한다. 안정 처리량 60~70%에서 4시간 Soak를 수행하고, 그 뒤 Spike와 failover를 실행한다.

## 9. 팀 역할과 시험 산출물

### 필수 역할

- **Test operator**: k6 실행, 단계 상승, raw 결과 보존
- **AWS observer**: EC2/CloudWatch/CPU credit/디스크 감시, 복구 실행
- **Application observer**: API 로그, JVM/GC/Hikari 지표 감시
- **Stop owner**: 중단 기준 확인과 실행 중단 판단

한 사람이 여러 역할을 맡을 수 있다. 1시간 시험 전에는 Test operator와 AWS 내부 지표 담당자를 지정한다.

### 실행별 보존 항목

- 실행 ID, 시작/종료 KST, Git SHA, 대상 URL
- k6 script SHA와 traffic mix
- 단계별 실제 RPS, VU, p50/p95/p99/max
- endpoint별 latency/오류/상태코드
- dropped iterations와 재시도/refresh 수
- EC2 CPU/memory/network/EBS/credit
- 컨테이너 CPU/memory/restart count
- JVM heap/GC/thread와 Hikari active/idle/pending
- 테스트 중 배포·cron·장애 이벤트
- 중단 여부와 정확한 사유

## 10. 실행 순서

1. P0 체크리스트와 백업을 완료한다.
2. 현재 배포 상태 그대로 1시간 2→80 RPS 기준선을 측정한다.
3. 안정 처리량과 첫 병목을 확정한다.
4. P1 변경을 하나의 변수 단위로 적용한다.
5. 동일한 1시간 테스트로 전후를 비교한다.
6. 개선 후 안정 처리량의 60~70%에서 1시간 Soak를 수행한다.
7. 누수 신호가 없으면 최종 연구용 Soak를 4시간으로 연장한다.
8. 이후에만 100 RPS 이상 Capacity와 Spike를 승인한다.

## 참고 자료

- [AWS EC2 burstable unlimited mode](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/burstable-performance-instances-unlimited-mode-concepts.html)
- [AWS EC2 automatic recovery](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-instance-recover.html)
- [CloudFront compressed files](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/ServingCompressedFiles.html)
- [CloudFront cache policy](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/controlling-the-cache-key.html)
- [Spring Boot Actuator metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [k6 ramping arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/)
- [k6 dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/)
