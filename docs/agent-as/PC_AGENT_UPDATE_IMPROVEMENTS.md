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
