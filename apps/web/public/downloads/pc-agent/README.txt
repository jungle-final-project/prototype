BuildGraph PC Agent download

This local demo build does not ship a signed installer yet.

For Windows CLI packaging, build agent.exe from the repository:

  cd apps\pc-agent
  build-agent-exe.cmd

The build creates:

  apps\pc-agent\dist\agent.exe

Run examples:

  agent.exe doctor --config agent-config.json
  agent.exe collect --config agent-config.json --iterations 1
  agent.exe upload --config agent-config.json --no-open

Not included yet:

  Windows Service
  tray app
  installer
  auto-update
  signed release channel
