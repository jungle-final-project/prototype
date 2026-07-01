# AS Chat AI Profile Benchmark

- generatedAt: 2026-07-01T19:00:10
- totalCases: 2

## Summary

| variant | profile | provider | successRate | avgFirstEventMs | avgRagReadyMs | avgToolsReadyMs | avgLlmOnlyMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| vector-on-smoke | AS_CHAT_FAST | openai | 100.0% | 13 | 3058 | 3077 | 9378 | 13629 | 13629 | 1221 | 481 | 1702 | 100.0% | 100.0% | 0.0 |
| vector-on-smoke | AS_CHAT_54_MINI_FAST | openai | 100.0% | 20 | 273 | 280 | 4829 | 5614 | 5614 | 1267 | 663 | 1930 | 100.0% | 100.0% | 0.0 |

## Cases

| variant | profile | provider | case | risk | ok | firstEventMs | ragReadyMs | toolsReadyMs | llmOnlyMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| vector-on-smoke | AS_CHAT_FAST | openai | gpu-thermal-frame-drop | medium | yes | 13 | 3058 | 3077 | 9378 | 13629 | gpt-5.5 | 1221 | 481 | 1702 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| vector-on-smoke | AS_CHAT_54_MINI_FAST | openai | gpu-thermal-frame-drop | medium | yes | 20 | 273 | 280 | 4829 | 5614 | gpt-5.4-mini | 1267 | 663 | 1930 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |

## Selection Notes

- 기본 profile 후보는 schema valid 100%, 성공률 95% 이상을 먼저 만족해야 한다.
- 근거 없는 단정 카운트가 0인 profile을 우선한다.
- cause candidate가 RAG evidence 또는 Tool invocation을 참조하는 비율을 grounded evidence rate로 본다.
- 첫 진행 이벤트 평균이 1초 이하인 profile을 우선한다.
- 평균 응답 시간이 10초 이하인 profile을 우선한다.
- p95 응답 시간이 20초를 넘으면 사용자 체감상 감점한다.
- 품질 차이가 작으면 더 빠른 profile을 선택한다.
- RAG vector on/off 비교는 같은 profile과 case를 두 번 실행하고 `--variant-label vector-on|vector-off`로 구분한다.
- benchmark 명령은 기본적으로 보고서 생성을 성공으로 본다. 전체 통과를 CI gate로 강제하려면 `--strict`를 사용한다.
