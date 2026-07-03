# 2026-07-03 PC Agent exe 이벤트 감지 모달 UI 적용

## 2026-07-03 이벤트 감지 모달 고정 액션 레이아웃

### 현재 목표

- 이벤트 감지 모달에서 하단 버튼/로그 링크가 Windows 배율과 본문 길이에 밀려 사라지지 않도록 프론트 레이아웃을 구조적으로 수정한다.
- 기존 감지 조건, IncidentWindow, gzip 생성, upload API 호출 구조는 유지한다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 이벤트 감지 모달을 `본문 영역`과 `고정 하단 액션 영역`으로 분리했다.
- 하단 액션 영역에 `접수하기`, `무시하기`, `로그 확인하기 ↗`를 고정 배치했다.
- `원격 접수` / `방문 접수` 신청 방식 선택은 유지했다.
- 모달 크기를 하단 액션이 잘리지 않는 기준으로 조정했다.

### 마지막 검증 결과

- `apps/pc-agent`: `python -m py_compile buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

### 남은 리스크

- 실제 PyInstaller exe 패키징 화면에서 Windows 배율별 시각 확인이 한 번 더 필요하다.
- exe 재빌드와 웹 다운로드용 `agent.exe` 교체는 수행하지 않았다.

## 2026-07-03 PC Agent 상태 홈 아이콘 깨짐 재수정

### 현재 목표

- 상태 홈 상단 카드 아이콘이 Windows/Tk 화면에서 깨져 보이는 문제를 수정한다.
- 기존 상태 홈 제목/부제목과 로그/AS 접수 흐름은 유지한다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 카드 아이콘을 Tk Canvas 직접 선 그리기에서 내부 생성 PNG 이미지 기반 표시로 바꿨다.
- Pillow/Tk 조합에서 이미지 생성이 실패할 경우에도 깨진 선 아이콘 대신 `OK`, `PC`, `UP`, `i` 텍스트 배지가 보이도록 fallback을 추가했다.

### 마지막 검증 결과

- `python -m py_compile apps/pc-agent/buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

## 2026-07-03 이벤트 감지 모달 버튼/신청 방식 보강

### 현재 목표

- 기존 이벤트 감지 모달의 감지/업로드 로직은 유지하고, 하단 버튼이 보이도록 UI를 보강한다.
- 모달 안에서 `원격 접수` / `방문 접수` 신청 방식을 선택할 수 있게 한다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 이벤트 감지 모달 높이를 조정해 하단 액션 영역이 보이도록 했다.
- 모달에 `신청 방식` 라디오 선택을 추가했다.
- `접수하기`, `무시하기`, `로그 보기 ↗` 구조를 유지했다.
- 선택한 신청 방식은 새 서버 필드를 만들지 않고 기존 증상 문구 앞에 붙여 업로드 요청에 포함하도록 했다.
- 기존 이벤트 감지 조건, IncidentWindow, gzip 생성, upload API 호출 구조는 변경하지 않았다.
- `apps/pc-agent/test_buildgraph_agent.py`에서 신청 방식 문구가 기존 업로드 요청 증상 문자열에 포함되는지 검증했다.

### 마지막 검증 결과

- `apps/pc-agent`: `python -m py_compile buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

### 남은 리스크

- Tkinter 라디오 버튼과 버튼 폭은 Windows 배율별로 실제 exe에서 한 번 더 확인해야 한다.
- exe 재빌드와 웹 다운로드용 `agent.exe` 교체는 수행하지 않았다.
- 커밋은 생성하지 않았다.

## 2026-07-03 PC Agent 상태 홈 헤더/카드 아이콘 재보정

### 현재 목표

- 상태 홈 상단의 빈 공간에 제목과 부제목을 표시한다.
- 깨져 보이던 상단 카드 아이콘을 더 안정적인 teal 배지형 라인 아이콘으로 수정한다.
- 로그/AS 접수/서버/API/DB 흐름은 변경하지 않는다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 상태 홈에 `상태 홈` 제목과 `PC Agent가 시스템을 안전하게 보호하고 있습니다.` 부제목을 추가했다.
- 카드 아이콘을 오른쪽 배치에서 왼쪽 고정 배지 배치로 바꿔 텍스트와 겹치거나 잘려 보이는 문제를 줄였다.

### 마지막 검증 결과

- `python -m py_compile apps/pc-agent/buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

## 현재 목표

- `codex/pc-agent-exe-ui-front` 브랜치에서 기존 이벤트 감지 패널의 감지/업로드 동작은 유지하고, 모달 UI만 두 번째 참고 이미지처럼 작고 차분한 카드형으로 바꾼다.
- 서버/API/DB/웹 화면, 로그 탭 구조, AS 접수 페이지 구조, 감지 조건, 업로드 API 흐름은 변경하지 않는다.

## 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 `event_panel_model()`에 모달 표시용 읽기 전용 값인 발생 시간, 감지 신호, 전송 구간 텍스트를 추가했다.
- `show_event_panel()`의 레이아웃을 작은 카드형 알림으로 정리했다.
- 기존 `로그 전송하고 AS 검토 요청` 버튼 문구를 `AS 접수하기`로 바꾸고, `나중에 보기`는 `무시하기`로 정리했다.
- `로그 보기 ↗` 링크를 추가해 누르면 기존 exe 로그 뷰어가 해당 감지 신호 시간대로 열린다.
- 이벤트 감지 조건, `IncidentWindow`, `gzip_window()`, `upload_gzip()` 흐름은 유지했다.
- `apps/pc-agent/test_buildgraph_agent.py`에 모달 표시 모델의 발생 시간/감지 신호/전송 구간 검증을 추가했다.

## 검증

- `apps/pc-agent`: `python -m py_compile buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

## 남은 리스크

- Tkinter 창 위치, topmost 해제, Windows 배율별 버튼 폭은 실제 exe 패키징 환경에서 한 번 더 눈으로 확인해야 한다.
- exe 재빌드와 웹 다운로드용 `agent.exe` 교체는 수행하지 않았다.
- 커밋은 생성하지 않았다.

# 2026-07-03 PC Agent exe 이벤트 감지 모달 디자인 기획

## 현재 목표

- `codex/pc-agent-exe-ui-front` 브랜치에서 PC Agent exe의 이벤트 감지 모달/알림 디자인 방향만 정리한다.
- 코드 구현, 상태 홈 UI 동작 변경, 로그 탭, AS 접수 화면, 서버/API/DB/웹 화면 수정은 하지 않는다.
- 기존 빨간 경고 느낌을 피하고, 현재 상태 홈과 맞는 teal/blue/muted amber 계열의 차분한 `확인 필요` 톤으로 정리한다.

## 작업 전 확인

- 현재 브랜치: `codex/pc-agent-exe-ui-front`.
- `git status --short` 확인 결과 기존 미커밋 변경이 있었다:
  - 수정됨: `apps/pc-agent/README.md`, `apps/pc-agent/buildgraph_agent.py`, `apps/pc-agent/test_buildgraph_agent.py`, `status.md`
  - 미추적: `docs/agent-as/AI_TEAM_LOG_SCENARIO_HANDOFF.md`, `docs/agent-as/PC_AGENT_EXE_MONITORING_ADDITIONS_ONLY.md`, `docs/agent-as/PC_AGENT_EXE_UI_DESIGN_OPTIONS.md`
- `status.md`를 읽고, 이전 PC Agent exe UI/이벤트 패널 작업 기록을 확인했다.
- `docs/agent-as/PC_AGENT_EXE_UI_DESIGN_OPTIONS.md`를 읽고, 기존 전체 exe UI 디자인 옵션을 확인했다.
- `apps/pc-agent/buildgraph_agent.py`는 상태 홈 톤 확인용으로만 읽었다. 확인한 톤은 `#f5f7f8`, `#e7f2ef`, `#1f8a70`, `#d7e0e3` 중심의 차분한 teal 계열이다.

## 완료한 일

- `imagegen` built-in tool로 preview-only 이벤트 감지 모달/알림 시안 3개를 생성했다.
  - 상태 홈 중앙 모달
  - 우측 하단 작은 알림 패널
  - 상태 홈 내부 카드형 알림
- `docs/agent-as/PC_AGENT_EXE_UI_DESIGN_OPTIONS.md`에 이벤트 감지 모달/알림 디자인 옵션 섹션을 추가했다.
- 각 안의 목적, 장점, 단점, 적합한 사용 상황을 정리했다.
- 1차 추천안은 `상태 홈 내부 카드형 알림`으로 선정했다.
- 백그라운드 상황의 보조안은 `우측 하단 작은 알림 패널`로 남겼다.
- `AS 접수하기`는 선택안으로만 두고, 기본 강조 버튼은 `로그 보기`로 둔다는 기준을 명시했다.
- 나중에 Tkinter로 구현할 때의 `Toplevel`, `topmost`, wraplength, 버튼 우선순위, 민감정보 비노출, 메인 스레드 UI 업데이트 주의점을 정리했다.

## 검증

- 코드 수정은 하지 않았다.
- `apps/pc-agent/buildgraph_agent.py`는 읽기만 했고 수정하지 않았다.
- 테스트 코드 수정은 하지 않았다.
- 테스트/빌드는 실행하지 않았다. 이번 작업은 디자인 문서와 preview 이미지 정리 범위다.
- 커밋은 생성하지 않았다.

## 남은 리스크

- 생성 이미지는 방향 검토용 preview이며 workspace asset으로 저장하지 않았다.
- 실제 Tkinter 구현 시 Windows 배율, 폰트 렌더링, topmost 동작은 별도 확인이 필요하다.
- 현재 작업트리에는 이번 작업 전부터 있던 PC Agent 관련 미커밋 변경이 남아 있다. 이번 작업에서는 되돌리거나 정리하지 않았다.

# 2026-07-03 PC Agent exe 이벤트 감지 우측 패널 UI

## 현재 목표

- 기존 Tkinter 상태 홈은 유지하고, 이벤트 감지 시 중앙 모달이 아닌 오른쪽 아래 Blue/Teal 알림 패널을 띄운다.
- 패널은 진단 확정이 아니라 AS 검토 시작 여부를 묻는 알림으로 제한한다.
- 서버/API/DB/웹 관리자 화면, exe 재빌드, 웹 다운로드용 `agent.exe` 교체는 수행하지 않는다.

## 완료한 일

- `detect_recent_signals()` 결과 중 driver/app/network 오류 반복, WHEA/BSOD, Kernel-Power, SMART, thermal 계열 코드만 우측 이벤트 패널 표시 대상으로 필터링했다.
- Tkinter 우측 하단 이벤트 패널을 추가했다. 제목, 최신 감지 시간, 사용자용 요약 최대 3개, “자동 AS 확정 아님. 정확한 판단은 로그 전송 후 확인합니다.” 문구를 표시한다.
- `로그 전송하고 AS 검토 요청` 버튼은 기존 `IncidentWindow` + `gzip_window()` + `upload_gzip()` 흐름을 재사용해 `/api/agent/log-uploads` 업로드와 ticket 생성까지 이어지게 했다.
- 등록, 동의, 서버 연결, 로그 없음 문제는 raw 오류 대신 사용자용 실패 메시지로 표시한다.
- `상세 확인하기` 버튼은 상태 홈을 열고 해당 감지 신호의 날짜/시간 로그로 이동하게 했다.
- `나중에 보기` 버튼은 패널만 닫고 백그라운드 수집을 유지한다.
- token, Authorization header, activation token, raw path, 전체 process list, raw payload는 패널에 노출하지 않는다.
- 상태 홈/이벤트 패널/PowerShell fallback/tray tooltip의 `BuildGraph Agent` 계열 화면 표기를 제거하고, 제거한 브랜드 영역은 빈 spacer로 유지해 기존 레이아웃 높이와 간격을 유지했다.
- 좌측 메뉴 `상태`, `로그`, `AS 접수`, `설정`에 새 의존성 없이 Tkinter 텍스트 아이콘을 추가했다.
- Tkinter 상태 홈과 로그 화면을 실제 `status_view`/`log_view` content frame 전환 구조로 분리했다.
- 처음 실행 시 상태 탭에는 상단 카드와 최근 감지 신호만 표시하고, 날짜/시간 필터, `1시간 로그`, `현재`, 로그 테이블, 범위 표시는 숨긴다.
- 로그 탭에서만 날짜 입력, 시간 선택, `1시간 로그`, `현재`, 로그 테이블, 선택 범위 표시를 보여준다.
- 상태 탭의 최근 감지 신호와 이벤트 패널 `상세 확인하기`는 로그 탭으로 이동하면서 해당 날짜/시간대를 자동 선택하고 refresh한다.
- 로그 탭에 표시할 row가 없으면 `표시할 로그가 없습니다` empty state를 표시한다.
- README에 우측 이벤트 패널 동작을 짧게 문서화했다.

## 검증

- `python -m py_compile apps/pc-agent/buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 31개 테스트 통과.

## 남은 리스크

- Tkinter 창 표시와 topmost 동작은 실제 Windows exe 패키징 환경에서 한 번 더 확인해야 한다.
- 현재는 명확한 이벤트가 로그에 남은 뒤 다음 수집 루프에서 패널을 띄우는 방식이다.
- exe 재빌드와 웹 다운로드용 `agent.exe` 교체는 수행하지 않았다.
- 커밋은 생성하지 않았다.

# 2026-07-03 PC Agent exe 상태 홈 UI visual pass

## 현재 목표

- `codex/pc-agent-exe-ui-front` 브랜치에서 PC Agent exe의 Tkinter 상태 홈 기능 틀은 유지하고, 기본 Tk 회색 화면 느낌을 줄이는 1차 visual pass를 진행한다.
- 서버/API/DB/웹 화면, heartbeat 신규 호출, 업로드/AS 마법사, 위험 모달은 추가하지 않는다.

## 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 `show_log_viewer()` 중심으로 Tkinter 배경, 좌측 메뉴, 상태 카드, 최근 신호, 1시간 로그 테이블의 여백과 정렬을 정리했다.
- 좌측 메뉴는 선택된 `상태` 항목이 흰 배경과 좌측 포인트 바를 갖도록 바꿨다.
- 상단 4개 카드는 균일한 높이, 간격, 테두리, 배경색을 갖는 그리드로 정리했다.
- 최근 감지 신호는 버튼 대신 클릭 가능한 한 줄 항목 형태로 바꾸고, 기존 신호 선택 시 해당 시간대 로그로 이동하는 동작은 유지했다.
- 로그 테이블은 Treeview 스타일, 헤더, row height, 컬럼 폭, 세로/가로 스크롤, alternate row 배경을 정리했다.
- UI 실행 실패 원인을 확인했다. bare `python`은 시스템 Python 3.10을 가리키며 `Tk()` 생성 시 Tcl/Tk 오류가 발생했고, `%LOCALAPPDATA%\BuildGraphAgent\agent-config.json`은 다른 Windows 사용자 ACL로 제한되어 현재 Codex 실행 계정에서 읽을 수 없었다.
- 같은 상태 홈을 개발 검증에서 재현 가능하게 열 수 있도록 `viewer --config ...` CLI를 추가했다.
- README에 `.venv` Python과 명시 config를 사용하는 상태 홈 실행법을 추가했다.
- 기존 UI 모델 함수, 로그 파싱, 최근 신호 탐지, tray 실행, 로그 폴더 열기, AS 페이지 열기, CLI 경로는 변경하지 않았다.

## 검증

- `apps/pc-agent`: `python -m py_compile buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 28개 테스트 통과.

## 남은 리스크

- Tkinter/ttk 테마는 Windows 런타임과 Python 배포 환경별로 세부 색상과 테두리 표현이 다를 수 있어 실제 exe 화면 확인이 필요하다.
- 이번 작업은 visual pass이며 PyInstaller exe 재빌드와 웹 다운로드 파일 교체는 수행하지 않았다.
- 커밋은 생성하지 않았다.

# 2026-07-03 FINAL_SUPPORT_SCENARIOS 100% 구현 Goal

## 2026-07-03 PC Agent 상태 홈 카드 아이콘/사이드바 탭 보정

### 현재 목표

- PC Agent 상태 홈 상단 카드에 이미지 기준의 간단한 teal 라인 아이콘을 추가한다.
- 왼쪽 사이드바 탭 시작 위치와 높이만 보정한다.
- 로그/AS/상태 홈 본문 구조는 변경하지 않는다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 Tk 상태 홈 상단 카드 4개에 canvas 기반 아이콘을 추가했다.
- 빈 로고 영역 때문에 내려가 있던 사이드바 탭 시작 위치를 위로 올렸다.
- 사이드바 탭 높이와 간격을 소폭 정리했다.

### 마지막 검증 결과

- `python -m py_compile apps/pc-agent/buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 33개 테스트 통과.

## 2026-07-03 PC Agent 이벤트 감지 모달 시안 이미지 생성

### 현재 목표

- 1번째 상태 홈 UI를 메인 방향으로 유지하고, 이벤트 감지 시 표시할 모달 디자인을 별도로 비교한다.
- 기존 빨간색 위험 모달보다 덜 강렬한 UI 톤을 찾는다.

### 완료한 일

- `imagegen` built-in tool로 preview-only 이벤트 감지 모달 시안 3개를 생성했다.
  - muted amber 좌측 강조 모달
  - blue/teal 중앙 검토 모달
  - lower-right compact 검토 패널형 모달

### 마지막 검증 결과

- 코드 수정은 하지 않았다.
- 생성 이미지는 구현 후보 검토용 preview이며, 아직 workspace asset으로 저장하지 않았다.
- 테스트/빌드는 실행하지 않았다.

## 2026-07-03 PC Agent 상태 홈 프론트 1차 구현

### 현재 목표

- `codex/pc-agent-exe-ui-front` 브랜치에서 PC Agent exe 프론트만 상태 홈 1차 UI로 변경한다.
- 서버/API/DB/웹 화면, heartbeat 신규 호출, 로그 업로드/AS 접수 마법사, 위험 모달, exe 재빌드는 제외한다.

### 완료한 일

- `apps/pc-agent/buildgraph_agent.py`의 Tk 로그 뷰어 첫 화면을 상태 홈 구조로 바꿨다.
- 좌측 내비게이션, 상단 상태 카드, 최근 신호, 1시간 로그 테이블을 추가했다.
- PowerShell fallback 로그 뷰어도 상태 홈 형태로 갱신했다.
- 최근 신호는 최종 11종 기준으로 현재 로그에서 실제 매칭되는 항목만 최대 3개 표시하도록 했다.
- 단순 CPU/RAM/GPU 고사용률만으로는 최근 신호에 올리지 않도록 했다.
- 로그 테이블은 flat row와 envelope row를 모두 읽고, token/raw path/process list 전체 payload를 화면에 노출하지 않는 표시 모델을 사용한다.
- `apps/pc-agent/README.md`에 상태 홈 범위와 실행 기준을 반영했다.
- `apps/pc-agent/test_buildgraph_agent.py`에 envelope row 시간 처리, 신호 매핑, 고사용률 제외, 민감정보 비노출 테스트를 추가했다.

### 마지막 검증 결과

- `apps/pc-agent`: `python -m py_compile buildgraph_agent.py` 성공.
- `apps/pc-agent`: `python -m unittest -q` 성공. 총 28개 테스트 통과.
- 커밋 `ed925c0 feat(pc-agent): add status home UI`를 `pcagent/codex/pc-agent-exe-ui-front`에 push 완료.
- 실제 `dist/agent.exe` 재빌드와 웹 다운로드 exe 교체는 이번 범위에서 수행하지 않았다.

### 남은 일

- 사용자가 실제 exe 산출물 갱신을 요청하면 별도 단계에서 PyInstaller 빌드와 다운로드 asset 교체 여부를 다시 결정해야 한다.
- 서버 연결을 실제 heartbeat 기반으로 갱신하려면 15분 주기, 비차단 실패 처리, 로컬 상태 저장 정책을 별도 런타임 작업으로 설계해야 한다.

## 2026-07-03 PC Agent exe UI 시안 이미지 생성

### 현재 목표

- `docs/agent-as/PC_AGENT_EXE_UI_DESIGN_OPTIONS.md` 기준으로 구현 전에 UI 방향을 이미지로 먼저 확인한다.
- 새 브랜치에서 프론트/UI 작업만 이어갈 수 있게 준비한다.

### 완료한 일

- `codex/pc-agent-exe-ui-front` 브랜치를 새로 생성했다.
- 기존 미커밋 변경은 되돌리지 않고 그대로 보존했다.
- `imagegen` built-in tool로 preview-only UI 시안 3개를 생성했다.
  - 트레이 중심 최소안
  - AS 접수 마법사형
  - 대시보드 + 위험 알림형

### 마지막 검증 결과

- 코드 수정은 하지 않았다.
- 생성 이미지는 구현 후보 검토용 preview이며, 아직 workspace asset으로 저장하지 않았다.
- 테스트/빌드는 실행하지 않았다.

## 2026-07-03 PC Agent exe UI 디자인 기획 문서

### 현재 목표

- 지금까지 정리한 Agent AI AS 기획을 바탕으로 PC Agent exe UI 수정 방향을 디자인 시안용 Markdown으로 저장한다.

### 완료한 일

- 현재 exe UI 구현을 확인했다: 트레이 메뉴, 로그 뷰어, 날짜/시간 필터, CPU/MEM/Event/Message 표시, 웹 AS 페이지 열기.
- 현재 exe 로그 수집 상태를 문서 기준과 대조했다: CPU/RAM/Disk 일부는 실제 수집, GPU/온도/top process/driver warning은 demo 값이다.
- `docs/agent-as/PC_AGENT_EXE_UI_DESIGN_OPTIONS.md`를 새로 생성했다.
- 문서에는 트레이 중심 최소안, AS 접수 마법사형, Agent 상태 대시보드형, 위험 알림 우선형, 웹 연결 강조형을 분리해 정리했다.
- 단순 CPU/RAM/GPU 고사용률만으로 AS 접수를 유도하지 않는 기준을 UI 문서에도 반영했다.

### 마지막 검증 결과

- `Get-Content -Encoding UTF8 docs\agent-as\PC_AGENT_EXE_UI_DESIGN_OPTIONS.md`로 문서가 UTF-8 기준 정상 표시되는 것을 확인했다.
- 코드 수정은 하지 않았다. 이번 작업은 UI 디자인용 문서 추가다.

## Current goal

- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md`의 최종 기획을 기준으로 PC Agent AS 기능을 구현한다.
- 기존 공통 계약(`docs/API_CONTRACT.md`, `docs/DB_SCHEMA.md`, `docs/openapi.yaml`, Flyway, Java public enum/DTO/API 계약)은 삭제/변경/축소하지 않고, 필요한 항목만 additive로 확장한다.
- 원격 push는 하지 않는다.

## Done

- Goal objective 파일 `C:\Users\whqja\.codex\attachments\ee35ec0c-ac83-440c-8454-58287709c08d\goal-objective.md`를 UTF-8로 읽었다.
- `skill-router` 기준 primary skill을 `goal-handoff`로 선택했고, `git-workflow`, `code-review`, `wiki-writing`을 보조로 확인했다.
- 기준 원격 `https://github.com/jungle-final-project/PCagent.git`가 `pcagent` remote임을 확인했다.
- 기존 작업트리 변경은 `pre-final-support-complete-20260703-131037` stash로 백업했다.
- `git fetch pcagent --prune` 성공 후 `pcagent/main`이 `6e2ba0b docs(agent-as): add final support scenarios`임을 확인했다.
- 기존 `qa/final-support-scenarios-complete` 브랜치를 덮어쓰지 않고 `qa/final-support-scenarios-complete-20260703-131130` 브랜치를 `pcagent/main` 기준으로 생성했다.
- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md` 존재를 확인했다.
- clean main 기준 baseline 검증을 실행했다.

## Baseline verification

- `python tools\validate_openapi.py`: 성공, `OpenAPI validation passed: 67 paths`.
- `apps\web`: `npm run build` 성공.
- `apps\api`: `.\gradlew.bat compileTestJava --no-daemon` 성공.
- `apps\api`: 한글 경로 `C:\나만무\prototype`에서 `.\gradlew.bat test --no-daemon` 실패. 원인은 여러 테스트 클래스 `ClassNotFoundException`으로, assertion 실패 전 단계다.
- ASCII junction `C:\codex\pcagent-prototype`에서 `.\gradlew.bat test --no-daemon` 실패. clean main baseline failure는 `AgentAsMigrationContractTest.logSummaryMigrationAddsLogSummaryRoutingAndExceptionApprovalFields()` line 92 assertion 1건이다.
- `apps\web`: `npm run test -- --reporter=dot`는 300초 timeout. 실행 중 1개 실패 표시와 Vite proxy `EACCES` 로그가 반복됐다.

## Done in current implementation pass

- 최종 시나리오 기준으로 analyzer가 신규 흐름에서는 `SELF_SOLVABLE`, `REMOTE_POSSIBLE`, `VISIT_REQUIRED`, `NEEDS_MORE_INFO`만 생성하도록 보정했다. 기존 `REPAIR_OR_REPLACE`, `MONITOR_ONLY`, `UNSUPPORTED` enum은 호환성 때문에 삭제하지 않았다.
- 원격 6종, 방문 5종, 기본 미지원 항목의 reason/action/visit/blocking enum을 additive로 확장했다.
- `safetyAdviceLevel`, `safetyNotices`, 단계별 consent type, 사용자 원격지원 요청 API, 사용자 feedback API, 관리자 `diagnosticAccuracy`를 코드/OpenAPI/API/DB 문서/Flyway에 추가했다.
- 사용자 `/support/{ticketId}` 화면에 위험 안내, 원격지원 요청, 처리 피드백 저장을 연결했다.
- 관리자 AS 상세 화면에 진단 적중 여부 저장과 사용자 피드백 표시를 연결했다.
- Playwright visual fixture와 QA screenshots를 최종 IncidentWindow/안전 안내/원격지원 요청/피드백 흐름 기준으로 갱신했다.

## Final verification

- `python tools\validate_openapi.py`: 성공, `OpenAPI validation passed: 69 paths`.
- `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentLogAnalyzerTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --tests com.buildgraph.prototype.agent.persistence.AgentAsJpaMappingTest --tests com.buildgraph.prototype.agent.persistence.AgentAsMigrationContractTest --tests com.buildgraph.prototype.ticket.contract.SupportContractSerializationTest --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --tests com.buildgraph.prototype.ticket.TicketControllerTest --no-daemon`: 성공.
- `C:\codex\pcagent-prototype\apps\api` `.\gradlew.bat test --no-daemon`: 성공.
- `apps\web` `npm run build`: 성공.
- `apps\web` `npm run test -- --reporter=dot`: escalated 실행에서 74 passed.
- `git diff --check`: 성공.

## Remaining risks

- 한글 경로 `C:\나만무\prototype`에서 Gradle full test가 `ClassNotFoundException`으로 실패하는 baseline/environment 문제가 있어 backend test는 ASCII junction `C:\codex\pcagent-prototype`에서 검증했다.
- sandbox 기본 권한에서는 Playwright가 Vite proxy `EACCES`로 timeout될 수 있어, 최종 프론트 테스트는 escalated 환경에서 검증했다.
- 원격 push와 커밋은 하지 않았다.

## Runtime check

- `docker --version`: Docker CLI 확인됨. 단, `C:\Users\whqja\.docker\config.json` 접근 경고가 표시됐다.
- `docker compose config --quiet`: 성공.
- `localhost:8080`, `localhost:5173`: 현재 포트는 비어 있음.
- `docker compose up --build -d`: 실패. 원인은 Docker Desktop Linux engine daemon 미실행(`npipe:////./pipe/dockerDesktopLinuxEngine` 없음).
- 바로 화면 확인하려면 Docker Desktop을 켠 뒤 `docker compose up --build`를 다시 실행해야 한다.

# 2026-07-03 Agent AS B 서버 LogSummary/routing 구현

## Current goal

- `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md` 기준 B 담당 범위만 구현한다.
- 업로드 RawLog 검증, IncidentWindow 기반 LogSummary, rule routing, 관리자 최종 decision/예외 승인, LLM 요청 payload 제한을 서버에서 처리한다.
- 커밋/푸시는 사용자 승인 전까지 진행하지 않는다.

## Done

- `PcAgentLogAnalyzer`를 추가해 RawLog 필수 필드 검증, JSONL 한 줄 파싱 실패 시 전체 실패, raw path 미마스킹 reject, event message 마스킹, IncidentWindow 필터링, rule 기반 `LogSummary`/`supportRouting`/`AiDiagnosisRequest` 생성을 구현했다.
- 원격 6종, 방문 5종, 미지원 7종 routing rule을 추가했다.
- `rawSamples`는 `evidenceRefs`와 연결된 로그만 최대 20개로 제한하고, sample payload의 긴 list를 잘라 전체 프로세스 목록이 LLM 요청에 들어가지 않게 했다.
- `/api/agent/log-uploads`에서 최근 30분 고정 대신 증상별 IncidentWindow를 저장/연결하고, `agent_log_uploads`, `as_tickets`에 summary/routing/AI 요청 JSONB를 저장하도록 변경했다.
- 관리자 `PATCH /api/admin/as-tickets/{id}`에서 `UNSUPPORTED` 기본 예약 차단과 예외 승인 필수 필드, audit metadata, 전환 후 decision 저장을 구현했다.
- JPA entity, support decision enum, Flyway V56 migration, OpenAPI/API/DB 문서를 갱신했다.
- 관련 단위 테스트를 추가/수정했다.

## Remaining issues

- 한글 경로 `C:\나만무\prototype`에서 Gradle test worker가 테스트 클래스를 `ClassNotFoundException`으로 로드하지 못하는 기존 문제가 재현됐다. 테스트 클래스 산출물은 존재하고 `javap` 로드는 가능하지만, Gradle `test` 태스크가 실행 단계 전에 중단된다.
- 커밋/푸시는 하지 않았다.

## Last verification

- `cd apps\api; .\gradlew.bat compileTestJava --no-daemon` 성공.
- `python tools\validate_openapi.py` 성공. 결과: `OpenAPI validation passed: 67 paths`.
- `git diff --check` 성공.
- 변경 범위 테스트 명령은 실패: `.\gradlew.bat test --tests PcAgentLogAnalyzerTest --tests PcAgentAsServiceTest --tests TicketQueryServiceTest --tests AgentAsJpaMappingTest --tests AgentAsMigrationContractTest --no-daemon`. 원인은 각 테스트 클래스 `ClassNotFoundException`이며 assertion 실패는 확인되지 않았다.

# 2026-07-02 PCagent main Agent AS runtime QA

## Current goal

- `pcagent/main` 최신 커밋 `961d0ba` 기준으로 서버, 웹, PC Agent register/consent/heartbeat/upload/supportDecision happy path를 실제 런타임에서 검증한다.

## Done

- `qa/pcagent-main-runtime-qa` 브랜치를 `pcagent/main` 최신 커밋에서 생성했다.
- 문서 기준은 `docs/agent-as/E2E_HAPPY_PATH.md`, `docs/agent-as/README.md`, `apps/pc-agent/README.md`를 확인했다.
- 기존 Compose DB volume은 Flyway V53/V54 checksum mismatch가 있어 건드리지 않고, 별도 QA 컨테이너를 사용했다.
- QA API: `http://127.0.0.1:18080`, QA Web: `http://127.0.0.1:15173`, QA DB: `pcagent-postgres-runtime-qa`로 격리 실행했다.
- register 전 `agent status`는 `UNREGISTERED`, register 후 `REGISTERED`를 확인했다.
- `SERVER_UPLOAD` consent accepted, heartbeat `ACTIVE`, gzip upload, `ticketId` 반환을 확인했다.
- 관리자 `PATCH /api/admin/as-tickets/{ticketId}`로 `supportDecision=REMOTE_POSSIBLE`, `reviewStatus=APPROVED` 저장을 확인했다.
- 사용자 `GET /api/as-tickets/{ticketId}`와 웹 `/support/{ticketId}` 화면에서 `RULE_READY`, `APPROVED`, `REMOTE_POSSIBLE` 반영을 확인했다.
- 같은 upload `Idempotency-Key` 재시도 시 같은 `ticketId`가 반환되는 것을 확인했다.
- 화면 증거: `artifacts/qa/pcagent-support-decision.png`.

## Fixed

- multipart upload 요청을 generic Agent idempotency filter가 body caching으로 소비해 `file` part가 사라지는 문제를 수정했다.
- `/api/agent/log-uploads`는 service 레벨에서 `agent_upload_jobs(device_id,idempotency_key)` 기준 replay/conflict를 처리하도록 수정했다.
- upload SQL에서 `Instant`와 `delete_after` timestamp 파라미터 타입 추론 오류를 수정했다.
- PC Agent gzip 생성이 재시도마다 다른 gzip bytes를 만들지 않도록 deterministic gzip `mtime=0`을 적용했다.
- missing `Idempotency-Key` header도 공통 `VALIDATION_ERROR` 응답으로 처리되도록 보강했다.

## Remaining issues

- 기존 Compose DB volume은 V53/V54 checksum mismatch 상태다. 이번 QA에서는 데이터 삭제를 하지 않고 별도 QA DB로 우회했다.
- `apps/pc-agent`의 `supportUrl` 생성은 `apiBaseUrl`이 기본 `:8080`일 때만 웹 `:5173`으로 바꾼다. 이번 격리 QA처럼 `:18080`을 쓰면 출력 URL은 API 포트 기준이라 실제 웹 URL은 `http://127.0.0.1:15173/support/{ticketId}`를 사용해야 했다.

## Last verification

- `.\gradlew.bat test --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon` 성공. 한글 경로 ClassNotFound 기존 이슈를 피해 `C:\codex\pcagent-prototype` junction에서 실행했다.
- `docker build -t prototype-api:latest .\apps\api` 성공.
- `docker build -t prototype-web:latest .\apps\web` 성공.
- `python tools\validate_openapi.py` 성공. 결과: `OpenAPI validation passed: 63 paths`.
- `docker compose config --quiet` 성공.
- 최종 QA ticketId: `9e39f4bd-440a-439d-b690-6457ec3e0354`.

# Agent AS Goal 4/5 status

Updated: 2026-07-02

## Member A scope

- Goal 4: Agent Register + Consent hardening
- Goal 5: Agent Heartbeat hardening
- This note records policy points that remain ambiguous in the project docs. No new feature behavior is introduced here.

## Confirmed current behavior

- `POST /api/agent/devices/register` is the only `/api/agent/**` endpoint allowed before Agent token authentication.
- `POST /api/agent/consents`, `POST /api/agent/heartbeat`, and `POST /api/agent/log-uploads` require an Agent bearer token.
- Agent mutation APIs require `Idempotency-Key`, including heartbeat under the current implementation.
- Register returns the raw agent token only in the response and stores only `agent_token_hash`.
- Log upload checks accepted `SERVER_UPLOAD` consent before creating upload/ticket rows.

## Need confirmation

- Register duplicate policy is not fully settled by the contract docs.
- Current MVP behavior refreshes the existing device token when the same user and `registrationIdempotencyKey` are reused.
- It is still unclear whether the same `activationToken` may register multiple devices.
- It is still unclear whether duplicate `deviceFingerprintHash` with a new `registrationIdempotencyKey` should create a new device, reject with conflict, or rotate the existing token.
- Consent update policy is not fully settled. Current behavior appends a consent row instead of updating an older row.

# Agent AS contract/web QA follow-up

Updated: 2026-07-02

## Security boundary

- Web Agent/RAG session APIs stay on web JWT:
  - `POST /api/agent/sessions`
  - `POST /api/agent/sessions/{id}/run`
  - `GET /api/agent/sessions/{id}`
- PC Agent token security applies only to PC Agent lifecycle endpoints:
  - `POST /api/agent/devices/register`
  - `POST /api/agent/consents`
  - `POST /api/agent/heartbeat`
  - `POST /api/agent/log-uploads`
- `POST /api/agent/devices/register` remains bootstrap-only and must not receive an Authorization header.
- `/api/agent-logs/upload` remains the web JWT/manual upload path. `/api/agent/log-uploads` is the PC Agent token upload path.

## Demo account and download notes

- Demo user/admin credentials must be prepared from the auth seed or shared by the integration owner before QA. Do not hard-code new demo credentials in the web app.
- Latest main provides a demo `agent.exe` download at `/downloads/pc-agent/agent.exe`, backed by `apps/web/public/downloads/pc-agent/agent.exe`.
- The sample JSONL download remains a manual AS upload fallback for QA environments where the Windows runtime is not used.
- When `agent.exe` changes, verify the web download path, `README.txt`, SHA256, and the local `agent-config.json` path documented by the PC Agent runtime.

## Main merge verification commands

Run these after every main merge touching Agent AS, support UI, or OpenAPI:

```powershell
python tools\validate_openapi.py
cd apps\api
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.AgentSecurityChainTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --no-daemon
.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon
cd ..\..
cd apps\web
npm run build
npm run test -- --reporter=dot
```
## Agent AS contract/web/QA P1-P3 completion

- 기준 브랜치: `integration/agent-as-e2e`
- 완료 범위:
  - P1: 사용자 `/support/{ticketId}` 화면 QA, 관리자 `supportDecision`/`reviewStatus`/`riskLevel`/`adminNote`/`remoteSupportLink` 저장 UI, 저장 후 사용자 화면 반영 Playwright QA, demo USER/ADMIN 계정 및 테스트 데이터 절차 문서화, 최신 main의 `agent.exe` 다운로드와 샘플 JSONL fallback을 runbook에 고정.
  - P2: `RULE_READY`, `REQUIRED`, `REMOTE_POSSIBLE`, `NEEDS_MORE_INFO` 및 관련 support enum 한글 배지 표시, 사용자/관리자 AS 화면 raw enum 노출 축소, 데모용 업로드 실패 메시지 유지, frontend DTO와 backend/OpenAPI 필드 불일치 방지 테스트 보강.
  - P3: `tools/validate_openapi.py`가 PC Agent token 경계와 AS ticket decision schema를 검증하도록 강화, OpenAPI client generation 검토 결과 문서화, 운영 장애 대응/runbook/발표 시나리오 정리.
- 추가 문서:
  - `docs/agent-as/DEMO_RUNBOOK.md`
  - `docs/agent-as/README.md` runbook 링크
- 실행 검증:
  - `git diff --check`: pass
  - `C:\Users\82103\anaconda3\python.exe tools\validate_openapi.py`: pass, 67 paths
  - `cd apps\api; .\gradlew.bat test --tests com.buildgraph.prototype.ticket.TicketQueryServiceTest --tests com.buildgraph.prototype.config.security.AgentSecurityChainTest --tests com.buildgraph.prototype.config.security.PcAgentControllerSecurityTest --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon`: pass
  - `cd apps\api; .\gradlew.bat test --no-daemon`: pass
  - `cd apps\web; npm run build`: pass
  - `cd apps\web; npm run test -- --reporter=dot`: pass, 74 tests
- 제외/주의:
  - Quick Assist 직접 실행, Windows Service, signed installer, auto-update, release channel 운영은 범위 밖으로 유지.
  - sandbox 내부에서 Gradle wrapper와 Playwright proxy가 네트워크 권한에 막혀, 필요한 테스트는 승인된 escalated 실행으로 검증했다.

## PC Agent exe runtime wiring check

Updated: 2026-07-03

- 확인 목표: `agent.exe`가 PC metric JSONL을 만들고 `/api/agent/log-uploads`로 올린 뒤, 생성된 AS ticket이 웹 `/support/{ticketId}`에서 조회되는지 확인.
- 실행 상태:
  - 기존 compose DB는 Flyway checksum mismatch로 API가 실패했다.
  - 기존 DB 볼륨은 삭제하지 않고, 임시 컨테이너 `pcagent-demo-postgres`, `pcagent-demo-api`, `pcagent-demo-web`로 런타임을 확인했다.
  - `http://localhost:8080/api/health`: pass
  - `http://localhost:5173/api/health`: pass
  - 웹 다운로드 `http://localhost:5173/downloads/pc-agent/agent.exe` SHA256이 repo 파일과 일치: `44791862BD8A33869F7D33891078FDACAFC180AC11EC87F801EE0EE6EE198456`
- 수정 내용:
  - `apps/pc-agent/buildgraph_agent.py`: Agent JSONL row에 서버 필수 envelope(`schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, `privacyFlags`)를 추가.
  - `apps/pc-agent/buildgraph_agent.py`: multipart text field에 `Content-Type: text/plain; charset=utf-8` 추가.
  - `apps/api/src/main/java/com/buildgraph/prototype/agent/PcAgentAsService.java`: Spring multipart text decoding 후 깨진 UTF-8로 보이는 값을 한글로 복원.
  - `apps/web/public/downloads/pc-agent/agent.exe`: 수정된 PC Agent로 재빌드 후 교체.
- 검증:
  - `python -m pytest apps\pc-agent -q`: pass, 24 tests
  - `.\gradlew.bat test --tests com.buildgraph.prototype.agent.PcAgentAsServiceTest --no-daemon`: pass
  - `agent.exe register --config artifacts\runtime\pcagent-demo\agent-config.json`: pass
  - `agent.exe collect --config ... --iterations 1`: pass
  - `POST /api/agent/consents`: pass
  - `POST /api/agent/heartbeat`: pass
  - `agent.exe upload --config ... --symptom-type REMOTE_DRIVER_OS --no-open`: pass, latest ticket `2116e340-6534-4769-a773-d0e90d36f8bb`
  - DB 직접 확인: latest ticket `symptom` is stored as `게임 중 화면 드라이버 경고와 발열이 있습니다.`
- 남은 주의:
  - 현재 PC Agent는 Windows Service/installer가 아니라 MVP용 CLI/트레이 실행이다.
  - 더블클릭 트레이의 로그 뷰어는 PyInstaller 빌드 중 tkinter broken 경고가 있어 별도 GUI 확인이 필요하다. CLI register/collect/upload는 정상 확인했다.

## AI team AS log/scenario handoff note

Updated: 2026-07-03

- 사용자 요청: 자동 AS 로그가 올라온 뒤 사용자에게 1차 증상을 보여주는 흐름과 AI 팀이 요구한 로그/시나리오 규격이 기획서에 맞는지 확인하고, AI 팀 전달용 md를 작성.
- 확인 기준:
  - `docs/agent-as/FINAL_SUPPORT_SCENARIOS.md`
  - `docs/API_CONTRACT.md`
  - `docs/DB_SCHEMA.md`
  - `apps/pc-agent/README.md`
  - `PcAgentLogAnalyzer`
- 생성 문서:
  - `docs/agent-as/AI_TEAM_LOG_SCENARIO_HANDOFF.md`
- 판단:
  - 사용자 1차 안내, `LogSummary`/`supportRouting`/`AiDiagnosisRequest`, 관리자 승인 후 개선 데이터 활용은 기획서에 맞다.
  - raw gzip/전체 JSONL을 직접 학습 입력으로 쓰는 것은 기획서와 맞지 않는다. 학습 입력은 요약/라우팅/AI 결과/관리자 라벨/피드백 중심이어야 한다.
  - AI 팀원의 XGBoost/홈 하단 부품 추천 연결은 AS 기획서 직접 범위가 아니며 별도 추천 파트 연동 설계로 분리했다.
  - 사용자가 추가로 제공한 exe 수집 필요 목록은 PC Agent AS 최종 시나리오 기준으로 맞다고 판정했다.
  - 현재 사용자가 붙인 flat exe 로그는 상태 샘플로는 가능하지만, 최종 서버/AI 계약에는 부족하다. 공식 저장 형식은 JSONL 한 줄마다 `schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, `privacyFlags`를 포함하는 envelope 방식으로 정리했다.
  - 추가 요청에 따라 현재 exe에서 추가해야 할 모니터링 수집 대상만 `docs/agent-as/PC_AGENT_EXE_MONITORING_ADDITIONS_ONLY.md`로 분리했다.
  - 후속 확인에서 현재 exe가 `psutil` 기반 CPU/RAM 사용률은 이미 수집함을 반영해, 추가 대상 문서의 `SYSTEM_METRIC` 항목을 신규 수집이 아니라 공식 payload 정규화 대상으로 수정했다.
  - 추가 재검토 요청에 따라 `apps/pc-agent/buildgraph_agent.py`, `requirements.txt`, `test_buildgraph_agent.py`, `README.md`를 다시 읽고, 문서를 “현재 exe에 이미 있는 것 / 현재 있지만 정규화가 필요한 것 / 실제 추가해야 할 수집 대상”으로 재작성했다.
  - 사용자의 알림 정책 우려를 반영해 RAM/CPU/GPU 단순 임계치만으로는 AS 알림을 보내지 않고, heartbeat 장기 누락, SMART critical, WHEA/BSOD, Kernel-Power, thermal shutdown, 반복 driver/app/network 오류처럼 명확한 문제 신호가 있을 때만 AS 접수 유도 시나리오로 문서화했다.
  - 최종 기획용으로 `AI_TEAM_LOG_SCENARIO_HANDOFF.md`와 `PC_AGENT_EXE_MONITORING_ADDITIONS_ONLY.md`를 다시 압축했다. 중복된 현재 로그 예시, 기획 대조 판정, 후보성 확장 문구, 부품 추천/XGBoost 관련 언급은 제거하고 RawLog 계약, AI 입출력, AS 접수 유도 기준, 추가 구현 항목, 검증 기준만 남겼다.
  - 사용자가 실행 중인 `%LOCALAPPDATA%\BuildGraphAgent\logs\agent-metrics.jsonl`을 확인한 결과, 최근 row에 `schemaVersion`, `collectedAt`, `agentId`, `sequence`, `kind`, `payload`, `privacyFlags`가 없어 이전 빌드 또는 이전 collector가 실행 중인 상태로 판단했다. 문서에 실행 중인 exe 최신 여부 확인 기준을 추가했다.
  - 웹 AS 화면 다운로드 URL `http://localhost:5173/downloads/pc-agent/agent.exe`를 직접 내려받아 repo 파일 `apps/web/public/downloads/pc-agent/agent.exe`와 SHA256을 비교했다. 둘 다 `44791862BD8A33869F7D33891078FDACAFC180AC11EC87F801EE0EE6EE198456`로 일치해, 현재 웹에서 내려받는 파일은 workspace 기준 최신 exe임을 확인했다.
  - 같은 `agent-metrics.jsonl` 안에 14:31 flat row와 15:41 이후 envelope row가 함께 있음을 확인했다. JSONL은 append 방식이라 이전 exe가 찍은 row가 파일 앞부분에 남아 있고, 최신 여부는 tail row 기준으로 확인해야 한다.
