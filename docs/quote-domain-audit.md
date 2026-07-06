# 견적 도메인 모델 감사 — "현실과 모델의 어긋남" 전수 조사 및 수정 계획

> 배경: "램 2개들이 킷이 스틱 1개로 계산된다"(→ V94 + 슬롯 검사로 해소)와 같은 부류의 문제,
> 즉 **단순화 가정이 실제 하드웨어 현실·실데이터와 어긋나 사용자에게 틀린 판정을 주는 지점**을 전수 탐색했다.
> 방법: 7개 렌즈(저장장치/폼팩터/RAM 심화/전력·냉각/수량 의미론/데이터 커버리지/논리 완결성) 병렬 감사
> — 모든 발견은 코드 근거(file:line)와 로컬 DB 실측 수치를 요구했고, 별도 적대적 검증자가 재확인했다.
> 결과: **확정 28건, 반박 0건, 강등 3건** (2026-07-06). 중복 통합 후 아래 20개 작업 항목으로 정리.

## 우선순위 원칙

1. **틀린 PASS가 최악** — "장착 불가능한 조합이 초록불"이 "검사 안 함"보다 나쁘다. false PASS 제거가 1순위.
2. **데이터가 이미 있는데 안 쓰는 것 먼저** — 검사 한 줄이면 되는 것(P0)과 백필이 필요한 것(P1)과 팀 정책 합의가 필요한 것(P2)을 분리.
3. **인테이크 오염원은 즉시 차단** — 잘못된 데이터를 계속 만드는 버그는 데이터 백필보다 먼저 고친다.

---

## P0 — 데이터는 이미 있다, 검사만 없다 (코드만으로 false PASS 제거)

### P0-1. 쿨러 TDP 대응 검사 [HIGH]
- **증상**: 170W 라이젠 9950X에 65W급 로우프로파일 쿨러(Noctua NH-L9a)가 '호환됨' — 소켓만 보고 냉각 용량은 안 봄. tdpW≥120 ACTIVE CPU 13개 전부 이 조합이 PASS.
- **근거**: ToolCheckService.java:86 (socketSupported만), 쿨러 tdpW **31/31(100%) 보유**, CPU tdpW 30/33.
- **설계**: compatibility()에 `coolerTdpW >= cpuTdpW` — 미달 FAIL, 마진<20% WARN, 결측 시 생략(ramSlotsChecked 패턴). CPU–COOLER 엣지 라벨 'TDP 대응 265W / CPU 170W' 분기.
- **AC**: 9950X+NH-L9a → FAIL. 후보 패널에서 저용량 쿨러 숨김. 결측 CPU 3개 백필.

### P0-2. RAM formFactor 게이트 — 노트북/서버 램 차단 [HIGH]
- **증상**: 노트북용 SODIMM(133만원)·서버용 ECC REG RDIMM(220만원)이 데스크톱 후보로 '호환됨' — 실제로는 꽂히지도 않거나(262핀) 부팅 불가. ACTIVE RAM 20개 중 2개(10%)가 이 부류.
- **근거**: ToolCheckService.java:85 (memoryType 한 줄이 전부), V21이 formFactor/ecc/registered를 저장까지 해놨는데 검사가 안 읽음.
- **설계**: formFactor 존재 && != UDIMM → FAIL('데스크탑 보드에 장착 불가 폼팩터'), registered=true → FAIL. 장기: 해당 상품 카탈로그 큐레이션 제외 검토.
- **AC**: SODIMM/RDIMM 후보가 FAIL 숨김 처리.

### P0-3. PSU 깊이 vs 케이스 허용 길이 [HIGH 일부]
- **증상**: SFX 전용 ITX 케이스(Fractal Terra, 허용 130mm)에 깊이 200mm ATX 파워(HX1500i)가 PASS — 나사 구멍부터 안 맞는 조합.
- **근거**: PSU depthMm **71/74 보유**, 케이스 maxPsuLengthMm 24/27 보유 — 그런데 size()가 안 읽음(유일한 소비자는 챗봇 교체 필터 DefaultAiChatEngine:1267).
- **설계**: size()에 `psu.depthMm <= case.maxPsuLengthMm` 추가(기존 GPU 길이 검사와 동일 패턴). PSU 폼팩터(ATX/SFX) 백필은 P1-1에 편입.
- **AC**: Terra+HX1500i → size FAIL. 동일제품 중복행 depthMm 모순(160 vs 125) 정정.

### P0-4. 수랭(AIO) 라디에이터 장착 검사 [HIGH]
- **증상**: 라디에이터를 아예 못 다는 케이스에 360mm 수랭이 PASS — AIO의 heightMm(27~38mm, 라디에이터 두께)가 공랭용 높이 검사를 항상 통과. 그래프엔 '높이 여유 50mm'라는 무의미한 초록 라벨.
- **근거**: radiatorSizeMm 15/15, 케이스 radiatorSupportMm 24/27 **이미 보유** — 검사만 없음 (ToolCheckService:159-160).
- **설계**: size()에서 coolerType=LIQUID_AIO 분기 — radiatorSizeMm ∈ case.radiatorSupportMm (미포함 FAIL, 케이스 결측 WARN). 부수: maxCpuCoolerHeightMm 기본값 190 제거(결측인데 '근거 있음'처럼 통과하는 문제).
- **AC**: Terra(라디 불가)+Kraken 360 → FAIL. COOLER–CASE 엣지 라벨 AIO 분기.

### P0-5. 인테이크 휴리스틱 버그 2건 + 오염 행 백필 [HIGH]
- **증상①**: 네이버 인테이크 moduleCount가 `? 2 : 2` — **양쪽 분기가 모두 2인 상수 대입**(NaverShoppingOfferService:1205). 단품 스틱도 킷으로 등록 → NAVER 유래 RAM 12/12 전부 moduleCount=2, 그중 명백한 단품 2건('…32GB, 1개', 서버용 단품). V94로 정확해진 스틱 검사에 오염 입력이 들어가 **단품 3개 담으면 6스틱 오탐 FAIL**.
- **증상②**: capacityGb가 '64GB 있으면 64, 아니면 32' 이진 규칙(:1204) — 48/96/128GB 유입 시 전부 32로. STORAGE도 동류(:1211, 8TB가 2000GB로 등록된 실사례).
- **설계**: 제목 정규식 파싱으로 교체 — `(\d+)GB?\s*[xX×]\s*(\d+)`/`(NGx2)`/`N개` → moduleCount, `(\d+)(TB|GB)` 최대값 → capacityGb. **패턴 없으면 값을 넣지 않는다**(소비처가 전부 미존재=1/생략 폴백이라 자연스럽게 안전). 기존 오염 행 백필 마이그레이션(NAVER RAM 12건 + STORAGE 용량 오류 행).
- **AC**: '…32GB, 1개' → moduleCount 미기재(스틱 1 계산). 8TB SSD → 8000.

### P0-6. 후보 평가 '담기' 의미론 [HIGH]
- **증상**: RAM 4/4 만석에서 후보 패널의 모든 킷이 '호환됨' → 담는 순간 6스틱>4슬롯 FAIL 반전·구매 잠김. "안 맞는 후보는 숨김"이라는 안내와 정면 모순. (#73에서 의도적으로 보류했던 갭 — 감사가 HIGH로 확정)
- **근거**: PartCompatibleCandidateService.evaluate:239-242 — 카테고리 전체 제거 후 후보 1개로 평가(교체-전체 가정). UI 기본 액션은 '담기'(SlotCandidatePanel:217).
- **설계**: evaluate에 ADD/REPLACE 모드 — multi 카테고리 + replaceTarget 없음 = 기존 유지 + 후보 합산으로 checkBuild. 교체 모드는 대상 행만 제외. UI가 모드를 쿼리 파라미터로 전달.
- **AC**: 만석 상태에서 후보 킷들이 FAIL(숨김 or '슬롯 부족' 뱃지). 교체 모드에서는 기존처럼 평가.

---

## P1 — 데이터 백필 필요 (V94 웹 검증 워크플로 재사용)

### P1-1. 폼팩터 정합 3종 세트 [HIGH×2 + MEDIUM]
순서 중요: **(a) 데이터 정리 → (b) 검사 → (c) 엣지**. 데이터가 오염된 채 검사부터 넣으면 오판정이 된다.
- **(a) 데이터**: ①보드 formFactor **27% 오염**(ATX 표기 52건 중 14건이 실제 ITX/mATX — Z890I가 ATX로!) → 이름 패턴+웹 검증 백필. ②어휘 3계열 혼재('Mini-ITX' vs 'MINI_ITX' vs 'M-ATX') → 단일 enum(ATX/MATX/ITX/EATX) 정규화 + 인테이크 2개 서비스 기록 통일. ③케이스 지원 폼팩터가 8가지 비정규 문자열('EATX_ATX_MATX_ITX', 'mATX_ATX'…) → JSON 배열로 마이그레이션. **주의: `'MATX_ITX'.contains('ATX')=true` — contains 구현 금지, 정확 토큰 매칭.**
- **(b) 검사**: size()에 보드 formFactor ∈ 케이스 지원 배열 (결측 시 생략). PSU 폼팩터(SFX/ATX) 백필 후 케이스 대조.
- **(c) 그래프**: MOTHERBOARD↔CASE 'REQUIRES' 엣지 신설 + FALLBACK_EDGES 동반 추가, checkedTools에 MOTHERBOARD/CASE↔size 매핑.
- **증상 해소**: ITX 전용 케이스+ATX 보드 PASS(현재 케이스 2종×ATX 보드 52종 조합 전부 무경고), 케이스 상세의 'EATX_ATX_MATX_ITX' 원시 문자열 노출.

### P1-2. STORAGE 도메인 신설 — "RAM에서 한 것을 SSD에도" [HIGH + MEDIUM×3]
- **증상**: SSD는 **어떤 검사도 받지 않는다**(checkedTools 빈 목록 → 무조건 '호환됨'). 보드 M.2 슬롯 데이터 0/72. SSD 4~5종을 담아도 전부 초록. 그래프에서 STORAGE는 엣지가 하나도 없는 고립 노드라 구조적으로 항상 '정상' 뱃지. 미니슬롯은 quantity 무시(아이템 수 기준) — 같은 화면에서 RAM과 규칙이 다름.
- **설계**:
  1. 보드 m2Slots(+sataPorts) 백필 — **V94와 동일한 웹 검증 워크플로 재사용**(60개 보드, 이미 검증된 방법).
  2. compatibility()에 Σ(M.2 storage quantity) ≤ m2Slots (데이터 없으면 생략). checkedTools STORAGE→compatibility.
  3. MOTHERBOARD–STORAGE 엣지 신설(라벨 'M.2 장착') → 노드 status 자동 전파(#73의 worst-edge 로직이 그대로 적용됨).
  4. 미니슬롯 miniFillBy를 'quantity'로 통일(STORAGE는 moduleCount 없어 스틱계산이 수량 그대로 반환 — 프론트 1줄).
  5. 인터페이스 데이터 정정은 P0-5에 편입(Gen5가 'M.2 NVMe'로 뭉개진 것, 중고 상품 필터).
- **AC**: 2 M.2 보드에 SSD 3종 → FAIL. 미니슬롯이 수량 반영. STORAGE 카드가 검사 결과를 반영.

### P1-3. 구매 차단·집계의 사각 해소 [MEDIUM×2]
- **증상①**: GPU 없는 견적에서 파워 부족(300W PSU+170W CPU → power 툴 FAIL)이어도 요약바 '이상 없음'·구매 활성 — 구매 차단이 PART-PART 엣지 유도 FAIL만 보고, 상대 카테고리가 비어 엣지가 없으면 툴 FAIL이 화면 어디에도 안 뜸.
- **증상②**: 검사·그래프의 총액/전력 합산이 quantity·동종 복수 상품 무시 — UI 총액과 그래프 총액이 245,500원 어긋나는 실측(draft#1), 전력은 카테고리당 1개만 계상.
- **설계**: ①quoteHasCompatibilityFail이 toolResults FAIL(장착 카테고리 관련만)+CONSTRAINT 노드 FAIL도 반영, 요약바에 '조건 미충족' 구분 표기. ②total()/estimatedWattage()를 effectiveQuantity 가중 합산으로(스틱 검사에서 이미 쓰는 패턴).
- **AC**: GPU 없는 파워부족 견적 구매 차단. 그래프 총액=UI 총액.

---

## P2 — 정책·구조 판단 필요 (팀 합의 후 진행)

### P2-1. 견적함 저장 모델과 드래프트의 불일치 [MEDIUM×2 — 구조적]
- **증상**: RAM 2종 담은 **합법 드래프트가 '내 견적함에 추가'에서 영구 400**('같은 카테고리 중복 불가')인데 UI는 "잠시 후 다시 시도"로 안내. 저장돼도 quantity가 price로 접혀 소실 → 셀프견적에서 FAIL(스틱 초과)이던 구성이 견적함 재검증에선 PASS로 **판정 역전**.
- **설계(합의 필요 — DB 스키마·builds owner)**: build_items에 quantity 컬럼 + 카테고리 유니크→(build_id,part_id) 유니크 완화, partsByBuildId quantity 왕복. 단기 완화(합의 전): 저장 실패 사유 정직하게 표기.
- **왜 P2**: 견적함은 다른 소비처(추천·에이전트)가 많아 스키마 변경 영향 검토 필요.

### P2-2. "미검증"과 "통과"의 구분 [MEDIUM×2 — UX 정책]
- **증상**: 검사가 없거나(STORAGE), 속성이 없어 생략되거나(null-lenient same()), 스펙이 제목 추정치(CPU 100%·보드 87%가 ESTIMATED_FROM_TITLE)여도 전부 똑같은 초록 '정상'/'호환됨'/confidence HIGH. 보드 memoryType은 인테이크가 무조건 'DDR5' 하드코딩 — DDR4 병존 칩셋(B760 등) 유입 순간 오판 시한폭탄.
- **설계**: ①검사 근거 없는 판정은 중립 표기('미확인' 회색 뱃지 — PASS와 시각 분리), ②ESTIMATED_FROM_TITLE 포함 판정은 confidence를 MEDIUM 캡 + details에 estimatedSpecs 노출, ③DDR4 병존 칩셋은 memoryType 미기재로 흐르게, 번들 상품('+') 인테이크 제외.
- **왜 P2**: '무엇을 미확인으로 보여줄 것인가'는 데모 인상과 직결되는 UX 정책 — 팀 판단 필요.

### P2-3. 최소구성 구매 게이트 [MEDIUM — 정책]
- **증상**: CPU 1개(265,000원)만 담아도 '미장착 슬롯 7개' 배너와 파란 '구매하기'가 동시에 뜸. 결제 화면도 재검증 없음.
- **설계**: 필수 카테고리 집합 정의(예: CPU/MB/RAM/STORAGE/PSU/CASE) → 미충족 시 차단 또는 명시적 확인 단계. **어디까지 필수인지가 정책**(예: 쿨러는 CPU 기본쿨러 가정?).

### P2-4. 심화 스펙 검사 (데이터 확보가 큰 건) [MEDIUM×3]
- 보드 **최대 메모리 용량**(0/60 — 128GB 킷×2=256GB가 192GB 한계 보드에서 PASS), **DDR 속도/EXPO·XMP**(EXPO 없는 7200 킷+AM5 보드 → JEDEC 속도로만 동작하는데 무경고), **CPU 실부하 전력**(인텔 K는 PL2가 tdpW의 2배 — 부하율 74% 표시 vs 실제 ~82%).
- 전부 "WARN이 적정"(부팅은 됨)·데이터 백필이 본체. 폼팩터/M.2 백필 워크플로에 필드만 추가하면 한 번에 가능 — **P1 백필과 묶어서 데이터만 먼저 채워두는 것을 권장**.

### 부록 — LOW 10건 (관찰 기록)
HDD가 'SSD' 라벨·글리프로 표시 / PSU 스펙 UI가 존재하지 않는 키 참조(항상 빈 항목) / 그래프 노드 라벨 '32GB · 2개'가 '모듈당 32GB'로 오독 여지 / 동종 중복 시 검사는 '마지막', 그래프 요약은 '첫 번째' 부품을 봐 설명 불일치 + 동일 id 노드 중복으로 '안 맞음 N개' 부풀림 / 두 번째 이후 RAM의 DDR 규격 미검사 등 — 상위 항목 작업 시 같은 파일을 만지면 함께 처리.

---

## 실행 요약

| 단계 | 항목 수 | 성격 | 규모 감 |
|---|---|---|---|
| **P0** (1~6) | HIGH 6 | 코드만 — false PASS 제거 + 오염원 차단 | 검사 로직 각 10~40줄 + 테스트, 백필 마이그레이션 1개 |
| **P1** (1~3) | HIGH 2 + MEDIUM 5 | 백필(웹 검증 워크플로 재사용) + 검사 + 엣지 | 마이그레이션 2~3개 + 검사/엣지/프론트 |
| **P2** (1~4) | MEDIUM 다수 | 팀 정책·스키마 합의 선행 | 합의 후 산정 |

권장 진행: **P0 전체를 한 PR**(전부 검사 레이어 + 인테이크 수정), **P1-1과 P1-2를 각각 PR**(백필+검사 세트), P1-3은 P0에 편입 가능한 소형. P2는 회의 안건.
