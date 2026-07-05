# Build Chat 성능 계측 노트

Build Chat 성능 최적화 판단의 **근거 기록**이다. 외부 성능 리서치가 여러 최적화를 제안했을 때, 코드 구조뿐 아니라 "실제로 그 경로에 얼마나 도달하는지"를 계측으로 확인해 우선순위를 정한 결과를 남긴다.

최종 갱신: 2026-07-05 (PR #57~#60 반영 main 기준)

## 1. 측정 방법

`BuildChatService.logBuildChatPath(...)`가 요청마다 `pathType`과 `latencyMs`, 단계별 시간(`redisMs/tierMs/deterministicMs/semanticMs/engineMs`)을 INFO 로그로 남긴다. 대표 프롬프트 세트를 API로 호출한 뒤 로그를 집계한다.

```bash
# 콜드: Redis flush 후 대표 세트 1회씩 호출
docker exec buildgraph-redis redis-cli FLUSHDB
# ... POST /api/ai/build-chat 반복 ...
docker logs buildgraph-api --since 90s 2>&1 | grep -oE "pathType=[A-Z_]+" | sort | uniq -c | sort -rn
docker logs buildgraph-api --since 90s 2>&1 | grep -oE "latencyMs=[0-9]+" | grep -oE "[0-9]+" | sort -n
```

대표 세트(28건)는 예산 명확 / 용도-only / 미지원 / 명확화 / 애매한 자연어 / 드래프트 완성·시뮬레이션을 고루 포함한다.

## 2. 실측 결과 (2026-07-05, 28건 세트)

| pathType | 콜드 | 웜(동일 재요청) | 비고 |
| --- | --- | --- | --- |
| CACHE_HIT | 0 | 15 | 예산·용도 추천이 exact cache로 흡수 |
| FAST_DETERMINISTIC | 7 | 1 | 그리디 예산/용도 조합 |
| FAST_CLARIFICATION | 6 | 9 | 되묻기(캐시 안 함, 항상 fast) |
| FAST_UNSUPPORTED | 5 | 6 | 고정 안내(항상 fast) |
| FAST_TIER_SNAPSHOT | 5 | 0(캐시 흡수) | 인메모리 티어 |
| FAST_SIMULATION | 2 | 4 | 부품 교체 비교 |
| FAST_DRAFT_COMPLETION | 1 | 1 | 드래프트 완성 |
| **LLM_FULL** | **2 (7%)** | **1 (3.5%)** | 애매한 자연어만 |
| **SEMANTIC_CACHE_HIT** | **0** | **0** | 아무도 도달 안 함 |

- 콜드에서도 **93%가 밀리초 fast path**(deterministic/tier/unsupported/clarification/simulation/completion).
- 웜 라운드 **latency median 1ms**, LLM 1건만 4초.
- **semantic cache 경로 도달 0건** (콜드·웜 모두). exact cache가 반복 요청을 전부 흡수하므로 semantic까지 갈 일이 없다.

## 3. 외부 리서치 제안별 판단

성능 리서치(PR #57~#59 반영 감사)가 제안한 항목을 실측 기준으로 정리한다.

| 제안 | 코드상 사실? | 실측 근거 | 판단 |
| --- | --- | --- | --- |
| 프론트 eager draft fetch 제거 (P0-1) | ✅ `enabled: hasToken`이라 패널 닫혀도 draft 선행 | 전역 렌더라 로그인 시 전 페이지 발생 | **반영함 (PR #60)**. `enabled: hasToken && open` |
| semantic cache 임베딩 선행 경량화 (P0-2) | ✅ lookup이 embed 먼저 호출 | **도달 0건**. exact cache가 반복 흡수 | **보류**. 도달 빈도 0, 손볼 실익 없음 |
| simulation exact cache (P0-3) | ✅ 매번 DB/Tool 재조회 | 웜 4건, 이미 수십 ms | **보류**. 절대량 작고 이미 빠름 |
| background executor 분리 (P0-4) | ✅ 공용 runAsync | 부하 없음 | **보류**. 부하 테스트 단계 항목 |
| pathType/stage 계측 표준화 | 부분(로그엔 있음, 벤치 assert 없음) | — | **부분 반영**. 이 노트가 로그 집계 절차를 문서화 |
| tier 범위 확대 | ✅ 200만~1300만 | 고예산도 그리디 콜드 ~29ms | **보류**. 티어 밖도 이미 빠름 |

## 3.5. 렌더링 병목과 JVM 콜드 스타트 (실측 발견)

정적 코드 감사로는 안 보이고 실제 브라우저 계측으로만 드러난 두 가지.

### 챗봇 메시지 리스트 리렌더 (해결됨)

- 증상: "API 응답은 빠른데 화면 띄우는 게 1초+ 늦다". 브라우저 실측 — API 왕복 6~81ms인데 대화 메시지가 쌓이면 새 메시지 하나 추가에 렌더가 느려지고, 30개(카드 90개)에서 렌더러가 수 초 프리즈.
- 원인: 새 메시지 추가 시 기존 모든 메시지+카드를 전부 리렌더. 세션 저장→`syncSession`이 메시지 객체를 매번 새로 만들어 참조 비교로는 memo가 무효.
- 수정: `ChatMessage`를 message.id 기반 comparator memo로 감싸고 `selectBuild`를 `useCallback`으로 안정화. 새 메시지만 렌더(O(n)→O(1)).
- 실측 확증(user 계정 실제 로그인): 메시지 15개 상태에서 새 메시지 추가 **12ms, long task 0**. 90개(270카드)는 브라우저 DOM 한계라 별개 — 실사용에선 안 쌓임.

### JVM 콜드 스타트 (운영으로 대응)

- 증상: 서버 재시작 직후 첫 build-chat 요청이 느리고, 두 번째부터 빠름.
- 원인: JVM 콜드 스타트(클래스 로딩 + JIT 컴파일). API 재시작 직후 수십 초간은 완전히 데워지지 않는다. 프리웜(12개 예산 프롬프트)이 무거운 경로(예산 조합 그리디)를 워밍하지만, 프리웜 자체가 콜드에서 돌아 첫 몇 요청은 여전히 JIT 전이다. warm 후엔 프리웜에 없는 새 문구도 첫 요청 7~23ms.
- 판단: **프리웜 경로 확대는 보류.** 무거운 경로는 이미 프리웜+티어가 커버하고, 나머지(unsupported/clarification)는 고정 응답이라 콜드여도 빠르다. 프리웜으로 콜드 스타트를 0으로 만들 수 없다(프리웜도 콜드에서 돎).
- **운영 대응**:
  - 데모 전 서버를 **미리 켜둔다**(JVM warm + 프리웜 완료까지 최소 1~2분). 재빌드/재시작 직후 바로 데모하지 않는다.
  - 프론트만 수정할 땐 `docker compose up --build -d web`(web만)으로 재빌드한다. `depends_on: api` 때문에 전체/api 재빌드는 API도 재시작시켜 JVM을 콜드로 되돌린다.

## 4. 결론과 재검토 트리거

**현재 서버는 손댈 곳이 없다.** 93% fast path, semantic 0 도달, 웜 median 1ms. 프론트 eager fetch(PR #60)까지 반영하면 체감 latency도 마무리된다.

리서치의 나머지 서버 최적화는 **실사용 트래픽이 아래 분포를 바꿀 때** 재측정 후 판단한다.

- **애매한 자연어 요청 비율이 급증** → LLM_FULL/semantic 도달이 늘면 semantic 경량화(P0-2)가 의미를 가진다.
- **동시 요청 부하 발생** → executor 분리(P0-4)가 p95/p99에 영향을 준다.
- **같은 드래프트로 시뮬레이션 반복이 잦아짐** → simulation cache(P0-3) ROI 상승.

추측으로 미리 최적화하지 않고, `pathType` 로그를 주기적으로 집계해 분포가 바뀌면 이 노트를 갱신한다.
