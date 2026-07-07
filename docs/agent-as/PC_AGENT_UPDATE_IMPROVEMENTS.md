# PC Agent Update Improvements

업데이트일: 2026-07-07

이 문서는 PCAgent self-update 기능의 현재 동작 방식, 검증된 내용, 앞으로 보강할 개선사항을 기록한다. 현재 구현은 로컬 Docker/prototype 데모 기준으로 동작 검증이 끝난 상태이며, 운영 배포 전에는 보안, 릴리즈 자동화, 복구 흐름을 추가로 보강해야 한다.

## 현재 방식

PCAgent 업데이트는 manifest 기반 self-update 방식이다.

1. 웹이 최신 exe와 manifest를 정적 파일로 제공한다.
   - `apps/web/public/downloads/pc-agent/agent.exe`
   - `apps/web/public/downloads/pc-agent/latest.json`
2. 설치된 PCAgent는 상태 화면의 `업데이트 확인` 버튼으로 `{webBaseUrl}/downloads/pc-agent/latest.json`을 조회한다.
3. manifest의 `version`이 현재 `agent-config.json`의 `agentVersion`보다 높으면 `downloadUrl`의 exe를 내려받는다.
4. 다운로드한 exe의 SHA-256을 manifest의 `sha256`과 비교한다.
5. 검증된 exe는 `%LOCALAPPDATA%\BuildGraphAgent\updates`에 staging한다.
6. `apply-pcagent-update.cmd`가 실행 중인 PCAgent 프로세스를 종료하고 `%LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe`를 교체한다.
7. 교체 후 `agent-config.json`의 `agentVersion`을 새 버전으로 갱신하고 PCAgent를 다시 백그라운드로 실행한다.

업데이트는 실행파일만 교체한다. `agent-config.json`, agent token, 로그, 진단 기록은 유지한다.

### 2026-07-07 추가 확인: 업데이트와 재등록은 별도 흐름

`업데이트 확인`으로 최신 exe를 적용한 뒤에도 PC 진단이 `진단 실패: 웹에서 PCAgent.exe와 pcagent-activation.json을 다시 받아 실행해 주세요.`로 실패할 수 있다. 이 메시지는 exe 업데이트 실패가 아니라 설치된 `agent-config.json`의 agent token이 현재 API/DB의 `agent_devices`와 맞지 않거나, token이 없거나, 서버가 agent token을 `401/403`으로 거부했을 때 표시된다.

현재 self-update는 `%LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe`와 `agentVersion`만 갱신하고, `agent-config.json`의 `agentToken`과 `activationToken`은 유지한다. 따라서 로컬 DB를 새로 만들었거나, prototype DB가 바뀌었거나, 기존 agent token이 폐기된 경우에는 업데이트만으로 복구되지 않는다. 이때는 로그인한 웹 다운로드 흐름으로 새 `pcagent-activation.json`을 발급받아 PCAgent를 다시 실행해 재등록해야 한다.

이 동작은 보안상 맞지만 UX가 헷갈리기 쉽다. 사용자는 "최신 버전으로 업데이트했는데 왜 다시 다운로드/등록하라는가"로 이해할 수 있으므로, 업데이트 상태와 등록 상태를 분리해서 보여주는 개선이 필요하다.

## 검증된 내용

- 로컬 웹 manifest가 최신 버전과 SHA-256을 정상 제공한다.
- HTTP로 내려받은 exe 바이트의 SHA-256이 manifest와 일치한다.
- 설치된 PCAgent `0.1.3`에서 `업데이트 확인` 버튼을 눌러 `0.1.4`로 교체되는 것을 GUI로 확인했다.
- 업데이트 후 `%LOCALAPPDATA%\BuildGraphAgent\PCAgent.exe` 해시가 웹 manifest 해시와 일치했다.
- 업데이트 후 `agent-config.json`의 `agentVersion`이 `0.1.4`로 갱신됐다.
- 다시 실행한 PCAgent 상태 화면에서 `버전 0.1.4`가 표시되는 것을 확인했다.
- 중복 실행된 PCAgent 프로세스가 exe 파일을 잠그는 문제를 발견했고, 설치 대상 경로와 같은 `PCAgent.exe` 프로세스를 종료하도록 적용 스크립트를 보강했다.

## 남은 개선사항

| 우선순위 | 항목 | 이유 | 방향 |
| --- | --- | --- | --- |
| P0 | 코드 서명 | 서명되지 않은 exe는 Windows SmartScreen, 백신, 조직 보안 정책에서 경고 또는 차단될 수 있다. | 운영 배포 전 Authenticode 코드 서명 인증서를 적용하고 서명 검증 절차를 릴리즈 체크에 포함한다. |
| P0 | HTTPS 배포 고정 | SHA-256 검증은 파일 변조 탐지에는 도움되지만, manifest 자체가 HTTP로 전달되면 보호가 약하다. | 운영에서는 `webBaseUrl`을 HTTPS 도메인으로 고정하고 HTTP 업데이트를 차단한다. |
| P0 | manifest 서명 | 현재는 exe 해시만 검증한다. manifest가 변조되면 공격자가 version/downloadUrl/sha256을 함께 바꿀 수 있다. | manifest에 서명값을 추가하거나, 서버가 서명한 release metadata를 검증한다. |
| P0 | 등록 상태 진단과 재등록 UX | 업데이트는 exe만 교체하고 agent token은 유지한다. 로컬 DB 초기화, 서버 변경, token 폐기 이후에는 최신 exe여도 PC 진단이 401/403으로 실패한다. | 업데이트 확인과 별도로 `등록 상태 확인`을 제공하고, agent token이 거부되면 웹에서 새 activation token을 발급받아 재등록하는 안내/버튼을 명확히 분리한다. |
| P1 | 롤백/백업 | 새 exe 자체가 실행 불능이면 사용자가 수동으로 복구해야 한다. | 교체 전 이전 exe를 `PCAgent.previous.exe`로 백업하고, 새 버전 실행 확인 실패 시 자동 복구한다. |
| P1 | 릴리즈 자동화 | `DEFAULT_AGENT_VERSION`을 올리지 않거나 웹 컨테이너를 재빌드하지 않으면 업데이트가 사용자에게 보이지 않는다. | 빌드 스크립트 또는 CI에서 version bump, exe 생성, SHA-256 manifest 갱신, 웹 이미지 재빌드 여부를 검증한다. |
| P1 | 업데이트 잔여 파일 정리 | `%LOCALAPPDATA%\BuildGraphAgent\updates`에 staged exe가 계속 쌓일 수 있다. | 성공한 업데이트 이후 오래된 `PCAgent-*.exe`, `pending-update.json`, 적용 스크립트를 보관 개수 또는 기간 기준으로 정리한다. |
| P1 | 업데이트 상태 기록 | 사용자가 업데이트 실패 원인을 알기 어렵다. | `updates/update-history.jsonl` 또는 별도 로그에 확인, 다운로드, 검증, 적용, 실패 사유를 기록한다. |
| P1 | 단일 인스턴스 UX 정리 | 중복 실행 시 백그라운드/뷰어 프로세스가 여러 개 보일 수 있다. | 백그라운드와 viewer single-instance lock, 기존 창 focus, tray menu 동작을 더 명확히 정리한다. |
| P2 | 채널 정책 | 로컬, staging, production 업데이트 채널이 아직 분리되어 있지 않다. | manifest에 `channel`, `minSupportedVersion`, `rolloutPercent`, `forceUpdate` 같은 필드를 추가할지 검토한다. |
| P2 | 사용자 안내 문구 | 업데이트 진행 중 앱이 닫히고 다시 뜨는 흐름이 낯설 수 있다. | 적용 전/후 메시지와 실패 시 재시도 안내를 다듬는다. |
| P2 | E2E 자동화 | 현재 GUI 업데이트는 수동 검증에 가깝다. | PyInstaller 산출물 2개 버전으로 local install -> update click -> hash/config 검증을 자동화하는 QA 스크립트를 둔다. |

## 운영 전 체크리스트

- [ ] exe 코드 서명 적용
- [ ] HTTPS update URL 사용
- [ ] manifest 서명 또는 서버 신뢰 경계 확정
- [ ] 이전 exe 백업과 실패 시 롤백 구현
- [ ] 업데이트 성공/실패 로그 기록
- [ ] 오래된 staged update 파일 정리
- [ ] CI에서 `DEFAULT_AGENT_VERSION`, `latest.json`, `agent.exe` 해시 불일치 검출
- [ ] Docker/배포 환경에서 최신 정적 파일이 실제로 서빙되는지 릴리즈 후 확인
- [ ] 업데이트 버튼이 없는 구버전 사용자를 위한 최초 1회 재설치/교체 안내 유지

## 로컬 검증 기준

로컬 Docker 기준 업데이트 검증은 다음 조건을 모두 만족해야 한다.

- `latest.json`의 `version`이 설치된 `agentVersion`보다 높다.
- `latest.json`의 `sha256`이 HTTP로 내려받은 `agent.exe`의 SHA-256과 일치한다.
- PCAgent 상태 화면에서 `업데이트 확인` 클릭 후 설치 위치의 exe 해시가 manifest 해시로 바뀐다.
- `%LOCALAPPDATA%\BuildGraphAgent\agent-config.json`의 `agentVersion`이 최신 버전으로 바뀐다.
- 다시 실행한 PCAgent 상태 화면의 버전 카드에 최신 버전이 표시된다.
