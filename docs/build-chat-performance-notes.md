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

## 4. 결론과 재검토 트리거

**현재 서버는 손댈 곳이 없다.** 93% fast path, semantic 0 도달, 웜 median 1ms. 프론트 eager fetch(PR #60)까지 반영하면 체감 latency도 마무리된다.

리서치의 나머지 서버 최적화는 **실사용 트래픽이 아래 분포를 바꿀 때** 재측정 후 판단한다.

- **애매한 자연어 요청 비율이 급증** → LLM_FULL/semantic 도달이 늘면 semantic 경량화(P0-2)가 의미를 가진다.
- **동시 요청 부하 발생** → executor 분리(P0-4)가 p95/p99에 영향을 준다.
- **같은 드래프트로 시뮬레이션 반복이 잦아짐** → simulation cache(P0-3) ROI 상승.

추측으로 미리 최적화하지 않고, `pathType` 로그를 주기적으로 집계해 분포가 바뀌면 이 노트를 갱신한다.
