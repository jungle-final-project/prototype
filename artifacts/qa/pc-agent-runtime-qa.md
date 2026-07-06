# PC Agent Runtime QA

Updated: 2026-07-02

## Branch / merge readiness

- Current branch: `feat/pc-agent-exe-download`
- Current HEAD before local edits: `24a4f0b68fc2e3eeca43f1a5a1ddc87cf89613f9`
- Local `origin/main`: `961d0ba4124c8e1108267b59d6d7d217526d30b5`
- Local `origin/feat/pc-agent-exe-download`: `24a4f0b68fc2e3eeca43f1a5a1ddc87cf89613f9`
- `origin/feat/pc-agent-exe-download` is not an ancestor of local `origin/main`.
- `stash@{0}` exists and was not applied, popped, or dropped.

## Implemented runtime checks

- Double-click/no-arg execution path starts `run-background`.
- Default config path: `%LOCALAPPDATA%\BuildGraphAgent\agent-config.json`.
- Default log path: `%LOCALAPPDATA%\BuildGraphAgent\logs\agent-metrics.jsonl`.
- Startup command path: current user's Startup folder as `BuildGraphAgent.cmd`.
- Tray menu includes log viewer, log folder, AS page, and stop actions.
- Log viewer supports date plus 1-hour range selection.
- `apiBaseUrl` and `webBaseUrl` are separate config fields.
- Empty recent log windows fail before creating an upload gzip.
- Register token save attempts to restrict Windows config ACL to current user, Administrators, and SYSTEM.

## Build artifact

- Download file: `apps/web/public/downloads/pc-agent/agent.exe`
- Size: `15933980`
- SHA256: `293DCCD04D591BA9D4395C4D57D66E2DFD263D47238AB1134ED012E5DABF464E`

## Verified commands

```powershell
python -m pytest apps\pc-agent
apps\pc-agent\dist\agent.exe doctor --config apps\pc-agent\agent-config.example.json
npm.cmd run build
git diff --check
Get-FileHash apps\web\public\downloads\pc-agent\agent.exe -Algorithm SHA256
docker compose down
docker compose up -d --build
```

## Manual QA still required

- Downloaded `agent.exe` double-click from the web page on a fresh Windows user profile.
- Confirm tray icon appears in the Windows notification area.
- Confirm `%LOCALAPPDATA%\BuildGraphAgent\agent-config.json` and `logs` are created.
- Confirm Startup shortcut runs after sign-out/sign-in or reboot.
- Confirm tray Stop exits the background process.
- Confirm register, consent, heartbeat, and upload succeed against a clean QA DB.
- Confirm the generated `ticketId` opens the expected `/support/{ticketId}` page.

## Not implemented

- Windows Service
- installer
- code signing
- auto-update
- release channel
- token storage in Windows Credential Manager
