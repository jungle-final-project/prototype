# 전체 서버 부하 테스트 가이드

`infra/k6/server-workload.js`는 smoke를 제외한 다음 5종을 같은 API 혼합으로 실행한다.

| 종류 | 목적 | 로컬 프로필 |
|---|---|---|
| Load | 예상 정상 부하에서 SLO 확인 | 20 VU, 90초 |
| Stress | 동시 사용자를 단계적으로 높여 성능 저하 확인 | 20→40→60→80 VU, 120초 |
| Spike | 짧은 시간에 트래픽이 폭증했을 때 회복 확인 | 10→100 VU, 60초 |
| Soak | 장시간 반복 시 누수·고갈 징후 확인 | 20 VU, 연구 실행 60분 |
| Capacity | 일정 도착률을 높이며 처리 가능한 RPS 확인 | 50→100→200→300→400 iteration/s |

혼합 요청은 인증, 부품, 홈 추천부품/XGBoost, 견적초안, 견적 이력, 가격 알림, 조립 요청 이력, 읽기 전용 AI 위치 강조를 포함한다. 외부 OpenAI 호출은 대량 부하에서 제외해 BuildGraph API·DB·Redis·scorer의 용량을 측정하며, 실제 LLM 병렬 결과는 별도 Build Chat QA 보고서에서 관리한다.

각 VU는 독립 access/refresh token을 유지한다. 보호 API가 `401`을 반환하면 `/api/auth/refresh` 후 원 요청을 한 번만 재시도하며, refresh도 실패할 때만 로그인으로 복구한다. 15분 access token TTL보다 긴 Soak에서 setup token 하나를 계속 재사용하면 서버 내구성이 아니라 클라이언트 인증 만료를 측정하게 되므로 금지한다.

Docker Desktop 기준 실행 예시:

```powershell
docker run --rm `
  -e TEST_TYPE=load `
  -e BASE_URL=http://host.docker.internal:18082 `
  -e SUMMARY_PATH=/work/infra/k6/reports/server-load-20260711.json `
  -v "${PWD}:/work" -w /work `
  grafana/k6:0.54.0 run infra/k6/server-workload.js
```

`TEST_TYPE`을 `stress`, `spike`, `soak`, `capacity`로 바꿔 순서대로 실행한다. 5분 Soak는 설정 확인용 short baseline일 뿐 연구용 내구 결론으로 사용하지 않는다.

## 누적 연구 실행

다음 러너는 실행마다 `시각-commit` 형식의 고유 `runId`를 만들고 원시 k6 summary, 콘솔 로그, manifest, Soak JVM 자원 표본을 별도 디렉터리에 누적한다. 이전 실행 파일은 덮어쓰지 않는다.

```powershell
.\tools\run_server_load_suite.ps1 `
  -BaseUrl http://127.0.0.1:18082 `
  -SoakDuration 1h `
  -SoakVus 20 `
  -ApiLogPath .qa-results\api-18082-server-suite.out.log
```

결과 경로:

```text
infra/k6/reports/runs/{runId}/
  manifest.json
  server-load.json
  server-load.console.log
  ...
  server-soak-resources.csv
  api.log
```

한국어 종합 보고서는 다음처럼 생성한다.

```powershell
python tools\summarize_server_load_suite.py `
  --run-dir infra\k6\reports\runs\{runId} `
  --output docs\reports\server-load-suite-{runId}.md `
  --json-output docs\reports\server-load-suite-{runId}.json
```

60분 Soak는 5분 단위 12구간의 평균/p95/최대/오류율과 1분 단위 JVM 메모리를 기록한다. 운영 인증에는 같은 인프라 조건으로 2시간 이상 재실행하는 것이 적절하다.
