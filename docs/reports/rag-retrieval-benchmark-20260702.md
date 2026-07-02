# RAG Retrieval Benchmark

- generatedAt: 2026-07-02T10:33:27
- distinctCases: 190
- variants: 2
- totalRows: 380
- endpoint: `GET /api/rag/search`

## Summary

| variant | purpose | cases | top1HitRate | topKHitRate | avgLatencyMs | p95LatencyMs | avgResults |
|---|---|---:|---:|---:|---:|---:|---:|
| vector-on | REQUIREMENT_PARSE | 90 | 43.3% | 71.1% | 311 | 430 | 10.0 |
| vector-on | BUILD_RECOMMEND | 20 | 65.0% | 80.0% | 290 | 391 | 9.0 |
| vector-on | BUILD_EXPLAIN | 10 | 100.0% | 100.0% | 371 | 505 | 3.0 |
| vector-on | AS_ANALYZE | 50 | 68.0% | 92.0% | 347 | 758 | 6.0 |
| vector-on | PUBLIC_SEARCH | 20 | 80.0% | 90.0% | 299 | 394 | 10.0 |
| vector-off | REQUIREMENT_PARSE | 90 | 0.0% | 0.0% | 13 | 31 | 0.0 |
| vector-off | BUILD_RECOMMEND | 20 | 0.0% | 0.0% | 11 | 27 | 0.0 |
| vector-off | BUILD_EXPLAIN | 10 | 0.0% | 0.0% | 14 | 28 | 0.0 |
| vector-off | AS_ANALYZE | 50 | 2.0% | 2.0% | 13 | 28 | 0.0 |
| vector-off | PUBLIC_SEARCH | 20 | 0.0% | 0.0% | 11 | 27 | 0.3 |

## Cases

| variant | purpose | case | top1 | topK | latencyMs | k | count | modes | topSources | error |
|---|---|---|---:|---:|---:|---:|---:|---|---|---|
| vector-on | REQUIREMENT_PARSE | req-001 | no | yes | 267 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-002 | yes | yes | 264 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-003 | no | no | 250 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-004 | yes | yes | 266 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-005 | yes | yes | 364 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-006 | no | yes | 272 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-007 | yes | yes | 359 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-008 | no | yes | 310 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-009 | no | no | 281 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-010 | no | no | 316 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-011 | no | no | 294 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-012 | no | yes | 288 | 3 | 10 | VECTOR | requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-013 | yes | yes | 323 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, guide-requirement-parse-budget-resolution-workload |  |
| vector-on | REQUIREMENT_PARSE | req-014 | no | no | 248 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-015 | no | yes | 365 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-016 | yes | yes | 417 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-017 | yes | yes | 345 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-018 | yes | yes | 263 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-019 | yes | yes | 298 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-020 | yes | yes | 367 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-021 | yes | yes | 163 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-022 | yes | yes | 309 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-023 | yes | yes | 405 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-024 | no | yes | 369 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-025 | yes | yes | 298 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-026 | yes | yes | 292 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-027 | yes | yes | 304 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-028 | yes | yes | 268 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-029 | no | yes | 275 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-030 | no | yes | 287 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-031 | no | yes | 430 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-032 | yes | yes | 302 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-033 | yes | yes | 290 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-034 | yes | yes | 243 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-035 | no | yes | 279 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-036 | yes | yes | 288 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-037 | no | no | 302 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-038 | yes | yes | 757 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-039 | yes | yes | 402 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-040 | yes | yes | 322 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-041 | yes | yes | 279 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-042 | no | yes | 302 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-043 | no | no | 408 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-044 | no | no | 290 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-045 | no | no | 281 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-noise-upgrade-brand, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-046 | no | no | 294 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-047 | no | yes | 376 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-048 | no | yes | 273 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-049 | no | no | 255 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-050 | no | no | 432 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, guide-requirement-parse-budget-resolution-workload, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-051 | no | yes | 254 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-052 | no | no | 339 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-053 | no | yes | 722 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-054 | no | yes | 250 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-055 | yes | yes | 390 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-056 | yes | yes | 142 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-057 | no | no | 268 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-058 | yes | yes | 269 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-059 | yes | yes | 375 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-060 | yes | yes | 441 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-061 | no | no | 323 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-062 | no | yes | 243 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-063 | yes | yes | 340 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-064 | no | yes | 377 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-counterexample-premium-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-065 | no | no | 239 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-066 | no | yes | 258 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-067 | yes | yes | 303 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, requirement-rule-explicit-gpu-class-hard-constraint, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-068 | no | yes | 284 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-noise-upgrade-brand, benchmark-requirement-parse-gaming-development |  |
| vector-on | REQUIREMENT_PARSE | req-069 | no | no | 302 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-070 | no | no | 413 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-071 | no | no | 163 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-072 | no | yes | 325 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-073 | yes | yes | 302 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-074 | yes | yes | 147 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai |  |
| vector-on | REQUIREMENT_PARSE | req-075 | yes | yes | 279 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, requirement-example-gaming-resolution-refresh |  |
| vector-on | REQUIREMENT_PARSE | req-076 | no | yes | 308 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-noise-upgrade, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-077 | no | no | 238 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-078 | no | no | 391 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-079 | no | yes | 317 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-080 | yes | yes | 286 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-081 | yes | yes | 304 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-082 | no | yes | 234 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, requirement-example-workload-mixed-creator-ai, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-083 | no | no | 296 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget |  |
| vector-on | REQUIREMENT_PARSE | req-084 | yes | yes | 259 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-085 | yes | yes | 299 | 3 | 10 | VECTOR | requirement-example-workload-mixed-creator-ai, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-086 | no | no | 270 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, benchmark-requirement-parse-gaming-development, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | REQUIREMENT_PARSE | req-087 | no | no | 323 | 3 | 10 | VECTOR | requirement-counterexample-explicit-gpu-with-user-budget, requirement-example-gaming-resolution-refresh, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | REQUIREMENT_PARSE | req-088 | no | yes | 259 | 3 | 10 | VECTOR | requirement-example-gaming-resolution-refresh, internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand |  |
| vector-on | REQUIREMENT_PARSE | req-089 | no | no | 315 | 3 | 10 | VECTOR | requirement-example-noise-upgrade-brand, internal-rule-requirement-parse-premium-open-budget, internal-rule-requirement-parse-noise-upgrade |  |
| vector-on | REQUIREMENT_PARSE | req-090 | no | no | 232 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-example-gaming-resolution-refresh |  |
| vector-on | BUILD_RECOMMEND | build-001 | yes | yes | 258 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-002 | yes | yes | 256 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-003 | yes | yes | 252 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-004 | yes | yes | 241 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, internal-rule-psu-atx31-power-margin |  |
| vector-on | BUILD_RECOMMEND | build-005 | yes | yes | 274 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-006 | no | yes | 302 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-007 | yes | yes | 282 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-008 | yes | yes | 331 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-on | BUILD_RECOMMEND | build-009 | yes | yes | 124 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, part-catalog-rtx50-tool-ready-dimensions, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-010 | yes | yes | 258 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-011 | no | no | 277 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-saved-price-and-psu-headroom |  |
| vector-on | BUILD_RECOMMEND | build-012 | no | no | 280 | 3 | 9 | VECTOR | part-spec-rtx-5090-class, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-013 | no | no | 310 | 3 | 9 | VECTOR | build-rule-hard-gpu-class-selection, build-rule-saved-price-and-psu-headroom, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-014 | no | no | 335 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions, build-rule-memory-storage-workload-floor |  |
| vector-on | BUILD_RECOMMEND | build-015 | yes | yes | 260 | 3 | 9 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, build-rule-saved-price-and-psu-headroom, part-catalog-rtx50-tool-ready-dimensions |  |
| vector-on | BUILD_RECOMMEND | build-016 | yes | yes | 391 | 3 | 9 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, build-rule-hard-gpu-class-selection |  |
| vector-on | BUILD_RECOMMEND | build-017 | yes | yes | 252 | 3 | 9 | VECTOR | build-rule-memory-storage-workload-floor, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | BUILD_RECOMMEND | build-018 | no | yes | 305 | 3 | 9 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, part-spec-rtx-5090-class |  |
| vector-on | BUILD_RECOMMEND | build-019 | no | yes | 417 | 3 | 9 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, part-spec-rtx-5090-class, build-rule-airflow-cooler-case-fit |  |
| vector-on | BUILD_RECOMMEND | build-020 | yes | yes | 385 | 3 | 9 | VECTOR | build-rule-saved-price-and-psu-headroom, build-rule-hard-gpu-class-selection, part-spec-rtx-5090-class |  |
| vector-on | BUILD_EXPLAIN | explain-001 | yes | yes | 505 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-002 | yes | yes | 301 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-003 | yes | yes | 242 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-004 | yes | yes | 374 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-005 | yes | yes | 429 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance |  |
| vector-on | BUILD_EXPLAIN | explain-006 | yes | yes | 241 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-007 | yes | yes | 277 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first, benchmark-guide-ram-video-dev-floor |  |
| vector-on | BUILD_EXPLAIN | explain-008 | yes | yes | 402 | 3 | 3 | VECTOR | internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-009 | yes | yes | 505 | 3 | 3 | VECTOR | benchmark-guide-ram-video-dev-floor, internal-rule-case-gpu-clearance, price-guide-saved-snapshot-first |  |
| vector-on | BUILD_EXPLAIN | explain-010 | yes | yes | 438 | 3 | 3 | VECTOR | price-guide-saved-snapshot-first, internal-rule-case-gpu-clearance, benchmark-guide-ram-video-dev-floor |  |
| vector-on | AS_ANALYZE | as-001 | yes | yes | 1924 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | AS_ANALYZE | as-002 | yes | yes | 373 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-003 | yes | yes | 300 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-004 | yes | yes | 290 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-005 | yes | yes | 375 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-006 | no | yes | 306 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-007 | no | no | 141 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-008 | yes | yes | 758 | 3 | 6 | VECTOR | support-guide-airflow-upgrade-before-gpu, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-009 | no | yes | 316 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-010 | yes | yes | 266 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-011 | yes | yes | 242 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-012 | yes | yes | 248 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-013 | yes | yes | 293 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-014 | yes | yes | 261 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-015 | yes | yes | 539 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-016 | yes | yes | 311 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-017 | yes | yes | 135 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-018 | yes | yes | 284 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-019 | yes | yes | 293 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-020 | yes | yes | 261 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-driver-crash-event-log, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-021 | yes | yes | 299 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-022 | yes | yes | 263 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-023 | yes | yes | 292 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-024 | no | yes | 258 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-025 | no | yes | 244 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-026 | no | yes | 267 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-027 | yes | yes | 313 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-028 | yes | yes | 256 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-029 | yes | yes | 293 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-power-instability, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-030 | no | no | 996 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-031 | no | no | 285 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-032 | no | yes | 263 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-033 | no | yes | 260 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-memory-storage-pressure, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-034 | no | yes | 295 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-035 | yes | yes | 259 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-036 | no | yes | 242 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-037 | yes | yes | 267 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-038 | yes | yes | 242 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-039 | yes | yes | 269 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop |  |
| vector-on | AS_ANALYZE | as-040 | yes | yes | 372 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-driver-crash-event-log, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-041 | yes | yes | 287 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-042 | yes | yes | 420 | 3 | 6 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-043 | no | no | 307 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-044 | yes | yes | 255 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-memory-storage-pressure, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-045 | yes | yes | 407 | 3 | 6 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-power-instability |  |
| vector-on | AS_ANALYZE | as-046 | no | yes | 393 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | AS_ANALYZE | as-047 | yes | yes | 293 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-048 | no | yes | 252 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-049 | no | yes | 293 | 3 | 6 | VECTOR | as-guide-memory-storage-pressure, as-guide-power-instability, as-guide-driver-crash-event-log |  |
| vector-on | AS_ANALYZE | as-050 | yes | yes | 277 | 3 | 6 | VECTOR | as-guide-power-instability, as-guide-gpu-thermal-frame-drop, as-guide-driver-crash-event-log |  |
| vector-on | PUBLIC_SEARCH | public-001 | yes | yes | 357 | 3 | 10 | VECTOR | part-spec-rtx-5090-class, build-rule-hard-gpu-class-selection, requirement-rule-explicit-gpu-class-hard-constraint |  |
| vector-on | PUBLIC_SEARCH | public-002 | yes | yes | 288 | 3 | 10 | VECTOR | internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-premium-with-user-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-003 | yes | yes | 429 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu, build-rule-hard-gpu-class-selection |  |
| vector-on | PUBLIC_SEARCH | public-004 | yes | yes | 268 | 3 | 10 | VECTOR | internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom, internal-rule-case-gpu-clearance |  |
| vector-on | PUBLIC_SEARCH | public-005 | yes | yes | 287 | 3 | 10 | VECTOR | internal-rule-case-gpu-clearance, part-catalog-rtx50-tool-ready-dimensions, build-rule-hard-gpu-class-selection |  |
| vector-on | PUBLIC_SEARCH | public-006 | yes | yes | 249 | 3 | 10 | VECTOR | benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-007 | yes | yes | 284 | 3 | 10 | VECTOR | as-guide-gpu-thermal-frame-drop, support-guide-gpu-thermal-frame-drop, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-008 | yes | yes | 283 | 3 | 10 | VECTOR | as-guide-driver-crash-event-log, as-guide-gpu-thermal-frame-drop, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-009 | yes | yes | 271 | 3 | 10 | VECTOR | as-guide-memory-storage-pressure, benchmark-guide-ram-video-dev-floor, build-rule-memory-storage-workload-floor |  |
| vector-on | PUBLIC_SEARCH | public-010 | yes | yes | 249 | 3 | 10 | VECTOR | as-guide-power-instability, internal-rule-psu-atx31-power-margin, build-rule-saved-price-and-psu-headroom |  |
| vector-on | PUBLIC_SEARCH | public-011 | no | no | 279 | 3 | 10 | VECTOR | build-rule-hard-gpu-class-selection, internal-rule-build-qhd-gaming-gpu-priority, build-rule-cpu-gpu-balance-and-bottleneck |  |
| vector-on | PUBLIC_SEARCH | public-012 | no | yes | 298 | 3 | 10 | VECTOR | build-rule-memory-storage-workload-floor, benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai |  |
| vector-on | PUBLIC_SEARCH | public-013 | no | yes | 276 | 3 | 10 | VECTOR | internal-rule-build-qhd-gaming-gpu-priority, requirement-example-gaming-resolution-refresh, support-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-014 | yes | yes | 394 | 3 | 10 | VECTOR | price-guide-saved-snapshot-first, build-rule-saved-price-and-psu-headroom, as-guide-memory-storage-pressure |  |
| vector-on | PUBLIC_SEARCH | public-015 | yes | yes | 361 | 3 | 10 | VECTOR | part-catalog-rtx50-tool-ready-dimensions, internal-rule-psu-atx31-power-margin, part-spec-rtx-5090-class |  |
| vector-on | PUBLIC_SEARCH | public-016 | yes | yes | 328 | 3 | 10 | VECTOR | build-rule-cpu-gpu-balance-and-bottleneck, internal-rule-build-qhd-gaming-gpu-priority, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-017 | yes | yes | 296 | 3 | 10 | VECTOR | support-guide-airflow-upgrade-before-gpu, support-guide-gpu-thermal-frame-drop, as-guide-gpu-thermal-frame-drop |  |
| vector-on | PUBLIC_SEARCH | public-018 | no | no | 265 | 3 | 10 | VECTOR | requirement-counterexample-premium-with-user-budget, internal-rule-requirement-parse-premium-open-budget, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-on | PUBLIC_SEARCH | public-019 | yes | yes | 252 | 3 | 10 | VECTOR | internal-rule-requirement-parse-noise-upgrade, requirement-example-noise-upgrade-brand, support-guide-airflow-upgrade-before-gpu |  |
| vector-on | PUBLIC_SEARCH | public-020 | yes | yes | 262 | 3 | 10 | VECTOR | benchmark-requirement-parse-gaming-development, requirement-example-workload-mixed-creator-ai, internal-rule-build-qhd-gaming-gpu-priority |  |
| vector-off | REQUIREMENT_PARSE | req-001 | no | no | 33 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-002 | no | no | 17 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-003 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-004 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-005 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-006 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-007 | no | no | 7 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-008 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-009 | no | no | 32 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-010 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-011 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-012 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-013 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-014 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-015 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-016 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-017 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-018 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-019 | no | no | 9 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-020 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-021 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-022 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-023 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-024 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-025 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-026 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-027 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-028 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-029 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-030 | no | no | 21 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-031 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-032 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-033 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-034 | no | no | 21 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-035 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-036 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-037 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-038 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-039 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-040 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-041 | no | no | 31 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-042 | no | no | 30 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-043 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-044 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-045 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-046 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-047 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-048 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-049 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-050 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-051 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-052 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-053 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-054 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-055 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-056 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-057 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-058 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-059 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-060 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-061 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-062 | no | no | 9 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-063 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-064 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-065 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-066 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-067 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-068 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-069 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-070 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-071 | no | no | 18 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-072 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-073 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-074 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-075 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-076 | no | no | 23 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-077 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-078 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-079 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-080 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-081 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-082 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-083 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-084 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-085 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-086 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-087 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-088 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-089 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | REQUIREMENT_PARSE | req-090 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-001 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-002 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-003 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-004 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-005 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-006 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-007 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-009 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-011 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-012 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-014 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-015 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-016 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-017 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-018 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-019 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_RECOMMEND | build-020 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-001 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-002 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-003 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-004 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-005 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-006 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-007 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-009 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | BUILD_EXPLAIN | explain-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-001 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-002 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-003 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-004 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-005 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-006 | no | no | 19 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-007 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-008 | no | no | 29 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-009 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-011 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-012 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-014 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-015 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-016 | no | no | 26 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-017 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-018 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-019 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-020 | no | no | 13 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-021 | yes | yes | 11 | 3 | 1 | - | as-guide-power-instability |  |
| vector-off | AS_ANALYZE | as-022 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-023 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-024 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-025 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-026 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-027 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-028 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-029 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-030 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-031 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-032 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-033 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-034 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-035 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-036 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-037 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-038 | no | no | 28 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-039 | no | no | 5 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-040 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-041 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-042 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-043 | no | no | 14 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-044 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-045 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-046 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-047 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-048 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-049 | no | no | 20 | 3 | 0 | - | - |  |
| vector-off | AS_ANALYZE | as-050 | no | no | 16 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-001 | no | no | 22 | 3 | 6 | - | requirement-rule-explicit-gpu-class-hard-constraint, build-rule-hard-gpu-class-selection, requirement-counterexample-explicit-gpu-with-user-budget |  |
| vector-off | PUBLIC_SEARCH | public-002 | no | no | 25 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-003 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-004 | no | no | 15 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-005 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-006 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-007 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-008 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-009 | no | no | 24 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-010 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-011 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-012 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-013 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-014 | no | no | 6 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-015 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-016 | no | no | 3 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-017 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-018 | no | no | 4 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-019 | no | no | 27 | 3 | 0 | - | - |  |
| vector-off | PUBLIC_SEARCH | public-020 | no | no | 16 | 3 | 0 | - | - |  |

## Policy Reading Guide

- `topKHitRate`가 vector-off와 2%p 미만 차이면 해당 경로는 latency를 보고 끌 후보가 된다.
- `REQUIREMENT_PARSE`와 `BUILD_RECOMMEND`는 5090, 끝판왕, 예산 표현 같은 의미 검색 실패 감소를 우선한다.
- `AS_ANALYZE`는 thermal, driver, memory, storage, power 증상 근거가 top3 안에 들어오는지를 우선한다.
- 이 보고서는 기본 env를 바꾸지 않고 다음 PR의 정책 판단 근거로만 사용한다.
