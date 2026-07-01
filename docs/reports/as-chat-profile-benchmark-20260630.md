# AS Chat AI Profile Benchmark

- generatedAt: 2026-06-30T21:42:43
- totalCases: 24

## Summary

| profile | provider | successRate | avgFirstEventMs | avgFinalLatencyMs | p95FinalLatencyMs | avgInputTokens | avgOutputTokens | avgTokens | schemaValidRate | avgGroundedEvidenceRate | avgUnsupportedClaims |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| AS_CHAT_FAST | openai | 83.3% | 20 | 9664 | 11377 | 1242 | 601 | 1843 | 100.0% | 100.0% | 0.2 |
| AS_CHAT_NANO_FAST | openai | 33.3% | 9 | 6469 | 8041 | 1241 | 668 | 1909 | 33.3% | 33.3% | 0.0 |
| AS_CHAT_BALANCED | openai | 100.0% | 15 | 10998 | 12936 | 1350 | 833 | 2183 | 100.0% | 100.0% | 0.0 |
| AS_CHAT_HIGH_QUALITY | openai | 83.3% | 6 | 15168 | 17212 | 1874 | 1144 | 3018 | 100.0% | 100.0% | 0.2 |

## Cases

| profile | provider | case | risk | ok | firstEventMs | finalLatencyMs | model | inTok | outTok | tokens | evidence | tools | actions | keywords | grounded | unsupported | failureType | error |
|---|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|
| AS_CHAT_FAST | openai | gpu-thermal-frame-drop | medium | yes | 18 | 9946 | gpt-5.5 | 1248 | 597 | 1845 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | driver-crash-event-log | medium | yes | 29 | 11377 | gpt-5.5 | 1252 | 619 | 1871 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | memory-pressure | low | no | 26 | 10591 | gpt-5.5 | 1249 | 609 | 1858 | 2 | 3 | 2 | 3/3 | 100% | 1 | - |  |
| AS_CHAT_FAST | openai | storage-bottleneck | low | yes | 21 | 7839 | gpt-5.5 | 1243 | 545 | 1788 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | power-instability | high | yes | 4 | 8433 | gpt-5.5 | 1237 | 591 | 1828 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_FAST | openai | mixed-thermal-driver | high | yes | 20 | 9797 | gpt-5.5 | 1224 | 643 | 1867 | 2 | 3 | 2 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | gpu-thermal-frame-drop | medium | yes | 14 | 5935 | gpt-5.4-nano | 1256 | 680 | 1936 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | driver-crash-event-log | medium | no | - | 5454 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| AS_CHAT_NANO_FAST | openai | memory-pressure | low | no | - | 6250 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| AS_CHAT_NANO_FAST | openai | storage-bottleneck | low | yes | 4 | 6268 | gpt-5.4-nano | 1226 | 656 | 1882 | 2 | 3 | 2 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_NANO_FAST | openai | power-instability | high | no | - | 8041 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| AS_CHAT_NANO_FAST | openai | mixed-thermal-driver | high | no | - | 6864 | - | - | - | - | 0 | 0 | 0 | 0/3 | 0% | 0 | schema | POST /api/ai/as-chat/stream failed: {'type': 'ResponseStatusException', 'message': '502 BAD_GATEWAY "LLM이 JSON 계약을 지키지 않았습니다."'} |
| AS_CHAT_BALANCED | openai | gpu-thermal-frame-drop | medium | yes | 3 | 12518 | gpt-5.5 | 1342 | 989 | 2331 | 3 | 3 | 3 | 2/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | driver-crash-event-log | medium | yes | 21 | 9846 | gpt-5.5 | 1335 | 780 | 2115 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | memory-pressure | low | yes | 4 | 9759 | gpt-5.5 | 1355 | 763 | 2118 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | storage-bottleneck | low | yes | 14 | 8407 | gpt-5.5 | 1358 | 700 | 2058 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | power-instability | high | yes | 28 | 12525 | gpt-5.5 | 1353 | 834 | 2187 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_BALANCED | openai | mixed-thermal-driver | high | yes | 21 | 12936 | gpt-5.5 | 1355 | 934 | 2289 | 3 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | gpu-thermal-frame-drop | medium | yes | 3 | 17212 | gpt-5.5 | 1859 | 1345 | 3204 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | driver-crash-event-log | medium | yes | 3 | 17208 | gpt-5.5 | 1869 | 1207 | 3076 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | memory-pressure | low | no | 23 | 14116 | gpt-5.5 | 1899 | 1030 | 2929 | 5 | 3 | 3 | 3/3 | 100% | 1 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | storage-bottleneck | low | yes | 3 | 11975 | gpt-5.5 | 1877 | 910 | 2787 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | power-instability | high | yes | 3 | 16630 | gpt-5.5 | 1893 | 1230 | 3123 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |
| AS_CHAT_HIGH_QUALITY | openai | mixed-thermal-driver | high | yes | 3 | 13870 | gpt-5.5 | 1847 | 1145 | 2992 | 5 | 3 | 3 | 3/3 | 100% | 0 | - |  |

## Selection Notes

- 기본 profile 후보는 schema valid 100%, 성공률 95% 이상을 먼저 만족해야 한다.
- 근거 없는 단정 카운트가 0인 profile을 우선한다.
- cause candidate가 RAG evidence 또는 Tool invocation을 참조하는 비율을 grounded evidence rate로 본다.
- 첫 진행 이벤트 평균이 1초 이하인 profile을 우선한다.
- 평균 응답 시간이 10초 이하인 profile을 우선한다.
- p95 응답 시간이 20초를 넘으면 사용자 체감상 감점한다.
- 품질 차이가 작으면 더 빠른 profile을 선택한다.
- benchmark 명령은 기본적으로 보고서 생성을 성공으로 본다. 전체 통과를 CI gate로 강제하려면 `--strict`를 사용한다.
