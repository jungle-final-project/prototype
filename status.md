# 작업 상태

## 2026-07-14 AI 채팅 입력 자동완성·안내 영역 정리

- 현재 목표: 최신 `main` 기준으로 AI 채팅 입력의 브라우저 자동완성을 끄고 불필요한 상단 예시·하단 저장 안내를 제거한다.
- 완료: 중앙/측면 채팅 폼과 입력창에 `autoComplete="off"`를 적용하고, `이렇게 물어보세요` 빠른 질문 영역과 대화 기록 임시 저장 안내문을 삭제했다.
- 완료: 견적 체크리스트에서 열려 있는 CPU 등 같은 카테고리를 다시 누르면 후보 목록과 URL 선택이 함께 닫히도록 토글 동작을 추가했다. Enter·Space 키에도 같은 동작을 적용했다.
- 완료: 결제 버튼의 기본 표시 문구를 `토스 결제하기`에서 `결제하기`로 변경했으며 결제 동작은 유지했다.
- Git: EXE 작업 브랜치 `codex/self-quote-ui-main-merge`와 분리된 `codex/ai-chat-cleanup` 브랜치를 `origin/main` `7841145`에서 생성했다. `front-ui`는 이미 PR #164로 main에 포함되어 있어 merge 충돌은 없었다.
- CI 수정: 최신 `origin/main`을 병합하고 채팅 UI 충돌 1건을 해결했다. 최신 도킹형 UI와 닫기 동작은 유지하면서 자동완성 차단, 빠른 질문 삭제, 임시 저장 안내 삭제를 반영했다.
- 마지막 검증: 실패했던 Playwright `desktop-chromium` 테스트 4개를 단일 워커로 재실행해 4/4 통과했다. 전체 웹 테스트는 사용자 요청에 따라 실행하지 않았다. 이후 CI에서 발견된 빠른 질문 영역 관련 테스트 2개도 새 UI 계약에 맞춰 수정하고 해당 테스트만 재실행했다.

- CI follow-up: Adjusted the compact performance panel first-column offset from `-10px` to `-12px` after the latest main padding change, restoring checklist edge alignment.
- Verification: The single FPS performance-panel Playwright test passed on `desktop-chromium` (1/1).

## 현재 목표
- 실제 Windows Display PnP 장치, signed driver, 그래픽/WHEA/Kernel-Power 이벤트를 읽기 전용으로 수집하고 공통 evidence로 변환한다.

## 완료한 일
- 기존 PowerShell 숨김 실행 방식과 Provider 패턴을 재사용한 `WindowsGraphicsDiagnosticsProvider`를 추가했다.
- `Win32_PnPEntity`에서 Display 장치 status와 실제 `ConfigManagerErrorCode`를 수집한다.
- `Get-PnpDevice`/`Get-PnpDeviceProperty` 경로는 CIM 조회 실패 시 fallback으로 유지한다.
- `Win32_PnPSignedDriver`에서 공급자, 버전, 날짜, 서명 여부, signer, INF를 수집한다.
- provider/eventId 기반으로 최근 그래픽, WHEA, Kernel-Power 이벤트를 조회한다.
- 이벤트 없음, 조회 실패, 권한 부족을 서로 다른 상태로 보존한다.
- 기존 `DiagnosisEvidence`에 category, code, occurredAt, description 선택 필드만 확장했다.
- 정상 장치, problem code 22, 이벤트 없음/조회 실패 구분 테스트 3개를 추가했다.

## 마지막 검증 결과
- 변경 Python 파일 3개 문법 검사: 통과
- `python -m pytest -q test_windows_graphics_diagnostics.py test_diagnosis_result.py`: 17 passed
- 실제 read-only Provider smoke test: 통과
  - Intel(R) Iris(R) Xe Graphics: PnP OK, problem code 0
  - Intel(R) Arc(TM) A350M Graphics: PnP Error, problem code 43, DEVICE_REPORTED_PROBLEM
  - 두 Display driver 모두 signed=true, signer/INF 조회 성공
  - 최근 7일 그래픽 이벤트 0건, WHEA 0건, Kernel-Power 0건

## 남은 일
- 이번 단계 범위에는 없음. 판정, 결과 문구, UI, AS 연결은 다음 단계에서만 진행한다.

## 막힌 점 / 리스크
- 이 PC에서 직접 `Get-PnpDevice` 조회는 timeout됐지만, `Win32_PnPEntity` 우선 경로로 필수 PnP status/problem code를 실제 검증했다.
- `Get-PnpDevice` fallback 자체는 단위 테스트 대상이 아니며, CIM 조회 실패 환경에서만 실행된다.
