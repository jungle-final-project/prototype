# Build Chat AI Profile Benchmark

- generatedAt: 2026-07-01T19:00:44
- totalCases: 4

## Summary

| variant | profile | successRate | avgLatencyMs | p95LatencyMs | schemaValidRate |
|---|---|---:|---:|---:|---:|
| vector-on-smoke | BUILD_CHAT_FAST | 100.0% | 5868 | 6225 | 100.0% |
| vector-on-smoke | BUILD_CHAT_54_MINI_FAST | 100.0% | 3805 | 3834 | 100.0% |

## Cases

| variant | profile | case | ok | latencyMs | answerType | builds | actions | hardConstraint | warningOk | error |
|---|---|---|---:|---:|---|---:|---|---:|---:|---|
| vector-on-smoke | BUILD_CHAT_FAST | qhd-gaming-budget | yes | 6225 | BUDGET | 3 | - | yes | yes |  |
| vector-on-smoke | BUILD_CHAT_FAST | rtx-5090-hard-constraint | yes | 5510 | BUDGET | 3 | - | yes | yes |  |
| vector-on-smoke | BUILD_CHAT_54_MINI_FAST | qhd-gaming-budget | yes | 3834 | BUDGET | 3 | - | yes | yes |  |
| vector-on-smoke | BUILD_CHAT_54_MINI_FAST | rtx-5090-hard-constraint | yes | 3776 | BUDGET | 3 | - | yes | yes |  |

## Notes

- 이 벤치마크는 UI를 변경하지 않고 `/api/ai/build-chat`의 optional profile header만 바꿔 실행한다.
- 기본 서비스 profile은 별도 gate를 통과하기 전까지 `BUILD_CHAT_FAST`로 유지한다.
- 5090 같은 명시 부품 조건은 추천 build의 GPU item에 보존되어야 한다.
