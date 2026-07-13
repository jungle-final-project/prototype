# BuildGraph 데모 안정화 버그 현황

## 1. 기준과 상태

- 작성일: 2026-07-13 KST
- 원격 기준: `upstream/main=2995a5b` (`feat: 체크아웃 배송지와 희망일 입력 개선`)
- 로컬 브랜치: `codex/demo-scenario-stability`
- 로컬 HEAD: 최신 main 통합 후 데모 안정화 커밋 1개
- 현재 수정 사항은 PR 전 최종 검증을 완료한 상태다.
- 이 문서는 과거 전체 PR의 수정 건수를 합산한 문서가 아니다. 최신 main 이후 확인한 데모 시나리오 버그와 현재 로컬 수정 상태를 기록한다.
- 아래 자동 검증 수치는 `upstream/main=2995a5b`를 통합한 작업 브랜치의 최종 결과다.

상태 정의:

- `LOCAL_FIXED`: 로컬 코드와 테스트까지 수정했지만 아직 PR로 보내지 않음
- `RESOLVED`: 이 문서에서 추적하던 항목을 로컬 코드와 회귀 테스트로 해결함
- `MAIN_GUARANTEE`: 최신 main에 이미 들어가 있어 현재 작업 성과로 중복 계산하지 않음
- `OPEN`: 재현됐지만 아직 수정하지 않음
- `VERIFY`: 일부 계층은 수정했으나 실제 앱 또는 전체 사용자 흐름 재검증이 필요함

## 2. 현재 로컬에서 수정 완료한 독립 이슈

| ID | 우선순위 | 상태 | 문제 | 수정 내용 |
|---|---|---|---|---|
| BG-LOCAL-01 | P1 | LOCAL_FIXED | QHD 게임용 완성 견적이 GPU 후보 구성 실패 후 GPU 없는 조합으로 내려갈 수 있음 | GPU가 필요한 고예산 게임 견적에서 no-GPU fallback을 금지하고 GPU 포함 조합만 반환 |
| BG-LOCAL-02 | P1 | LOCAL_FIXED | `GPU를 한 단계 낮은 저렴한 제품으로` 요청이 동급 GPU를 선택할 수 있음 | 명확한 방향 교체는 ranker fast path로 처리하고 GPU 하향 요청은 실제 하위 tier만 허용 |
| BG-LOCAL-03 | P1 | LOCAL_FIXED | 2-DIMM RAM 키트를 수량 2로 담아 4개 모듈과 가격 2배로 계산함 | 상품의 `moduleCount`를 읽어 목표 모듈 수를 retail package 수량으로 변환 |
| BG-LOCAL-04 | P1 | LOCAL_FIXED | 기존 RAM 슬롯 초과나 PSU 크기 실패가 무관한 CPU/GPU 후보까지 FAIL로 오염시킴 | 전체 Tool 결과를 후보 카테고리와 직접 관련된 compatibility/size 항목으로 투영 |
| BG-LOCAL-05 | P1 | LOCAL_FIXED | RAM·SSD 수량을 1에서 0으로 내릴 수 없어 잘못 담은 마지막 상품을 제거하지 못함 | 수량 0 요청을 DELETE로 연결하고 마지막 RAM 객체까지 제거 허용 |
| BG-LOCAL-06 | P1 | LOCAL_FIXED | 조립 요청에서 수령인·전화·배송 주소를 모두 필수로 요구해 데모 흐름을 막음 | 지역·희망일·AS 동의만 필수로 두고 연락처·주소는 선택 입력, 기본 지역·일정 제공 |
| BG-LOCAL-07 | P1 | LOCAL_FIXED | 외부 기사가 입찰해도 사용자가 내 견적함과 진행 중 의뢰에서 제안을 찾거나 선택하기 어려움 | available offer 수 반환, 5초 polling, 내 견적함 및 요청 상세의 제안 비교·선택 직행 동선 추가 |
| BG-LOCAL-08 | P1 | LOCAL_FIXED | 사용자가 올린 로그는 검증 문구만 저장되고 실제 AS RAG 분석 결과가 티켓 근거로 남지 않음 | 업로드 시 `as_rag_analysis`와 요약을 저장하고 분석 장애 시 로그 업로드 자체는 유지 |
| BG-LOCAL-09 | P1 | LOCAL_FIXED | 종료·취소된 티켓 또는 기존 데모 seed 상담이 신규 AS 접수를 막고 상담방을 재생성함 | 고정 seed 티켓만 archive/cancel하고 terminal ticket에는 새 상담방을 만들지 않음 |
| BG-LOCAL-10 | P1 | LOCAL_FIXED | 원격지원 링크 갱신 때 세션이 중복 생성되거나 티켓 종료 후 세션이 활성 상태로 남음 | 최신 active session upsert, 상담 archive 시 cancel, 티켓 resolved/closed/cancelled 시 세션 종료 |
| BG-LOCAL-11 | P2 | LOCAL_FIXED | 상품명의 연속 공백·괄호·특수문자 차이로 정확 상품 `견적에 담아줘` fast path가 실패함 | 서버와 요청 상품명을 동일 규칙으로 정규화해 단일 exact 자산을 찾음 |
| BG-LOCAL-12 | P2 | LOCAL_FIXED | AI 동작 수정 후에도 기존 exact/semantic 캐시가 과거 응답을 재생할 수 있음 | 상태형 추천·후속 답변 파서·가성비 정렬 정책까지 반영해 exact cache `v48`, intent router cache `v23`으로 namespace 갱신 |
| BG-LOCAL-13 | P2 | LOCAL_FIXED | 배치도에서 hover한 부품 객체가 인접 레이어 아래로 가려짐 | hover hitbox의 z-index를 올려 선택 객체를 전면 표시 |
| BG-LOCAL-14 | P2 | LOCAL_FIXED | 기사에게 공개된 선택 입력 연락처·주소가 빈 값일 때 화면 문구가 깨질 수 있음 | 빈 연락처·주소는 `미입력`으로 표시 |
| BG-LOCAL-15 | P2 | LOCAL_FIXED | 관리자 원격지원 처리 후 사용자 티켓 화면이 자동으로 갱신되지 않음 | 사용자 티켓 상세에 5초 polling 적용 |
| BG-LOCAL-16 | P2 | LOCAL_FIXED | 체크리스트에서 상품 하나를 고르면 카테고리 목록이 닫히고 다음 슬롯으로 강제 이동함 | 선택한 카테고리 아코디언과 URL을 유지해 `9600X → 285K`처럼 연속 교체 가능 |
| BG-LOCAL-17 | P2 | LOCAL_FIXED | 관리자 티켓 감사 로그 INSERT에서 PostgreSQL 파라미터 타입 추론이 실패할 수 있음 | nullable audit 필드를 명시적으로 `text` cast |
| BG-LOCAL-18 | P1 | LOCAL_FIXED | 현재 B860 견적에서 `CPU 추천해줘`가 AM5 전용 9950X3D·9900X3D를 TOP3와 담기 칩으로 노출함 | 엔진의 현재 견적 플랫폼 필터와 서버 활성 draft 기반 Tool 최종 게이트를 추가했다. FAIL 후보를 제거한 뒤 최대 50개 후보 안에서 통과 후보 3개를 채우며, 3개가 모이면 즉시 검사를 중단한다. |
| BG-LOCAL-19 | P1 | LOCAL_FIXED | `FRAME 4000D` 같은 상품명을 RAM으로 잘못 감지해 정확 상품 담기 흐름이 깨질 수 있음 | 카테고리 alias를 단어 경계 기준으로 판정해 상품명 내부 문자열 오탐을 차단 |
| BG-LOCAL-20 | P1 | LOCAL_FIXED | RAM·SSD를 실제로 담은 다음 같은 추천을 하면 방금 담은 상품이 TOP3에 다시 나옴 | ADD 카테고리도 현재 선택 exact part를 후보에서 제외하고 뒤의 Tool 통과 후보로 보충 |
| BG-LOCAL-21 | P1 | LOCAL_FIXED | `램 하나 넣어줘`처럼 상품이 불명확한 요청이 임의의 고가 RAM을 바로 선택함 | 상품·스펙·예산·방향이 없으면 제품을 고르지 않고 용량/가격/성능 기준 칩을 제시 |
| BG-LOCAL-22 | P1 | LOCAL_FIXED | 되묻기에 `32GB`라고 답하면 `추천해줘 32GB`를 상품명으로 오인하고 후보 없음으로 종료함 | 짧은 답은 카테고리 문맥과 합성하되 추천 동사·가격 수식어를 모델 토큰에서 제거하고, 완전한 후속 명령은 원문 중복 합성을 생략 |
| BG-LOCAL-23 | P1 | LOCAL_FIXED | 추천 상품 칩을 누른 뒤 빈 견적에서는 미리보기가 생성되지 않거나 exact 상품이 다시 LLM 경로로 흐름 | exact ACTIVE 상품은 빈 견적 ADD, 단일 슬롯 REPLACE, 같은 RAM/SSD 수량 증가 의미로 결정적 미리보기 생성 |
| BG-LOCAL-24 | P1 | LOCAL_FIXED | `장착 여유 큰 케이스` 요청이 현재와 같은 수치의 파생 상품이나 더 작은 케이스를 추천함 | GPU·쿨러·파워의 알려진 여유가 모두 비퇴행이고 하나 이상 실제 증가하는 후보만 허용 |
| BG-LOCAL-25 | P1 | LOCAL_FIXED | 서버가 만든 `가성비` 칩이 CPU 9950X3D, GPU RTX 5090, 1500W 파워부터 추천하고 4~9초가 걸림 | 객관적 가격 대비 지표로 정렬하고 단순 가성비/최저가/고성능 요청을 결정적 fast path로 처리; 실제 API 8개 12~22ms |
| BG-LOCAL-26 | P1 | LOCAL_FIXED | 셀프견적에서 변경 동사가 없는 추천·후속 질문은 최신 draft가 빠져 과거 추천 카드 또는 캐시 문맥과 충돌할 수 있음 | `/self-quote`의 모든 챗 요청과 clarification 후속에 최신 `currentQuoteDraft`를 첨부하고 홈의 문맥 없는 전체 추천만 shared cache 유지 |

## 3. 현재 로컬의 신규 연결 기능

| ID | 상태 | 내용 | 경계 |
|---|---|---|---|
| BG-FEATURE-01 | LOCAL_FIXED | 쇼핑몰 챗봇에서 멈춤·검은 화면·재부팅·부팅 실패·과열·저장장치·네트워크·오디오 증상을 접수 전 안내로 인식 | 증상 기반 일반 가능성은 안내하되 위험도·확정 원인·로그 근거는 만들지 않음 |
| BG-FEATURE-02 | LOCAL_FIXED | 챗봇 결과 카드에서 PC Agent 다운로드와 `/support/new` AS 접수 제공 | 실제 로그 기반 진단은 별도 PC Agent AI가 담당 |
| BG-FEATURE-03 | LOCAL_FIXED | AS 화면과 쇼핑몰 챗봇이 동일한 Agent ZIP 생성·activation token·SHA-256 검증 경로 사용 | 다운로드 코드 중복 제거 |

## 4. 최신 main에서 이미 보장되는 항목

다음은 현재 로컬 수정 건수로 중복 계산하지 않는다.

| ID | 상태 | 보장 내용 |
|---|---|---|
| BG-MAIN-01 | MAIN_GUARANTEE | TARGET 예산 요청은 모든 카드가 예산 ±12.5% 범위여야 함 |
| BG-MAIN-02 | MAIN_GUARANTEE | 밴드 안 조합이 부족하면 추천 카드는 1~3개만 반환 가능 |
| BG-MAIN-03 | MAIN_GUARANTEE | Tool FAIL 변경안은 적용 미리보기에서 hard drop |
| BG-MAIN-04 | MAIN_GUARANTEE | CPU/GPU/PSU/CASE/COOLER 같은 단일 카테고리 ADD는 기존 부품 REPLACE로 보정 |
| BG-MAIN-05 | MAIN_GUARANTEE | simulation은 읽기 전용이며 draft mutation action을 만들지 않음 |

## 5. 현재 확인된 미해결 및 추가 검증 항목

| ID | 우선순위 | 상태 | 재현 또는 위험 | 다음 조치 |
|---|---|---|---|---|
| BG-OPEN-01 | P1 | RESOLVED | 추천 카드의 `균형/고성능` 라벨과 적용 후 1000점 평가·병목 조언이 충돌함 | 가격 인덱스 relabel 제거, 공통 1000점 평가 snapshot으로 tier·label·badge 생성 |
| BG-OPEN-02 | P1 | OPEN | 점수 조언의 `상위 GPU 추천` 칩이 동급 GPU를 추천해 점수·FPS가 개선되지 않을 수 있음 | 추천 단계에도 현재 대비 실질 delta 양수 guard 적용 |
| BG-OPEN-03 | P1 | RESOLVED | 과거 변경 미리보기 `currentBuilds`가 최신 활성 draft보다 강한 문맥으로 사용될 수 있음 | 셀프견적의 모든 챗 요청과 clarification 후속에 최신 `currentQuoteDraft`를 첨부하고 Playwright로 고정 |
| BG-OPEN-04 | P1 | VERIFY | AS 분석 저장은 보강했지만 Display 4101/nvlddmkm 같은 근거가 발표용 한글 evidence row로 끝까지 보이는지 미확정 | 실제 Agent fixture 업로드부터 사용자·관리자 UI까지 재검증 |
| BG-OPEN-05 | P1 | OPEN | `300만원 이하 RTX 5090` 같은 하드 조건 요청이 로컬에서 약 7.4초로 5초 목표 초과 | hard-constraint deterministic snapshot 또는 별도 fast candidate 검토 |
| BG-OPEN-06 | P2 | OPEN | build 1개를 반환하면서 `추천 조합 3개`라고 말할 수 있음 | 응답 문구를 실제 `builds.size()` 기준으로 생성 |
| BG-OPEN-07 | P2 | OPEN | 불가능 예산 역제안의 최소 구성 금액과 실제 표시 카드 금액이 다를 수 있음 | 문장과 카드가 같은 최소 구성 snapshot 사용 |
| BG-OPEN-08 | P2 | OPEN | 역제안 문구에 `원을(를)` 같은 부자연스러운 조사 또는 드문 외국어 토큰이 섞일 수 있음 | 서버 후처리 및 한국어 문구 template 보강 |
| BG-OPEN-09 | P2 | OPEN | 일반 사용자 화면에서 기사 프로필 없음이 예상된 404 console error로 누적됨 | 204/empty profile 계약 또는 클라이언트 expected state 처리 |
| BG-OPEN-10 | P2 | OPEN | 홈 정적 추천 조합 가격이 1천만~2천만원대로 노출돼 데모 신뢰도를 낮춤 | 홈 seed 추천을 실제 데모 가격대와 내부 자산 기준으로 재구성 |
| BG-OPEN-11 | P2 | VERIFY | 실제 PC Agent 앱이 쇼핑몰에서 발급한 activation 설정으로 등록·로그 업로드·진단까지 이어지는지 미검증 | PC Agent 실행 파일 기준 E2E 수행 |

## 6. 검증 현황

- 백엔드 Gradle 전체 테스트: 732개 재실행, 실패 0건(의도된 skip 4건)
- 백엔드 `bootJar`: 통과
- OpenAPI: 150 paths 검증 통과
- `git diff --check`: 통과
- 웹 전체 Playwright: 270개 단일 worker 재실행 결과 `passed`, 실패 0건
- 웹 production build: 통과
- Docker API 이미지 재빌드: 완료
- 기존 볼륨과 분리한 clean PostgreSQL에서 Flyway V117/V118 적용 및 `/api/health=UP` 확인
- 실제 상태형 RAM 체인: clarification 20ms → `32GB` 후보 3개 30ms → 생성 상품 칩 `draft-edit` 미리보기 1개 19ms, warning 0건
- 실제 8개 카테고리 단순 가성비 추천: 각 12~22ms, 호환 후보가 있는 카테고리는 TOP3 반환
- 실제 Build Chat smoke `게임하다 화면이 자꾸 멈춰`: `DISPLAY_FREEZE`, `PRE_DIAGNOSIS`, build 0, simulation 없음, 원인 후보 없음, Agent 다운로드/AS 접수 반환

## 7. 현재 작업 상태와 공유 시 주의점

- 의도한 데모 안정화 코드·테스트·문서만 커밋 대상으로 정리했다.
- `docs/reports/aws-load-test-preparation-handoff-20260712.md`는 별도 AWS 작업 문서이며 이 버그 수정 PR에 포함하지 않는다.
- 원격 `V117__user_contact_address.sql`과 겹치던 로컬 seed 정리 migration은 `V118__close_blocking_demo_support_seeds.sql`로 재배정했고 clean DB 적용을 확인했다.
- 이번 현황을 화면 예시 개수와 같은 수의 독립 버그 수정으로 표현하면 부정확하다. 독립 원인 기준 로컬 버그 수정은 26개, 신규 연결 기능은 3개이며 해결 이력 1개를 제외한 미해결/검증 항목은 별도 표를 따른다.
- 최신 main 재확인, 의도한 파일만 stage, 전체 회귀 재실행을 완료했다.

## 8. 사용자 흐름 기준 세부 변경 예시

아래 목록은 팀원이 실제 화면과 자연어 시나리오에서 무엇이 달라졌는지 빠르게 확인하기 위한 상세 예시다. 하나의 독립 버그를 API, 화면, 상태 처리로 나눈 항목이 포함되어 있으므로 `독립 버그 40개`라는 의미는 아니다. `MAIN_GUARANTEE` 항목은 최신 main에 이미 있던 방어이며 현재 로컬 작업 성과로 중복 계산하지 않는다.

### AI 견적과 부품 변경

1. `LOCAL_FIXED` QHD 게임용 완성 견적에는 GPU가 반드시 포함된다.
2. `LOCAL_FIXED` GPU 포함 조합 생성이 실패해도 GPU 없는 사무용 조합으로 조용히 대체하지 않는다.
3. `LOCAL_FIXED` 2-DIMM RAM 키트는 상품 수량 1, 실제 모듈 수 2로 계산한다.
4. `LOCAL_FIXED` RAM 키트의 가격과 Tool 슬롯 검증도 retail package 수량을 기준으로 계산한다.
5. `LOCAL_FIXED` `GPU를 한 단계 낮은 더 저렴한 제품으로 바꿔줘`는 현재 GPU보다 실제 tier가 낮은 후보만 사용한다.
6. `LOCAL_FIXED` 명확한 상향·하향 교체는 LLM 재해석 없이 ranker와 Tool 검증을 거친 미리보기로 반환한다.
7. `LOCAL_FIXED` 상품명의 연속 공백, 괄호, 특수문자 차이를 정규화해 정확 상품 `견적에 담아줘`를 찾는다.
8. `LOCAL_FIXED` 동작 변경 후 이전 Redis 응답이 재생되지 않도록 exact/semantic cache namespace를 갱신했다.
9. `MAIN_GUARANTEE` Tool FAIL이 발생한 변경안은 적용 미리보기 카드로 제공하지 않는다.
10. `MAIN_GUARANTEE` CPU·GPU·PSU·CASE·COOLER 같은 단일 카테고리에 `ADD`가 들어와도 기존 부품 `REPLACE`로 보정한다.

### 수동 견적과 호환 후보

11. `LOCAL_FIXED` 현재 견적의 전체 Tool FAIL을 모든 후보에 그대로 복사하지 않고 후보 카테고리 관련 항목만 평가한다.
12. `LOCAL_FIXED` 기존 RAM 슬롯 초과 때문에 무관한 CPU 후보가 장착 불가로 표시되지 않는다.
13. `LOCAL_FIXED` 기존 PSU 크기 문제 때문에 실제로 장착 가능한 GPU 후보가 장착 불가로 표시되지 않는다.
14. `LOCAL_FIXED` 후보 FAIL 문구는 소켓, RAM 규격, 슬롯 수, 쿨러 TDP, 실제 장착 치수처럼 해당 후보의 원인을 설명한다.
15. `LOCAL_FIXED` RAM·SSD 수량 감소 버튼을 1에서도 누를 수 있고 0이 되면 항목 삭제로 처리한다.
16. `LOCAL_FIXED` 마지막 RAM 객체도 제거할 수 있어 잘못 담은 다중 부품 때문에 견적을 버릴 필요가 없다.
17. `LOCAL_FIXED` 배치도에서 hover한 부품 객체가 인접 부품 레이어 아래로 가려지지 않는다.
18. `LOCAL_FIXED` CPU 9600X를 고른 뒤에도 CPU 목록이 유지되어 바로 285K로 다시 선택할 수 있다.

### 조립 기사 요청과 제안 선택

19. `LOCAL_FIXED` 조립 요청에서 수령인 입력은 선택 사항이다.
20. `LOCAL_FIXED` 수령인을 비우면 서버가 계정 이름 또는 이메일을 snapshot 기본값으로 사용한다.
21. `LOCAL_FIXED` 전화번호 입력은 선택 사항이며 비어 있어도 요청을 만들 수 있다.
22. `LOCAL_FIXED` 배송 방식을 선택해도 초기 기사 제안 요청 단계의 주소는 선택 사항이다.
23. `LOCAL_FIXED` 데모 입력 부담을 줄이기 위해 지역은 서울, 희망일은 현재 날짜 기준 3일 후로 시작한다.
24. `LOCAL_FIXED` 조립 요청 목록 API가 현재 선택 가능한 기사 제안 수를 함께 반환한다.
25. `LOCAL_FIXED` 제안 대기 중인 요청 목록과 상세는 5초마다 새 제안을 확인한다.
26. `LOCAL_FIXED` 내 견적함에서 새 제안이 있으면 기사 제안 비교 화면으로 바로 이동한다.
27. `LOCAL_FIXED` 진행 중 의뢰 상세에서 도착한 제안 수와 `기사 제안 비교·선택` 버튼을 표시한다.
28. `LOCAL_FIXED` 선택 기사 화면에서 비어 있는 연락처와 주소는 깨진 값 대신 `미입력`으로 표시한다.

### AS 로그와 원격지원 상태

29. `LOCAL_FIXED` 사용자 로그 업로드 시 검증 결과뿐 아니라 AS RAG 분석 payload도 저장한다.
30. `LOCAL_FIXED` 로그 목록의 요약은 분석 결과가 있으면 실제 `summaryText`를 우선 사용한다.
31. `LOCAL_FIXED` AS RAG 분석이 실패해도 검증된 로그 업로드 자체는 정상 저장한다.
32. `LOCAL_FIXED` 깨끗한 DB의 고정 데모 상담 두 건만 종료해 신규 AS 접수를 막지 않게 한다.
33. `LOCAL_FIXED` CLOSED·CANCELLED 티켓에는 사용자 상담방을 다시 만들지 않는다.
34. `LOCAL_FIXED` 상담방을 archive하면 남아 있는 원격지원 요청도 CANCELLED로 종료한다.
35. `LOCAL_FIXED` 같은 티켓에 원격지원 링크를 다시 저장할 때 새 세션을 계속 만들지 않고 최신 active session을 갱신한다.
36. `LOCAL_FIXED` 티켓을 RESOLVED·CLOSED·CANCELLED로 변경하면 활성 원격지원 세션도 COMPLETED 또는 CANCELLED로 끝낸다.
37. `LOCAL_FIXED` 관리자 감사 로그의 nullable 문자열을 명시적으로 cast해 PostgreSQL 타입 추론 오류를 방지한다.
38. `LOCAL_FIXED` 사용자 티켓 화면은 5초 polling으로 관리자 링크·처리 메모·종료 상태를 반영한다.

### 쇼핑몰 AI와 PC Agent 연결

39. `LOCAL_FIXED` 쇼핑몰 AI는 멈춤·검은 화면·재부팅·부팅 실패 등의 증상을 인식하고 일반적인 원인 가능성을 안내하지만, 위험도·확정 원인·로그 근거는 단정하지 않는다.
40. `LOCAL_FIXED` 쇼핑몰 안내 카드에서 동일한 공통 다운로드 모듈로 PC Agent ZIP을 만들고 AS 접수 화면으로 이동할 수 있으며, 실제 로그 기반 진단은 별도 PC Agent AI가 담당한다.

### 상태형 추천 체인 추가 예시

41. `LOCAL_FIXED` `램 하나 넣어줘`는 임의 상품을 고르지 않고 32GB·64GB·최저가 기준 칩을 먼저 보여준다.
42. `LOCAL_FIXED` 되묻기에 짧게 `32GB`라고 답해도 실제 32GB 이상 RAM 후보 3개로 이어진다.
43. `LOCAL_FIXED` 서버가 생성한 정확 상품 칩을 다시 보내면 빈 견적에서도 단일 변경 미리보기가 생성된다.
44. `LOCAL_FIXED` RAM/SSD를 실제 추가한 뒤 같은 조건을 다시 물으면 방금 담은 상품 대신 다른 후보를 채운다.
45. `LOCAL_FIXED` RAM 슬롯이 꽉 찬 경우 제품 3개를 억지로 만들지 않고 제거 또는 호환 문제 설명으로 전환한다.
46. `LOCAL_FIXED` `가성비` 칩은 최고가 제품이 아니라 카테고리별 객관적 가격 대비 기준으로 정렬한다.
47. `LOCAL_FIXED` 단순 가성비·최저가·고성능 부품 추천은 LLM을 거치지 않아 후보 50개 Tool 보충을 포함해도 실제 API에서 12~22ms에 완료된다.
48. `LOCAL_FIXED` 셀프견적에서 `CPU 추천해줘`처럼 변경 동사가 없는 질문도 최신 장바구니를 서버에 보내 후속 후보와 현재 상태가 충돌하지 않는다.
