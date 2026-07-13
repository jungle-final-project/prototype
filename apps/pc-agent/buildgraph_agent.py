from __future__ import annotations

import argparse
import base64
import calendar
import gzip
import hashlib
import json
import mimetypes
import os
import platform
import random
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import webbrowser
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from io import BytesIO, TextIOWrapper
from pathlib import Path
from typing import Any, Sequence
from urllib.parse import quote, urljoin

from diagnosis_request_agent import (
    AgentDiagnosisWebSocketClient,
    BackgroundViewerController,
    DiagnosisSession,
    DiagnosisSessionStore,
    DiagnosisRequestProcessor,
    ViewerRequestSignal,
)

try:
    import tkinter as tk
    from tkinter import font as tkfont
    from tkinter import ttk
except Exception:  # pragma: no cover - optional in minimal packaged runtimes
    tk = None
    tkfont = None
    ttk = None

try:
    import psutil
except Exception:  # pragma: no cover - optional for prototype environments
    psutil = None

try:
    import pystray
    from PIL import Image, ImageDraw
except Exception:  # pragma: no cover - optional outside packaged Windows agent
    pystray = None
    Image = None
    ImageDraw = None

KST = timezone(timedelta(hours=9))
DEFAULT_CONFIG_PATH = Path("agent-config.json")
DEFAULT_LOG_DIR = Path("out/logs")
DEFAULT_LOG_FILE = "agent-metrics.jsonl"
ACTIVATION_CONFIG_FILE = "pcagent-activation.json"
LEGACY_ACTIVATION_CONFIG_FILES = ("buildgraph-agent-activation.json",)
DEFAULT_RANGE_MINUTES = 30
DEFAULT_SCHEMA_VERSION = 1
REMOTE_SYMPTOM_TYPES = {
    "REMOTE_AGENT",
    "REMOTE_DRIVER_OS",
    "REMOTE_APP_LAUNCHER",
    "REMOTE_STORAGE_MEMORY",
    "REMOTE_STARTUP_SERVICE",
    "REMOTE_LOCAL_NETWORK",
}
VISIT_SYMPTOM_TYPES = {
    "VISIT_BOOT_REMOTE_BLOCKED",
    "VISIT_DISK_FAILURE",
    "VISIT_WHEA_BSOD",
    "VISIT_POWER_SHUTDOWN",
    "VISIT_FAN_THERMAL",
}
REGISTER_PATH = "/api/agent/devices/register"
CONSENT_PATH = "/api/agent/consents"
LOG_UPLOAD_PATH = "/api/agent/log-uploads"
AS_DRAFT_PATH = "/api/agent/as-drafts"
AS_RAG_PREVIEW_PATH = "/api/agent/log-uploads/as-rag-preview"
DIAGNOSIS_CHAT_PATH = "/api/agent/diagnosis-chat"
UPDATE_MANIFEST_PATH = "/downloads/pc-agent/latest.json"
REGISTERED_STATUS = "REGISTERED"
UNREGISTERED_STATUS = "UNREGISTERED"
DISPLAY_APP_NAME = "PCAgent"
DATA_APP_NAME = "BuildGraphAgent"
LEGACY_DISPLAY_APP_NAMES = ("BuildGraphAgent", "PC Agent", "BuildGraph PC Agent")
DOWNLOAD_FILE_PREFIX = DISPLAY_APP_NAME
LEGACY_DOWNLOAD_FILE_PREFIXES = ("BuildGraphAgent",)
APP_NAME = DISPLAY_APP_NAME
APP_ASSET_DIR = "assets"
AGENT_ICON_PNG = "specup-agent.png"
AGENT_ICON_ICO = "specup-agent.ico"
BACKGROUND_INSTANCE_MUTEX_NAME = r"Local\SpecUpPcAgentBackground"
VIEWER_INSTANCE_MUTEX_NAME = r"Local\SpecUpPcAgentViewer"
DEFAULT_AGENT_VERSION = "0.1.8"
DEFAULT_POLICY_VERSION = "policy-v1"
STATUS_HOME_SIGNAL_LIMIT = 3
LOG_TABLE_LIMIT = 500
EVENT_PANEL_SIGNAL_LIMIT = 3
STATUS_LOG_SUMMARY_LIMIT = 6
DIAGNOSIS_HISTORY_FILE = "diagnosis-history.jsonl"
DIAGNOSIS_HISTORY_LIMIT = 100
DIAGNOSIS_CHAT_HISTORY_FILE = "diagnosis-chat-history.jsonl"
DIAGNOSIS_CHAT_HISTORY_LIMIT = 100
UPDATE_DIR_NAME = "updates"
UPDATE_APPLY_SCRIPT_FILE = "apply-pcagent-update.cmd"
UPDATE_PENDING_FILE = "pending-update.json"
HOME_MEMORY_WARNING_THRESHOLD = 85.0
PC_AGENT_USAGE_WARNING_THRESHOLD = 80.0
PC_AGENT_USAGE_DANGER_THRESHOLD = 95.0
PC_AGENT_CPU_TEMP_WARNING_THRESHOLD = 85.0
PC_AGENT_CPU_TEMP_DANGER_THRESHOLD = 95.0
PC_AGENT_GPU_TEMP_WARNING_THRESHOLD = 85.0
PC_AGENT_GPU_TEMP_DANGER_THRESHOLD = 95.0
DIAGNOSIS_DETAIL_EVENT_LIMIT = 5
DIAGNOSIS_DETAIL_WARNING_THRESHOLD = HOME_MEMORY_WARNING_THRESHOLD
DIAGNOSIS_DETAIL_DANGER_THRESHOLD = 95.0
HOME_USAGE_LOOKBACK_MINUTES = 5
UI_FONT_CANDIDATES = ("Segoe UI Variable", "Segoe UI", "Malgun Gothic")
FONT_BODY_PX = 14
FONT_SECONDARY_PX = 12
FONT_BUTTON_PX = 14
FONT_SECTION_TITLE_PX = 16
FONT_PAGE_TITLE_PX = 20
FONT_LOG_PX = 12
CARD_ICON_FILES = {
    "pc": Path("assets/icons/pc-security.png"),
    "server": Path("assets/icons/server-cloud.png"),
    "upload": Path("assets/icons/upload-cloud.png"),
    "startup": Path("assets/icons/startup-windows.png"),
    "version": Path("assets/icons/version-info.png"),
}
PC_AGENT_UI_FLOW = (
    "SYMPTOM_CONFIRM",
    "DIAGNOSING",
    "DIAGNOSIS_RESULT",
    "AS_REQUEST_CREATED",
)
PC_AGENT_DIAGNOSIS_STEPS = ("증상 확인", "하드웨어 진단", "결과 및 조치")
PC_AGENT_WINDOW_WIDTH = 1000
PC_AGENT_WINDOW_HEIGHT = 740


def next_pc_agent_ui_state(current: str) -> str:
    try:
        index = PC_AGENT_UI_FLOW.index(current)
    except ValueError:
        return PC_AGENT_UI_FLOW[0]
    return PC_AGENT_UI_FLOW[min(index + 1, len(PC_AGENT_UI_FLOW) - 1)]


def resolve_ui_font_family(root: Any | None = None) -> str:
    if tkfont is None:
        return UI_FONT_CANDIDATES[-1]
    try:
        installed = {str(name).casefold(): str(name) for name in tkfont.families(root)}
    except Exception:
        return UI_FONT_CANDIDATES[-1]
    for candidate in UI_FONT_CANDIDATES:
        found = installed.get(candidate.casefold())
        if found:
            return found
    return UI_FONT_CANDIDATES[-1]


def tk_ui_font(family: str, size_px: int, weight: str = "regular", underline: bool = False) -> tuple[Any, ...]:
    styles: list[str] = []
    if weight in {"semibold", "bold"}:
        styles.append("bold")
    if underline:
        styles.append("underline")
    return (family, -size_px, *styles)
EVENT_PANEL_SIGNAL_CODES = {
    "REMOTE_DRIVER_OS",
    "REMOTE_APP_LAUNCHER",
    "REMOTE_LOCAL_NETWORK",
    "VISIT_DISK_FAILURE",
    "VISIT_WHEA_BSOD",
    "VISIT_POWER_SHUTDOWN",
    "VISIT_FAN_THERMAL",
}
EVENT_PANEL_SUMMARIES = {
    "REMOTE_DRIVER_OS": "드라이버 또는 OS 관련 오류 신호가 감지되었습니다.",
    "REMOTE_APP_LAUNCHER": "앱 또는 런처 실행 오류가 반복된 기록이 있습니다.",
    "REMOTE_LOCAL_NETWORK": "로컬 네트워크 연결 문제 신호가 감지되었습니다.",
    "VISIT_DISK_FAILURE": "디스크 상태 점검이 필요한 신호가 감지되었습니다.",
    "VISIT_WHEA_BSOD": "블루스크린 또는 하드웨어 오류 로그가 반복 감지되었습니다.",
    "VISIT_POWER_SHUTDOWN": "예상치 못한 전원 종료 기록이 감지되었습니다.",
    "VISIT_FAN_THERMAL": "과열 또는 팬 상태 점검이 필요한 신호가 감지되었습니다.",
}
FINAL_SIGNAL_RULES: tuple[dict[str, Any], ...] = (
    {
        "code": "REMOTE_AGENT",
        "title": "Agent 등록/업로드 오류",
        "level": "주의",
        "keywords": (
            "remote_agent",
            "agent_health",
            "register failed",
            "registration failed",
            "upload failed",
            "auth 401",
            "auth 409",
            "token error",
            "config parse",
            "acl",
            "permission",
            "heartbeat missing",
        ),
    },
    {
        "code": "REMOTE_DRIVER_OS",
        "title": "드라이버/OS 오류",
        "level": "주의",
        "keywords": (
            "remote_driver_os",
            "display_driver_warning",
            "display driver",
            "nvlddmkm",
            "driver reset",
            "windows update",
            "pnp",
            "device manager",
        ),
    },
    {
        "code": "REMOTE_APP_LAUNCHER",
        "title": "앱/런처 실행 오류",
        "level": "주의",
        "keywords": (
            "remote_app_launcher",
            "application error",
            "windows error reporting",
            ".net runtime",
            "sidebyside",
            "launcher crash",
            "app crash",
            "runtime error",
        ),
    },
    {
        "code": "REMOTE_STORAGE_MEMORY",
        "title": "저장공간/메모리 압박",
        "level": "주의",
        "keywords": (
            "remote_storage_memory",
            "memory pressure",
            "out of memory",
            "storage low",
            "disk full",
            "pagefile",
            "free space low",
        ),
    },
    {
        "code": "REMOTE_STARTUP_SERVICE",
        "title": "시작프로그램/서비스 부하",
        "level": "주의",
        "keywords": (
            "remote_startup_service",
            "startup app",
            "startup service",
            "service crash loop",
            "background service",
            "idle high cpu",
        ),
    },
    {
        "code": "REMOTE_LOCAL_NETWORK",
        "title": "로컬 네트워크 문제",
        "level": "주의",
        "keywords": (
            "remote_local_network",
            "dns failure",
            "gateway unreachable",
            "adapter disabled",
            "nic driver",
            "network diagnostic",
        ),
    },
    {
        "code": "VISIT_BOOT_REMOTE_BLOCKED",
        "title": "부팅/원격 연결 불가",
        "level": "검토",
        "keywords": (
            "visit_boot_remote_blocked",
            "device offline",
            "remote help not available",
            "boot failure",
            "heartbeat long missing",
        ),
    },
    {
        "code": "VISIT_DISK_FAILURE",
        "title": "디스크 장애 의심",
        "level": "위험",
        "keywords": (
            "visit_disk_failure",
            "smart critical",
            "bad block",
            "filesystem write failure",
            "disk event 7",
            "disk event 51",
            "disk event 55",
            "disk event 129",
            "disk event 153",
        ),
    },
    {
        "code": "VISIT_WHEA_BSOD",
        "title": "WHEA/블루스크린 반복",
        "level": "위험",
        "keywords": (
            "visit_whea_bsod",
            "whea-logger",
            "bugcheck",
            "bsod",
            "minidump",
        ),
    },
    {
        "code": "VISIT_POWER_SHUTDOWN",
        "title": "전원 꺼짐 반복",
        "level": "위험",
        "keywords": (
            "visit_power_shutdown",
            "kernel-power",
            "eventlog 6008",
            "unexpected shutdown",
            "power event",
        ),
    },
    {
        "code": "VISIT_FAN_THERMAL",
        "title": "과열/팬 이상",
        "level": "위험",
        "keywords": (
            "visit_fan_thermal",
            "thermal shutdown",
            "thermal throttle",
            "fan rpm 0",
            "thermal_service_required",
        ),
    },
)


class ConfigError(ValueError):
    pass


class RegisterError(RuntimeError):
    pass


class AgentError(RuntimeError):
    pass


class UploadError(AgentError):
    pass


class NamedInstanceLock:
    ERROR_ALREADY_EXISTS = 183

    def __init__(self, name: str) -> None:
        self.name = name
        self.handle: int | None = None
        self.acquired = False
        self._kernel32: Any = None

    def acquire(self) -> bool:
        if os.name != "nt":
            self.acquired = True
            return True
        try:
            import ctypes

            self._kernel32 = ctypes.windll.kernel32
            handle = self._kernel32.CreateMutexW(None, True, self.name)
            if not handle:
                self.acquired = True
                return True
            if self._kernel32.GetLastError() == self.ERROR_ALREADY_EXISTS:
                self._kernel32.CloseHandle(handle)
                return False
            self.handle = handle
            self.acquired = True
            return True
        except Exception:
            self.acquired = True
            return True

    def release(self) -> None:
        if not self.acquired:
            return
        if os.name == "nt" and self.handle and self._kernel32 is not None:
            try:
                self._kernel32.ReleaseMutex(self.handle)
            except Exception:
                pass
            try:
                self._kernel32.CloseHandle(self.handle)
            except Exception:
                pass
        self.handle = None
        self.acquired = False


def acquire_named_instance_lock(name: str) -> NamedInstanceLock | None:
    instance_lock = NamedInstanceLock(name)
    if not instance_lock.acquire():
        return None
    return instance_lock


@dataclass(frozen=True)
class IncidentWindow:
    incident_id: str
    trigger_type: str
    symptom_type: str
    detected_at: datetime
    started_at: datetime
    ended_at: datetime
    selected_by_user: bool
    consent_id: str | None = None

    def range_minutes(self) -> int:
        seconds = (self.ended_at - self.started_at).total_seconds()
        return max(1, int((seconds + 59) // 60))

    def metadata(self) -> dict[str, str]:
        fields = {
            "incidentId": self.incident_id,
            "triggerType": self.trigger_type,
            "symptomType": self.symptom_type,
            "detectedAt": self.detected_at.isoformat(),
            "startedAt": self.started_at.isoformat(),
            "endedAt": self.ended_at.isoformat(),
            "rangeStartedAt": self.started_at.isoformat(),
            "rangeEndedAt": self.ended_at.isoformat(),
            "rangeMinutes": str(self.range_minutes()),
            "selectedByUser": str(self.selected_by_user).lower(),
        }
        if self.consent_id:
            fields["consentId"] = self.consent_id
        return fields


@dataclass(frozen=True)
class IssueDraftMacro:
    symptom_type: str
    title: str
    detail: str
    symptom: str
    support_request_kind: str


class AgentRuntime:
    def __init__(self) -> None:
        self.running = True
        self.index = 0
        self.last_event_panel_signature: str | None = None
        self.last_issue_notification_at: float = 0.0

    def stop(self) -> None:
        self.running = False


@dataclass(frozen=True)
class AgentConfig:
    api_base_url: str
    activation_token: str
    device_fingerprint_hash: str
    os_version: str
    agent_version: str
    policy_version: str
    agent_token: str | None = None
    log_dir: Path = DEFAULT_LOG_DIR
    schema_version: int = DEFAULT_SCHEMA_VERSION
    web_base_url: str | None = None
    environment: str = "local"
    symptom: str | None = None
    symptom_type: str | None = None
    device_id: str | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AgentConfig":
        return cls(
            api_base_url=required_config_text(data, "apiBaseUrl").rstrip("/"),
            activation_token=required_config_text(data, "activationToken"),
            device_fingerprint_hash=required_config_text(data, "deviceFingerprintHash"),
            os_version=required_config_text(data, "osVersion"),
            agent_version=required_config_text(data, "agentVersion"),
            policy_version=required_config_text(data, "policyVersion"),
            agent_token=optional_config_text(data, "agentToken"),
            log_dir=optional_config_path(data, "logDir", DEFAULT_LOG_DIR),
            schema_version=optional_config_int(data, "schemaVersion", DEFAULT_SCHEMA_VERSION),
            web_base_url=optional_config_text(data, "webBaseUrl"),
            environment=optional_config_text(data, "environment") or "local",
            symptom=optional_config_text(data, "symptom"),
            symptom_type=optional_config_text(data, "symptomType"),
            device_id=optional_config_text(data, "deviceId"),
        )

    def registration_status(self) -> str:
        if self.agent_token:
            return REGISTERED_STATUS
        return UNREGISTERED_STATUS


@dataclass(frozen=True)
class AgentUpdateInfo:
    version: str
    download_url: str
    sha256: str
    file_name: str = f"{APP_NAME}.exe"
    notes: str = ""


def required_config_text(data: dict[str, Any], field: str) -> str:
    if field not in data:
        raise ConfigError(f"Missing required config field: {field}")
    value = data[field]
    if not isinstance(value, str) or not value.strip():
        raise ConfigError(f"Config field must be a non-empty string: {field}")
    return value.strip()


def optional_config_text(data: dict[str, Any], field: str) -> str | None:
    if field not in data or data[field] is None:
        return None
    value = data[field]
    if not isinstance(value, str):
        raise ConfigError(f"Config field must be a string when provided: {field}")
    value = value.strip()
    return value or None


def optional_config_path(data: dict[str, Any], field: str, default: Path) -> Path:
    value = optional_config_text(data, field)
    return Path(value) if value else default


def optional_config_int(data: dict[str, Any], field: str, default: int) -> int:
    if field not in data or data[field] is None:
        return default
    value = data[field]
    if not isinstance(value, int):
        raise ConfigError(f"Config field must be an integer when provided: {field}")
    return value


def read_config_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise ConfigError(f"Config file not found: {path}")
    try:
        with path.open("r", encoding="utf-8-sig") as file:
            data = json.load(file)
    except json.JSONDecodeError as exception:
        raise ConfigError(f"Config file is not valid JSON: {path}: {exception.msg}") from exception
    if not isinstance(data, dict):
        raise ConfigError("Config file root must be a JSON object.")
    return data


def load_config(path: Path) -> AgentConfig:
    data = read_config_json(path)
    return AgentConfig.from_dict(data)


def ensure_runtime_config_version(path: Path) -> None:
    try:
        data = read_config_json(path)
    except ConfigError:
        return
    if data.get("agentVersion") == DEFAULT_AGENT_VERSION:
        return
    data["agentVersion"] = DEFAULT_AGENT_VERSION
    with path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(path)


def app_data_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    if root:
        return Path(root) / DATA_APP_NAME
    return Path.home() / f".{DATA_APP_NAME.lower()}"


def runtime_asset_path(relative_path: Path) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / relative_path


def default_background_config_path() -> Path:
    return app_data_dir() / "agent-config.json"


def web_base_url(config: AgentConfig) -> str:
    if config.web_base_url:
        return config.web_base_url.rstrip("/")
    base = config.api_base_url.rstrip("/")
    if base.endswith(":8080"):
        return base[:-5] + ":5173"
    return base


def support_new_url(config: AgentConfig) -> str:
    return f"{web_base_url(config)}/support/new"


def support_draft_url(config: AgentConfig, draft_id: str) -> str:
    return f"{support_new_url(config)}?draftId={quote(draft_id)}"


def update_manifest_url(config: AgentConfig) -> str:
    return f"{web_base_url(config)}{UPDATE_MANIFEST_PATH}"


def update_dir() -> Path:
    return app_data_dir() / UPDATE_DIR_NAME


def normalize_version_parts(version: str) -> list[int]:
    parts: list[int] = []
    for token in re.split(r"[^0-9]+", version):
        if token:
            parts.append(int(token))
    return parts or [0]


def compare_versions(left: str, right: str) -> int:
    left_parts = normalize_version_parts(left)
    right_parts = normalize_version_parts(right)
    size = max(len(left_parts), len(right_parts))
    left_parts.extend([0] * (size - len(left_parts)))
    right_parts.extend([0] * (size - len(right_parts)))
    if left_parts > right_parts:
        return 1
    if left_parts < right_parts:
        return -1
    return 0


def update_available(current_version: str, latest_version: str) -> bool:
    return compare_versions(latest_version, current_version) > 0


def parse_agent_update_manifest(payload: Any, manifest_url: str) -> AgentUpdateInfo:
    if not isinstance(payload, dict):
        raise AgentError("update manifest root must be a JSON object.")
    version = str(payload.get("version") or "").strip()
    download_url = str(payload.get("downloadUrl") or "").strip()
    sha256 = str(payload.get("sha256") or "").strip().lower()
    if not version:
        raise AgentError("update manifest is missing version.")
    if not download_url:
        raise AgentError("update manifest is missing downloadUrl.")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise AgentError("update manifest sha256 must be a 64-character hex string.")
    file_name = sanitize_filename(str(payload.get("fileName") or f"{APP_NAME}.exe"), f"{APP_NAME}.exe")
    notes = sanitize_display_text(payload.get("notes"), 500)
    return AgentUpdateInfo(
        version=version,
        download_url=urljoin(manifest_url, download_url),
        sha256=sha256,
        file_name=file_name,
        notes="" if notes == "-" else notes,
    )


def fetch_agent_update_manifest(
    config: AgentConfig,
    timeout_seconds: int = 8,
    opener: Any = None,
) -> AgentUpdateInfo:
    manifest_url = update_manifest_url(config)
    request = urllib.request.Request(
        manifest_url,
        method="GET",
        headers={"Accept": "application/json"},
    )
    open_fn = opener or urllib.request.urlopen
    try:
        with open_fn(request, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise AgentError(f"update check failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise AgentError(f"update check failed: {exception.reason}") from exception
    try:
        data = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise AgentError(f"update manifest is not JSON: {payload[:200]}") from exception
    return parse_agent_update_manifest(data, manifest_url)


def sanitize_filename(value: str, fallback: str) -> str:
    name = Path(value).name.strip()
    if not name:
        return fallback
    return re.sub(r"[^A-Za-z0-9._-]", "_", name)


def download_bytes(url: str, timeout_seconds: int = 30, opener: Any = None) -> bytes:
    request = urllib.request.Request(url, method="GET")
    open_fn = opener or urllib.request.urlopen
    try:
        with open_fn(request, timeout=timeout_seconds) as response:
            return response.read()
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise AgentError(f"update download failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise AgentError(f"update download failed: {exception.reason}") from exception


def stage_agent_update(
    info: AgentUpdateInfo,
    timeout_seconds: int = 30,
    opener: Any = None,
) -> Path:
    data = download_bytes(info.download_url, timeout_seconds=timeout_seconds, opener=opener)
    digest = hashlib.sha256(data).hexdigest()
    if digest.lower() != info.sha256:
        raise AgentError("downloaded PCAgent update failed sha256 verification.")
    directory = update_dir()
    directory.mkdir(parents=True, exist_ok=True)
    staged_name = sanitize_filename(f"{APP_NAME}-{info.version}.exe", f"{APP_NAME}.exe")
    staged_path = directory / staged_name
    tmp_path = staged_path.with_suffix(staged_path.suffix + ".tmp")
    tmp_path.write_bytes(data)
    tmp_path.replace(staged_path)
    pending_path = directory / UPDATE_PENDING_FILE
    pending_path.write_text(
        json.dumps(
            {
                "version": info.version,
                "downloadUrl": info.download_url,
                "sha256": info.sha256,
                "stagedPath": str(staged_path),
                "createdAt": datetime.now(KST).isoformat(),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    return staged_path


def write_update_apply_script(staged_executable: Path, target_executable: Path, latest_version: str) -> Path:
    script_path = update_dir() / UPDATE_APPLY_SCRIPT_FILE
    script_path.parent.mkdir(parents=True, exist_ok=True)
    pid_path = pid_file()
    config_path = default_background_config_path()
    script = f"""@echo off
setlocal
set "SOURCE={staged_executable}"
set "TARGET={target_executable}"
set "PIDFILE={pid_path}"
set "CONFIG={config_path}"
set "VERSION={latest_version}"
if exist "%PIDFILE%" (
  set /p AGENT_PID=<"%PIDFILE%"
  if not "%AGENT_PID%"=="" taskkill /PID %AGENT_PID% /T /F >nul 2>nul
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$target=$env:TARGET; Get-CimInstance Win32_Process | Where-Object {{ $_.Name -eq 'PCAgent.exe' -and $_.ExecutablePath -and [string]::Equals($_.ExecutablePath, $target, [System.StringComparison]::OrdinalIgnoreCase) }} | ForEach-Object {{ Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }}" >nul 2>nul
timeout /t 2 /nobreak >nul
copy /Y "%SOURCE%" "%TARGET%" >nul
if errorlevel 1 (
  echo Failed to update PCAgent executable.
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$p=$env:CONFIG; if (Test-Path -LiteralPath $p) {{ $j=Get-Content -LiteralPath $p -Raw | ConvertFrom-Json; $j.agentVersion=$env:VERSION; $j | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $p -Encoding UTF8 }}" >nul 2>nul
start "" "%TARGET%" run-background
exit /b 0
"""
    script_path.write_text(script, encoding="utf-8")
    return script_path


def prepare_agent_update(config: AgentConfig, opener: Any = None) -> dict[str, Any]:
    info = fetch_agent_update_manifest(config, opener=opener)
    if not update_available(config.agent_version, info.version):
        return {
            "status": "UP_TO_DATE",
            "currentVersion": config.agent_version,
            "latestVersion": info.version,
        }
    if not getattr(sys, "frozen", False):
        return {
            "status": "DEV_MODE",
            "currentVersion": config.agent_version,
            "latestVersion": info.version,
            "downloadUrl": info.download_url,
        }
    staged_path = stage_agent_update(info, opener=opener)
    target = installed_executable_path()
    script_path = write_update_apply_script(staged_path, target, info.version)
    return {
        "status": "READY",
        "currentVersion": config.agent_version,
        "latestVersion": info.version,
        "stagedPath": str(staged_path),
        "scriptPath": str(script_path),
    }


def launch_update_apply_script(script_path: Path) -> None:
    if os.name != "nt":
        raise AgentError("PCAgent self-update is currently supported only on Windows packaged builds.")
    subprocess.Popen(
        ["cmd.exe", "/c", str(script_path)],
        cwd=str(script_path.parent),
        **hidden_subprocess_kwargs(),
    )


def hidden_subprocess_kwargs() -> dict[str, Any]:
    if os.name != "nt":
        return {}
    flag = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return {"creationflags": flag} if flag else {}


def restrict_file_to_current_user(path: Path) -> None:
    if os.name == "nt":
        user_sid = current_user_sid()
        if not user_sid:
            return
        try:
            subprocess.run(
                [
                    "icacls",
                    str(path),
                    "/inheritance:r",
                    "/grant:r",
                    f"*{user_sid}:F",
                    "*S-1-5-32-544:F",
                    "*S-1-5-18:F",
                ],
                check=False,
                capture_output=True,
                text=True,
                **hidden_subprocess_kwargs(),
            )
        except Exception:
            return
    else:
        try:
            path.chmod(0o600)
        except Exception:
            return


def current_user_sid() -> str | None:
    if os.name != "nt":
        return None
    try:
        result = subprocess.run(
            ["whoami", "/user", "/fo", "csv", "/nh"],
            check=False,
            capture_output=True,
            text=True,
            **hidden_subprocess_kwargs(),
        )
    except Exception:
        return None
    if result.returncode != 0:
        return None
    parts = [part.strip().strip('"') for part in result.stdout.strip().split(",")]
    if len(parts) < 2 or not parts[1].startswith("S-"):
        return None
    return parts[1]


def config_access_summary(path: Path) -> str:
    if os.name == "nt":
        return "restricted to current user when saved on Windows"
    try:
        mode = path.stat().st_mode & 0o777
    except OSError:
        return "unknown"
    return oct(mode)


def device_fingerprint_hash() -> str:
    raw = f"{socket.gethostname()}:{os.environ.get('USERNAME', '')}:{platform.platform()}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def ensure_default_config(path: Path) -> Path:
    if path.exists():
        ensure_runtime_config_version(path)
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "apiBaseUrl": "http://localhost:8080",
        "activationToken": "demo-agent-activation-token",
        "deviceFingerprintHash": device_fingerprint_hash(),
        "osVersion": platform.platform(),
        "agentVersion": DEFAULT_AGENT_VERSION,
        "policyVersion": DEFAULT_POLICY_VERSION,
        "agentToken": None,
        "logDir": str(path.parent / "logs"),
        "schemaVersion": DEFAULT_SCHEMA_VERSION,
        "webBaseUrl": "http://localhost:5173",
        "environment": "local",
    }
    with path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(path)
    return path


def downloads_dir() -> Path:
    return Path.home() / "Downloads"


def activation_config_candidates() -> list[Path]:
    config_names = (ACTIVATION_CONFIG_FILE, *LEGACY_ACTIVATION_CONFIG_FILES)
    candidates: list[Path] = []
    for directory in (Path.cwd(), downloads_dir(), app_data_dir()):
        candidates.extend(directory / name for name in config_names)
    for directory in (Path.cwd(), downloads_dir(), app_data_dir()):
        if directory.exists():
            candidates.extend(directory.glob("pcagent-activation*.json"))
            candidates.extend(directory.glob("buildgraph-agent-activation*.json"))
    unique: dict[Path, Path] = {}
    for path in candidates:
        if path.exists():
            unique[path.resolve()] = path
    return list(unique.values())


def cleanup_activation_config_files() -> None:
    for path in activation_config_candidates():
        try:
            path.unlink()
        except Exception:
            continue


def latest_activation_config() -> Path | None:
    candidates = activation_config_candidates()
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def activation_token_from_executable_name() -> str | None:
    name = Path(sys.executable).name if getattr(sys, "frozen", False) else Path(sys.argv[0]).name
    prefixes = "|".join(re.escape(prefix) for prefix in (DOWNLOAD_FILE_PREFIX, *LEGACY_DOWNLOAD_FILE_PREFIXES))
    match = re.search(rf"(?:{prefixes})-([A-Za-z0-9_-]{{20,}})", name)
    return match.group(1) if match else None


def import_activation_config(config_path: Path, activation_path: Path | None = None) -> bool:
    executable_token = activation_token_from_executable_name()
    if executable_token:
        config_data = read_config_json(config_path)
        if config_data.get("activationToken") != executable_token:
            config_data["activationToken"] = executable_token
            config_data["agentToken"] = None
            with config_path.open("w", encoding="utf-8") as file:
                json.dump(config_data, file, ensure_ascii=False, indent=2)
                file.write("\n")
            restrict_file_to_current_user(config_path)
            return True
        return False

    source = activation_path or latest_activation_config()
    if source is None:
        return False
    activation_data = read_config_json(source)
    activation_token = optional_config_text(activation_data, "activationToken")
    if not activation_token:
        return False

    config_data = read_config_json(config_path)
    changed = False
    for field in ("apiBaseUrl", "webBaseUrl", "environment", "symptom", "symptomType"):
        value = optional_config_text(activation_data, field)
        if value and config_data.get(field) != value:
            config_data[field] = value
            changed = True
    if config_data.get("activationToken") != activation_token:
        config_data["activationToken"] = activation_token
        config_data["agentToken"] = None
        changed = True
    if changed:
        with config_path.open("w", encoding="utf-8") as file:
            json.dump(config_data, file, ensure_ascii=False, indent=2)
            file.write("\n")
        restrict_file_to_current_user(config_path)
    return changed


def log_file(config: AgentConfig) -> Path:
    return config.log_dir / DEFAULT_LOG_FILE


def print_status(config_path: Path) -> None:
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass

    config = load_config(config_path)
    print(config.registration_status())


def print_doctor(config_path: Path) -> None:
    config = load_config(config_path)
    path = log_file(config)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    print("config: ok")
    print(f"apiBaseUrl: {config.api_base_url}")
    print(f"registration: {config.registration_status()}")
    print(f"logDir: {config.log_dir.resolve()}")
    print(f"logFile: {path}")
    print(f"logBytes: {path.stat().st_size if path.exists() else 0}")
    print(f"agentVersion: {config.agent_version}")
    print(f"policyVersion: {config.policy_version}")
    print(f"environment: {config.environment}")
    print(f"webBaseUrl: {web_base_url(config)}")
    print(f"configAccess: {config_access_summary(config_path)}")
    if config.agent_token:
        print("agentToken: present")
    else:
        print("agentToken: missing; run register first or wait for Goal 10 token storage.")


def register_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + REGISTER_PATH


def consent_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + CONSENT_PATH


def registration_idempotency_key(config: AgentConfig) -> str:
    digest = hashlib.sha256(config.device_fingerprint_hash.encode("utf-8")).hexdigest()
    return f"agent-register-{digest[:32]}"


def register_request_body(config: AgentConfig) -> dict[str, str]:
    return {
        "activationToken": config.activation_token,
        "deviceFingerprintHash": config.device_fingerprint_hash,
        "registrationIdempotencyKey": registration_idempotency_key(config),
        "osVersion": config.os_version,
        "agentVersion": config.agent_version,
        "policyVersion": config.policy_version,
    }


def call_register(config: AgentConfig, timeout_seconds: int = 15) -> str:
    request_body = json.dumps(register_request_body(config)).encode("utf-8")
    request = urllib.request.Request(
        register_endpoint(config.api_base_url),
        data=request_body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace").strip()
        message = detail or exception.reason
        raise RegisterError(f"Register request failed with HTTP {exception.code}: {message}") from exception
    except urllib.error.URLError as exception:
        raise RegisterError(f"Register request failed: {exception.reason}") from exception

    try:
        payload = json.loads(response_body)
    except json.JSONDecodeError as exception:
        raise RegisterError("Register response is not valid JSON.") from exception
    if not isinstance(payload, dict):
        raise RegisterError("Register response root must be a JSON object.")

    agent_token = payload.get("agentToken")
    if not isinstance(agent_token, str) or not agent_token.strip():
        raise RegisterError("Register response is missing agentToken.")
    return agent_token.strip()


def call_server_upload_consent(config: AgentConfig, timeout_seconds: int = 15) -> None:
    if not config.agent_token:
        raise RegisterError("agentToken is missing; cannot save server upload consent.")
    request_body = json.dumps(
        {
            "consentType": "SERVER_UPLOAD",
            "policyVersion": config.policy_version,
            "accepted": True,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        consent_endpoint(config.api_base_url),
        data=request_body,
        headers={
            "Accept": "application/json",
            "Authorization": f"Bearer {config.agent_token}",
            "Content-Type": "application/json",
            "Idempotency-Key": "agent-consent-server-upload-" + hashlib.sha256(
                config.device_fingerprint_hash.encode("utf-8")
            ).hexdigest()[:32],
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response.read()
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace").strip()
        message = detail or exception.reason
        raise RegisterError(f"Consent request failed with HTTP {exception.code}: {message}") from exception
    except urllib.error.URLError as exception:
        raise RegisterError(f"Consent request failed: {exception.reason}") from exception


def save_agent_token(config_path: Path, agent_token: str) -> None:
    if not agent_token.strip():
        raise ConfigError("agentToken must be a non-empty string.")
    data = read_config_json(config_path)
    data["agentToken"] = agent_token.strip()
    with config_path.open("w", encoding="utf-8") as file:
        json.dump(data, file, ensure_ascii=False, indent=2)
        file.write("\n")
    restrict_file_to_current_user(config_path)


def register_agent(config_path: Path) -> None:
    config = load_config(config_path)
    agent_token = call_register(config)
    save_agent_token(config_path, agent_token)
    call_server_upload_consent(load_config(config_path))
    print(REGISTERED_STATUS)
    print(f"agentToken: saved to {config_path}")
    print("serverUploadConsent: accepted")


def auto_register_agent(config_path: Path) -> bool:
    config = load_config(config_path)
    if config.agent_token:
        return False
    agent_token = call_register(config)
    save_agent_token(config_path, agent_token)
    call_server_upload_consent(load_config(config_path))
    return True


DISK_DELTA_FIELDS = (
    "diskReadBytesPerSec",
    "diskWriteBytesPerSec",
    "diskReadCountPerSec",
    "diskWriteCountPerSec",
    "diskBusyEstimatePercent",
)
GPU_FIELDS = (
    "gpuUsage",
    "gpuUsagePercent",
    "vramUsage",
    "vramUsagePercent",
    "gpuTemp",
    "gpuTempCelsius",
    "gpuFanRpm",
    "gpuFanPercent",
)
CPU_TEMP_FIELDS = ("cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius")
CPU_USAGE_FIELDS = ("cpuUsage", "cpuUsagePercent")
MEMORY_USAGE_FIELDS = ("memoryUsage", "ramUsage", "memoryUsedPercent")
MEMORY_USED_FIELDS = ("memoryUsedBytes",)
MEMORY_TOTAL_FIELDS = ("memoryTotalBytes",)
DISK_USAGE_FIELDS = ("diskUsage", "diskUsedPercent")
DISK_ACTIVE_FIELDS = ("diskBusyEstimatePercent",)
DISK_HEALTH_FIELDS = ("diskSmartStatus", "diskHealth")
GPU_USAGE_FIELDS = ("gpuUsage", "gpuUsagePercent")
GPU_VRAM_FIELDS = ("vramUsage", "vramUsagePercent")
GPU_TEMP_FIELDS = ("gpuTemp", "gpuTempCelsius")
GPU_FAN_RPM_FIELDS = ("gpuFanRpm",)
GPU_FAN_PERCENT_FIELDS = ("gpuFanPercent",)
OPTIONAL_SENSOR_STATUS_FIELDS = (
    "vramUsagePercent",
    "cpuTempCelsius",
    "gpuTempCelsius",
    "gpuFanRpm",
    "gpuFanPercent",
    "diskSmartStatus",
)
WINDOWS_GPU_ENGINE_COUNTER = r"\GPU Engine(*)\Utilization Percentage"
WINDOWS_DISK_BUSY_COUNTER = r"\PhysicalDisk(_Total)\% Disk Time"
SYSTEM_METRIC_KIND = "SYSTEM_METRIC"
OPTIONAL_SENSOR_UNSUPPORTED_TEXT = "현재 수집기 미지원"


def is_number(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def rounded_or_none(value: Any) -> float | None:
    if not is_number(value):
        return None
    return round(float(value), 1)


def parse_float(value: Any) -> float | None:
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def clamp_percent(value: Any) -> float | None:
    number = rounded_or_none(value)
    if number is None:
        return None
    return max(0.0, min(100.0, number))


def set_metric_aliases(payload: dict[str, Any], fields: Sequence[str], value: Any) -> None:
    for field in fields:
        payload[field] = value


def mark_unavailable(
    payload: dict[str, Any],
    reasons: dict[str, str],
    fields: Sequence[str],
    reason: str,
) -> None:
    for field in fields:
        payload[field] = None
        reasons.setdefault(field, reason)


def sensor_reason_is_failure(reason: str | None) -> bool:
    normalized = (reason or "").casefold()
    return any(token in normalized for token in ("failed", "timed out", "exception"))


def safe_process_name(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip().replace("/", "\\")
    if not text:
        return None
    return text.rsplit("\\", 1)[-1] or None


def disk_usage_root() -> str:
    if os.name == "nt":
        drive = os.environ.get("SystemDrive", "C:")
        return drive + "\\"
    return "/"


def counter_delta(current: Any, previous: Any, field: str) -> float | None:
    if not hasattr(current, field) or not hasattr(previous, field):
        return None
    current_value = parse_float(getattr(current, field, None))
    previous_value = parse_float(getattr(previous, field, None))
    if current_value is None or previous_value is None:
        return None
    return max(0.0, current_value - previous_value)


def disk_busy_percent_from_counters(
    current: Any,
    previous: Any,
    elapsed: float,
) -> tuple[float | None, str | None, str | None]:
    if elapsed <= 0:
        return None, "disk delta elapsed time unavailable", None
    busy_delta_ms = counter_delta(current, previous, "busy_time")
    if busy_delta_ms is not None:
        return round(max(0.0, min(100.0, busy_delta_ms / 1000.0 / elapsed * 100.0)), 1), None, "psutil-busy-time"
    read_delta_ms = counter_delta(current, previous, "read_time")
    write_delta_ms = counter_delta(current, previous, "write_time")
    if read_delta_ms is not None and write_delta_ms is not None:
        io_delta_ms = read_delta_ms + write_delta_ms
        return round(max(0.0, min(100.0, io_delta_ms / 1000.0 / elapsed * 100.0)), 1), None, "psutil-read-write-time"
    return None, "disk busy_time/read_time/write_time unavailable", None


def read_windows_disk_busy_percent_powershell(
    runner: Any = subprocess.run,
) -> tuple[float | None, str | None]:
    if os.name != "nt":
        return None, "Windows disk performance counters unavailable on this OS"
    command = (
        "(Get-Counter '\\PhysicalDisk(_Total)\\% Disk Time' -ErrorAction Stop)."
        "CounterSamples | Select-Object -ExpandProperty CookedValue"
    )
    try:
        result = runner(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
            **(hidden_subprocess_kwargs() if runner is subprocess.run else {}),
        )
    except FileNotFoundError:
        return None, "PowerShell unavailable"
    except subprocess.TimeoutExpired:
        return None, "PowerShell disk counter timed out"
    except Exception:
        return None, "PowerShell disk counter query failed"
    if getattr(result, "returncode", 1) != 0:
        return None, "PowerShell disk counter query failed"
    lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
    if not lines:
        return None, "PowerShell disk counter returned no values"
    value = clamp_percent(parse_float(lines[-1]))
    if value is None:
        return None, "PowerShell disk counter output parse failed"
    return value, None


def read_windows_gpu_usage_percent_win32pdh(win32pdh_module: Any = None) -> tuple[float | None, str | None]:
    if os.name != "nt" and win32pdh_module is None:
        return None, "Windows GPU performance counters unavailable on this OS"
    if win32pdh_module is None:
        try:
            import win32pdh as win32pdh_module  # type: ignore[import-not-found]
        except Exception:
            return None, "pywin32 win32pdh unavailable"
    try:
        counters, instances = win32pdh_module.EnumObjectItems(
            None,
            None,
            "GPU Engine",
            win32pdh_module.PERF_DETAIL_WIZARD,
        )
    except Exception:
        return None, "Windows GPU counter enumeration failed"
    if "Utilization Percentage" not in counters or not instances:
        return None, "Windows GPU counters unavailable"

    query = None
    try:
        query = win32pdh_module.OpenQuery()
        counter_handles = []
        for instance in instances:
            try:
                counter_path = win32pdh_module.MakeCounterPath(
                    (None, "GPU Engine", instance, None, 0, "Utilization Percentage"),
                )
                counter_handles.append(win32pdh_module.AddCounter(query, counter_path))
            except Exception:
                continue
        if not counter_handles:
            return None, "Windows GPU counters could not be opened"
        win32pdh_module.CollectQueryData(query)
        time.sleep(0.1)
        win32pdh_module.CollectQueryData(query)
        values: list[float] = []
        for handle in counter_handles:
            try:
                _, value = win32pdh_module.GetFormattedCounterValue(handle, win32pdh_module.PDH_FMT_DOUBLE)
            except Exception:
                continue
            number = parse_float(value)
            if number is not None:
                values.append(max(0.0, number))
        if not values:
            return None, "Windows GPU counter values unavailable"
        # GPU Engine exposes per-engine utilization. Sum active engines, then clamp
        # to a task-manager-like 0-100 estimate for the single UI percentage column.
        return round(max(0.0, min(100.0, sum(values))), 1), None
    except Exception:
        return None, "Windows GPU performance counter query failed"
    finally:
        if query is not None:
            try:
                win32pdh_module.CloseQuery(query)
            except Exception:
                pass


def read_windows_gpu_usage_percent_powershell(
    runner: Any = subprocess.run,
) -> tuple[float | None, str | None]:
    if os.name != "nt":
        return None, "PowerShell GPU counters unavailable on this OS"
    command = (
        "$samples = (Get-Counter '\\GPU Engine(*)\\Utilization Percentage' -ErrorAction Stop).CounterSamples; "
        "$sum = ($samples | Measure-Object -Property CookedValue -Sum).Sum; "
        "if ($null -eq $sum) { '' } else { [Math]::Min(100, [Math]::Max(0, $sum)) }"
    )
    try:
        result = runner(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
            **(hidden_subprocess_kwargs() if runner is subprocess.run else {}),
        )
    except FileNotFoundError:
        return None, "PowerShell unavailable"
    except subprocess.TimeoutExpired:
        return None, "PowerShell GPU counter timed out"
    except Exception:
        return None, "PowerShell GPU counter query failed"
    if getattr(result, "returncode", 1) != 0:
        return None, "PowerShell GPU counter query failed"
    lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
    if not lines:
        return None, "PowerShell GPU counter returned no values"
    value = clamp_percent(parse_float(lines[-1]))
    if value is None:
        return None, "PowerShell GPU counter output parse failed"
    return value, None


def should_initialize_log_filter(log_tab_opened: bool, user_touched_filter: bool) -> bool:
    return not log_tab_opened and not user_touched_filter


def default_log_filter_values(now: datetime | None = None) -> tuple[str, int]:
    current = now or datetime.now(KST)
    if current.tzinfo is None:
        current = current.replace(tzinfo=KST)
    else:
        current = current.astimezone(KST)
    return current.strftime("%Y-%m-%d"), current.hour


def build_metric_snapshot(ts: datetime, index: int, event_type: str, payload: dict[str, Any]) -> dict:
    collected_at = ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    snapshot = {
        "schemaVersion": str(DEFAULT_SCHEMA_VERSION),
        "collectedAt": collected_at,
        "agentId": socket.gethostname() or "local-agent",
        "sequence": index,
        "kind": event_type,
        "payload": payload,
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
            "containsUserContent": False,
        },
        "timestamp": ts.isoformat(),
    }
    snapshot.update(payload)
    return snapshot


class HardwareMetricCollector:
    def __init__(
        self,
        psutil_module: Any = psutil,
        nvidia_smi_runner: Any = None,
        gpu_counter_reader: Any = None,
        powershell_gpu_counter_reader: Any = None,
        disk_busy_reader: Any = None,
        time_fn: Any = time.monotonic,
    ) -> None:
        self.psutil = psutil_module
        self.nvidia_smi_runner = nvidia_smi_runner or self.run_nvidia_smi
        self.gpu_counter_reader = gpu_counter_reader or read_windows_gpu_usage_percent_win32pdh
        self.powershell_gpu_counter_reader = powershell_gpu_counter_reader or read_windows_gpu_usage_percent_powershell
        self.disk_busy_reader = disk_busy_reader or read_windows_disk_busy_percent_powershell
        self.time_fn = time_fn
        self._last_disk_io: Any = None
        self._last_disk_at: float | None = None
        self._last_process_cpu: dict[int, tuple[float, str]] | None = None
        self._last_process_at: float | None = None

    def collect(self, ts: datetime, index: int) -> dict:
        observed_at = float(self.time_fn())
        payload: dict[str, Any] = {
            "metricKind": "system",
            "eventType": "SYSTEM_METRIC",
            "message": "System metrics collected.",
            "osErrorEvent": None,
        }
        reasons: dict[str, str] = {}

        self.collect_cpu_memory(payload, reasons)
        self.collect_disk_usage(payload, reasons)
        self.collect_disk_io(payload, reasons, observed_at)
        mark_unavailable(
            payload,
            reasons,
            DISK_HEALTH_FIELDS,
            "SMART health unsupported by this collector",
        )
        self.collect_gpu(payload, reasons)
        self.collect_cpu_temperature(payload, reasons)
        self.collect_top_processes(payload, reasons, observed_at)
        self.collect_sensor_status(payload, reasons)

        payload["unavailableReason"] = reasons
        return build_metric_snapshot(ts, index, "SYSTEM_METRIC", payload)

    def collect_cpu_memory(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, "psutil unavailable")
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, "psutil unavailable")
            return
        try:
            cpu_usage = clamp_percent(self.psutil.cpu_percent(interval=0.05))
        except Exception:
            cpu_usage = None
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, "psutil cpu_percent query failed")
        try:
            memory = self.psutil.virtual_memory()
            memory_usage = clamp_percent(getattr(memory, "percent", None))
            memory_used = rounded_or_none(getattr(memory, "used", None))
            memory_total = rounded_or_none(getattr(memory, "total", None))
        except Exception:
            memory_usage = None
            memory_used = None
            memory_total = None
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, "psutil virtual_memory query failed")
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, "psutil virtual_memory query failed")
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, "psutil virtual_memory query failed")
        if cpu_usage is None:
            mark_unavailable(payload, reasons, CPU_USAGE_FIELDS, "psutil cpu_percent unavailable")
        else:
            set_metric_aliases(payload, CPU_USAGE_FIELDS, cpu_usage)
        if memory_usage is None:
            mark_unavailable(payload, reasons, MEMORY_USAGE_FIELDS, "psutil virtual_memory unavailable")
        else:
            set_metric_aliases(payload, MEMORY_USAGE_FIELDS, memory_usage)
        if memory_used is None:
            mark_unavailable(payload, reasons, MEMORY_USED_FIELDS, "psutil memory used bytes unavailable")
        else:
            set_metric_aliases(payload, MEMORY_USED_FIELDS, memory_used)
        if memory_total is None:
            mark_unavailable(payload, reasons, MEMORY_TOTAL_FIELDS, "psutil memory total bytes unavailable")
        else:
            set_metric_aliases(payload, MEMORY_TOTAL_FIELDS, memory_total)

    def collect_disk_usage(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, "psutil unavailable")
            return
        try:
            disk_usage = clamp_percent(self.psutil.disk_usage(disk_usage_root()).percent)
        except Exception:
            disk_usage = None
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, "psutil disk_usage query failed")
        if disk_usage is None:
            mark_unavailable(payload, reasons, DISK_USAGE_FIELDS, "psutil disk_usage unavailable")
        else:
            set_metric_aliases(payload, DISK_USAGE_FIELDS, disk_usage)

    def collect_disk_io(self, payload: dict[str, Any], reasons: dict[str, str], observed_at: float) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "psutil unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        try:
            current = self.psutil.disk_io_counters()
        except Exception:
            current = None
        if current is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "psutil disk_io_counters unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        previous = self._last_disk_io
        previous_at = self._last_disk_at
        self._last_disk_io = current
        self._last_disk_at = observed_at
        if previous is None or previous_at is None:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "disk delta requires previous sample")
            payload["diskCollectorSource"] = "unavailable"
            return
        elapsed = observed_at - previous_at
        if elapsed <= 0:
            mark_unavailable(payload, reasons, DISK_DELTA_FIELDS, "disk delta elapsed time unavailable")
            payload["diskCollectorSource"] = "unavailable"
            return
        payload["diskReadBytesPerSec"] = round(
            max(0.0, float(getattr(current, "read_bytes", 0) - getattr(previous, "read_bytes", 0))) / elapsed,
            1,
        )
        payload["diskWriteBytesPerSec"] = round(
            max(0.0, float(getattr(current, "write_bytes", 0) - getattr(previous, "write_bytes", 0))) / elapsed,
            1,
        )
        payload["diskReadCountPerSec"] = round(
            max(0.0, float(getattr(current, "read_count", 0) - getattr(previous, "read_count", 0))) / elapsed,
            1,
        )
        payload["diskWriteCountPerSec"] = round(
            max(0.0, float(getattr(current, "write_count", 0) - getattr(previous, "write_count", 0))) / elapsed,
            1,
        )
        disk_busy, disk_busy_reason, disk_source = disk_busy_percent_from_counters(current, previous, elapsed)
        disk_bytes_per_sec = payload["diskReadBytesPerSec"] + payload["diskWriteBytesPerSec"]
        needs_windows_fallback = disk_busy is None or (disk_busy == 0.0 and disk_bytes_per_sec > 0)
        if needs_windows_fallback:
            try:
                fallback_busy, fallback_reason = self.disk_busy_reader()
            except Exception:
                fallback_busy, fallback_reason = None, "Windows disk counter query failed"
            fallback_busy = clamp_percent(fallback_busy)
            if fallback_busy is not None:
                payload["diskBusyEstimatePercent"] = fallback_busy
                payload["diskCollectorSource"] = "windows-performance-counter"
                return
            payload["diskBusyEstimatePercent"] = None
            payload["diskCollectorSource"] = "unavailable"
            reasons["diskBusyEstimatePercent"] = (
                fallback_reason
                or disk_busy_reason
                or "disk busy counter unavailable"
            )
        else:
            payload["diskBusyEstimatePercent"] = disk_busy
            payload["diskCollectorSource"] = disk_source or "psutil"

    def run_nvidia_smi(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu,fan.speed",
                "--format=csv,noheader,nounits",
            ],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
            **hidden_subprocess_kwargs(),
        )

    def collect_nvidia_gpu(self, payload: dict[str, Any], reasons: dict[str, str]) -> tuple[bool, str | None]:
        try:
            result = self.nvidia_smi_runner()
        except FileNotFoundError:
            return False, "nvidia-smi unavailable"
        except subprocess.TimeoutExpired:
            return False, "nvidia-smi timed out"
        except Exception:
            return False, "nvidia-smi query failed"
        if getattr(result, "returncode", 1) != 0:
            return False, "nvidia-smi query failed"
        lines = [line.strip() for line in str(getattr(result, "stdout", "")).splitlines() if line.strip()]
        if not lines:
            return False, "nvidia-smi returned no GPU metrics"
        parts = [part.strip() for part in lines[0].split(",")]
        if len(parts) < 4:
            return False, "nvidia-smi output parse failed"
        gpu_usage = clamp_percent(parse_float(parts[0]))
        memory_used = parse_float(parts[1])
        memory_total = parse_float(parts[2])
        gpu_temp = rounded_or_none(parse_float(parts[3]))
        gpu_fan = clamp_percent(parse_float(parts[4])) if len(parts) >= 5 else None
        if gpu_usage is None:
            payload["gpuUsage"] = None
            payload["gpuUsagePercent"] = None
            reasons["gpuUsage"] = "nvidia-smi GPU utilization unavailable"
            reasons["gpuUsagePercent"] = "nvidia-smi GPU utilization unavailable"
        else:
            payload["gpuUsage"] = gpu_usage
            payload["gpuUsagePercent"] = gpu_usage
        if memory_used is None or memory_total is None or memory_total <= 0:
            payload["vramUsage"] = None
            payload["vramUsagePercent"] = None
            reasons["vramUsage"] = "nvidia-smi VRAM utilization unavailable"
            reasons["vramUsagePercent"] = "nvidia-smi VRAM utilization unavailable"
        else:
            vram_usage = round(max(0.0, min(100.0, memory_used / memory_total * 100.0)), 1)
            payload["vramUsage"] = vram_usage
            payload["vramUsagePercent"] = vram_usage
        if gpu_temp is None:
            payload["gpuTemp"] = None
            payload["gpuTempCelsius"] = None
            reasons["gpuTemp"] = "nvidia-smi GPU temperature unavailable"
            reasons["gpuTempCelsius"] = "nvidia-smi GPU temperature unavailable"
        else:
            payload["gpuTemp"] = gpu_temp
            payload["gpuTempCelsius"] = gpu_temp
        payload["gpuFanRpm"] = None
        reasons["gpuFanRpm"] = "GPU fan RPM unsupported by nvidia-smi"
        if gpu_fan is None:
            payload["gpuFanPercent"] = None
            reasons["gpuFanPercent"] = "nvidia-smi GPU fan sensor unavailable"
        else:
            payload["gpuFanPercent"] = gpu_fan
        payload["gpuCollectorSource"] = "nvidia-smi"
        return True, None

    def collect_gpu_counter_fallback(
        self,
        payload: dict[str, Any],
        reasons: dict[str, str],
        reader: Any,
        source: str,
    ) -> tuple[bool, str | None]:
        try:
            usage, reason = reader()
        except Exception:
            return False, f"{source} query failed"
        usage = clamp_percent(usage)
        if usage is None:
            return False, reason or f"{source} unavailable"
        set_metric_aliases(payload, GPU_USAGE_FIELDS, usage)
        mark_unavailable(payload, reasons, GPU_VRAM_FIELDS, f"VRAM utilization unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_TEMP_FIELDS, f"GPU temperature unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_FAN_RPM_FIELDS, f"GPU fan RPM unavailable from {source}")
        mark_unavailable(payload, reasons, GPU_FAN_PERCENT_FIELDS, f"GPU fan sensor unavailable from {source}")
        payload["gpuCollectorSource"] = source
        return True, None

    def collect_gpu(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        nvidia_ok, nvidia_reason = self.collect_nvidia_gpu(payload, reasons)
        if nvidia_ok:
            return

        counter_ok, counter_reason = self.collect_gpu_counter_fallback(
            payload,
            reasons,
            self.gpu_counter_reader,
            "windows-performance-counter",
        )
        if counter_ok:
            return

        powershell_ok, powershell_reason = self.collect_gpu_counter_fallback(
            payload,
            reasons,
            self.powershell_gpu_counter_reader,
            "powershell-get-counter",
        )
        if powershell_ok:
            return

        reason = "; ".join(
            part
            for part in (nvidia_reason, counter_reason, powershell_reason)
            if part
        ) or "GPU metrics unavailable"
        mark_unavailable(payload, reasons, GPU_FIELDS, reason)
        payload["gpuCollectorSource"] = "unavailable"

    def collect_cpu_temperature(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        if self.psutil is None:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "psutil unavailable")
            return
        sensors = getattr(self.psutil, "sensors_temperatures", None)
        if not callable(sensors):
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "CPU temperature sensor unavailable")
            return
        try:
            readings = sensors(fahrenheit=False) or {}
        except Exception:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "CPU temperature sensor query failed")
            return
        selected: float | None = None
        fallback: float | None = None
        for entries in readings.values():
            for entry in entries:
                current = rounded_or_none(getattr(entry, "current", None))
                if current is None:
                    continue
                if fallback is None:
                    fallback = current
                label = str(getattr(entry, "label", "")).lower()
                if "cpu" in label or "package" in label or "core" in label:
                    selected = current
                    break
            if selected is not None:
                break
        cpu_temp = selected if selected is not None else fallback
        if cpu_temp is None:
            mark_unavailable(payload, reasons, CPU_TEMP_FIELDS, "CPU temperature sensor unavailable")
        else:
            set_metric_aliases(payload, CPU_TEMP_FIELDS, cpu_temp)

    def collect_sensor_status(self, payload: dict[str, Any], reasons: dict[str, str]) -> None:
        status: dict[str, str] = {}
        default_reasons = {
            "vramUsagePercent": "VRAM sensor currently unsupported by this collector",
            "cpuTempCelsius": "CPU temperature sensor currently unsupported by this collector",
            "gpuTempCelsius": "GPU temperature sensor currently unsupported by this collector",
            "gpuFanRpm": "GPU fan RPM sensor currently unsupported by this collector",
            "gpuFanPercent": "GPU fan sensor currently unsupported by this collector",
            "diskSmartStatus": "SMART health currently unsupported by this collector",
        }
        for field in OPTIONAL_SENSOR_STATUS_FIELDS:
            if payload.get(field) is None:
                reason = reasons.get(field, default_reasons[field])
                status[field] = "failed" if sensor_reason_is_failure(reason) else "unsupported"
                reasons.setdefault(field, default_reasons[field])
            else:
                status[field] = "collected"
        payload["sensorStatus"] = status

    def collect_top_processes(self, payload: dict[str, Any], reasons: dict[str, str], observed_at: float) -> None:
        if self.psutil is None:
            payload["topCpuProcess"] = None
            payload["topRamProcess"] = None
            reasons["topCpuProcess"] = "psutil unavailable"
            reasons["topRamProcess"] = "psutil unavailable"
            return
        samples: dict[int, tuple[float, str]] = {}
        top_ram: tuple[int, str] | None = None
        try:
            processes = self.psutil.process_iter(["name", "memory_info", "cpu_times"])
        except Exception:
            processes = []
        for process in processes:
            try:
                info = getattr(process, "info", {}) or {}
                name = safe_process_name(info.get("name"))
                if not name:
                    name = safe_process_name(process.name())
                if not name:
                    continue
                memory_info = info.get("memory_info")
                if memory_info is None and hasattr(process, "memory_info"):
                    memory_info = process.memory_info()
                rss = getattr(memory_info, "rss", None)
                if isinstance(rss, int) and (top_ram is None or rss > top_ram[0]):
                    top_ram = (rss, name)
                cpu_times = info.get("cpu_times")
                if cpu_times is None and hasattr(process, "cpu_times"):
                    cpu_times = process.cpu_times()
                user_time = getattr(cpu_times, "user", None)
                system_time = getattr(cpu_times, "system", None)
                if is_number(user_time) and is_number(system_time):
                    samples[int(getattr(process, "pid", id(process)))] = (float(user_time) + float(system_time), name)
            except Exception:
                continue
        previous = self._last_process_cpu
        previous_at = self._last_process_at
        self._last_process_cpu = samples
        self._last_process_at = observed_at
        if top_ram is None:
            payload["topRamProcess"] = None
            reasons["topRamProcess"] = "process memory info unavailable"
        else:
            payload["topRamProcess"] = top_ram[1]
        if not samples:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU info unavailable"
            return
        if previous is None or previous_at is None:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU delta requires previous sample"
            return
        elapsed = observed_at - previous_at
        if elapsed <= 0:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "process CPU delta elapsed time unavailable"
            return
        cpu_count = 1
        try:
            cpu_count = int(self.psutil.cpu_count() or 1)
        except Exception:
            cpu_count = 1
        top_cpu: tuple[float, str] | None = None
        for pid, (total_time, name) in samples.items():
            previous_sample = previous.get(pid)
            if previous_sample is None:
                continue
            delta = max(0.0, total_time - previous_sample[0])
            percent = delta / elapsed / max(1, cpu_count) * 100.0
            if top_cpu is None or percent > top_cpu[0]:
                top_cpu = (percent, name)
        if top_cpu is None or top_cpu[0] <= 0:
            payload["topCpuProcess"] = None
            reasons["topCpuProcess"] = "no process CPU activity observed"
        else:
            payload["topCpuProcess"] = top_cpu[1]


DEFAULT_METRIC_COLLECTOR = HardwareMetricCollector()


def metric_snapshot(ts: datetime, index: int, collector: Any = None) -> dict:
    metric_collector = collector or DEFAULT_METRIC_COLLECTOR
    return metric_collector.collect(ts, index)


def metric_payload_from_snapshot(snapshot: dict[str, Any]) -> dict[str, Any]:
    payload = snapshot.get("payload")
    if isinstance(payload, dict):
        return payload
    return snapshot


def sample_metric_snapshot(ts: datetime, index: int) -> dict:
    event_type = "DISPLAY_DRIVER_WARNING" if index % 7 == 0 else "DEMO_METRIC"
    message = "Display driver warning observed." if event_type != "DEMO_METRIC" else "Demo metric collected."
    cpu_usage = round(min(99, 38 + index * 3 + random.random() * 8), 1)
    memory_usage = round(min(99, 62 + index * 2 + random.random() * 6), 1)
    disk_usage = round(49 + random.random(), 1)
    gpu_usage = round(min(98, 64 + index * 4 + random.random() * 8), 1)
    vram_usage = round(min(95, 58 + index * 3 + random.random() * 5), 1)
    gpu_temp = round(min(91, 70 + index * 1.8 + random.random() * 3), 1)
    cpu_temp = round(min(86, 62 + index * 1.2 + random.random() * 2), 1)
    payload = {
        "metricKind": "sample-demo",
        "cpuUsage": cpu_usage,
        "cpuUsagePercent": cpu_usage,
        "memoryUsage": memory_usage,
        "ramUsage": memory_usage,
        "memoryUsedPercent": memory_usage,
        "gpuUsage": gpu_usage,
        "gpuUsagePercent": gpu_usage,
        "vramUsage": vram_usage,
        "vramUsagePercent": vram_usage,
        "gpuTemp": gpu_temp,
        "gpuTempCelsius": gpu_temp,
        "cpuTemp": cpu_temp,
        "cpuTempCelsius": cpu_temp,
        "cpuTemperatureCelsius": cpu_temp,
        "diskUsage": disk_usage,
        "diskUsedPercent": disk_usage,
        "eventType": event_type,
        "message": message,
        "osErrorEvent": None if event_type == "DEMO_METRIC" else "Display driver warning",
        "topCpuProcess": "game.exe" if index % 2 else "ide64.exe",
        "topRamProcess": "game.exe",
        "unavailableReason": {},
    }
    return build_metric_snapshot(ts, index, event_type, payload)


def metric_log_row(
    ts: datetime,
    index: int,
    schema_version: int,
    agent_id: str,
    collector: Any = None,
) -> dict:
    snapshot = metric_snapshot(ts, index, collector)
    payload = metric_payload_from_snapshot(snapshot)
    return {
        **payload,
        "schemaVersion": schema_version,
        "collectedAt": ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "agentId": agent_id,
        "sequence": index,
        "kind": payload.get("eventType") or snapshot.get("kind") or "SYSTEM_METRIC",
        "payload": payload,
        "timestamp": ts.isoformat(),
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
        },
    }


def sample_metric_log_row(ts: datetime, index: int, schema_version: int, agent_id: str) -> dict:
    snapshot = sample_metric_snapshot(ts, index)
    payload = metric_payload_from_snapshot(snapshot)
    return {
        **payload,
        "schemaVersion": schema_version,
        "collectedAt": ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "agentId": agent_id,
        "sequence": index,
        "kind": payload.get("eventType") or snapshot.get("kind") or "DEMO_METRIC",
        "payload": payload,
        "timestamp": ts.isoformat(),
        "privacyFlags": {
            "containsRawPath": False,
            "masked": True,
        },
    }


def write_sample(out: Path, count: int, interval_seconds: int) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    start = datetime.now(KST) - timedelta(seconds=count * interval_seconds)
    with out.open("w", encoding="utf-8") as file:
        for index in range(count):
            row = sample_metric_log_row(
                start + timedelta(seconds=index * interval_seconds),
                index,
                DEFAULT_SCHEMA_VERSION,
                "sample-agent",
            )
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def read_recent_rows(source: Path, minutes: int) -> list[dict]:
    cutoff = datetime.now(KST) - timedelta(minutes=minutes)
    rows: list[dict] = []
    with source.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            ts = parse_log_timestamp(row)
            if ts is None:
                continue
            if ts >= cutoff:
                rows.append(row)
    return rows


def parse_datetime(value: str, field_name: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exception:
        raise ConfigError(f"{field_name} must be ISO-8601 datetime.") from exception
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    return parsed


def default_incident_window(
    symptom_type: str,
    detected_at: datetime | None = None,
    trigger_type: str = "USER_REQUEST",
    incident_id: str | None = None,
    selected_by_user: bool = True,
    consent_id: str | None = None,
) -> IncidentWindow:
    detected = detected_at or datetime.now(KST)
    if symptom_type in VISIT_SYMPTOM_TYPES and symptom_type != "VISIT_BOOT_REMOTE_BLOCKED":
        pre = timedelta(minutes=30)
        post = timedelta(minutes=10)
    elif symptom_type == "VISIT_BOOT_REMOTE_BLOCKED":
        pre = timedelta(minutes=30)
        post = timedelta(minutes=0)
    else:
        pre = timedelta(minutes=15)
        post = timedelta(minutes=5)
    return IncidentWindow(
        incident_id=incident_id or f"incident-{uuid.uuid4()}",
        trigger_type=trigger_type,
        symptom_type=symptom_type,
        detected_at=detected,
        started_at=detected - pre,
        ended_at=detected + post,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )


def build_incident_window(
    symptom_type: str,
    detected_at: str | None,
    started_at: str | None,
    ended_at: str | None,
    trigger_type: str,
    incident_id: str | None,
    selected_by_user: bool,
    consent_id: str | None,
) -> IncidentWindow:
    detected = parse_datetime(detected_at, "detectedAt") if detected_at else datetime.now(KST)
    window = default_incident_window(
        symptom_type,
        detected,
        trigger_type=trigger_type,
        incident_id=incident_id,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )
    start = parse_datetime(started_at, "startedAt") if started_at else window.started_at
    end = parse_datetime(ended_at, "endedAt") if ended_at else window.ended_at
    if not end > start:
        raise ConfigError("endedAt must be after startedAt.")
    return IncidentWindow(
        incident_id=window.incident_id,
        trigger_type=trigger_type,
        symptom_type=symptom_type,
        detected_at=detected,
        started_at=start,
        ended_at=end,
        selected_by_user=selected_by_user,
        consent_id=consent_id,
    )


def read_window_rows(source: Path, window: IncidentWindow) -> list[dict]:
    rows: list[dict] = []
    with source.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp is None:
                continue
            if timestamp.tzinfo is None:
                timestamp = timestamp.replace(tzinfo=KST)
            if window.started_at <= timestamp <= window.ended_at:
                rows.append(row)
    return rows


def export_recent(source: Path, out: Path, minutes: int) -> None:
    rows = read_recent_rows(source, minutes)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def export_window(source: Path, out: Path, window: IncidentWindow) -> None:
    rows = read_window_rows(source, window)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def append_metric_with_row(config: AgentConfig, index: int = 0, collector: Any = None) -> tuple[Path, dict]:
    path = log_file(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = metric_log_row(
        datetime.now(KST),
        index,
        config.schema_version,
        config.device_fingerprint_hash,
        collector,
    )
    row["agentVersion"] = config.agent_version
    row["policyVersion"] = config.policy_version
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path, row


def append_metric(config: AgentConfig, index: int = 0, collector: Any = None) -> Path:
    path, _ = append_metric_with_row(config, index, collector)
    return path


def gzip_recent(source: Path, out: Path, minutes: int = DEFAULT_RANGE_MINUTES) -> int:
    if minutes <= 0:
        raise AgentError("minutes must be greater than 0.")
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    rows = read_recent_rows(source, minutes)
    if not rows:
        raise AgentError(f"no log rows found in the last {minutes} minutes: {source}")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as raw_file:
        with gzip.GzipFile(fileobj=raw_file, mode="wb", mtime=0) as gzip_file:
            with TextIOWrapper(gzip_file, encoding="utf-8") as text_file:
                for row in rows:
                    text_file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return out.stat().st_size


def gzip_window(source: Path, out: Path, window: IncidentWindow) -> int:
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    rows = read_window_rows(source, window)
    if not rows:
        raise AgentError(f"no log rows found in selected incident window: {source}")
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as raw_file:
        with gzip.GzipFile(fileobj=raw_file, mode="wb", mtime=0) as gzip_file:
            with TextIOWrapper(gzip_file, encoding="utf-8") as text_file:
                for row in rows:
                    text_file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return out.stat().st_size


def support_url(api_base_url: str, ticket_id: str, configured_web_base_url: str | None = None) -> str:
    base = configured_web_base_url.rstrip("/") if configured_web_base_url else api_base_url.rstrip("/")
    if not configured_web_base_url and base.endswith(":8080"):
        base = base[:-5] + ":5173"
    return f"{base}/support/{ticket_id}"


def as_rag_preview_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + AS_RAG_PREVIEW_PATH


def diagnosis_chat_endpoint(api_base_url: str) -> str:
    return api_base_url.rstrip("/") + DIAGNOSIS_CHAT_PATH


def support_mode_label_for_service(recommended_service: Any) -> str:
    return {
        "REMOTE_SUPPORT": "원격지원 신청",
        "VISIT_SUPPORT": "방문지원 신청",
        "DIAGNOSIS_ONLY": "우선 진단만 받기",
    }.get(str(recommended_service or "DIAGNOSIS_ONLY"), "우선 진단만 받기")


def format_as_rag_preview(result: dict[str, Any]) -> str:
    label = str(result.get("recommendedServiceLabel") or support_mode_label_for_service(result.get("recommendedService")))
    confidence = str(result.get("confidence") or "LOW")
    message = str(result.get("recommendationMessage") or "로그 기반 추천 결과를 확인했습니다.")
    summary = str(result.get("summaryText") or "").strip()
    if summary:
        return f"추천: {label} / 신뢰도: {confidence}\n{message}\n요약: {summary}"
    return f"추천: {label} / 신뢰도: {confidence}\n{message}"


def diagnosis_history_path(config: AgentConfig) -> Path:
    return config.log_dir.parent / DIAGNOSIS_HISTORY_FILE


def diagnosis_chat_history_path(config: AgentConfig) -> Path:
    return config.log_dir.parent / DIAGNOSIS_CHAT_HISTORY_FILE


def string_list(value: Any, limit: int = 6) -> list[str]:
    if not isinstance(value, list):
        return []
    items: list[str] = []
    for item in value:
        text = sanitize_display_text(item, 80)
        if text != "-":
            items.append(text)
        if len(items) >= limit:
            break
    return items


def diagnosis_record_tone(recommended_service: str, support_decision: str) -> str:
    if support_decision in {"VISIT_REQUIRED", "REPAIR_OR_REPLACE"} or recommended_service == "VISIT_SUPPORT":
        return "danger"
    if support_decision in {"REMOTE_POSSIBLE", "NEEDS_MORE_INFO"} or recommended_service == "REMOTE_SUPPORT":
        return "warning"
    return "ok"


def diagnosis_history_record(
    result: dict[str, Any],
    window: IncidentWindow,
    created_at: datetime | None = None,
) -> dict[str, Any]:
    created = created_at or datetime.now(KST)
    routing = result.get("supportRouting") if isinstance(result.get("supportRouting"), dict) else {}
    recommended_service = str(result.get("recommendedService") or routing.get("recommendedService") or "DIAGNOSIS_ONLY")
    service_label = str(result.get("recommendedServiceLabel") or routing.get("recommendedServiceLabel") or support_mode_label_for_service(recommended_service))
    support_decision = str(result.get("supportDecision") or routing.get("recommendedDecision") or "NEEDS_MORE_INFO")
    evidence_items: list[dict[str, str]] = []
    evidence = result.get("evidence")
    if isinstance(evidence, list):
        for item in evidence[:6]:
            if not isinstance(item, dict):
                continue
            evidence_items.append(
                {
                    "title": sanitize_display_text(item.get("title") or item.get("sourceId"), 80),
                    "summary": sanitize_display_text(item.get("summary"), 140),
                    "reasonCode": sanitize_display_text(item.get("reasonCode"), 80),
                }
            )
    return {
        "id": f"diagnosis-{uuid.uuid4()}",
        "createdAt": created.isoformat(),
        "recommendedService": recommended_service,
        "recommendedServiceLabel": service_label,
        "supportDecision": support_decision,
        "supportDecisionLabel": sanitize_display_text(result.get("supportDecisionLabel"), 80),
        "confidence": sanitize_display_text(result.get("confidence") or routing.get("confidence") or "LOW", 20),
        "recommendationMessage": sanitize_display_text(result.get("recommendationMessage"), 260),
        "summaryText": sanitize_display_text(result.get("summaryText") or result.get("recommendationMessage"), 420),
        "reasonCodes": string_list(routing.get("reasonCodes")),
        "remoteActions": string_list(routing.get("remoteActions")),
        "visitReasons": string_list(routing.get("visitReasons")),
        "blockingFactors": string_list(routing.get("blockingFactors")),
        "evidence": evidence_items,
        "incidentWindow": window.metadata(),
        "tone": diagnosis_record_tone(recommended_service, support_decision),
    }


def normalize_diagnosis_history_record(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    recommended_service = str(value.get("recommendedService") or "DIAGNOSIS_ONLY")
    support_decision = str(value.get("supportDecision") or "NEEDS_MORE_INFO")
    return {
        "id": sanitize_display_text(value.get("id") or f"diagnosis-{uuid.uuid4()}", 80),
        "createdAt": sanitize_display_text(value.get("createdAt"), 40),
        "recommendedService": recommended_service,
        "recommendedServiceLabel": sanitize_display_text(
            value.get("recommendedServiceLabel") or support_mode_label_for_service(recommended_service),
            80,
        ),
        "supportDecision": support_decision,
        "supportDecisionLabel": sanitize_display_text(value.get("supportDecisionLabel"), 80),
        "confidence": sanitize_display_text(value.get("confidence") or "LOW", 20),
        "recommendationMessage": sanitize_display_text(value.get("recommendationMessage"), 260),
        "summaryText": sanitize_display_text(value.get("summaryText") or value.get("recommendationMessage"), 420),
        "reasonCodes": string_list(value.get("reasonCodes")),
        "remoteActions": string_list(value.get("remoteActions")),
        "visitReasons": string_list(value.get("visitReasons")),
        "blockingFactors": string_list(value.get("blockingFactors")),
        "evidence": [
            {
                "title": sanitize_display_text(item.get("title"), 80),
                "summary": sanitize_display_text(item.get("summary"), 140),
                "reasonCode": sanitize_display_text(item.get("reasonCode"), 80),
            }
            for item in value.get("evidence", [])
            if isinstance(item, dict)
        ][:6],
        "incidentWindow": value.get("incidentWindow") if isinstance(value.get("incidentWindow"), dict) else {},
        "tone": str(value.get("tone") or diagnosis_record_tone(recommended_service, support_decision)),
    }


def append_diagnosis_history(config: AgentConfig, record: dict[str, Any]) -> Path:
    path = diagnosis_history_path(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(record, ensure_ascii=False) + "\n")
    return path


def read_diagnosis_history(config: AgentConfig, limit: int = DIAGNOSIS_HISTORY_LIMIT) -> list[dict[str, Any]]:
    path = diagnosis_history_path(config)
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            record = normalize_diagnosis_history_record(value)
            if record is not None:
                rows.append(record)
    return list(reversed(rows[-limit:]))


def latest_diagnosis_record(config: AgentConfig) -> dict[str, Any] | None:
    records = read_diagnosis_history(config, limit=1)
    return records[0] if records else None


def diagnosis_chat_context(record: dict[str, Any]) -> dict[str, Any]:
    evidence_ids: list[str] = []
    evidence = record.get("evidence")
    if isinstance(evidence, list):
        for index, item in enumerate(evidence[:5]):
            if isinstance(item, dict):
                reason_code = sanitize_display_text(item.get("reasonCode"), 80)
                title = sanitize_display_text(item.get("title"), 80)
                evidence_ids.append(reason_code if reason_code != "-" else f"pc-agent-evidence-{index + 1}:{title}")
    return {
        "diagnosisId": sanitize_display_text(record.get("id"), 80),
        "summaryText": sanitize_display_text(record.get("summaryText"), 420),
        "recommendationMessage": sanitize_display_text(record.get("recommendationMessage"), 260),
        "recommendedService": sanitize_display_text(record.get("recommendedService"), 80),
        "recommendedDecision": sanitize_display_text(record.get("supportDecision"), 80),
        "confidence": sanitize_display_text(record.get("confidence"), 20),
        "reasonCodes": string_list(record.get("reasonCodes"), 6),
        "remoteActions": string_list(record.get("remoteActions"), 6),
        "visitReasons": string_list(record.get("visitReasons"), 6),
        "blockingFactors": string_list(record.get("blockingFactors"), 6),
        "evidenceIds": evidence_ids,
    }


def normalize_diagnosis_chat_message(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    role = str(value.get("role") or "").strip().lower()
    if role not in {"user", "assistant"}:
        return None
    content = sanitize_display_text(value.get("content"), 2000)
    if content == "-":
        return None
    return {
        "id": sanitize_display_text(value.get("id") or f"chat-{uuid.uuid4()}", 80),
        "diagnosisId": sanitize_display_text(value.get("diagnosisId"), 80),
        "createdAt": sanitize_display_text(value.get("createdAt"), 40),
        "role": role,
        "content": content,
        "payload": value.get("payload") if isinstance(value.get("payload"), dict) else {},
    }


def append_diagnosis_chat_message(
    config: AgentConfig,
    diagnosis_id: str,
    role: str,
    content: str,
    payload: dict[str, Any] | None = None,
) -> Path:
    path = diagnosis_chat_history_path(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = {
        "id": f"chat-{uuid.uuid4()}",
        "diagnosisId": sanitize_display_text(diagnosis_id, 80),
        "createdAt": datetime.now(KST).isoformat(),
        "role": role,
        "content": sanitize_display_text(content, 2000),
        "payload": payload or {},
    }
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path


def read_diagnosis_chat_history(
    config: AgentConfig,
    diagnosis_id: str | None = None,
    limit: int = DIAGNOSIS_CHAT_HISTORY_LIMIT,
) -> list[dict[str, Any]]:
    path = diagnosis_chat_history_path(config)
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            message = normalize_diagnosis_chat_message(value)
            if message is None:
                continue
            if diagnosis_id is not None and message.get("diagnosisId") != diagnosis_id:
                continue
            rows.append(message)
    return rows[-limit:]


def format_diagnosis_history_time(value: Any) -> str:
    parsed = parse_iso_datetime(value)
    if parsed is None:
        return "-"
    return parsed.strftime("%Y-%m-%d %H:%M")


def compact_home_diagnosis_reason(record: dict[str, Any]) -> str:
    recommended_service = str(record.get("recommendedService") or "")
    support_decision = str(record.get("supportDecision") or "")
    if support_decision in {"VISIT_REQUIRED", "REPAIR_OR_REPLACE"} or recommended_service == "VISIT_SUPPORT":
        return "방문 점검이 필요한 신호가 있어 관리자 검토를 권장합니다."
    if support_decision == "REMOTE_POSSIBLE" or recommended_service == "REMOTE_SUPPORT":
        return "원격으로 먼저 확인 가능한 신호가 있어 원격지원을 권장합니다."
    if support_decision == "UNSUPPORTED":
        return "PC Agent AS 지원 범위 밖 신호로 분류되어 관리자 확인이 필요합니다."
    if support_decision == "MONITOR_ONLY":
        return "즉시 조치보다 재발 여부를 더 지켜보는 것이 적절합니다."
    return "최근 로그에서 뚜렷한 장애 신호가 없어 원격/방문 판단은 보류했습니다."


def compact_home_diagnosis_text(record: dict[str, Any]) -> str:
    label = sanitize_display_text(record.get("recommendedServiceLabel"), 40)
    confidence = sanitize_display_text(record.get("confidence"), 20)
    return f"최근 진단: {label} / 신뢰도 {confidence}\n{compact_home_diagnosis_reason(record)}"


def diagnosis_history_detail_text(record: dict[str, Any]) -> str:
    lines = [
        f"진단 시간: {format_diagnosis_history_time(record.get('createdAt'))}",
        f"추천 서비스: {sanitize_display_text(record.get('recommendedServiceLabel'), 80)}",
        f"신뢰도: {sanitize_display_text(record.get('confidence'), 20)}",
        "",
        "요약",
        sanitize_display_text(record.get("summaryText"), 420),
        "",
        "추천 문구",
        sanitize_display_text(record.get("recommendationMessage"), 260),
    ]
    for title, key in (
        ("근거 코드", "reasonCodes"),
        ("원격 조치 후보", "remoteActions"),
        ("방문 판단 근거", "visitReasons"),
        ("차단 요인", "blockingFactors"),
    ):
        items = record.get(key)
        if isinstance(items, list) and items:
            lines.extend(["", title, "- " + "\n- ".join(sanitize_display_text(item, 100) for item in items)])
    evidence = record.get("evidence")
    if isinstance(evidence, list) and evidence:
        lines.extend(["", "근거 요약"])
        for item in evidence[:6]:
            if isinstance(item, dict):
                title = sanitize_display_text(item.get("title"), 80)
                summary = sanitize_display_text(item.get("summary"), 140)
                lines.append(f"- {title}: {summary}")
    return "\n".join(lines)


def build_multipart(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = f"----buildgraph-agent-{uuid.uuid4().hex}"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n'
                "Content-Type: text/plain; charset=utf-8\r\n\r\n"
                f"{value}\r\n"
            ).encode("utf-8")
        )

    content_type = mimetypes.guess_type(file_path.name)[0] or "application/gzip"
    parts.append(
        (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{file_field}"; filename="{file_path.name}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
    )
    parts.append(file_path.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def upload_gzip(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    symptom: str | None = None,
    incident_window: IncidentWindow | None = None,
) -> dict:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata() if incident_window else {"rangeMinutes": str(DEFAULT_RANGE_MINUTES)}
    fields["schemaVersion"] = str(config.schema_version)
    if symptom:
        fields["symptom"] = symptom
    body, content_type = build_multipart(fields, "file", gzip_path)
    upload_url = config.api_base_url.rstrip("/") + LOG_UPLOAD_PATH
    request = urllib.request.Request(
        upload_url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"upload failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"upload failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"upload response is not JSON: {payload[:200]}") from exception

    if not isinstance(result, dict) or not result.get("ticketId"):
        raise UploadError(f"upload response did not include ticketId: {result}")
    return result


def preview_as_rag(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    incident_window: IncidentWindow,
    timeout_seconds: int = 8,
) -> dict[str, Any]:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata()
    fields["schemaVersion"] = str(config.schema_version)
    body, content_type = build_multipart(fields, "file", gzip_path)
    request = urllib.request.Request(
        as_rag_preview_endpoint(config.api_base_url),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"AS RAG preview failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"AS RAG preview failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"AS RAG preview response is not JSON: {payload[:200]}") from exception
    if not isinstance(result, dict) or not result.get("recommendedService"):
        raise UploadError(f"AS RAG preview response did not include recommendedService: {result}")
    return result


def send_diagnosis_chat(
    config: AgentConfig,
    diagnosis_record: dict[str, Any],
    messages: Sequence[dict[str, Any]],
    message: str,
    timeout_seconds: int = 20,
) -> dict[str, Any]:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    text = message.strip()
    if not text:
        raise UploadError("diagnosis chat message is empty.")
    payload = {
        "message": text,
        "diagnosis": diagnosis_chat_context(diagnosis_record),
        "messages": [
            {
                "role": str(item.get("role") or ""),
                "content": sanitize_display_text(item.get("content"), 2000),
            }
            for item in messages[-8:]
            if isinstance(item, dict) and item.get("role") in {"user", "assistant"}
        ],
    }
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        diagnosis_chat_endpoint(config.api_base_url),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Content-Type": "application/json; charset=utf-8",
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response_body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"diagnosis chat failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"diagnosis chat failed: {exception.reason}") from exception

    try:
        result = json.loads(response_body)
    except json.JSONDecodeError as exception:
        raise UploadError(f"diagnosis chat response is not JSON: {response_body[:200]}") from exception
    if not isinstance(result, dict) or not result.get("assistantMessage"):
        raise UploadError(f"diagnosis chat response did not include assistantMessage: {result}")
    return result


def create_as_draft(
    config: AgentConfig,
    gzip_path: Path,
    idempotency_key: str,
    macro: IssueDraftMacro,
    incident_window: IncidentWindow,
) -> dict:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = incident_window.metadata()
    fields["schemaVersion"] = str(config.schema_version)
    fields["symptom"] = macro.symptom
    fields["title"] = macro.title
    fields["detailDescription"] = macro.detail
    fields["supportRequestKind"] = macro.support_request_kind
    body, content_type = build_multipart(fields, "file", gzip_path)
    draft_url = config.api_base_url.rstrip("/") + AS_DRAFT_PATH
    request = urllib.request.Request(
        draft_url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {config.agent_token}",
            "Idempotency-Key": idempotency_key,
            "Content-Type": content_type,
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exception:
        detail = exception.read().decode("utf-8", errors="replace")
        raise UploadError(f"draft upload failed: HTTP {exception.code} {detail}") from exception
    except urllib.error.URLError as exception:
        raise UploadError(f"draft upload failed: {exception.reason}") from exception

    try:
        result = json.loads(payload)
    except json.JSONDecodeError as exception:
        raise UploadError(f"draft response is not JSON: {payload[:200]}") from exception

    if not isinstance(result, dict) or not result.get("draftId"):
        raise UploadError(f"draft response did not include draftId: {result}")
    return result


def collect_metrics(config: AgentConfig, iterations: int | None, interval_seconds: int) -> None:
    index = 0
    collector = HardwareMetricCollector()
    while iterations is None or index < iterations:
        path = append_metric(config, index, collector)
        print(f"appended system metric to {path}")
        index += 1
        if iterations is None or index < iterations:
            time.sleep(interval_seconds)


def hide_console_window() -> None:
    if os.name != "nt":
        return
    try:
        import ctypes

        window = ctypes.windll.kernel32.GetConsoleWindow()
        if window:
            ctypes.windll.user32.ShowWindow(window, 0)
    except Exception:
        return


def startup_dir() -> Path:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        raise AgentError("APPDATA is not available; cannot register startup command.")
    return Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs" / "Startup"


def installed_executable_path() -> Path:
    return app_data_dir() / f"{APP_NAME}.exe"


def files_have_same_content(left: Path, right: Path) -> bool:
    try:
        if left.stat().st_size != right.stat().st_size:
            return False
        with left.open("rb") as left_file, right.open("rb") as right_file:
            while True:
                left_chunk = left_file.read(1024 * 1024)
                right_chunk = right_file.read(1024 * 1024)
                if left_chunk != right_chunk:
                    return False
                if not left_chunk:
                    return True
    except OSError:
        return False


def executable_install_required(source: Path, target: Path) -> bool:
    if not target.exists():
        return True
    try:
        source_stat = source.stat()
        target_stat = target.stat()
        if source_stat.st_size != target_stat.st_size:
            return True
    except OSError:
        return True
    return not files_have_same_content(source, target)


def install_permission_error_message(target: Path, exception: OSError) -> str:
    return (
        f"PCAgent 시작프로그램 실행 파일을 교체할 수 없습니다: {target}\n"
        "이미 실행 중인 PCAgent가 있으면 트레이 아이콘에서 종료하거나 작업 관리자에서 "
        "PCAgent.exe를 종료한 뒤 다시 실행해 주세요. 재부팅 직후 다시 실행해도 해결할 수 있습니다.\n"
        f"원인: {exception}"
    )


def ensure_installed_executable() -> Path:
    if not getattr(sys, "frozen", False):
        return Path(sys.executable)

    source = Path(sys.executable).resolve()
    target = installed_executable_path()
    if source == target.resolve():
        return target

    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        if executable_install_required(source, target):
            shutil.copy2(source, target)
    except PermissionError as exception:
        raise AgentError(install_permission_error_message(target, exception)) from exception
    except OSError as exception:
        raise AgentError(f"Failed to install PCAgent executable for startup: {exception}") from exception
    return target


def executable_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{ensure_installed_executable()}" run-background'
    script = Path(__file__).resolve()
    return f'"{sys.executable}" "{script}" run-background'


def cleanup_legacy_startup_commands(directory: Path) -> list[Path]:
    removed: list[Path] = []
    current_name = f"{APP_NAME}.cmd".casefold()
    for name in LEGACY_DISPLAY_APP_NAMES:
        if not name:
            continue
        legacy_path = directory / f"{name}.cmd"
        if legacy_path.name.casefold() == current_name:
            continue
        try:
            if legacy_path.exists() and legacy_path.is_file():
                legacy_path.unlink()
                removed.append(legacy_path)
        except OSError:
            continue
    return removed


def register_startup() -> Path:
    directory = startup_dir()
    directory.mkdir(parents=True, exist_ok=True)
    cleanup_legacy_startup_commands(directory)
    path = directory / f"{APP_NAME}.cmd"
    path.write_text(f"@echo off\nstart \"\" {executable_command()}\n", encoding="utf-8")
    return path


def pid_file() -> Path:
    return app_data_dir() / "agent.pid"


def write_pid() -> None:
    path = pid_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(str(os.getpid()), encoding="utf-8")


def remove_pid() -> None:
    try:
        pid_file().unlink(missing_ok=True)
    except Exception:
        return


def app_asset_path(filename: str) -> Path:
    return runtime_asset_path(Path(APP_ASSET_DIR) / filename)


def apply_agent_window_icon(window: object) -> None:
    if tk is None:
        return
    ico_path = app_asset_path(AGENT_ICON_ICO)
    png_path = app_asset_path(AGENT_ICON_PNG)
    try:
        if sys.platform.startswith("win") and ico_path.exists():
            window.iconbitmap(str(ico_path))
    except Exception:
        pass
    try:
        if png_path.exists():
            photo = tk.PhotoImage(file=str(png_path))
            window.iconphoto(True, photo)
            setattr(window, "_specup_agent_icon", photo)
    except Exception:
        pass


def create_tray_image() -> object | None:
    if Image is None or ImageDraw is None:
        return None
    icon_path = app_asset_path(AGENT_ICON_PNG)
    if icon_path.exists():
        try:
            image = Image.open(icon_path).convert("RGBA")
            resample = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
            return image.resize((64, 64), resample)
        except Exception:
            pass
    image = Image.new("RGB", (64, 64), "#1d4ed8")
    draw = ImageDraw.Draw(image)
    draw.ellipse((10, 10, 54, 54), fill="#ffffff")
    draw.rectangle((27, 18, 37, 46), fill="#1d4ed8")
    draw.rectangle((18, 27, 46, 37), fill="#1d4ed8")
    return image


def open_log_folder(config_path: Path) -> None:
    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    os.startfile(str(config.log_dir.resolve()))


def read_log_tail(path: Path, limit: int = 100) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                rows.append(row)
    return rows[-limit:]


def parse_iso_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def parse_log_timestamp(row: dict[str, Any]) -> datetime | None:
    for field in ("timestamp", "collectedAt", "receivedAt", "detectedAt"):
        parsed = parse_iso_datetime(row.get(field))
        if parsed is not None:
            return parsed
    payload = row.get("payload")
    if isinstance(payload, dict):
        for field in ("timestamp", "collectedAt", "detectedAt"):
            parsed = parse_iso_datetime(payload.get(field))
            if parsed is not None:
                return parsed
    return None


def log_payload(row: dict[str, Any]) -> dict[str, Any]:
    payload = row.get("payload")
    return payload if isinstance(payload, dict) else {}


def normalized_log_kind(row: dict[str, Any]) -> str:
    value = log_value(row, "kind", "eventType")
    return str(value).strip().upper() if value is not None else ""


def is_system_metric_row(row: dict[str, Any]) -> bool:
    return normalized_log_kind(row) == SYSTEM_METRIC_KIND


def log_value(row: dict[str, Any], *fields: str) -> Any:
    payload = log_payload(row)
    for field in fields:
        if field in row and row[field] is not None:
            return row[field]
        if field in payload and payload[field] is not None:
            return payload[field]
    return None


def format_log_timestamp(row: dict[str, Any]) -> str:
    timestamp = parse_log_timestamp(row)
    if timestamp is None:
        return "-"
    return timestamp.strftime("%Y-%m-%d %H:%M:%S")


def format_log_time(row: dict[str, Any]) -> str:
    timestamp = parse_log_timestamp(row)
    if timestamp is None:
        return "-"
    return timestamp.strftime("%H:%M:%S")


def read_log_hour(path: Path, date_text: str, hour: int, limit: int = 500) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        selected_date = datetime.strptime(date_text, "%Y-%m-%d").date()
    except ValueError:
        return []
    if hour < 0 or hour > 23:
        return []

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp and timestamp.date() == selected_date and timestamp.hour == hour:
                rows.append(row)
    rows.sort(key=lambda row: parse_log_timestamp(row) or datetime.min.replace(tzinfo=KST), reverse=True)
    return rows[:limit]


def read_log_day_latest(path: Path, date_text: str, limit: int = LOG_TABLE_LIMIT) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    try:
        selected_date = datetime.strptime(date_text, "%Y-%m-%d").date()
    except ValueError:
        return []

    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(row, dict):
                continue
            timestamp = parse_log_timestamp(row)
            if timestamp and timestamp.date() == selected_date:
                rows.append(row)
    rows.sort(key=lambda row: parse_log_timestamp(row) or datetime.min.replace(tzinfo=KST), reverse=True)
    return rows[:limit]


def read_log_rows_for_filter(
    path: Path,
    date_text: str,
    hour: int,
    user_touched_filter: bool,
    limit: int = LOG_TABLE_LIMIT,
) -> list[dict[str, Any]]:
    if user_touched_filter:
        return read_log_hour(path, date_text, hour, limit)
    return read_log_day_latest(path, date_text, limit)


def format_percent(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}%"
    return "-"


def format_temperature(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}C"
    return "-"


def format_optional_sensor_percent(row: dict[str, Any], *fields: str) -> str:
    value = log_value(row, *fields)
    if isinstance(value, int | float):
        return f"{value:.1f}%"
    if is_system_metric_row(row):
        return OPTIONAL_SENSOR_UNSUPPORTED_TEXT
    return "-"


def format_optional_sensor_temperature(row: dict[str, Any], *fields: str) -> str:
    value = log_value(row, *fields)
    if isinstance(value, int | float):
        return f"{value:.1f}C"
    if is_system_metric_row(row):
        return OPTIONAL_SENSOR_UNSUPPORTED_TEXT
    return "-"


def sanitize_display_text(value: Any, limit: int = 160) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        text = "true" if value else "false"
    elif isinstance(value, int | float):
        text = str(value)
    else:
        text = str(value)
    text = re.sub(r"(?i)(authorization|agenttoken|activationtoken|token|password)\s*[:=]\s*\S+", r"\1=[hidden]", text)
    text = re.sub(r"[A-Za-z]:\\[^\s\t\r\n]+", "[path hidden]", text)
    text = re.sub(r"(/[^\s\t\r\n]+){2,}", "[path hidden]", text)
    text = text.replace("\r", " ").replace("\n", " ").strip()
    if not text:
        return "-"
    if len(text) > limit:
        return text[: limit - 1] + "..."
    return text


def display_log_kind(row: dict[str, Any]) -> str:
    return sanitize_display_text(log_value(row, "kind", "eventType"), 60)


def display_log_message(row: dict[str, Any]) -> str:
    return sanitize_display_text(log_value(row, "message", "osErrorEvent", "status", "summary"), 180)


def display_log_event_summary(row: dict[str, Any]) -> str:
    kind = display_log_kind(row)
    labels = {
        "DEMO_METRIC": "상태 수집",
        "SYSTEM_METRIC": "상태 수집",
        "DISPLAY_DRIVER_WARNING": "드라이버 경고",
        "EVENT_LOG": "시스템 이벤트",
        "WINDOWS_EVENT": "시스템 이벤트",
        "AGENT_HEALTH": "Agent 상태",
    }
    if kind != "-":
        return labels.get(kind.upper(), sanitize_display_text(kind, 80))
    return sanitize_display_text(display_log_message(row), 80)


def display_core_metric_values(row: dict[str, Any]) -> tuple[str, str, str, str]:
    return (
        format_percent(log_value(row, "cpuUsage", "cpuUsagePercent")),
        format_percent(log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")),
        format_percent(log_value(row, "diskBusyEstimatePercent")),
        format_percent(log_value(row, "gpuUsage", "gpuUsagePercent")),
    )


def display_log_table_values(row: dict[str, Any]) -> tuple[str, ...]:
    cpu, memory, disk, gpu = display_core_metric_values(row)
    return (
        format_log_time(row),
        display_log_kind(row),
        cpu,
        memory,
        disk,
        gpu,
        format_optional_sensor_percent(row, "vramUsage", "vramUsagePercent"),
        format_optional_sensor_temperature(row, "cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius"),
        format_optional_sensor_temperature(row, "gpuTemp", "gpuTempCelsius", "gpuTemperatureCelsius"),
        display_log_message(row),
    )


def display_log_summary_values(row: dict[str, Any]) -> tuple[str, ...]:
    cpu, memory, disk, gpu = display_core_metric_values(row)
    return (
        format_log_time(row),
        cpu,
        memory,
        disk,
        gpu,
        display_log_event_summary(row),
    )


def read_status_log_summary_rows(path: Path, limit: int = STATUS_LOG_SUMMARY_LIMIT) -> list[dict[str, Any]]:
    cutoff = datetime.now(KST) - timedelta(hours=1)
    rows: list[dict[str, Any]] = []
    for row in read_log_tail(path, LOG_TABLE_LIMIT):
        timestamp = parse_log_timestamp(row)
        if timestamp is None:
            continue
        if timestamp.tzinfo is None:
            timestamp = timestamp.replace(tzinfo=KST)
        if timestamp >= cutoff:
            rows.append(row)
    return rows[-limit:]


def signal_search_text(row: dict[str, Any]) -> str:
    fields = (
        "kind",
        "eventType",
        "message",
        "osErrorEvent",
        "symptomType",
        "supportDecision",
        "riskLevel",
        "reasonCode",
        "reasonCodes",
        "visitReason",
        "visitReasons",
        "blockingFactor",
        "blockingFactors",
        "status",
    )
    values: list[str] = []
    payload = log_payload(row)
    for field in fields:
        for container in (row, payload):
            value = container.get(field)
            if isinstance(value, str):
                values.append(value)
            elif isinstance(value, Sequence) and not isinstance(value, str):
                values.extend(str(item) for item in value if isinstance(item, str))
    return " ".join(values).lower()


def detect_signal(row: dict[str, Any]) -> dict[str, Any] | None:
    text = signal_search_text(row)
    if not text:
        return None
    for rule in FINAL_SIGNAL_RULES:
        if any(keyword in text for keyword in rule["keywords"]):
            timestamp = parse_log_timestamp(row)
            fallback = datetime.now(KST)
            return {
                "code": rule["code"],
                "title": rule["title"],
                "level": rule["level"],
                "timestamp": timestamp,
                "time": timestamp.strftime("%H:%M:%S") if timestamp else "-",
                "date": (timestamp or fallback).strftime("%Y-%m-%d"),
                "hour": (timestamp or fallback).hour,
            }
    return None


def detect_recent_signals(rows: Sequence[dict[str, Any]], limit: int = STATUS_HOME_SIGNAL_LIMIT) -> list[dict[str, Any]]:
    signals: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in reversed(list(rows)):
        signal = detect_signal(row)
        if signal is None or signal["code"] in seen:
            continue
        seen.add(signal["code"])
        signals.append(signal)
        if len(signals) >= limit:
            break
    return signals


def numeric_log_value(row: dict[str, Any], *fields: str) -> float | None:
    value = log_value(row, *fields)
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


SENSOR_COLLECTED = "collected"
SENSOR_UNSUPPORTED = "unsupported"
SENSOR_FAILED = "failed"


@dataclass(frozen=True)
class SensorReading:
    value: float | str | None
    availability: str
    reason: str | None = None
    unit: str = ""


@dataclass(frozen=True)
class HardwareSensorSnapshot:
    cpu_usage: SensorReading
    cpu_temp: SensorReading
    gpu_usage: SensorReading
    gpu_temp: SensorReading
    gpu_fan: SensorReading
    memory_usage: SensorReading
    memory_used: SensorReading
    memory_total: SensorReading
    disk_activity: SensorReading
    disk_usage: SensorReading
    disk_health: SensorReading
    cpu_history: tuple[float, ...]
    gpu_history: tuple[float, ...]
    memory_history: tuple[float, ...]
    disk_history: tuple[float, ...]


@dataclass(frozen=True)
class SymptomScreenInput:
    snapshot: HardwareSensorSnapshot
    symptom: str
    symptom_type: str | None


@dataclass(frozen=True)
class HardwareWidgetState:
    key: str
    title: str
    primary: str
    details: tuple[str, ...]
    status: str
    tone: str
    history: tuple[float, ...]
    highlighted: bool = False


@dataclass(frozen=True)
class SymptomScreenState:
    widgets: tuple[HardwareWidgetState, ...]
    symptom: str
    suspected_components: tuple[str, ...]


def metric_metadata(row: dict[str, Any], field: str) -> dict[str, Any]:
    payload = log_payload(row)
    value = row.get(field)
    if not isinstance(value, dict):
        value = payload.get(field)
    return value if isinstance(value, dict) else {}


def sensor_reading(row: dict[str, Any], *fields: str, unit: str = "") -> SensorReading:
    value = log_value(row, *fields)
    if value is not None and (not isinstance(value, str) or value.strip()):
        normalized = rounded_or_none(value)
        return SensorReading(normalized if normalized is not None else str(value).strip(), SENSOR_COLLECTED, unit=unit)

    statuses = metric_metadata(row, "sensorStatus")
    reasons = metric_metadata(row, "unavailableReason")
    reason = next((str(reasons[field]) for field in fields if reasons.get(field)), None)
    explicit_status = next((str(statuses[field]) for field in fields if statuses.get(field)), None)
    availability = SENSOR_FAILED if explicit_status == SENSOR_FAILED or sensor_reason_is_failure(reason) else SENSOR_UNSUPPORTED
    return SensorReading(None, availability, reason, unit)


def sensor_history(rows: Sequence[dict[str, Any]], *fields: str) -> tuple[float, ...]:
    history: list[float] = []
    for row in rows[-16:]:
        value = numeric_log_value(row, *fields)
        if value is not None:
            history.append(max(0.0, min(100.0, value)))
    return tuple(history)


def hardware_sensor_snapshot(current: dict[str, Any], rows: Sequence[dict[str, Any]]) -> HardwareSensorSnapshot:
    fan_rpm = sensor_reading(current, *GPU_FAN_RPM_FIELDS, unit="RPM")
    fan_percent = sensor_reading(current, *GPU_FAN_PERCENT_FIELDS, unit="%")
    gpu_fan = fan_rpm if fan_rpm.availability == SENSOR_COLLECTED else fan_percent
    if gpu_fan.availability != SENSOR_COLLECTED and fan_rpm.availability == SENSOR_FAILED:
        gpu_fan = fan_rpm

    disk_history = sensor_history(rows, *DISK_ACTIVE_FIELDS)
    if not disk_history:
        disk_history = sensor_history(rows, *DISK_USAGE_FIELDS)
    return HardwareSensorSnapshot(
        cpu_usage=sensor_reading(current, *CPU_USAGE_FIELDS),
        cpu_temp=sensor_reading(current, *CPU_TEMP_FIELDS),
        gpu_usage=sensor_reading(current, *GPU_USAGE_FIELDS),
        gpu_temp=sensor_reading(current, *GPU_TEMP_FIELDS),
        gpu_fan=gpu_fan,
        memory_usage=sensor_reading(current, *MEMORY_USAGE_FIELDS),
        memory_used=sensor_reading(current, *MEMORY_USED_FIELDS),
        memory_total=sensor_reading(current, *MEMORY_TOTAL_FIELDS),
        disk_activity=sensor_reading(current, *DISK_ACTIVE_FIELDS),
        disk_usage=sensor_reading(current, *DISK_USAGE_FIELDS),
        disk_health=sensor_reading(current, *DISK_HEALTH_FIELDS),
        cpu_history=sensor_history(rows, *CPU_USAGE_FIELDS),
        gpu_history=sensor_history(rows, *GPU_USAGE_FIELDS),
        memory_history=sensor_history(rows, *MEMORY_USAGE_FIELDS),
        disk_history=disk_history,
    )


def failed_system_metric_row(reason: str) -> dict[str, Any]:
    fields = (
        *CPU_USAGE_FIELDS,
        *CPU_TEMP_FIELDS,
        *GPU_USAGE_FIELDS,
        *GPU_TEMP_FIELDS,
        *GPU_FAN_RPM_FIELDS,
        *GPU_FAN_PERCENT_FIELDS,
        *MEMORY_USAGE_FIELDS,
        *MEMORY_USED_FIELDS,
        *MEMORY_TOTAL_FIELDS,
        *DISK_ACTIVE_FIELDS,
        *DISK_USAGE_FIELDS,
        *DISK_HEALTH_FIELDS,
    )
    payload: dict[str, Any] = {field: None for field in fields}
    payload["unavailableReason"] = {field: reason for field in fields}
    payload["sensorStatus"] = {field: SENSOR_FAILED for field in fields}
    return build_metric_snapshot(datetime.now(KST), 0, SYSTEM_METRIC_KIND, payload)


class SystemSensorProvider:
    def __init__(self, collector: HardwareMetricCollector | None = None) -> None:
        self.collector = collector or HardwareMetricCollector()

    def load(self, config: AgentConfig) -> SymptomScreenInput:
        rows = [row for row in read_log_tail(log_file(config), 16) if is_system_metric_row(row)]
        if rows:
            current = rows[-1]
            snapshot_rows = rows
        else:
            try:
                current = self.collector.collect(datetime.now(KST), 0)
            except Exception:
                current = failed_system_metric_row("system sensor collection failed")
            snapshot_rows = [current]
        return SymptomScreenInput(
            snapshot=hardware_sensor_snapshot(current, snapshot_rows),
            symptom=config.symptom or "웹에서 전달받은 증상이 없습니다.",
            symptom_type=config.symptom_type,
        )


class DemoSensorProvider:
    DEMO_SYMPTOM = "게임을 실행하면 처음에는 괜찮은데, 조금 지나면 프레임이 심하게 끊깁니다."
    CPU_HISTORY = (18, 22, 27, 31, 35, 42, 38, 34, 30, 28, 32, 36, 33, 30, 29, 32)
    GPU_HISTORY = (24, 31, 38, 45, 53, 61, 70, 78, 82, 86, 84, 87, 86, 85, 84, 86)
    MEMORY_HISTORY = (53, 54, 54, 55, 56, 57, 58, 59, 59, 60, 60, 61, 61, 62, 62, 62)
    DISK_HISTORY = (14, 18, 22, 19, 25, 31, 28, 24, 20, 17, 21, 26, 23, 19, 22, 24)

    def load(self, config: AgentConfig) -> SymptomScreenInput:
        rows: list[dict[str, Any]] = []
        for index in range(16):
            payload = {
                "cpuUsagePercent": self.CPU_HISTORY[index],
                "cpuTempCelsius": 58.0,
                "gpuUsagePercent": self.GPU_HISTORY[index],
                "gpuTempCelsius": 88.0,
                "gpuFanRpm": 0.0,
                "gpuFanPercent": None,
                "memoryUsedPercent": self.MEMORY_HISTORY[index],
                "memoryUsedBytes": 10 * 1024**3,
                "memoryTotalBytes": 16 * 1024**3,
                "diskBusyEstimatePercent": self.DISK_HISTORY[index],
                "diskUsedPercent": 48.0,
                "diskSmartStatus": "정상",
                "sensorStatus": {
                    "cpuTempCelsius": SENSOR_COLLECTED,
                    "gpuTempCelsius": SENSOR_COLLECTED,
                    "gpuFanRpm": SENSOR_COLLECTED,
                    "gpuFanPercent": SENSOR_UNSUPPORTED,
                    "diskSmartStatus": SENSOR_COLLECTED,
                },
                "unavailableReason": {"gpuFanPercent": "demo fan percentage unsupported"},
            }
            rows.append(build_metric_snapshot(datetime.now(KST), index, SYSTEM_METRIC_KIND, payload))
        return SymptomScreenInput(
            snapshot=hardware_sensor_snapshot(rows[-1], rows),
            symptom=config.symptom or self.DEMO_SYMPTOM,
            symptom_type=config.symptom_type or "VISIT_FAN_THERMAL",
        )


def reading_number(reading: SensorReading) -> float | None:
    return float(reading.value) if is_number(reading.value) else None


def reading_text(reading: SensorReading, unit: str = "") -> str:
    if reading.availability == SENSOR_FAILED:
        return "확인 실패"
    if reading.availability != SENSOR_COLLECTED:
        return "측정 불가"
    if isinstance(reading.value, str):
        return reading.value
    value = reading_number(reading)
    if value is None:
        return "측정 불가"
    formatted = f"{value:.0f}" if value.is_integer() else f"{value:.1f}"
    return f"{formatted}{unit}"


def memory_size_text(reading: SensorReading) -> str:
    value = reading_number(reading)
    if reading.availability != SENSOR_COLLECTED or value is None:
        return reading_text(reading)
    return f"{value / 1024**3:.1f}GB"


def fan_text(reading: SensorReading) -> str:
    value = reading_number(reading)
    if reading.availability != SENSOR_COLLECTED or value is None:
        return reading_text(reading)
    unit = reading.unit or "%"
    if value == 0:
        return f"0 {unit} (정지)"
    return f"정상 회전 · {value:.0f}{unit}"


def component_status(
    usage: SensorReading,
    optional: Sequence[SensorReading] = (),
    temperature: SensorReading | None = None,
    fan: SensorReading | None = None,
    temperature_warning: float = 1000.0,
    temperature_danger: float = 1000.0,
) -> tuple[str, str]:
    if usage.availability == SENSOR_FAILED or any(item.availability == SENSOR_FAILED for item in optional):
        return "확인 실패", "warning"
    if usage.availability != SENSOR_COLLECTED:
        return "측정 불가", "default"
    usage_value = reading_number(usage) or 0.0
    temperature_value = reading_number(temperature) if temperature is not None else None
    fan_value = reading_number(fan) if fan is not None else None
    if usage_value >= PC_AGENT_USAGE_DANGER_THRESHOLD or (
        temperature_value is not None and temperature_value >= temperature_danger
    ):
        return "이상", "danger"
    if fan_value == 0 and temperature_value is not None and temperature_value >= temperature_warning:
        return "이상", "danger"
    if usage_value >= PC_AGENT_USAGE_WARNING_THRESHOLD or (
        temperature_value is not None and temperature_value >= temperature_warning
    ):
        return "주의", "warning"
    if fan_value == 0 or any(item.availability != SENSOR_COLLECTED for item in optional):
        return "보통", "default"
    if usage_value >= 50.0:
        return "보통", "default"
    return "정상", "default"


def suspected_hardware_components(symptom: str, symptom_type: str | None) -> tuple[str, ...]:
    normalized = symptom.casefold()
    matches: set[str] = set()
    keyword_groups = {
        "cpu": ("cpu", "프로세서", "과열", "발열"),
        "gpu": ("gpu", "그래픽", "프레임", "게임", "화면", "냉각", "팬", "온도"),
        "ram": ("ram", "메모리"),
        "disk": ("disk", "디스크", "ssd", "저장", "부팅"),
    }
    for component, keywords in keyword_groups.items():
        if any(keyword in normalized for keyword in keywords):
            matches.add(component)
    type_components = {
        "REMOTE_DRIVER_OS": ("gpu",),
        "REMOTE_STORAGE_MEMORY": ("ram", "disk"),
        "VISIT_FAN_THERMAL": ("cpu", "gpu"),
        "VISIT_DISK_FAILURE": ("disk",),
    }
    matches.update(type_components.get(symptom_type or "", ()))
    return tuple(component for component in ("cpu", "gpu", "ram", "disk") if component in matches)


def build_symptom_screen_state(screen_input: SymptomScreenInput) -> SymptomScreenState:
    snapshot = screen_input.snapshot
    suspected = suspected_hardware_components(screen_input.symptom, screen_input.symptom_type)

    cpu_status, cpu_tone = component_status(
        snapshot.cpu_usage,
        (snapshot.cpu_temp,),
        temperature=snapshot.cpu_temp,
        temperature_warning=PC_AGENT_CPU_TEMP_WARNING_THRESHOLD,
        temperature_danger=PC_AGENT_CPU_TEMP_DANGER_THRESHOLD,
    )
    gpu_status, gpu_tone = component_status(
        snapshot.gpu_usage,
        (snapshot.gpu_temp, snapshot.gpu_fan),
        temperature=snapshot.gpu_temp,
        fan=snapshot.gpu_fan,
        temperature_warning=PC_AGENT_GPU_TEMP_WARNING_THRESHOLD,
        temperature_danger=PC_AGENT_GPU_TEMP_DANGER_THRESHOLD,
    )
    memory_status, memory_tone = component_status(
        snapshot.memory_usage,
        (snapshot.memory_used, snapshot.memory_total),
    )
    disk_primary = snapshot.disk_activity if snapshot.disk_activity.availability == SENSOR_COLLECTED else snapshot.disk_usage
    disk_status, disk_tone = component_status(disk_primary, (snapshot.disk_health,))

    memory_capacity = f"{memory_size_text(snapshot.memory_used)} / {memory_size_text(snapshot.memory_total)}"
    disk_label = "활성 시간" if snapshot.disk_activity.availability == SENSOR_COLLECTED else "사용률"
    widgets = (
        HardwareWidgetState(
            "cpu", "CPU", f"사용률 {reading_text(snapshot.cpu_usage, '%')}",
            (f"온도 {reading_text(snapshot.cpu_temp, '°C')}",), cpu_status, cpu_tone,
            snapshot.cpu_history, "cpu" in suspected,
        ),
        HardwareWidgetState(
            "gpu", "GPU", f"사용률 {reading_text(snapshot.gpu_usage, '%')}",
            (f"온도 {reading_text(snapshot.gpu_temp, '°C')}", f"팬 {fan_text(snapshot.gpu_fan)}"), gpu_status, gpu_tone,
            snapshot.gpu_history, "gpu" in suspected,
        ),
        HardwareWidgetState(
            "ram", "RAM", f"사용률 {reading_text(snapshot.memory_usage, '%')}",
            (memory_capacity,), memory_status, memory_tone,
            snapshot.memory_history, "ram" in suspected,
        ),
        HardwareWidgetState(
            "disk", "디스크", f"{disk_label} {reading_text(disk_primary, '%')}",
            (f"SMART {reading_text(snapshot.disk_health)}",), disk_status, disk_tone,
            snapshot.disk_history, "disk" in suspected,
        ),
    )
    return SymptomScreenState(widgets, screen_input.symptom, suspected)


def home_usage_detection(rows: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    now = datetime.now(KST)
    cutoff = now - timedelta(minutes=HOME_USAGE_LOOKBACK_MINUTES)
    for row in reversed(list(rows)):
        timestamp = parse_log_timestamp(row)
        if timestamp is None:
            continue
        observed_at = timestamp.astimezone(KST) if timestamp.tzinfo else timestamp.replace(tzinfo=KST)
        if observed_at < cutoff:
            continue
        memory = numeric_log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")
        if memory is not None and memory >= HOME_MEMORY_WARNING_THRESHOLD:
            return {
                "code": "REMOTE_STORAGE_MEMORY",
                "title": "메모리 사용량 높음",
                "level": "주의",
                "timestamp": observed_at,
                "time": observed_at.strftime("%H:%M:%S"),
                "date": observed_at.strftime("%Y-%m-%d"),
                "hour": observed_at.hour,
                "detail": "최근 5분간 메모리 점유율이 높게 유지되었습니다.",
            }
    return None


def status_home_detection(rows: Sequence[dict[str, Any]], signals: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    if signals:
        primary = dict(signals[0])
        primary["detail"] = event_panel_signal_summary(primary)
        return primary
    return home_usage_detection(rows)


def pc_status_card(
    rows: Sequence[dict[str, Any]],
    signals: Sequence[dict[str, Any]],
    detection: dict[str, Any] | None,
) -> dict[str, str]:
    if any(str(signal.get("level")) == "위험" for signal in signals):
        return {"value": "점검 필요", "detail": "진단 또는 AS 접수가 필요합니다.", "tone": "danger"}
    if detection is not None:
        title = sanitize_display_text(detection.get("title"), 32)
        detail = f"이상 징후: {title}" if title else "일부 항목에서 이상 징후가 감지되었습니다."
        return {"value": "주의", "detail": detail, "tone": "warning"}
    if not rows:
        return {"value": "정상", "detail": "최근 문제 신호 없음", "tone": "ok"}
    return {"value": "정상", "detail": "최근 문제 신호 없음", "tone": "ok"}


def event_panel_signals(signals: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for signal in signals:
        code = str(signal.get("code", ""))
        if code not in EVENT_PANEL_SIGNAL_CODES or code in seen:
            continue
        seen.add(code)
        selected.append(signal)
        if len(selected) >= EVENT_PANEL_SIGNAL_LIMIT:
            break
    return selected


def event_panel_signal_summary(signal: dict[str, Any]) -> str:
    code = str(signal.get("code", ""))
    if code in EVENT_PANEL_SUMMARIES:
        return EVENT_PANEL_SUMMARIES[code]
    return sanitize_display_text(signal.get("title"), 90)


def event_panel_detected_at(signal: dict[str, Any]) -> datetime:
    timestamp = signal.get("timestamp")
    if isinstance(timestamp, datetime):
        return timestamp.astimezone(KST) if timestamp.tzinfo else timestamp.replace(tzinfo=KST)
    return datetime.now(KST)


def event_panel_latest_time(signals: Sequence[dict[str, Any]]) -> str:
    timestamps = [event_panel_detected_at(signal) for signal in signals]
    if not timestamps:
        return "-"
    return max(timestamps).strftime("%Y-%m-%d %H:%M:%S")


def event_panel_signature(signals: Sequence[dict[str, Any]]) -> str | None:
    selected = event_panel_signals(signals)
    if not selected:
        return None
    parts = [
        f"{signal.get('code')}:{signal.get('date')}:{signal.get('hour')}"
        for signal in selected
    ]
    return "|".join(parts)


def event_panel_model(signals: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    selected = event_panel_signals(signals)
    if not selected:
        return None
    primary = selected[0]
    detected_at = event_panel_detected_at(primary)
    symptom_type = str(primary.get("code", "REMOTE_DRIVER_OS"))
    window = default_incident_window(symptom_type, detected_at=detected_at, trigger_type="SYSTEM_DETECTED")
    return {
        "latestTime": event_panel_latest_time(selected),
        "summaries": [event_panel_signal_summary(signal) for signal in selected],
        "primarySignal": primary,
        "detectedTime": detected_at.strftime("%Y-%m-%d %H:%M"),
        "signalTitle": event_panel_signal_summary(primary),
        "windowText": (
            f"{window.started_at.strftime('%H:%M')} ~ {window.ended_at.strftime('%H:%M')} "
            f"({window.range_minutes()}분)"
        ),
    }


def event_panel_symptom(signals: Sequence[dict[str, Any]]) -> str:
    summaries = [event_panel_signal_summary(signal) for signal in event_panel_signals(signals)]
    if not summaries:
        return "PCAgent가 확인이 필요한 이벤트를 감지했습니다."
    return " / ".join(summaries[:EVENT_PANEL_SIGNAL_LIMIT])


AGENT_REGISTRATION_ERROR_MARKERS = (
    "agenttoken is missing",
    "agent token is required",
    "agent activation token is invalid",
    "activation token is invalid",
    "device is inactive",
    "http 401",
    "http 403",
)

AGENT_REGISTRATION_EVENT_HELP = (
    "Agent 등록이 필요합니다. 웹 지원 페이지에서 PCAgent를 다시 다운로드해 실행해 주세요."
)
AGENT_REGISTRATION_PREVIEW_HELP = (
    "Agent 등록이 필요합니다. 웹 지원 페이지에서 등록 토큰이 포함된 PCAgent를 다시 다운로드해 실행해 주세요."
)
AGENT_REGISTRATION_COMPACT_HELP = (
    "진단 실패: 웹에서 PCAgent.exe와 pcagent-activation.json을 다시 받아 실행해 주세요."
)


def is_agent_registration_error_text(text: str) -> bool:
    return any(marker in text for marker in AGENT_REGISTRATION_ERROR_MARKERS)


def event_panel_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_EVENT_HELP
    if "consent" in text or "consentaccepted" in text:
        return "서버 업로드 동의가 필요해 전송할 수 없습니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "전송할 이벤트 로그가 아직 없습니다. 잠시 후 다시 시도해 주세요."
    if "timed out" in text or "connection" in text or "refused" in text or "unreachable" in text:
        return "서버 연결을 확인할 수 없어 전송하지 못했습니다."
    return "전송하지 못했습니다. 등록, 동의, 서버 연결 상태를 확인해 주세요."


def as_rag_preview_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_PREVIEW_HELP
    if "consent" in text or "consentaccepted" in text:
        return "서버 업로드 동의가 필요해 AI 추천을 받을 수 없습니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "분석할 로그가 아직 없습니다. PCAgent가 로그를 수집한 뒤 다시 시도해 주세요."
    if (
        "timed out" in text
        or "timeout" in text
        or "connection" in text
        or "refused" in text
        or "unreachable" in text
        or "연결" in text
    ):
        return "서버 연결을 확인할 수 없어 AI 추천을 받지 못했습니다."
    return "AI 추천을 받지 못했습니다. 등록, 동의, 서버 연결 상태를 확인해 주세요."


def compact_as_rag_preview_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if is_agent_registration_error_text(text):
        return AGENT_REGISTRATION_COMPACT_HELP
    if "consent" in text or "consentaccepted" in text:
        return "진단 실패: 서버 업로드 동의가 필요합니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "진단 실패: 분석할 로그가 아직 없습니다."
    if (
        "timed out" in text
        or "timeout" in text
        or "connection" in text
        or "refused" in text
        or "unreachable" in text
        or "연결" in text
    ):
        return "진단 실패: 서버 연결을 확인해 주세요."
    return "진단 실패. 등록, 동의, 서버 상태를 확인해 주세요."


def latest_upload_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "upload failed" in text or "upload_failed" in text:
            return f"실패 {format_log_time(row)}"
        if "upload succeeded" in text or "upload success" in text or "upload_succeeded" in text:
            return f"성공 {format_log_time(row)}"
    return "기록 없음"


def latest_upload_card(rows: Sequence[dict[str, Any]]) -> dict[str, str]:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "upload failed" in text or "upload_failed" in text:
            return {"value": format_log_time(row), "detail": "업로드 상태: 실패", "tone": "danger"}
        if "upload succeeded" in text or "upload success" in text or "upload_succeeded" in text:
            return {"value": format_log_time(row), "detail": "업로드 상태: 성공", "tone": "ok"}
    return {"value": "기록 없음", "detail": "업로드 상태: 대기", "tone": "muted"}


def latest_server_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "heartbeat failed" in text or "heartbeat missing" in text:
            return f"실패 {format_log_time(row)}"
        if "heartbeat succeeded" in text or "heartbeat success" in text:
            return f"연결 기록 {format_log_time(row)}"
    return "확인 전"


def latest_server_card(rows: Sequence[dict[str, Any]]) -> dict[str, str]:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "heartbeat failed" in text or "heartbeat missing" in text:
            return {"value": "● 연결 실패", "detail": f"마지막 확인 : {format_log_time(row)}", "tone": "danger"}
        if "heartbeat succeeded" in text or "heartbeat success" in text:
            return {"value": "● 연결됨", "detail": f"마지막 확인 : {format_log_time(row)}", "tone": "ok"}
    return {"value": "● 확인 전", "detail": "heartbeat 대기", "tone": "muted"}


def startup_card_status() -> dict[str, str]:
    try:
        path = startup_dir() / f"{APP_NAME}.cmd"
    except Exception:
        return {"value": "확인 불가", "detail": "시작프로그램 경로 확인 실패", "tone": "warning"}
    if path.exists():
        return {"value": "사용 중", "detail": "시스템 시작 시 자동 실행", "tone": "ok"}
    return {"value": "미등록", "detail": "시작프로그램 미등록", "tone": "warning"}


def status_home_model(config: AgentConfig, path: Path) -> dict[str, Any]:
    rows = read_log_tail(path, LOG_TABLE_LIMIT)
    signals = detect_recent_signals(rows)
    home_detection = status_home_detection(rows, signals)
    pc_card = pc_status_card(rows, signals, home_detection)
    upload_card = latest_upload_card(rows)
    startup_card = startup_card_status()
    return {
        "agentStatus": "정상 실행 중" if config.agent_token else "등록 필요",
        "pcStatusCard": pc_card,
        "lastUpload": latest_upload_status(rows),
        "uploadCard": upload_card,
        "startupCard": startup_card,
        "version": config.agent_version,
        "policyVersion": config.policy_version,
        "versionCard": {"value": config.agent_version, "detail": "최신 버전", "tone": "ok"},
        "signals": signals,
        "homeDetection": home_detection,
    }


def diagnosis_tone_label(tone: str) -> str:
    if tone == "danger":
        return "위험"
    if tone == "warning":
        return "주의"
    if tone == "muted":
        return "확인 전"
    return "정상"


def diagnosis_metric_status(value: float | None) -> tuple[str, str]:
    if value is None:
        return "확인 불가", "muted"
    if value >= DIAGNOSIS_DETAIL_DANGER_THRESHOLD:
        return "위험", "danger"
    if value >= DIAGNOSIS_DETAIL_WARNING_THRESHOLD:
        return "주의", "warning"
    return "정상", "ok"


def diagnosis_metric_description(name: str, value: float | None, status: str) -> str:
    if value is None:
        return f"최근 로그에 {name} 값이 없습니다."
    if status == "정상":
        if name == "디스크":
            return "디스크 busy는 안정적입니다."
        return f"현재 {name} 사용률은 안정적입니다."
    if name == "메모리":
        return "메모리 사용량이 높습니다."
    if name == "디스크":
        return "디스크 busy가 높습니다."
    return f"{name} 사용률이 높습니다."


def latest_diagnosis_metric_row(rows: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    metric_fields = (
        ("cpuUsage", "cpuUsagePercent"),
        ("memoryUsage", "ramUsage", "memoryUsedPercent"),
        ("diskBusyEstimatePercent",),
        ("gpuUsage", "gpuUsagePercent"),
    )
    for row in reversed(list(rows)):
        if any(numeric_log_value(row, *fields) is not None for fields in metric_fields):
            return row
    return None


def diagnosis_metric_card(
    row: dict[str, Any] | None,
    name: str,
    fields: Sequence[str],
    current_label: str,
    threshold: str,
) -> dict[str, str]:
    value = numeric_log_value(row, *fields) if row is not None else None
    status, tone = diagnosis_metric_status(value)
    return {
        "name": name,
        "status": status,
        "tone": tone,
        "currentLabel": current_label,
        "currentValue": format_percent(value),
        "threshold": threshold,
        "description": diagnosis_metric_description(name, value, status),
    }


def diagnosis_detail_event_status(row: dict[str, Any]) -> tuple[str, str]:
    signal = detect_signal(row)
    if signal is not None:
        if str(signal.get("level")) == "위험":
            return "위험", "danger"
        return "주의", "warning"
    values = [
        numeric_log_value(row, "cpuUsage", "cpuUsagePercent"),
        numeric_log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent"),
        numeric_log_value(row, "diskBusyEstimatePercent"),
        numeric_log_value(row, "gpuUsage", "gpuUsagePercent"),
    ]
    numeric_values = [value for value in values if value is not None]
    if any(value >= DIAGNOSIS_DETAIL_DANGER_THRESHOLD for value in numeric_values):
        return "위험", "danger"
    if any(value >= DIAGNOSIS_DETAIL_WARNING_THRESHOLD for value in numeric_values):
        return "주의", "warning"
    return "정상", "ok"


def diagnosis_detail_event_rows(rows: Sequence[dict[str, Any]]) -> list[dict[str, str]]:
    events: list[dict[str, str]] = []
    for row in rows[-DIAGNOSIS_DETAIL_EVENT_LIMIT:]:
        status, tone = diagnosis_detail_event_status(row)
        content = display_log_message(row)
        if content == "-":
            content = display_log_event_summary(row)
        events.append(
            {
                "time": format_log_time(row),
                "type": display_log_event_summary(row),
                "content": content,
                "status": status,
                "tone": tone,
            }
        )
    return events


def diagnosis_detail_model(config: AgentConfig, path: Path) -> dict[str, Any]:
    rows = read_log_tail(path, LOG_TABLE_LIMIT)
    home_model = status_home_model(config, path)
    latest_row = rows[-1] if rows else None
    metric_row = latest_diagnosis_metric_row(rows)
    home_detection = home_model.get("homeDetection")
    has_result = latest_row is not None
    pc_card = home_model["pcStatusCard"]
    tone = "muted" if not has_result else str(pc_card.get("tone", "muted"))

    if not has_result:
        summary = "아직 진단 결과가 없습니다."
    elif isinstance(home_detection, dict):
        summary = sanitize_display_text(home_detection.get("detail") or home_detection.get("title"), 120)
    else:
        summary = "최근 로그에서 특이 신호가 없습니다."

    threshold = (
        f"{DIAGNOSIS_DETAIL_WARNING_THRESHOLD:.0f}% 이상 시 주의 / "
        f"{DIAGNOSIS_DETAIL_DANGER_THRESHOLD:.0f}% 이상 시 위험"
    )
    disk_threshold = (
        f"busy {DIAGNOSIS_DETAIL_WARNING_THRESHOLD:.0f}% 이상 시 주의 / "
        f"{DIAGNOSIS_DETAIL_DANGER_THRESHOLD:.0f}% 이상 시 위험"
    )
    summary_rows = read_status_log_summary_rows(path, DIAGNOSIS_DETAIL_EVENT_LIMIT)
    return {
        "hasResult": has_result,
        "title": "진단 결과 상세",
        "status": diagnosis_tone_label(tone),
        "tone": tone,
        "lastDiagnosticTime": format_log_timestamp(latest_row) if latest_row else "-",
        "summary": summary,
        "emptyTitle": "아직 진단 결과가 없습니다.",
        "emptyMessage": "PCAgent가 로그를 수집하면 상세 결과를 확인할 수 있습니다.",
        "metrics": [
            diagnosis_metric_card(metric_row, "CPU", ("cpuUsage", "cpuUsagePercent"), "현재 사용률", threshold),
            diagnosis_metric_card(
                metric_row,
                "메모리",
                ("memoryUsage", "ramUsage", "memoryUsedPercent"),
                "현재 사용률",
                threshold,
            ),
            diagnosis_metric_card(metric_row, "디스크", ("diskBusyEstimatePercent",), "현재 busy", disk_threshold),
            diagnosis_metric_card(metric_row, "GPU", ("gpuUsage", "gpuUsagePercent"), "현재 사용률", threshold),
        ],
        "events": diagnosis_detail_event_rows(summary_rows),
        "aiAnalysis": {
            "available": False,
            "summary": "AI 분석 결과 없음",
            "suspectedCauses": [],
            "recommendedActions": [],
            "adminReview": "AI 분석 결과가 저장되어 있지 않습니다.",
        },
    }


def powershell_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def show_agent_error_dialog(title: str, message: str) -> None:
    if tk is not None:
        root = None
        try:
            width = 560
            height = 260
            root = tk.Tk()
            root.title(title)
            root.update_idletasks()
            screen_width = root.winfo_screenwidth()
            screen_height = root.winfo_screenheight()
            x = max(0, (screen_width - width) // 2)
            y = max(0, (screen_height - height) // 2)
            root.geometry(f"{width}x{height}+{x}+{y}")
            root.minsize(520, 220)
            root.configure(background="#f8fafc")
            root.attributes("-topmost", True)
            root.after(250, lambda: root.attributes("-topmost", False))

            container = tk.Frame(root, background="#f8fafc", padx=22, pady=18)
            container.pack(fill="both", expand=True)
            tk.Label(
                container,
                text=title,
                font=("Segoe UI", 13, "bold"),
                foreground="#991b1b",
                background="#f8fafc",
                anchor="w",
            ).pack(fill="x")
            tk.Label(
                container,
                text=message,
                font=("Segoe UI", 10),
                foreground="#1f2937",
                background="#f8fafc",
                justify="left",
                anchor="nw",
                wraplength=500,
            ).pack(fill="both", expand=True, pady=(12, 16))
            tk.Button(
                container,
                text="확인",
                width=10,
                command=root.destroy,
            ).pack(anchor="e")
            root.protocol("WM_DELETE_WINDOW", root.destroy)
            root.mainloop()
            return
        except Exception:
            pass
        finally:
            if root is not None:
                try:
                    root.destroy()
                except Exception:
                    pass
    if os.name != "nt":
        return
    command = (
        "Add-Type -AssemblyName PresentationFramework; "
        f"[System.Windows.MessageBox]::Show({powershell_string(message)}, {powershell_string(title)}, 'OK', 'Error')"
    )
    try:
        subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command],
            **hidden_subprocess_kwargs(),
        )
    except OSError:
        return


def show_log_viewer_powershell(config_path: Path) -> None:
    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    path = log_file(config)
    model = status_home_model(config, path)
    signal_lines = [
        f"{signal['time']}  {signal['level']}  {signal['title']} ->"
        for signal in model["signals"]
    ] or ["최근 감지 신호 없음"]
    signals_text = "\r\n".join(signal_lines)
    viewer_path = app_data_dir() / "log-viewer.ps1"
    viewer_path.parent.mkdir(parents=True, exist_ok=True)
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$logPath = {powershell_string(str(log_file(config).resolve()))}
$logDir = {powershell_string(str(config.log_dir.resolve()))}
$supportUrl = {powershell_string(support_new_url(config))}
$pcStatus = {powershell_string(str(model["pcStatusCard"]["value"]))}
$pcDetail = {powershell_string(str(model["pcStatusCard"]["detail"]))}
$pcTone = {powershell_string(str(model["pcStatusCard"].get("tone", "muted")))}
$uploadStatus = {powershell_string(str(model["uploadCard"]["value"]))}
$uploadDetail = {powershell_string(str(model["uploadCard"]["detail"]))}
$startupStatus = {powershell_string(str(model["startupCard"]["value"]))}
$startupDetail = {powershell_string(str(model["startupCard"]["detail"]))}
$versionText = {powershell_string(str(model["versionCard"]["value"]))}
$versionDetail = {powershell_string(str(model["versionCard"]["detail"]))}
$signalsText = {powershell_string(signals_text)}

$appBg = [System.Drawing.ColorTranslator]::FromHtml("#f5f7f8")
$sidebarBg = [System.Drawing.ColorTranslator]::FromHtml("#e7f2ef")
$cardBg = [System.Drawing.ColorTranslator]::FromHtml("#ffffff")
$sectionBg = [System.Drawing.ColorTranslator]::FromHtml("#f8fbfb")
$primaryBg = [System.Drawing.ColorTranslator]::FromHtml("#1f8a70")
$warningColor = [System.Drawing.ColorTranslator]::FromHtml("#b7791f")
$dangerColor = [System.Drawing.ColorTranslator]::FromHtml("#b42318")
$okSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#e8f7f4")
$warningSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#fff7e6")
$dangerSoftBg = [System.Drawing.ColorTranslator]::FromHtml("#fff1f0")
$borderColor = [System.Drawing.ColorTranslator]::FromHtml("#d7e0e3")
$textColor = [System.Drawing.ColorTranslator]::FromHtml("#172b3a")
$mutedColor = [System.Drawing.ColorTranslator]::FromHtml("#5c6b73")
$uiFontFamily = "Malgun Gothic"
$installedFonts = New-Object System.Drawing.Text.InstalledFontCollection
$installedFontNames = @($installedFonts.Families | ForEach-Object {{ $_.Name }})
foreach ($candidate in @("Segoe UI Variable", "Segoe UI", "Malgun Gothic")) {{
  if ($installedFontNames -contains $candidate) {{
    $uiFontFamily = $candidate
    break
  }}
}}

function New-UIFont {{
  param([float]$Px, [System.Drawing.FontStyle]$Style = [System.Drawing.FontStyle]::Regular)
  return New-Object System.Drawing.Font($uiFontFamily, ($Px * 72.0 / 96.0), $Style, [System.Drawing.GraphicsUnit]::Point)
}}

function Get-ToneColor {{
  param([string]$Tone)
  switch ($Tone) {{
    "ok" {{ return $primaryBg }}
    "warning" {{ return $warningColor }}
    "danger" {{ return $dangerColor }}
    default {{ return $mutedColor }}
  }}
}}

function Get-ToneBackColor {{
  param([string]$Tone)
  switch ($Tone) {{
    "ok" {{ return $okSoftBg }}
    "warning" {{ return $warningSoftBg }}
    "danger" {{ return $dangerSoftBg }}
    default {{ return $sectionBg }}
  }}
}}

function Style-Button {{
  param($Button, [bool]$Primary = $false)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 1
  $Button.FlatAppearance.BorderColor = $borderColor
  $Button.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  if ($Primary) {{
    $Button.BackColor = $primaryBg
    $Button.ForeColor = [System.Drawing.Color]::White
  }} else {{
    $Button.BackColor = [System.Drawing.Color]::White
    $Button.ForeColor = $textColor
  }}
}}

function Style-NavLink {{
  param($Button)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 0
  $Button.Font = New-UIFont 14
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  $Button.BackColor = $sidebarBg
  $Button.ForeColor = $textColor
  $Button.TextAlign = [System.Drawing.ContentAlignment]::MiddleLeft
}}

function Add-SoftBorder {{
  param($Panel)
  $Panel.BorderStyle = [System.Windows.Forms.BorderStyle]::None
  $Panel.Add_Paint({{
    param($sender, $eventArgs)
    $eventArgs.Graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $rect = New-Object System.Drawing.Rectangle(0, 0, ($sender.Width - 1), ($sender.Height - 1))
    $radius = 8
    $diameter = $radius * 2
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($rect.X, $rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc(($rect.Right - $diameter), $rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc(($rect.Right - $diameter), ($rect.Bottom - $diameter), $diameter, $diameter, 0, 90)
    $path.AddArc($rect.X, ($rect.Bottom - $diameter), $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $pen = New-Object System.Drawing.Pen($borderColor, 1)
    $eventArgs.Graphics.DrawPath($pen, $path)
    $pen.Dispose()
    $path.Dispose()
  }})
}}

function Get-RowValue {{
  param($Row, [string[]]$Names)
  foreach ($name in $Names) {{
    if ($Row.PSObject.Properties[$name] -and $null -ne $Row.$name) {{
      return $Row.$name
    }}
    if ($Row.PSObject.Properties["payload"] -and $null -ne $Row.payload -and $Row.payload.PSObject.Properties[$name] -and $null -ne $Row.payload.$name) {{
      return $Row.payload.$name
    }}
  }}
  return $null
}}

function Hide-SensitiveText {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  $text = [string]$Value
  $text = [regex]::Replace($text, "(?i)(authorization|agenttoken|activationtoken|token|password)\s*[:=]\s*\S+", '$1=[hidden]')
  $text = [regex]::Replace($text, "[A-Za-z]:\\\\[^\s\t\r\n]+", "[path hidden]")
  $text = [regex]::Replace($text, "(/[^\s\t\r\n]+){{2,}}", "[path hidden]")
  if ($text.Length -gt 120) {{ $text = $text.Substring(0, 117) + "..." }}
  return $text
}}

function Format-Percent {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  try {{ return "{{0:N1}}%" -f [double]$Value }} catch {{ return "-" }}
}}

function Format-Temp {{
  param($Value)
  if ($null -eq $Value) {{ return "-" }}
  try {{ return "{{0:N1}}C" -f [double]$Value }} catch {{ return "-" }}
}}

function Get-ObservedAt {{
  param($Row)
  $value = Get-RowValue $Row @("timestamp", "collectedAt", "receivedAt", "detectedAt")
  if ($null -eq $value) {{ return $null }}
  try {{ return [datetime]::Parse([string]$value).ToLocalTime() }} catch {{ return $null }}
}}

function Get-FilteredLogText {{
  param([string]$DateText, [int]$Hour)
  if (-not (Test-Path -LiteralPath $logPath)) {{
    return "아직 수집된 로그가 없습니다."
  }}
  $output = New-Object System.Collections.Generic.List[string]
  try {{
    $start = [datetime]::ParseExact($DateText, "yyyy-MM-dd", $null).AddHours($Hour)
  }} catch {{
    return "날짜 형식이 올바르지 않습니다. yyyy-MM-dd 형식으로 입력하세요."
  }}
  $end = $start.AddHours(1)
  $output.Add(("{{0,-9}} {{1,-26}} {{2,8}} {{3,8}} {{4,8}} {{5,8}} {{6,8}}  {{7}}" -f "시간", "이벤트 종류", "CPU", "MEM", "DISK", "GPU", "CPU 온도", "메시지"))
  $output.Add("-" * 112)
  Get-Content -LiteralPath $logPath | ForEach-Object {{
    try {{
      $row = $_ | ConvertFrom-Json
      $observedAt = Get-ObservedAt $row
      if ($observedAt -ge $start -and $observedAt -lt $end) {{
        $timeText = $observedAt.ToString("HH:mm:ss")
        $kind = Hide-SensitiveText (Get-RowValue $row @("kind", "eventType"))
        $cpu = Format-Percent (Get-RowValue $row @("cpuUsage", "cpuUsagePercent"))
        $memory = Format-Percent (Get-RowValue $row @("memoryUsage", "ramUsage", "memoryUsedPercent"))
        $disk = Format-Percent (Get-RowValue $row @("diskBusyEstimatePercent"))
        $gpu = Format-Percent (Get-RowValue $row @("gpuUsage", "gpuUsagePercent"))
        $cpuTemp = Format-Temp (Get-RowValue $row @("cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius"))
        $message = Hide-SensitiveText (Get-RowValue $row @("message", "osErrorEvent", "status", "summary"))
        $output.Add(("{{0,-9}} {{1,-26}} {{2,8}} {{3,8}} {{4,8}} {{5,8}} {{6,8}}  {{7}}" -f $timeText, $kind, $cpu, $memory, $disk, $gpu, $cpuTemp, $message))
      }}
    }} catch {{}}
  }}
  if ($output.Count -eq 2) {{
    return "선택한 구간에 표시할 로그가 없습니다."
  }}
  if ($output.Count -gt 500) {{
    $output = $output.GetRange($output.Count - 500, 500)
  }}
  return ($output -join "`r`n")
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "PCAgent"
$form.Size = New-Object System.Drawing.Size(1000, 720)
$form.MinimumSize = New-Object System.Drawing.Size(1000, 720)
$form.MaximumSize = New-Object System.Drawing.Size(1000, 720)
$form.StartPosition = "CenterScreen"
$form.BackColor = $appBg
$form.Font = New-UIFont 14

$sidebar = New-Object System.Windows.Forms.Panel
$sidebar.Location = New-Object System.Drawing.Point(0, 0)
$sidebar.Size = New-Object System.Drawing.Size(150, 640)
$sidebar.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
$sidebar.BackColor = $sidebarBg
$form.Controls.Add($sidebar)

$brand = New-Object System.Windows.Forms.Label
$brand.Text = "PCAgent"
$brand.Font = New-UIFont 14
$brand.ForeColor = $textColor
$brand.BackColor = $sidebarBg
$brand.Location = New-Object System.Drawing.Point(24, 12)
$brand.Size = New-Object System.Drawing.Size(110, 26)
$sidebar.Controls.Add($brand)

$navStatus = New-Object System.Windows.Forms.Panel
$navStatus.Location = New-Object System.Drawing.Point(8, 58)
$navStatus.Size = New-Object System.Drawing.Size(134, 44)
$navStatus.BackColor = [System.Drawing.Color]::White
Add-SoftBorder $navStatus
$activeBar = New-Object System.Windows.Forms.Panel
$activeBar.Location = New-Object System.Drawing.Point(0, 0)
$activeBar.Size = New-Object System.Drawing.Size(4, 44)
$activeBar.BackColor = $primaryBg
$navStatus.Controls.Add($activeBar)
$navStatusLabel = New-Object System.Windows.Forms.Label
$navStatusLabel.Text = "  • 상태"
$navStatusLabel.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
$navStatusLabel.ForeColor = $textColor
$navStatusLabel.BackColor = [System.Drawing.Color]::White
$navStatusLabel.Location = New-Object System.Drawing.Point(18, 12)
$navStatusLabel.Size = New-Object System.Drawing.Size(96, 20)
$navStatus.Controls.Add($navStatusLabel)
$sidebar.Controls.Add($navStatus)

$navLog = New-Object System.Windows.Forms.Button
$navLog.Text = "≡ 기록"
$navLog.Location = New-Object System.Drawing.Point(28, 124)
$navLog.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navLog
$navLog.Add_Click({{ $textbox.Focus() }})
$sidebar.Controls.Add($navLog)

$navSupport = New-Object System.Windows.Forms.Button
$navSupport.Text = "+ 진단"
$navSupport.Location = New-Object System.Drawing.Point(28, 174)
$navSupport.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navSupport
$navSupport.Add_Click({{ Start-Process -FilePath $supportUrl }})
$sidebar.Controls.Add($navSupport)

$navSettings = New-Object System.Windows.Forms.Button
$navSettings.Text = "○ 설정"
$navSettings.Location = New-Object System.Drawing.Point(28, 224)
$navSettings.Size = New-Object System.Drawing.Size(96, 32)
Style-NavLink $navSettings
$navSettings.Add_Click({{ Start-Process -FilePath $logDir }})
$sidebar.Controls.Add($navSettings)

$title = New-Object System.Windows.Forms.Label
$title.Text = "상태 홈"
$title.Font = New-UIFont 20 ([System.Drawing.FontStyle]::Bold)
$title.ForeColor = $textColor
$title.BackColor = $appBg
$title.AutoSize = $true
$title.Location = New-Object System.Drawing.Point(170, 48)
$form.Controls.Add($title)

$subtitle = New-Object System.Windows.Forms.Label
$subtitle.Text = "시스템 상태와 감지 로그를 실시간으로 확인합니다."
$subtitle.Font = New-UIFont 12
$subtitle.ForeColor = $mutedColor
$subtitle.BackColor = $appBg
$subtitle.AutoSize = $true
$subtitle.Location = New-Object System.Drawing.Point(170, 82)
$form.Controls.Add($subtitle)

function Add-Card {{
  param(
    [int]$X,
    [string]$Icon,
    [string]$Title,
    [string]$Value,
    [string]$Detail,
    [System.Drawing.Color]$ToneColor = $primaryBg,
    [System.Drawing.Color]$ToneBackColor = $sectionBg,
    [bool]$HighlightTitle = $false
  )
  $card = New-Object System.Windows.Forms.Panel
  $card.Location = New-Object System.Drawing.Point($X, 114)
  $card.Size = New-Object System.Drawing.Size(185, 90)
  $card.BackColor = $cardBg
  Add-SoftBorder $card
  $form.Controls.Add($card)
  $iconBox = New-Object System.Windows.Forms.Label
  $iconBox.Text = $Icon
  $iconBox.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
  $iconBox.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $iconBox.ForeColor = $ToneColor
  $iconBox.BackColor = $ToneBackColor
  $iconBox.BorderStyle = [System.Windows.Forms.BorderStyle]::FixedSingle
  $iconBox.Location = New-Object System.Drawing.Point(144, 12)
  $iconBox.Size = New-Object System.Drawing.Size(28, 28)
  $card.Controls.Add($iconBox)
  $cardTitle = New-Object System.Windows.Forms.Label
  $cardTitle.Text = $Title
  $cardTitle.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
  if ($HighlightTitle) {{ $cardTitle.ForeColor = $ToneColor }} else {{ $cardTitle.ForeColor = $textColor }}
  $cardTitle.BackColor = $cardBg
  $cardTitle.Location = New-Object System.Drawing.Point(14, 14)
  $cardTitle.Size = New-Object System.Drawing.Size(124, 20)
  $card.Controls.Add($cardTitle)
  $cardValue = New-Object System.Windows.Forms.Label
  $cardValue.Text = $Value
  $cardValue.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  $cardValue.ForeColor = $ToneColor
  $cardValue.BackColor = $cardBg
  $cardValue.Location = New-Object System.Drawing.Point(14, 38)
  $cardValue.Size = New-Object System.Drawing.Size(136, 24)
  $card.Controls.Add($cardValue)
  $cardDetail = New-Object System.Windows.Forms.Label
  $cardDetail.Text = $Detail
  $cardDetail.ForeColor = $mutedColor
  $cardDetail.BackColor = $cardBg
  $cardDetail.Location = New-Object System.Drawing.Point(14, 68)
  $cardDetail.Size = New-Object System.Drawing.Size(154, 18)
  $card.Controls.Add($cardDetail)
}}

$pcToneColor = Get-ToneColor $pcTone
$pcToneBackColor = Get-ToneBackColor $pcTone
Add-Card 170 "PC" "PC 상태" $pcStatus $pcDetail $pcToneColor $pcToneBackColor $true
Add-Card 365 "UP" "마지막 업로드" $uploadStatus $uploadDetail
Add-Card 560 "ST" "시작프로그램" $startupStatus $startupDetail
Add-Card 755 "i" "버전" $versionText $versionDetail

$signalsPanel = New-Object System.Windows.Forms.Panel
$signalsPanel.Location = New-Object System.Drawing.Point(170, 216)
$signalsPanel.Size = New-Object System.Drawing.Size(790, 124)
$signalsPanel.BackColor = $cardBg
Add-SoftBorder $signalsPanel
$form.Controls.Add($signalsPanel)

$signalLabel = New-Object System.Windows.Forms.Label
$signalLabel.Text = "최근 감지 신호"
$signalLabel.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$signalLabel.ForeColor = $textColor
$signalLabel.BackColor = $cardBg
$signalLabel.Location = New-Object System.Drawing.Point(16, 14)
$signalLabel.AutoSize = $true
$signalsPanel.Controls.Add($signalLabel)

$signalsBody = New-Object System.Windows.Forms.Label
$signalsBody.Text = $signalsText
$signalsBody.Font = New-UIFont 14
$signalsBody.ForeColor = $textColor
$signalsBody.BackColor = $cardBg
$signalsBody.Location = New-Object System.Drawing.Point(16, 44)
$signalsBody.Size = New-Object System.Drawing.Size(750, 22)
$signalsPanel.Controls.Add($signalsBody)

$signalsHelp = New-Object System.Windows.Forms.Label
$signalsHelp.Text = "단순 CPU/RAM/GPU 고사용률은 AS 알림에서 제외됩니다"
$signalsHelp.Font = New-UIFont 12
$signalsHelp.ForeColor = $mutedColor
$signalsHelp.BackColor = $cardBg
$signalsHelp.Location = New-Object System.Drawing.Point(16, 70)
$signalsHelp.Size = New-Object System.Drawing.Size(750, 20)
$signalsPanel.Controls.Add($signalsHelp)

$dateLabel = New-Object System.Windows.Forms.Label
$dateLabel.Text = "날짜"
$dateLabel.AutoSize = $true
$dateLabel.ForeColor = $mutedColor
$dateLabel.BackColor = $appBg
$dateLabel.Location = New-Object System.Drawing.Point(138, 282)
$dateLabel.Visible = $false
$form.Controls.Add($dateLabel)

$dateInput = New-Object System.Windows.Forms.TextBox
$dateInput.Location = New-Object System.Drawing.Point(180, 278)
$dateInput.Size = New-Object System.Drawing.Size(96, 22)
$dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
$dateInput.Visible = $false
$form.Controls.Add($dateInput)

$hourLabel = New-Object System.Windows.Forms.Label
$hourLabel.Text = "시간"
$hourLabel.AutoSize = $true
$hourLabel.ForeColor = $mutedColor
$hourLabel.BackColor = $appBg
$hourLabel.Location = New-Object System.Drawing.Point(292, 282)
$hourLabel.Visible = $false
$form.Controls.Add($hourLabel)

$hourSelect = New-Object System.Windows.Forms.ComboBox
$hourSelect.DropDownStyle = "DropDownList"
$hourSelect.Location = New-Object System.Drawing.Point(334, 278)
$hourSelect.Size = New-Object System.Drawing.Size(72, 22)
0..23 | ForEach-Object {{ [void]$hourSelect.Items.Add(($_.ToString("00")) + ":00") }}
$hourSelect.SelectedIndex = [int](Get-Date).Hour
$hourSelect.Visible = $false
$form.Controls.Add($hourSelect)

$logPanel = New-Object System.Windows.Forms.Panel
$logPanel.Location = New-Object System.Drawing.Point(170, 350)
$logPanel.Size = New-Object System.Drawing.Size(790, 240)
$logPanel.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
$logPanel.BackColor = $cardBg
Add-SoftBorder $logPanel
$form.Controls.Add($logPanel)

$logTitle = New-Object System.Windows.Forms.Label
$logTitle.Text = "로그 현황"
$logTitle.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$logTitle.ForeColor = $textColor
$logTitle.BackColor = $cardBg
$logTitle.Location = New-Object System.Drawing.Point(16, 14)
$logTitle.AutoSize = $true
$logPanel.Controls.Add($logTitle)

$textbox = New-Object System.Windows.Forms.TextBox
$textbox.Multiline = $true
$textbox.ScrollBars = "Both"
$textbox.ReadOnly = $true
$textbox.Font = New-UIFont 12
$textbox.BackColor = [System.Drawing.Color]::White
$textbox.ForeColor = $textColor
$textbox.BorderStyle = [System.Windows.Forms.BorderStyle]::None
$textbox.Location = New-Object System.Drawing.Point(16, 46)
$textbox.Size = New-Object System.Drawing.Size(758, 178)
$textbox.Anchor = [System.Windows.Forms.AnchorStyles]::Top -bor [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left -bor [System.Windows.Forms.AnchorStyles]::Right
$textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
$logPanel.Controls.Add($textbox)

$refresh = New-Object System.Windows.Forms.Button
$refresh.Text = "로그 갱신"
$refresh.Location = New-Object System.Drawing.Point(420, 277)
$refresh.Size = New-Object System.Drawing.Size(88, 24)
Style-Button $refresh $true
$refresh.Add_Click({{ $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex }})
$refresh.Visible = $false
$form.Controls.Add($refresh)

$today = New-Object System.Windows.Forms.Button
$today.Text = "현재"
$today.Location = New-Object System.Drawing.Point(516, 277)
$today.Size = New-Object System.Drawing.Size(64, 24)
Style-Button $today
$today.Add_Click({{
  $dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
  $hourSelect.SelectedIndex = [int](Get-Date).Hour
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
}})
$today.Visible = $false
$form.Controls.Add($today)

$folder = New-Object System.Windows.Forms.Button
$folder.Text = "로그 폴더"
$folder.Location = New-Object System.Drawing.Point(138, 550)
$folder.Size = New-Object System.Drawing.Size(100, 28)
$folder.Anchor = [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
Style-Button $folder
$folder.Add_Click({{ Start-Process -FilePath $logDir }})
$folder.Visible = $false
$form.Controls.Add($folder)

$support = New-Object System.Windows.Forms.Button
$support.Text = "AS 페이지"
$support.Location = New-Object System.Drawing.Point(250, 550)
$support.Size = New-Object System.Drawing.Size(100, 28)
$support.Anchor = [System.Windows.Forms.AnchorStyles]::Bottom -bor [System.Windows.Forms.AnchorStyles]::Left
Style-Button $support
$support.Add_Click({{ Start-Process -FilePath $supportUrl }})
$support.Visible = $false
$form.Controls.Add($support)

$liveRefreshTimer = New-Object System.Windows.Forms.Timer
$liveRefreshTimer.Interval = 5000
$liveRefreshTimer.Add_Tick({{
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
  $textbox.SelectionStart = $textbox.TextLength
  $textbox.ScrollToCaret()
}})
$form.Add_Shown({{ $liveRefreshTimer.Start() }})
$form.Add_FormClosed({{ $liveRefreshTimer.Stop(); $liveRefreshTimer.Dispose() }})

[void]$form.ShowDialog()
"""
    viewer_path.write_text(script.strip() + "\n", encoding="utf-8")
    try:
        subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(viewer_path),
            ],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception:
        open_log_folder(config_path)


def show_log_viewer(
    config_path: Path,
    focus_signal: dict[str, Any] | None = None,
    support_signal: dict[str, Any] | None = None,
    background_mode: bool = False,
    diagnosis_session_provider: Any = None,
    connection_state_provider: Any = None,
    on_window_ready: Any = None,
    on_window_closed: Any = None,
) -> None:
    """Render the reference PC diagnosis flow while preserving the existing Agent boundaries."""
    if tk is None or ttk is None:
        show_log_viewer_powershell(config_path)
        return

    viewer_lock = acquire_named_instance_lock(VIEWER_INSTANCE_MUTEX_NAME)
    if viewer_lock is None:
        return

    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    root = tk.Tk()
    root.title(DISPLAY_APP_NAME)
    apply_agent_window_icon(root)
    root.geometry(f"{PC_AGENT_WINDOW_WIDTH}x{PC_AGENT_WINDOW_HEIGHT}")
    root.minsize(PC_AGENT_WINDOW_WIDTH, PC_AGENT_WINDOW_HEIGHT)
    root.maxsize(PC_AGENT_WINDOW_WIDTH, PC_AGENT_WINDOW_HEIGHT)
    root.resizable(False, False)
    root.overrideredirect(os.environ.get("PC_AGENT_CAPTURE_NATIVE") != "1")
    root.configure(background="#ffffff")
    root.attributes("-topmost", True)
    root.after(15000, lambda: root.attributes("-topmost", False))
    root.lift()
    root.focus_force()
    try:
        root.tk.call("tk", "scaling", 1.0)
    except tk.TclError:
        pass

    ui_font_family = resolve_ui_font_family(root)

    def font(size: int, weight: str = "regular") -> tuple[Any, ...]:
        return tk_ui_font(ui_font_family, size, weight)

    canvas = tk.Canvas(
        root,
        width=PC_AGENT_WINDOW_WIDTH,
        height=PC_AGENT_WINDOW_HEIGHT,
        background="#ffffff",
        highlightthickness=0,
        borderwidth=0,
    )
    canvas.pack(fill="both", expand=True)

    colors = {
        "text": "#111111",
        "muted": "#767676",
        "subtle": "#9a9a9a",
        "line": "#dedede",
        "soft": "#f5f5f5",
        "blue": "#1677ff",
        "red": "#ff4d3d",
        "red_soft": "#fff0ef",
        "green": "#12bd67",
        "green_soft": "#effbf5",
        "warning": "#f59e0b",
        "danger": "#c2410c",
    }
    capture_state = os.environ.get("PC_AGENT_CAPTURE_STATE", "").strip()
    if capture_state not in {"SYMPTOM_CONFIRM", "DIAGNOSING", "DIAGNOSIS_RESULT", "AS_REQUEST_CREATED"}:
        capture_state = "SYMPTOM_CONFIRM"
    diagnosis_session = diagnosis_session_provider() if callable(diagnosis_session_provider) else None
    ui = {
        "state": capture_state,
        "demo": diagnosis_session.request.mode == "DEMO" if isinstance(diagnosis_session, DiagnosisSession) else True,
        "diagnosisSession": diagnosis_session,
        "requestLocked": isinstance(diagnosis_session, DiagnosisSession),
        "busy": False,
        "diagnosisReady": False,
        "status": "",
        "diagnosis": None,
        "window": None,
        "ticketUrl": None,
    }
    system_sensor_provider = SystemSensorProvider()
    demo_sensor_provider = DemoSensorProvider()
    image_refs: list[Any] = []
    button_counter = {"value": 0}
    drag_origin = {"x": 0, "y": 0}

    def round_rect(
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        radius: int,
        fill: str,
        outline: str = "",
        width: int = 1,
        tags: tuple[str, ...] | str = (),
    ) -> int:
        points = [
            x1 + radius, y1, x2 - radius, y1, x2, y1, x2, y1 + radius,
            x2, y2 - radius, x2, y2, x2 - radius, y2, x1 + radius, y2,
            x1, y2, x1, y2 - radius, x1, y1 + radius, x1, y1,
        ]
        return canvas.create_polygon(
            points,
            smooth=True,
            splinesteps=24,
            fill=fill,
            outline=outline,
            width=width,
            tags=tags,
        )

    def text(
        x: int,
        y: int,
        value: str,
        size: int,
        color: str | None = None,
        weight: str = "regular",
        anchor: str = "nw",
        width: int | None = None,
        tags: tuple[str, ...] | str = (),
    ) -> int:
        options: dict[str, Any] = {
            "text": value,
            "font": font(size, weight),
            "fill": color or colors["text"],
            "anchor": anchor,
            "tags": tags,
        }
        if width is not None:
            options["width"] = width
        return canvas.create_text(x, y, **options)

    def line(x1: int, y1: int, x2: int, y2: int, color: str | None = None, width: int = 1) -> int:
        return canvas.create_line(x1, y1, x2, y2, fill=color or colors["line"], width=width)

    def bind_click(tag: str, command: Any) -> None:
        canvas.tag_bind(tag, "<Button-1>", lambda event: command())
        canvas.tag_bind(tag, "<Enter>", lambda event: canvas.configure(cursor="hand2"))
        canvas.tag_bind(tag, "<Leave>", lambda event: canvas.configure(cursor=""))

    def button(
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        label: str,
        command: Any,
        primary: bool = True,
        disabled: bool = False,
        size: int = 20,
    ) -> None:
        button_counter["value"] += 1
        tag = f"button-{button_counter['value']}"
        if primary:
            fill, stroke, foreground = ("#111111", "#111111", "#ffffff")
        else:
            fill, stroke, foreground = ("#ffffff", "#111111", "#111111")
        if disabled:
            fill, stroke, foreground = ("#f2f2f2", "#dedede", "#999999")
        round_rect(x1, y1, x2, y2, 10, fill, stroke, 1, tag)
        text((x1 + x2) // 2, (y1 + y2) // 2, label, size, foreground, "semibold", "center", tags=tag)
        if not disabled:
            bind_click(tag, command)

    def draw_check(x: int, y: int, size: int, color: str, width: int = 3) -> None:
        canvas.create_line(
            x - int(size * 0.42), y, x - int(size * 0.12), y + int(size * 0.30),
            x + int(size * 0.48), y - int(size * 0.36), fill=color, width=width,
            capstyle="round", joinstyle="round",
        )

    def draw_chat_icon(x: int, y: int, size: int = 22) -> None:
        round_rect(x - size, y - int(size * 0.72), x + size, y + int(size * 0.60), 6, "#f4f4f4", "#333333", 2)
        canvas.create_line(x - 8, y + int(size * 0.58), x - 13, y + int(size * 0.92), x - 2, y + int(size * 0.58), fill="#333333", width=2)
        for dx in (-7, 0, 7):
            canvas.create_oval(x + dx - 1, y - 1, x + dx + 1, y + 1, fill="#333333", outline="#333333")

    def draw_header() -> None:
        canvas.create_rectangle(0, 0, PC_AGENT_WINDOW_WIDTH, 56, fill="#ffffff", outline="")
        line(0, 55, PC_AGENT_WINDOW_WIDTH, 55, "#e0e0e0")
        try:
            if Image is not None:
                original = Image.open(runtime_asset_path(AGENT_ICON_PNG)).convert("RGBA")
                resampling = getattr(getattr(Image, "Resampling", Image), "LANCZOS", Image.LANCZOS)
                original.thumbnail((24, 24), resampling)
                buffer = BytesIO()
                original.save(buffer, format="PNG")
                photo = tk.PhotoImage(data=base64.b64encode(buffer.getvalue()).decode("ascii"))
                image_refs.append(photo)
                canvas.create_image(28, 28, image=photo)
            else:
                canvas.create_rectangle(16, 16, 40, 40, fill="#111111", outline="")
        except Exception:
            canvas.create_rectangle(16, 16, 40, 40, fill="#111111", outline="")
        canvas.create_polygon(22, 21, 33, 21, 33, 32, 29, 32, 29, 26, 22, 26, fill="#ffffff", outline="")
        canvas.create_polygon(22, 26, 26, 30, 26, 35, 22, 31, fill="#ffffff", outline="")
        text(52, 28, "PC Agent", 16, colors["text"], "regular", "w")
        text(909, 28, "—", 18, colors["text"], "regular", "center", tags="window-min")
        canvas.create_rectangle(941, 21, 953, 35, fill="", outline="#111111", width=2, tags="window-max")
        canvas.create_line(974, 21, 986, 35, fill="#111111", width=2, tags="window-close")
        canvas.create_line(986, 21, 974, 35, fill="#111111", width=2, tags="window-close")
        bind_click("window-min", root.iconify)
        bind_click("window-close", close_window)
        canvas.tag_bind("window-max", "<Button-1>", lambda event: None)
        canvas.tag_bind("window-max", "<Enter>", lambda event: canvas.configure(cursor="hand2"))
        canvas.tag_bind("window-max", "<Leave>", lambda event: canvas.configure(cursor=""))

        connection_state = connection_state_provider() if callable(connection_state_provider) else "IDLE"
        connected = connection_state in {"IDLE", "REQUEST_RECEIVED", "RUNNING", "COMPLETED"}
        round_rect(778, 68, 866, 102, 8, "#ffffff", "#d8d8d8")
        canvas.create_oval(791, 81, 799, 89, fill=colors["green"] if connected else colors["subtle"], outline="")
        text(809, 85, "연결됨" if connected else "연결 끊김", 13, colors["text"], "regular", "w")
        tag = "demo-toggle"
        round_rect(878, 68, 970, 102, 8, "#ffffff", "#111111" if ui["demo"] else "#cfcfcf", 1, tag)
        text(924, 85, "시연 모드", 13, colors["text"], "regular", "center", tags=tag)
        bind_click(tag, toggle_demo)

    def draw_step(number: int, x: int, label: str, style: str) -> None:
        if style == "active" or style == "done-black":
            canvas.create_oval(x - 16, 120, x + 16, 152, fill="#050505", outline="#050505")
            text(x, 136, str(number), 13, "#ffffff", "regular", "center")
            label_color = colors["text"]
        elif style == "done-check":
            canvas.create_oval(x - 16, 120, x + 16, 152, fill="#ffffff", outline="#d3d8dc")
            draw_check(x, 136, 10, "#111111", 2)
            label_color = colors["muted"]
        else:
            canvas.create_oval(x - 16, 120, x + 16, 152, fill="#ffffff", outline="#d7dce0")
            text(x, 136, str(number), 13, colors["subtle"], "regular", "center")
            label_color = colors["muted"]
        text(x + 27, 136, label, 14, label_color, "regular", "w")

    def draw_stepper() -> None:
        state = str(ui["state"])
        if state == "SYMPTOM_CONFIRM":
            styles = ("active", "idle", "idle")
        elif state == "DIAGNOSING":
            styles = ("done-check", "active", "idle")
        elif state == "DIAGNOSIS_RESULT":
            styles = ("idle", "idle", "active")
        else:
            styles = ("done-black", "done-black", "done-black")
        draw_step(1, 145, PC_AGENT_DIAGNOSIS_STEPS[0], styles[0])
        line(252, 136, 402, 136, "#d9d9d9")
        draw_step(2, 432, PC_AGENT_DIAGNOSIS_STEPS[1], styles[1])
        line(570, 136, 710, 136, "#d9d9d9")
        draw_step(3, 740, PC_AGENT_DIAGNOSIS_STEPS[2], styles[2])

    def draw_sparkline(x: int, y: int, values: Sequence[float], emphasis: bool = False) -> None:
        for index, value in enumerate(values):
            shade = "#8e8e8e" if emphasis and 6 <= index <= 9 else "#e7e7e7"
            height = max(4, int(min(100.0, max(0.0, value)) * 0.38))
            canvas.create_rectangle(x + index * 10, y - height, x + index * 10 + 6, y, fill=shade, outline="")

    def draw_symptom() -> None:
        provider = demo_sensor_provider if ui["demo"] else system_sensor_provider
        screen_input = provider.load(config)
        active_session = ui["diagnosisSession"]
        if isinstance(active_session, DiagnosisSession):
            screen_input = replace(
                screen_input,
                symptom=active_session.request.symptom,
                symptom_type=None,
            )
        screen = build_symptom_screen_state(screen_input)
        for x, card in zip((70, 287, 504, 721), screen.widgets, strict=False):
            tone_color = colors[card.tone] if card.tone in {"warning", "danger"} else None
            outline = tone_color or (colors["blue"] if card.highlighted else "#d9d9d9")
            emphasis = bool(tone_color or card.highlighted)
            round_rect(x, 182, x + 209, 350, 12, "#ffffff", outline, 2 if emphasis else 1)
            text(x + 18, 199, card.title, 17, tone_color or colors["text"], "semibold")
            text(x + 18, 227, card.primary, 13, tone_color or colors["text"], "semibold")
            for detail_index, detail in enumerate(card.details[:2]):
                text(x + 18, 251 + detail_index * 18, detail, 10, colors["muted"])
            text(x + 18, 291, f"상태 {card.status}", 11, tone_color or colors["muted"], "semibold")
            draw_sparkline(x + 18, 336, card.history, emphasis=emphasis)

        round_rect(70, 370, 930, 530, 12, "#ffffff", "#dddddd")
        canvas.create_oval(90, 393, 132, 435, fill="#f4f4f4", outline="")
        draw_chat_icon(111, 414, 9)
        text(153, 390, "전달받은 증상", 17, colors["text"], "semibold")
        text(153, 423, f"“{screen.symptom}”", 15, colors["text"], width=735)
        text(153, 477, "웹 상담 정보를 바탕으로 점검 범위를 설정했습니다.", 13, colors["muted"])
        button(390, 555, 610, 608, "진단 시작", start_diagnosis, True, size=15)

    def draw_progress_ring(x: int, y: int) -> None:
        canvas.create_oval(x - 18, y - 18, x + 18, y + 18, outline="#e8e8e8", width=4)
        canvas.create_arc(x - 18, y - 18, x + 18, y + 18, start=90, extent=-238, style="arc", outline="#6f7478", width=4)

    def draw_diagnosing() -> None:
        round_rect(70, 180, 930, 650, 12, "#ffffff", "#d7dce0")
        canvas.create_oval(94, 207, 136, 249, fill="#ffffff", outline="#d7dce0")
        draw_chat_icon(115, 228, 9)
        text(153, 201, "전달받은 증상", 12, colors["muted"])
        text(153, 225, "게임을 실행하면 처음에는 괜찮은데, 조금 지나면 프레임이 심하게 끊깁니다.", 15, colors["text"], "regular")
        text(153, 252, "전달받은 증상을 바탕으로 진단 범위를 설정했습니다.", 11, colors["muted"])
        line(95, 278, 905, 278)
        text(500, 298, "진단 진행 중", 21, colors["text"], "semibold", "center")
        draw_progress_ring(462, 342)
        text(515, 342, "66%", 24, colors["text"], "regular", "center")
        detail = ui["status"] or "전달받은 증상을 바탕으로 전체 하드웨어 검사를 진행하고 있습니다."
        text(500, 376, str(detail), 12, colors["muted"], "regular", "center")
        line(95, 404, 905, 404)

        centers = (180, 385, 600, 805)
        labels = (("CPU", "검사 완료"), ("GPU", "집중 분석 중"), ("RAM", "대기"), ("디스크", "대기"))
        for index, (cx, values) in enumerate(zip(centers, labels, strict=False)):
            if index == 1:
                round_rect(cx - 76, 420, cx + 92, 456, 8, "#ffffff", colors["red"])
                canvas.create_oval(cx - 60, 432, cx - 50, 442, fill=colors["red"], outline="")
                draw_check(cx - 55, 437, 4, "#ffffff", 1)
                text(cx - 42, 438, values[0], 12, colors["text"], "semibold", "w")
                text(cx + 5, 438, values[1], 11, colors["text"], "regular", "w")
            else:
                icon_color = colors["green"] if index == 0 else "#92989d"
                canvas.create_oval(cx - 53, 432, cx - 43, 442, fill=icon_color if index == 0 else "#ffffff", outline=icon_color)
                if index == 0:
                    draw_check(cx - 48, 437, 4, "#ffffff", 1)
                else:
                    canvas.create_line(cx - 48, 434, cx - 48, 437, fill=icon_color)
                    canvas.create_line(cx - 48, 437, cx - 45, 440, fill=icon_color)
                text(cx - 36, 438, values[0], 12, colors["text"], "semibold", "w")
                text(cx + 12, 438, values[1], 11, colors["muted"], "regular", "w")
            if index < 3:
                line(cx + 100, 421, cx + 100, 455, "#e0e0e0")
        line(95, 474, 905, 474)

        text(95, 493, "검사 진행 내용", 13, colors["text"], "semibold")
        checklist = [
            ("전체 센서 수집 완료", "#9ba1a5"),
            ("온도 및 사용률 분석 중", colors["red"]),
            ("냉각 팬 회전 상태 확인 중", "#92989d"),
            ("열 제한 징후 확인 예정", "#92989d"),
        ]
        for idx, (label, dot) in enumerate(checklist):
            cy = 530 + idx * 25
            canvas.create_oval(100, cy - 6, 112, cy + 6, fill="#ffffff" if idx != 1 else dot, outline=dot)
            if idx == 0:
                draw_check(106, cy, 5, dot, 1)
            elif idx > 1:
                canvas.create_line(106, cy - 3, 106, cy, fill=dot)
                canvas.create_line(106, cy, 109, cy + 2, fill=dot)
            text(128, cy, label, 11, colors["text"], "regular", "w")

        text(500, 493, "실시간 진단 로그", 13, colors["text"], "semibold")
        round_rect(500, 520, 905, 620, 7, "#fbfbfb", "#dedede")
        logs = [
            ("10:32:41", "시스템 센서 데이터 수집을 완료했습니다.", colors["muted"]),
            ("10:32:44", "CPU 상태 검사를 완료했습니다.", colors["muted"]),
            ("10:32:45", "GPU 온도 및 사용률 분석을 시작했습니다.", colors["red"]),
        ]
        for idx, (stamp, message, color) in enumerate(logs):
            cy = 545 + idx * 26
            text(517, cy, stamp, 10, color, "regular", "w")
            text(580, cy, message, 10, color, "regular", "w")
        button(390, 665, 610, 715, "분석 계속", continue_analysis, True, disabled=bool(ui["busy"] and not ui["demo"]), size=15)

    def draw_result_icon(x: int, y: int) -> None:
        canvas.create_oval(x - 20, y - 20, x + 20, y + 20, fill="#f4f4f4", outline="")
        canvas.create_line(x - 6, y + 8, x - 6, y - 9, x + 6, y - 9, fill="#111111", width=2)
        canvas.create_line(x + 6, y - 9, x + 6, y + 2, fill="#111111", width=2)
        canvas.create_line(x - 6, y + 1, x + 2, y + 1, fill="#111111", width=2)

    def draw_result() -> None:
        round_rect(70, 180, 930, 670, 12, "#ffffff", "#d7dce0")
        draw_result_icon(112, 222)
        text(145, 214, "진단 결과", 17, colors["text"], "semibold")
        text(145, 258, "현재 냉각 시스템 또는 하드웨어의 물리적 이상 가능성이 높습니다.", 23, colors["text"], "semibold")
        text(145, 307, "GPU 온도 상승, 팬 회전 상태 비정상, 열 제한 징후가 함께 감지되었습니다.", 14, "#5f6368")
        text(145, 334, "현재 환경에서는 소프트웨어를 통한 자동 복구가 불가능합니다.", 14, "#5f6368")
        line(145, 371, 855, 371)
        text(145, 392, "핵심 결과", 16, colors["text"], "semibold")
        chips = [(145, 422, 315, "GPU 온도 상승", "temp"), (337, 422, 545, "팬 회전 상태 비정상", "fan"), (567, 422, 725, "열 제한 징후", "warn")]
        for x1, y1, x2, label, kind in chips:
            round_rect(x1, y1, x2, 458, 18, "#ffeaea", "")
            if kind == "temp":
                canvas.create_oval(x1 + 17, 435, x1 + 25, 451, fill="#ffffff", outline=colors["red"], width=2)
                canvas.create_line(x1 + 21, 429, x1 + 21, 443, fill=colors["red"], width=2)
            elif kind == "fan":
                for angle in (0, 120, 240):
                    canvas.create_arc(x1 + 14, 429, x1 + 33, 451, start=angle, extent=65, style="arc", outline=colors["red"], width=2)
                canvas.create_oval(x1 + 22, 438, x1 + 26, 442, fill=colors["red"], outline="")
            else:
                canvas.create_polygon(x1 + 22, 429, x1 + 13, 451, x1 + 31, 451, fill="#ffffff", outline=colors["red"], width=2)
                text(x1 + 22, 445, "!", 10, colors["red"], "semibold", "center")
            text(x1 + 42, 440, label, 13, colors["text"], "regular", "w")
        line(145, 477, 855, 477)
        text(145, 497, "권장 조치", 16, colors["text"], "semibold")
        actions = [(162, "GPU 냉각 팬 점검"), (420, "팬 전원 연결 상태 확인"), (690, "AS 기사 연결 권장")]
        for index, (x, label) in enumerate(actions, start=1):
            canvas.create_oval(x - 13, 523, x + 13, 549, fill="#fff0ef", outline="")
            text(x, 536, str(index), 11, colors["red"], "semibold", "center")
            text(x + 31, 536, label, 13, "#333333", "regular", "w")
            if index < 3:
                line(x + 213, 523, x + 213, 549)
        button(270, 585, 485, 635, "진단 상세", show_diagnosis_detail, False, size=15)
        button(515, 585, 730, 635, "AS 연결하기", connect_as, True, disabled=bool(ui["busy"]), size=15)
        if ui["status"]:
            text(500, 652, str(ui["status"]), 11, colors["red"], "regular", "center")

    def draw_success() -> None:
        round_rect(150, 205, 850, 650, 12, "#ffffff", "#d7dce0")
        canvas.create_oval(458, 268, 542, 352, fill=colors["green_soft"], outline="#b9efd3")
        draw_check(500, 310, 32, colors["green"], 5)
        text(500, 392, "AS 요청이 생성되었습니다", 28, colors["text"], "semibold", "center")
        text(500, 445, "요청 내용은 웹에서 확인할 수 있습니다.", 16, "#5f6368", "regular", "center")
        button(370, 506, 630, 560, "웹에서 확인하기", open_created_ticket, True, size=15)

    def render() -> None:
        canvas.delete("all")
        button_counter["value"] = 0
        draw_header()
        draw_stepper()
        if ui["state"] == "SYMPTOM_CONFIRM":
            draw_symptom()
        elif ui["state"] == "DIAGNOSING":
            draw_diagnosing()
        elif ui["state"] == "DIAGNOSIS_RESULT":
            draw_result()
        else:
            draw_success()

    def toggle_demo() -> None:
        if ui["busy"] or ui["requestLocked"]:
            return
        ui["demo"] = not bool(ui["demo"])
        ui["state"] = "SYMPTOM_CONFIRM"
        ui["diagnosisReady"] = False
        ui["diagnosis"] = None
        ui["window"] = None
        ui["ticketUrl"] = None
        ui["status"] = ""
        render()

    def start_diagnosis() -> None:
        ui["state"] = next_pc_agent_ui_state(str(ui["state"]))
        ui["status"] = ""
        render()
        if ui["demo"]:
            ui["diagnosisReady"] = True
            return
        ui["busy"] = True
        ui["status"] = "최근 30분 로그를 준비하고 있습니다."
        render()

        def worker() -> None:
            try:
                current_config = load_config(config_path)
                current_path = log_file(current_config)
                window = default_incident_window("REMOTE_DRIVER_OS", detected_at=datetime.now(KST), trigger_type="USER_REQUEST")
                gzip_path = current_config.log_dir.parent / "previews" / f"{window.incident_id}.jsonl.gz"
                gzip_window(current_path, gzip_path, window)
                result = preview_as_rag(current_config, gzip_path, f"agent-rag-preview-{uuid.uuid4()}", window)
                record = diagnosis_history_record(result, window)
                append_diagnosis_history(current_config, record)

                def apply() -> None:
                    ui["busy"] = False
                    ui["diagnosisReady"] = True
                    ui["diagnosis"] = result
                    ui["window"] = window
                    ui["status"] = "하드웨어 분석이 완료되었습니다."
                    render()

                root.after(0, apply)
            except Exception as exception:
                def fail(current: Exception = exception) -> None:
                    ui["busy"] = False
                    ui["status"] = event_panel_failure_message(current)
                    render()
                root.after(0, fail)

        threading.Thread(target=worker, daemon=True).start()

    def continue_analysis() -> None:
        if not ui["demo"] and not ui["diagnosisReady"]:
            ui["status"] = "진단 완료 후 결과를 확인할 수 있습니다."
            render()
            return
        ui["state"] = next_pc_agent_ui_state(str(ui["state"]))
        ui["status"] = ""
        render()

    def show_diagnosis_detail() -> None:
        detail = "GPU 온도 상승\n팬 회전 상태 비정상\n열 제한 징후\n\n권장 조치: GPU 냉각 팬과 전원 연결 상태를 점검하세요."
        if isinstance(ui["diagnosis"], dict):
            detail = format_as_rag_preview(ui["diagnosis"])
        panel = tk.Toplevel(root)
        panel.title("진단 상세")
        panel.geometry("560x360")
        panel.configure(background="#ffffff")
        apply_agent_window_icon(panel)
        tk.Label(panel, text="진단 상세", font=font(24, "semibold"), background="#ffffff", anchor="w").pack(fill="x", padx=28, pady=(28, 14))
        tk.Label(panel, text=detail, font=font(16), foreground=colors["muted"], background="#ffffff", justify="left", anchor="nw", wraplength=500).pack(fill="both", expand=True, padx=28)
        tk.Button(panel, text="닫기", command=panel.destroy, font=font(15), background="#111111", foreground="#ffffff", relief="flat", padx=30, pady=8).pack(pady=24)
        panel.transient(root)
        panel.grab_set()

    def connect_as() -> None:
        if ui["demo"]:
            ui["state"] = next_pc_agent_ui_state(str(ui["state"]))
            ui["ticketUrl"] = support_new_url(config)
            render()
            return
        if ui["busy"]:
            return
        ui["busy"] = True
        ui["status"] = "AS 요청을 생성하고 있습니다."
        render()

        def worker() -> None:
            try:
                current_config = load_config(config_path)
                current_path = log_file(current_config)
                window = ui["window"] if isinstance(ui["window"], IncidentWindow) else default_incident_window(
                    "REMOTE_DRIVER_OS", detected_at=datetime.now(KST), trigger_type="USER_REQUEST"
                )
                gzip_path = current_config.log_dir.parent / "uploads" / f"{window.incident_id}.jsonl.gz"
                gzip_window(current_path, gzip_path, window)
                result = upload_gzip(
                    current_config,
                    gzip_path,
                    f"agent-support-{uuid.uuid4()}",
                    "게임 실행 후 프레임 끊김 / GPU 냉각 시스템 점검 필요",
                    window,
                )
                ticket_id = str(result["ticketId"])
                url = support_url(current_config.api_base_url, ticket_id, current_config.web_base_url)

                def apply() -> None:
                    ui["busy"] = False
                    ui["ticketUrl"] = url
                    ui["state"] = next_pc_agent_ui_state(str(ui["state"]))
                    ui["status"] = ""
                    render()

                root.after(0, apply)
            except Exception as exception:
                def fail(current: Exception = exception) -> None:
                    ui["busy"] = False
                    ui["status"] = event_panel_failure_message(current)
                    render()
                root.after(0, fail)

        threading.Thread(target=worker, daemon=True).start()

    def open_created_ticket() -> None:
        url = str(ui["ticketUrl"] or support_new_url(config))
        webbrowser.open(url)

    def start_drag(event: tk.Event) -> None:
        drag_origin["x"] = event.x_root - root.winfo_x()
        drag_origin["y"] = event.y_root - root.winfo_y()

    def drag_window(event: tk.Event) -> None:
        root.geometry(f"+{event.x_root - drag_origin['x']}+{event.y_root - drag_origin['y']}")

    def focus_window() -> None:
        try:
            root.deiconify()
            root.state("normal")
            root.lift()
            root.attributes("-topmost", True)
            root.after(800, lambda: root.attributes("-topmost", False))
            root.focus_force()
        except tk.TclError:
            return

    def close_window() -> None:
        if background_mode:
            root.withdraw()
        else:
            root.destroy()

    def apply_diagnosis_session(session: DiagnosisSession | None) -> None:
        ui["diagnosisSession"] = session
        ui["requestLocked"] = isinstance(session, DiagnosisSession)
        if isinstance(session, DiagnosisSession):
            ui["demo"] = session.request.mode == "DEMO"
            ui["state"] = "SYMPTOM_CONFIRM"
            ui["diagnosisReady"] = False
            ui["diagnosis"] = None
            ui["window"] = None
            ui["ticketUrl"] = None
            ui["status"] = ""
        render()

    def request_focus() -> None:
        root.after(0, focus_window)

    def request_apply_session(session: DiagnosisSession | None) -> None:
        root.after(0, lambda: apply_diagnosis_session(session))

    def request_destroy() -> None:
        root.after(0, root.destroy)

    canvas.bind("<ButtonPress-1>", lambda event: start_drag(event) if event.y <= 68 else None)
    canvas.bind("<B1-Motion>", lambda event: drag_window(event) if event.y <= 68 else None)

    def refresh_symptom_metrics() -> None:
        if root.winfo_exists() and ui["state"] == "SYMPTOM_CONFIRM":
            render()
        if root.winfo_exists():
            root.after(5000, refresh_symptom_metrics)

    render()
    if callable(on_window_ready):
        on_window_ready(request_focus, request_apply_session, request_destroy)
    root.after(5000, refresh_symptom_metrics)
    try:
        root.mainloop()
    finally:
        if callable(on_window_closed):
            on_window_closed()
        viewer_lock.release()


def open_support_page(config_path: Path) -> None:
    config = load_config(config_path)
    webbrowser.open(support_new_url(config))


def upload_event_panel_request(
    config: AgentConfig,
    source: Path,
    signals: Sequence[dict[str, Any]],
    request_mode: str | None = None,
) -> tuple[str, str]:
    selected = event_panel_signals(signals)
    if not selected:
        raise UploadError("no eligible event signal was selected for upload.")
    primary = selected[0]
    symptom_type = str(primary.get("code", ""))
    detected_at = event_panel_detected_at(primary)
    window = default_incident_window(
        symptom_type,
        detected_at=detected_at,
        trigger_type="SYSTEM_DETECTED",
        selected_by_user=True,
    )
    work_dir = config.log_dir.parent / "uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    gzip_window(source, gzip_path, window)
    symptom = event_panel_symptom(selected)
    if request_mode:
        symptom = f"{request_mode} / {symptom}"
    result = upload_gzip(
        config,
        gzip_path,
        f"agent-panel-{uuid.uuid4()}",
        symptom,
        window,
    )
    ticket_id = str(result["ticketId"])
    return ticket_id, support_url(config.api_base_url, ticket_id, config.web_base_url)


def show_event_panel(config_path: Path, signals: Sequence[dict[str, Any]]) -> None:
    if tk is None or ttk is None:
        return
    config = load_config(config_path)
    source = log_file(config)
    model = event_panel_model(signals)
    if model is None:
        return

    panel = tk.Tk()
    panel.title("감지 신호")
    apply_agent_window_icon(panel)
    panel.configure(background="#f8fbfc")
    panel.resizable(False, False)
    panel.attributes("-topmost", True)
    width = 386
    height = 432
    screen_width = panel.winfo_screenwidth()
    screen_height = panel.winfo_screenheight()
    x = max(20, screen_width - width - 28)
    y = max(20, screen_height - height - 64)
    panel.geometry(f"{width}x{height}+{x}+{y}")

    status_text = tk.StringVar(value="선택한 구간의 로그를 함께 첨부해 접수합니다.")
    request_mode = tk.StringVar(value="원격 접수")
    colors = {
        "bg": "#f8fbfc",
        "card": "#ffffff",
        "line": "#dde7ea",
        "teal": "#0f7aae",
        "deep": "#17313b",
        "muted": "#5f737b",
        "soft": "#eef8f9",
        "button": "#0e7490",
        "button_hover": "#0b6178",
        "secondary": "#ffffff",
        "secondary_hover": "#f1f6f7",
    }
    ui_font_family = resolve_ui_font_family(panel)

    def ui_font(size_px: int, weight: str = "regular", underline: bool = False) -> tuple[Any, ...]:
        return tk_ui_font(ui_font_family, size_px, weight, underline)

    panel.option_add("*Font", ui_font(FONT_BODY_PX))
    container = tk.Frame(panel, background=colors["bg"], padx=12, pady=12)
    container.pack(fill="both", expand=True)
    card = tk.Frame(
        container,
        background=colors["card"],
        highlightthickness=1,
        highlightbackground=colors["line"],
        padx=14,
        pady=12,
    )
    card.pack(fill="both", expand=True)
    card.grid_columnconfigure(0, weight=1)
    card.grid_rowconfigure(0, weight=1)
    body = tk.Frame(card, background=colors["card"])
    body.grid(row=0, column=0, sticky="nsew")
    footer = tk.Frame(card, background=colors["card"])
    footer.grid(row=1, column=0, sticky="ew", pady=(10, 0))

    header = tk.Frame(body, background=colors["card"])
    header.pack(fill="x")
    icon = tk.Canvas(header, width=28, height=28, background=colors["card"], highlightthickness=0)
    icon.pack(side="left", padx=(0, 8))
    icon.create_oval(3, 3, 25, 25, fill=colors["soft"], outline="#b8e2e6")
    icon.create_text(14, 14, text="!", fill=colors["teal"], font=ui_font(FONT_BUTTON_PX, "semibold"))
    title_box = tk.Frame(header, background=colors["card"])
    title_box.pack(side="left", fill="x", expand=True)
    tk.Label(
        title_box,
        text="확인이 필요한 신호가 감지되었습니다",
        font=ui_font(FONT_SECTION_TITLE_PX, "semibold"),
        foreground=colors["deep"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x")
    tk.Label(
        title_box,
        text="로그에서 자세히 확인할 수 있습니다.",
        font=ui_font(FONT_SECONDARY_PX),
        foreground=colors["muted"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x", pady=(2, 0))
    close_label = tk.Label(
        header,
        text="×",
        font=ui_font(FONT_SECTION_TITLE_PX),
        foreground=colors["muted"],
        background=colors["card"],
        cursor="hand2",
    )
    close_label.pack(side="right", padx=(8, 0))

    meta = tk.Frame(body, background=colors["soft"], padx=10, pady=8)
    meta.pack(fill="x", pady=(12, 10))

    def add_meta_row(label: str, value: str) -> None:
        row = tk.Frame(meta, background=colors["soft"])
        row.pack(fill="x", pady=(0, 3))
        tk.Label(
            row,
            text=label,
            font=ui_font(FONT_SECONDARY_PX, "semibold"),
            foreground=colors["muted"],
            background=colors["soft"],
            anchor="w",
            width=8,
        ).pack(side="left")
        tk.Label(
            row,
            text=value,
            font=ui_font(FONT_SECONDARY_PX),
            foreground=colors["deep"],
            background=colors["soft"],
            anchor="w",
            justify="left",
            wraplength=250,
        ).pack(side="left", fill="x", expand=True)

    add_meta_row("발생 시간", str(model["detectedTime"]))
    add_meta_row("감지 신호", str(model["signalTitle"]))
    add_meta_row("전송 구간", str(model["windowText"]))

    tk.Label(
        body,
        text="PCAgent가 관련 경고 이벤트를 감지했습니다. 필요하면 자동으로 정리된 제목과 로그 구간으로 AS 접수를 진행할 수 있습니다.",
        font=ui_font(FONT_BODY_PX),
        foreground=colors["deep"],
        background=colors["card"],
        anchor="w",
        justify="left",
        wraplength=318,
    ).pack(fill="x")

    mode_row = tk.Frame(body, background=colors["card"])
    mode_row.pack(fill="x", pady=(8, 0))
    tk.Label(
        mode_row,
        text="신청 방식",
        font=ui_font(FONT_SECONDARY_PX, "semibold"),
        foreground=colors["muted"],
        background=colors["card"],
        anchor="w",
        width=8,
    ).pack(side="left")
    for label in ("원격 접수", "방문 접수"):
        tk.Radiobutton(
            mode_row,
            text=label,
            variable=request_mode,
            value=label,
            font=ui_font(FONT_SECONDARY_PX),
            foreground=colors["deep"],
            background=colors["card"],
            activebackground=colors["card"],
            selectcolor=colors["card"],
        ).pack(side="left", padx=(0, 10))

    tk.Label(
        body,
        textvariable=status_text,
        font=ui_font(FONT_SECONDARY_PX),
        foreground=colors["teal"],
        background=colors["card"],
        anchor="w",
        justify="left",
        wraplength=318,
    ).pack(fill="x", pady=(8, 8))

    def request_review() -> None:
        send_button.configure(state="disabled")
        status_text.set("AS 접수를 준비하고 있습니다.")
        panel.update_idletasks()
        try:
            ticket_id, url = upload_event_panel_request(config, source, signals, request_mode.get())
        except Exception as exception:
            send_button.configure(state="normal")
            status_text.set(event_panel_failure_message(exception))
            return
        status_text.set(f"AS 접수가 완료되었습니다. 티켓 {ticket_id}")
        webbrowser.open(url)

    def open_detail() -> None:
        primary = model["primarySignal"]
        panel.destroy()
        show_log_viewer(config_path, focus_signal=primary)

    def close_panel() -> None:
        panel.destroy()

    close_label.bind("<Button-1>", lambda event: close_panel())

    button_row = tk.Frame(footer, background=colors["card"])
    button_row.pack(fill="x")

    def make_button(parent: tk.Frame, text: str, command: Any, primary: bool = False) -> tk.Button:
        button = tk.Button(
            parent,
            text=text,
            command=command,
            font=ui_font(FONT_BUTTON_PX, "semibold" if primary else "regular"),
            foreground="#ffffff" if primary else colors["deep"],
            background=colors["button"] if primary else colors["secondary"],
            activebackground=colors["button_hover"] if primary else colors["secondary_hover"],
            activeforeground="#ffffff" if primary else colors["deep"],
            relief="flat",
            highlightthickness=1 if not primary else 0,
            highlightbackground=colors["line"],
            padx=10,
            pady=7,
            cursor="hand2",
        )
        return button

    send_button = make_button(button_row, "접수하기", request_review, True)
    send_button.pack(side="left", fill="x", expand=True, padx=(0, 8))
    make_button(button_row, "무시하기", close_panel).pack(side="left", fill="x", expand=True)

    link = tk.Label(
        footer,
        text="로그 확인하기 ↗",
        font=ui_font(FONT_SECONDARY_PX, underline=True),
        foreground=colors["teal"],
        background=colors["card"],
        cursor="hand2",
        anchor="e",
    )
    link.pack(fill="x", pady=(8, 0))
    link.bind("<Button-1>", lambda event: open_detail())

    panel.after(900, lambda: panel.attributes("-topmost", False))
    panel.mainloop()


def show_event_panel_async(config_path: Path, signals: Sequence[dict[str, Any]]) -> None:
    import threading

    selected = list(event_panel_signals(signals))
    if not selected:
        return
    threading.Thread(target=show_event_panel, args=(config_path, selected), daemon=True).start()


def maybe_show_event_panel(config_path: Path, config: AgentConfig, runtime: AgentRuntime) -> None:
    rows = read_log_tail(log_file(config), LOG_TABLE_LIMIT)
    signals = event_panel_signals(detect_recent_signals(rows))
    signature = event_panel_signature(signals)
    if signature is None or signature == runtime.last_event_panel_signature:
        return
    runtime.last_event_panel_signature = signature
    show_event_panel_async(config_path, signals)


def is_issue_metric(row: dict) -> bool:
    event_type = str(row.get("eventType", "")).strip()
    if not event_type:
        return False
    return event_type not in {"DEMO_METRIC", "SYSTEM_METRIC", "INFO", "OK"}


def should_show_issue_notification(row: dict, runtime: AgentRuntime, now: float | None = None) -> bool:
    if not is_issue_metric(row):
        return False
    observed = time.time() if now is None else now
    if observed - runtime.last_issue_notification_at < 60:
        return False
    runtime.last_issue_notification_at = observed
    return True


def issue_macro(row: dict) -> IssueDraftMacro:
    event_type = str(row.get("eventType") or row.get("kind") or "").strip()
    payload = row.get("payload") if isinstance(row.get("payload"), dict) else {}
    message = str(row.get("message") or payload.get("message") or "").strip()
    if event_type == "DISPLAY_DRIVER_WARNING":
        return IssueDraftMacro(
            symptom_type="REMOTE_DRIVER_OS",
            title="디스플레이 드라이버 경고가 감지되었습니다",
            detail="PCAgent가 디스플레이 드라이버 관련 경고 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.",
            symptom=f"PCAgent 자동 감지: {message or 'Display driver warning observed.'}",
            support_request_kind="REMOTE_REQUESTED",
        )
    return IssueDraftMacro(
        symptom_type="REMOTE_AGENT",
        title="PCAgent가 문제를 감지했습니다",
        detail="PCAgent가 문제 이벤트를 감지했습니다. 선택된 구간의 로그를 함께 전송합니다.",
        symptom=f"PCAgent 자동 감지: {message or event_type or 'Unknown issue'}",
        support_request_kind="DIAGNOSIS_ONLY",
    )


def issue_idempotency_key(row: dict) -> str:
    payload = json.dumps(
        {
            "timestamp": row.get("timestamp") or row.get("collectedAt"),
            "eventType": row.get("eventType") or row.get("kind"),
            "message": row.get("message"),
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return "agent-draft-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def issue_ticket_idempotency_key(row: dict, title: str, detail: str, support_request_kind: str) -> str:
    payload = json.dumps(
        {
            "timestamp": row.get("timestamp") or row.get("collectedAt"),
            "eventType": row.get("eventType") or row.get("kind"),
            "message": row.get("message"),
            "title": title,
            "detail": detail,
            "supportRequestKind": support_request_kind,
            "target": "ticket",
        },
        ensure_ascii=False,
        sort_keys=True,
    )
    return "agent-ticket-" + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]


def agent_launch_parts(command: str, config_path: Path, issue_file: Path) -> tuple[str, list[str]]:
    if getattr(sys, "frozen", False):
        return sys.executable, [command, "--config", str(config_path), "--issue-file", str(issue_file)]
    return sys.executable, [str(Path(__file__).resolve()), command, "--config", str(config_path), "--issue-file", str(issue_file)]


def powershell_array(values: Sequence[str]) -> str:
    return "@(" + ", ".join(powershell_string(value) for value in values) + ")"


def show_issue_notification(config_path: Path, row: dict) -> None:
    config = load_config(config_path)
    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(macro.symptom_type, detected_at=detected_at, trigger_type="AGENT_DETECTED")
    message = str(row.get("message") or row.get("eventType") or "알 수 없는 문제가 감지되었습니다.").strip()
    if not message:
        message = "알 수 없는 문제가 감지되었습니다."
    title = DISPLAY_APP_NAME
    body = f"컴퓨터에 문제가 감지되었습니다: {message}"
    support_url_text = support_new_url(config)
    issue_file = app_data_dir() / "pending-issues" / f"issue-{uuid.uuid4()}.json"
    issue_file.parent.mkdir(parents=True, exist_ok=True)
    issue_file.write_text(json.dumps(row, ensure_ascii=False, indent=2), encoding="utf-8")
    launch_file, launch_args = agent_launch_parts("submit-issue", config_path, issue_file)
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$appBg = [System.Drawing.ColorTranslator]::FromHtml("#f8fbfc")
$cardBg = [System.Drawing.ColorTranslator]::FromHtml("#ffffff")
$softBg = [System.Drawing.ColorTranslator]::FromHtml("#eef8f9")
$primaryBg = [System.Drawing.ColorTranslator]::FromHtml("#0e7490")
$borderColor = [System.Drawing.ColorTranslator]::FromHtml("#d7e0e3")
$textColor = [System.Drawing.ColorTranslator]::FromHtml("#17313b")
$mutedColor = [System.Drawing.ColorTranslator]::FromHtml("#5f737b")
$uiFontFamily = "Malgun Gothic"
$installedFonts = New-Object System.Drawing.Text.InstalledFontCollection
$installedFontNames = @($installedFonts.Families | ForEach-Object {{ $_.Name }})
foreach ($candidate in @("Segoe UI Variable", "Segoe UI", "Malgun Gothic")) {{
  if ($installedFontNames -contains $candidate) {{
    $uiFontFamily = $candidate
    break
  }}
}}

function New-UIFont {{
  param([float]$Px, [System.Drawing.FontStyle]$Style = [System.Drawing.FontStyle]::Regular)
  return New-Object System.Drawing.Font($uiFontFamily, ($Px * 72.0 / 96.0), $Style, [System.Drawing.GraphicsUnit]::Point)
}}

function Style-PopupButton {{
  param($Button, [bool]$Primary = $false)
  $Button.FlatStyle = [System.Windows.Forms.FlatStyle]::Flat
  $Button.FlatAppearance.BorderSize = 1
  $Button.FlatAppearance.BorderColor = $borderColor
  $Button.Cursor = [System.Windows.Forms.Cursors]::Hand
  $Button.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
  if ($Primary) {{
    $Button.BackColor = $primaryBg
    $Button.ForeColor = [System.Drawing.Color]::White
  }} else {{
    $Button.BackColor = [System.Drawing.Color]::White
    $Button.ForeColor = $textColor
  }}
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "감지 신호"
$form.Size = New-Object System.Drawing.Size(352, 430)
$form.FormBorderStyle = [System.Windows.Forms.FormBorderStyle]::FixedDialog
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.ShowInTaskbar = $true
$form.BackColor = $appBg
$form.Font = New-UIFont 14
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$form.StartPosition = [System.Windows.Forms.FormStartPosition]::Manual
$form.Location = New-Object System.Drawing.Point(20, ($screen.Bottom - $form.Height - 20))

$iconLabel = New-Object System.Windows.Forms.Label
$iconLabel.Text = "!"
$iconLabel.TextAlign = [System.Drawing.ContentAlignment]::MiddleCenter
$iconLabel.Font = New-UIFont 14 ([System.Drawing.FontStyle]::Bold)
$iconLabel.ForeColor = $primaryBg
$iconLabel.BackColor = [System.Drawing.Color]::White
$iconLabel.BorderStyle = "FixedSingle"
$iconLabel.Location = New-Object System.Drawing.Point(18, 26)
$iconLabel.Size = New-Object System.Drawing.Size(22, 22)
$form.Controls.Add($iconLabel)

$headline = New-Object System.Windows.Forms.Label
$headline.Text = "확인이 필요한 신호가 감지되었습니다"
$headline.Location = New-Object System.Drawing.Point(54, 20)
$headline.Size = New-Object System.Drawing.Size(270, 24)
$headline.Font = New-UIFont 16 ([System.Drawing.FontStyle]::Bold)
$headline.ForeColor = $textColor
$headline.BackColor = $appBg
$form.Controls.Add($headline)

$subhead = New-Object System.Windows.Forms.Label
$subhead.Text = "로그에서 자세히 확인할 수 있습니다."
$subhead.Location = New-Object System.Drawing.Point(56, 46)
$subhead.Size = New-Object System.Drawing.Size(250, 20)
$subhead.Font = New-UIFont 12
$subhead.ForeColor = $mutedColor
$subhead.BackColor = $appBg
$form.Controls.Add($subhead)

$infoPanel = New-Object System.Windows.Forms.Panel
$infoPanel.Location = New-Object System.Drawing.Point(16, 84)
$infoPanel.Size = New-Object System.Drawing.Size(312, 96)
$infoPanel.BackColor = $softBg
$form.Controls.Add($infoPanel)

$timeCaption = New-Object System.Windows.Forms.Label
$timeCaption.Text = "발생 시간"
$timeCaption.Location = New-Object System.Drawing.Point(12, 12)
$timeCaption.Size = New-Object System.Drawing.Size(58, 18)
$timeCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$timeCaption.ForeColor = $mutedColor
$timeCaption.BackColor = $softBg
$infoPanel.Controls.Add($timeCaption)

$timeLabel = New-Object System.Windows.Forms.Label
$timeLabel.Text = {powershell_string(detected_at.strftime("%Y-%m-%d %H:%M"))}
$timeLabel.Location = New-Object System.Drawing.Point(82, 12)
$timeLabel.Size = New-Object System.Drawing.Size(210, 18)
$timeLabel.ForeColor = $textColor
$timeLabel.BackColor = $softBg
$infoPanel.Controls.Add($timeLabel)

$signalCaption = New-Object System.Windows.Forms.Label
$signalCaption.Text = "감지 신호"
$signalCaption.Location = New-Object System.Drawing.Point(12, 38)
$signalCaption.Size = New-Object System.Drawing.Size(58, 18)
$signalCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$signalCaption.ForeColor = $mutedColor
$signalCaption.BackColor = $softBg
$infoPanel.Controls.Add($signalCaption)

$signalLabel = New-Object System.Windows.Forms.Label
$signalLabel.Text = {powershell_string(message)}
$signalLabel.Location = New-Object System.Drawing.Point(82, 38)
$signalLabel.Size = New-Object System.Drawing.Size(210, 34)
$signalLabel.ForeColor = $textColor
$signalLabel.BackColor = $softBg
$infoPanel.Controls.Add($signalLabel)

$windowCaption = New-Object System.Windows.Forms.Label
$windowCaption.Text = "전송 구간"
$windowCaption.Location = New-Object System.Drawing.Point(12, 72)
$windowCaption.Size = New-Object System.Drawing.Size(58, 18)
$windowCaption.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$windowCaption.ForeColor = $mutedColor
$windowCaption.BackColor = $softBg
$infoPanel.Controls.Add($windowCaption)

$windowLabel = New-Object System.Windows.Forms.Label
$windowLabel.Text = {powershell_string(window.started_at.strftime("%H:%M") + " ~ " + window.ended_at.strftime("%H:%M") + f" ({window.range_minutes()}분)")}
$windowLabel.Location = New-Object System.Drawing.Point(82, 72)
$windowLabel.Size = New-Object System.Drawing.Size(210, 18)
$windowLabel.ForeColor = $textColor
$windowLabel.BackColor = $softBg
$infoPanel.Controls.Add($windowLabel)

$bodyText = New-Object System.Windows.Forms.Label
$bodyText.Text = "PCAgent가 관련 경고 이벤트를 감지했습니다. 필요하면 자동으로 정리된 제목과 로그 구간으로 AS 접수를 진행할 수 있습니다."
$bodyText.Location = New-Object System.Drawing.Point(18, 196)
$bodyText.Size = New-Object System.Drawing.Size(310, 58)
$bodyText.ForeColor = $textColor
$bodyText.BackColor = $appBg
$form.Controls.Add($bodyText)

$kindLabel = New-Object System.Windows.Forms.Label
$kindLabel.Text = "신청 방식"
$kindLabel.Location = New-Object System.Drawing.Point(18, 266)
$kindLabel.Size = New-Object System.Drawing.Size(70, 22)
$kindLabel.Font = New-UIFont 12 ([System.Drawing.FontStyle]::Bold)
$kindLabel.ForeColor = $mutedColor
$kindLabel.BackColor = $appBg
$form.Controls.Add($kindLabel)

$remoteRadio = New-Object System.Windows.Forms.RadioButton
$remoteRadio.Text = "원격 접수"
$remoteRadio.Location = New-Object System.Drawing.Point(96, 264)
$remoteRadio.Size = New-Object System.Drawing.Size(82, 24)
$remoteRadio.Checked = $true
$remoteRadio.ForeColor = $textColor
$remoteRadio.BackColor = $appBg
$form.Controls.Add($remoteRadio)

$visitRadio = New-Object System.Windows.Forms.RadioButton
$visitRadio.Text = "방문 접수"
$visitRadio.Location = New-Object System.Drawing.Point(188, 264)
$visitRadio.Size = New-Object System.Drawing.Size(82, 24)
$visitRadio.ForeColor = $textColor
$visitRadio.BackColor = $appBg
$form.Controls.Add($visitRadio)

$notice = New-Object System.Windows.Forms.Label
$notice.Text = "선택한 구간의 로그를 함께 첨부해 접수합니다."
$notice.Location = New-Object System.Drawing.Point(18, 302)
$notice.Size = New-Object System.Drawing.Size(300, 22)
$notice.Font = New-UIFont 12
$notice.ForeColor = $primaryBg
$notice.BackColor = $appBg
$form.Controls.Add($notice)

$send = New-Object System.Windows.Forms.Button
$send.Text = "AS 접수하기"
$send.Location = New-Object System.Drawing.Point(18, 340)
$send.Size = New-Object System.Drawing.Size(156, 36)
Style-PopupButton $send $true
$send.Add_Click({{
  $send.Enabled = $false
  $send.Text = "접수 중"
  $ignore.Enabled = $false
  $requestKind = "REMOTE_REQUESTED"
  if ($visitRadio.Checked) {{ $requestKind = "VISIT_REQUESTED" }}
  $args = {powershell_array(launch_args)}
  $args += @("--title", {powershell_string(macro.title)}, "--detail", {powershell_string(macro.detail)}, "--support-request-kind", $requestKind, "--no-open")
  $script:submitStdout = New-Object System.Text.StringBuilder
  $script:submitStderr = New-Object System.Text.StringBuilder
  $script:submitProcess = New-Object System.Diagnostics.Process
  $script:submitProcess.StartInfo.FileName = {powershell_string(launch_file)}
  $escapedArgs = New-Object System.Collections.Generic.List[string]
  foreach ($arg in $args) {{
    $escaped = [string]$arg
    $escaped = $escaped.Replace('"', '\"')
    [void]$escapedArgs.Add('"' + $escaped + '"')
  }}
  $script:submitProcess.StartInfo.Arguments = [string]::Join(" ", $escapedArgs)
  $script:submitProcess.StartInfo.UseShellExecute = $false
  $script:submitProcess.StartInfo.RedirectStandardOutput = $true
  $script:submitProcess.StartInfo.RedirectStandardError = $true
  $script:submitProcess.Add_OutputDataReceived({{
    if ($_.Data) {{ [void]$script:submitStdout.AppendLine($_.Data) }}
  }})
  $script:submitProcess.Add_ErrorDataReceived({{
    if ($_.Data) {{ [void]$script:submitStderr.AppendLine($_.Data) }}
  }})
  try {{
    [void]$script:submitProcess.Start()
    $script:submitProcess.BeginOutputReadLine()
    $script:submitProcess.BeginErrorReadLine()
  }} catch {{
    [System.Windows.Forms.MessageBox]::Show("AS 접수 실행에 실패했습니다.`r`n`r`n" + $_.Exception.Message, "PCAgent") | Out-Null
    $send.Enabled = $true
    $send.Text = "접수하기"
    $ignore.Enabled = $true
    return
  }}
  $script:submitTimer = New-Object System.Windows.Forms.Timer
  $script:submitTimer.Interval = 500
  $script:submitTimer.Add_Tick({{
    if (-not $script:submitProcess.HasExited) {{
      return
    }}
    $script:submitTimer.Stop()
    $stdout = $script:submitStdout.ToString().Trim()
    $stderr = $script:submitStderr.ToString().Trim()
    if ($script:submitProcess.ExitCode -eq 0) {{
      [System.Windows.Forms.MessageBox]::Show("AS 접수가 완료되었습니다.`r`n`r`n" + $stdout, "PCAgent") | Out-Null
      $form.Close()
    }} else {{
      [System.Windows.Forms.MessageBox]::Show("AS 접수에 실패했습니다.`r`n`r`n" + $stderr, "PCAgent") | Out-Null
      $send.Enabled = $true
      $send.Text = "접수하기"
      $ignore.Enabled = $true
    }}
  }})
  $script:submitTimer.Start()
}})
$form.Controls.Add($send)

$ignore = New-Object System.Windows.Forms.Button
$ignore.Text = "무시하기"
$ignore.Location = New-Object System.Drawing.Point(220, 340)
$ignore.Size = New-Object System.Drawing.Size(84, 36)
Style-PopupButton $ignore
$ignore.Add_Click({{ $form.Close() }})
$form.Controls.Add($ignore)

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 120000
$timer.Add_Tick({{ $timer.Stop(); $form.Close() }})
$timer.Start()

[void]$form.ShowDialog()
"""
    notification_path = app_data_dir() / "issue-notification.ps1"
    notification_path.parent.mkdir(parents=True, exist_ok=True)
    notification_path.write_text(script.strip() + "\n", encoding="utf-8")
    try:
        subprocess.Popen(
            [
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(notification_path),
            ],
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except Exception:
        webbrowser.open(support_url_text)


def collect_background_loop(
    config_path: Path,
    runtime: AgentRuntime,
    interval_seconds: int,
    collector: Any = None,
) -> None:
    metric_collector = collector if collector is not None else HardwareMetricCollector()
    while runtime.running:
        try:
            config = load_config(config_path)
            _, row = append_metric_with_row(config, runtime.index, metric_collector)
            if should_show_issue_notification(row, runtime):
                show_issue_notification(config_path, row)
            runtime.index += 1
            maybe_show_event_panel(config_path, config, runtime)
        except Exception as exception:
            error_log = app_data_dir() / "agent-error.log"
            error_log.parent.mkdir(parents=True, exist_ok=True)
            with error_log.open("a", encoding="utf-8") as file:
                file.write(f"{datetime.now(KST).isoformat()} {exception}\n")
        for _ in range(interval_seconds):
            if not runtime.running:
                break
            time.sleep(1)


def run_background(
    config_path: Path | None = None,
    interval_seconds: int = 5,
    with_tray: bool = True,
    open_viewer_when_running: bool = False,
) -> int:
    instance_lock = acquire_named_instance_lock(BACKGROUND_INSTANCE_MUTEX_NAME)
    if instance_lock is None:
        if open_viewer_when_running:
            ViewerRequestSignal(
                app_data_dir() / "show-viewer-request.json",
                restrict_file_to_current_user,
            ).signal()
        return 0
    try:
        path = ensure_default_config(config_path or default_background_config_path())
        try:
            imported_activation = import_activation_config(path)
            auto_register_agent(path)
            if imported_activation:
                cleanup_activation_config_files()
        except Exception as exception:
            error_log = app_data_dir() / "agent-error.log"
            error_log.parent.mkdir(parents=True, exist_ok=True)
            with error_log.open("a", encoding="utf-8") as file:
                file.write(f"{datetime.now(KST).isoformat()} auto-register failed: {exception}\n")
        register_startup()
        hide_console_window()
        write_pid()
        runtime = AgentRuntime()

        connection_state = {"value": "DISCONNECTED"}
        diagnosis_store = DiagnosisSessionStore(app_data_dir() / "diagnosis-request-state.json")
        viewer_controller = BackgroundViewerController(
            path,
            lambda: diagnosis_store.session,
            lambda: connection_state["value"],
            show_log_viewer,
        )

        def on_connection_state_changed(state: str) -> None:
            connection_state["value"] = state

        def on_initial_metrics_requested(session: DiagnosisSession) -> None:
            # 다음 단계가 실제 초기 메트릭 수집기를 이 경계에 연결한다.
            return

        try:
            current_config = load_config(path)
        except ConfigError:
            current_config = None
        diagnosis_processor = DiagnosisRequestProcessor(
            diagnosis_store,
            device_id=current_config.device_id if current_config else None,
            on_request=viewer_controller.show,
            on_initial_metrics_requested=on_initial_metrics_requested,
        )
        diagnosis_client = None
        if current_config and current_config.agent_token:
            diagnosis_client = AgentDiagnosisWebSocketClient(
                current_config.api_base_url,
                current_config.agent_token,
                diagnosis_processor,
                on_state_changed=on_connection_state_changed,
            )
            diagnosis_client.start()
        else:
            on_connection_state_changed("FAILED")

        import threading

        worker = threading.Thread(target=collect_background_loop, args=(path, runtime, interval_seconds), daemon=True)
        worker.start()
        viewer_signal = ViewerRequestSignal(
            app_data_dir() / "show-viewer-request.json",
            restrict_file_to_current_user,
        )
        viewer_signal_worker = threading.Thread(
            target=viewer_signal.monitor,
            args=(runtime, viewer_controller),
            daemon=True,
        )
        viewer_signal_worker.start()

        if with_tray and pystray is not None:
            def stop(icon: object, item: object = None) -> None:
                runtime.stop()
                if diagnosis_client is not None:
                    diagnosis_client.stop()
                viewer_controller.shutdown()
                remove_pid()
                icon.stop()

            icon = pystray.Icon(
                APP_NAME,
                create_tray_image(),
                DISPLAY_APP_NAME,
                menu=pystray.Menu(
                    pystray.MenuItem("PC Agent 열기", lambda icon, item: viewer_controller.show(), default=True),
                    pystray.MenuItem("로그 폴더 열기", lambda icon, item: open_log_folder(path)),
                    pystray.MenuItem("AS 페이지 열기", lambda icon, item: open_support_page(path)),
                    pystray.MenuItem("Agent 종료", stop),
                ),
            )
            icon.run()
        else:
            try:
                while runtime.running:
                    time.sleep(1)
            except KeyboardInterrupt:
                runtime.stop()

        runtime.stop()
        if diagnosis_client is not None:
            diagnosis_client.stop()
        viewer_controller.shutdown()
        remove_pid()
        return 0
    finally:
        instance_lock.release()


def upload_recent(
    config: AgentConfig,
    work_dir: Path,
    symptom: str | None,
    idempotency_key: str | None,
    open_browser: bool,
    incident_window: IncidentWindow | None = None,
) -> None:
    source = log_file(config)
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    key = idempotency_key or f"agent-upload-{uuid.uuid4()}"
    window = incident_window or default_incident_window("REMOTE_AGENT")
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(source, gzip_path, window)
    print(f"created gzip: {gzip_path} ({size} bytes)")
    print(f"upload path: {LOG_UPLOAD_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"symptomType: {window.symptom_type}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"rangeMinutes: {window.range_minutes()}")
    print(f"Idempotency-Key: {key}")
    print("Replay the same command with this Idempotency-Key to verify duplicate ticket prevention.")
    result = upload_gzip(config, gzip_path, key, symptom, window)
    ticket_id = str(result["ticketId"])
    url = support_url(config.api_base_url, ticket_id, config.web_base_url)
    print(f"ticketId: {ticket_id}")
    print(f"supportUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support ticket in default browser")


def open_issue_draft(config_path: Path, issue_file: Path, open_browser: bool = True) -> dict:
    config = load_config(config_path)
    try:
        row = json.loads(issue_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise AgentError(f"issue file cannot be read: {issue_file}") from exception
    if not isinstance(row, dict):
        raise AgentError("issue file root must be a JSON object.")

    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(
        macro.symptom_type,
        detected_at=detected_at,
        trigger_type="AGENT_DETECTED",
        incident_id=f"incident-{uuid.uuid4()}",
        selected_by_user=False,
        consent_id=f"agent-consent-{uuid.uuid4()}",
    )
    work_dir = app_data_dir() / "draft-uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(log_file(config), gzip_path, window)
    key = issue_idempotency_key(row)
    print(f"created draft gzip: {gzip_path} ({size} bytes)")
    print(f"draft upload path: {AS_DRAFT_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"detectedAt: {window.detected_at.isoformat()}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"Idempotency-Key: {key}")
    result = create_as_draft(config, gzip_path, key, macro, window)
    draft_id = str(result["draftId"])
    url = support_draft_url(config, draft_id)
    print(f"draftId: {draft_id}")
    print(f"supportDraftUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support draft in default browser")
    return result


def submit_issue_ticket(
    config_path: Path,
    issue_file: Path,
    title: str | None = None,
    detail: str | None = None,
    support_request_kind: str | None = None,
    open_browser: bool = False,
) -> dict:
    config = load_config(config_path)
    try:
        row = json.loads(issue_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise AgentError(f"issue file cannot be read: {issue_file}") from exception
    if not isinstance(row, dict):
        raise AgentError("issue file root must be a JSON object.")

    macro = issue_macro(row)
    detected_at = parse_log_timestamp(row) or datetime.now(KST)
    window = default_incident_window(
        macro.symptom_type,
        detected_at=detected_at,
        trigger_type="AGENT_CONFIRMED",
        incident_id=f"incident-{uuid.uuid4()}",
        selected_by_user=True,
        consent_id=f"agent-consent-{uuid.uuid4()}",
    )
    resolved_title = (title or macro.title).strip() or macro.title
    resolved_detail = (detail or macro.detail).strip() or macro.detail
    resolved_kind = (support_request_kind or macro.support_request_kind).strip() or macro.support_request_kind
    symptom = "\n".join(
        [
            f"[증상 제목] {resolved_title}",
            f"[증상 상세] {resolved_detail}",
            f"[감지 이벤트] {macro.symptom}",
            f"[신청 방식] {resolved_kind}",
            f"[발생 시각] {window.detected_at.isoformat()}",
            f"[선택 구간] {window.started_at.isoformat()} ~ {window.ended_at.isoformat()}",
        ]
    )
    work_dir = app_data_dir() / "ticket-uploads"
    gzip_path = work_dir / f"{window.incident_id}.jsonl.gz"
    size = gzip_window(log_file(config), gzip_path, window)
    key = issue_ticket_idempotency_key(row, resolved_title, resolved_detail, resolved_kind)
    print(f"created gzip: {gzip_path} ({size} bytes)")
    print(f"upload path: {LOG_UPLOAD_PATH}")
    print(f"incidentId: {window.incident_id}")
    print(f"detectedAt: {window.detected_at.isoformat()}")
    print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
    print(f"Idempotency-Key: {key}")
    result = upload_gzip(config, gzip_path, key, symptom, window)
    ticket_id = str(result["ticketId"])
    url = support_url(config.api_base_url, ticket_id, config.web_base_url)
    print(f"ticketId: {ticket_id}")
    print(f"supportUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support ticket in default browser")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        try:
            return run_background(open_viewer_when_running=True)
        except AgentError as exception:
            show_agent_error_dialog("PCAgent 실행 실패", str(exception))
            return 4

    parser = argparse.ArgumentParser(description="PCAgent prototype CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    sample = sub.add_parser("sample", help="generate sample JSONL hardware metrics")
    sample.add_argument("--out", type=Path, default=Path("sample-agent-log.jsonl"))
    sample.add_argument("--count", type=int, default=24)
    sample.add_argument("--interval-seconds", type=int, default=5)

    export = sub.add_parser("export", help="export recent JSONL rows")
    export.add_argument("--source", type=Path, required=True)
    export.add_argument("--out", type=Path, default=Path("incident-window.jsonl"))
    export.add_argument("--minutes", type=int, default=30)
    export.add_argument("--symptom-type", default=None)
    export.add_argument("--detected-at", default=None)
    export.add_argument("--started-at", default=None)
    export.add_argument("--ended-at", default=None)
    export.add_argument("--trigger-type", default="USER_REQUEST")
    export.add_argument("--incident-id", default=None)
    export.add_argument("--consent-id", default=None)

    status = sub.add_parser("status", help="read config and print registration state")
    status.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    doctor = sub.add_parser("doctor", help="validate config without registering or uploading")
    doctor.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    register = sub.add_parser("register", help="register this device and save the returned agent token")
    register.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    collect = sub.add_parser("collect", help="append hardware metrics every 5 seconds")
    collect.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    collect.add_argument("--iterations", type=int, default=1, help="number of hardware metric rows to append; use 0 for forever")
    collect.add_argument("--interval-seconds", type=int, default=5)

    upload = sub.add_parser("upload", help="gzip selected incident-window JSONL rows and upload to Agent AS API")
    upload.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    upload.add_argument("--work-dir", type=Path, default=Path("out"))
    upload.add_argument("--symptom", default=None)
    upload.add_argument("--symptom-type", default="REMOTE_AGENT")
    upload.add_argument("--detected-at", default=None)
    upload.add_argument("--started-at", default=None)
    upload.add_argument("--ended-at", default=None)
    upload.add_argument("--trigger-type", default="USER_REQUEST")
    upload.add_argument("--incident-id", default=None)
    upload.add_argument("--consent-id", default=None)
    upload.add_argument("--system-detected", action="store_true")
    upload.add_argument("--idempotency-key", default=None)
    upload.add_argument("--no-open", action="store_true", help="do not open /support/{ticketId} in the default browser")

    background = sub.add_parser("run-background", help="run as a startup-friendly background tray agent")
    background.add_argument("--config", type=Path, default=None)
    background.add_argument("--interval-seconds", type=int, default=5)
    background.add_argument("--no-tray", action="store_true")

    viewer = sub.add_parser("viewer", help="open the Tkinter status home and log viewer")
    viewer.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)

    open_issue = sub.add_parser("open-issue", help="upload detected issue logs as an AS draft and open the support form")
    open_issue.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    open_issue.add_argument("--issue-file", type=Path, required=True)
    open_issue.add_argument("--no-open", action="store_true")

    submit_issue = sub.add_parser("submit-issue", help="submit detected issue logs as an AS ticket after user confirmation")
    submit_issue.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    submit_issue.add_argument("--issue-file", type=Path, required=True)
    submit_issue.add_argument("--title", default=None)
    submit_issue.add_argument("--detail", default=None)
    submit_issue.add_argument("--support-request-kind", default=None)
    submit_issue.add_argument("--no-open", action="store_true")

    args = parser.parse_args(argv)

    try:
        if args.command == "sample":
            write_sample(args.out, args.count, args.interval_seconds)
            print(f"wrote {args.out}")
        elif args.command == "export":
            if args.symptom_type:
                window = build_incident_window(
                    args.symptom_type,
                    args.detected_at,
                    args.started_at,
                    args.ended_at,
                    args.trigger_type,
                    args.incident_id,
                    True,
                    args.consent_id,
                )
                export_window(args.source, args.out, window)
                print(f"incidentId: {window.incident_id}")
                print(f"window: {window.started_at.isoformat()} -> {window.ended_at.isoformat()}")
            else:
                export_recent(args.source, args.out, args.minutes)
            print(f"exported {args.out}")
        elif args.command == "status":
            print_status(args.config)
        elif args.command == "doctor":
            print_doctor(args.config)
        elif args.command == "register":
            register_agent(args.config)
        elif args.command == "collect":
            config = load_config(args.config)
            iterations = None if args.iterations == 0 else args.iterations
            collect_metrics(config, iterations, args.interval_seconds)
        elif args.command == "upload":
            config = load_config(args.config)
            window = build_incident_window(
                args.symptom_type,
                args.detected_at,
                args.started_at,
                args.ended_at,
                args.trigger_type,
                args.incident_id,
                not args.system_detected,
                args.consent_id,
            )
            upload_recent(config, args.work_dir, args.symptom, args.idempotency_key, not args.no_open, window)
        elif args.command == "run-background":
            return run_background(args.config, args.interval_seconds, not args.no_tray)
        elif args.command == "viewer":
            show_log_viewer(args.config)
        elif args.command == "open-issue":
            open_issue_draft(args.config, args.issue_file, not args.no_open)
        elif args.command == "submit-issue":
            submit_issue_ticket(
                args.config,
                args.issue_file,
                title=args.title,
                detail=args.detail,
                support_request_kind=args.support_request_kind,
                open_browser=not args.no_open,
            )
    except ConfigError as exception:
        print(f"config error: {exception}", file=sys.stderr)
        return 2
    except RegisterError as exception:
        print(f"register error: {exception}", file=sys.stderr)
        return 3
    except AgentError as exception:
        print(f"agent error: {exception}", file=sys.stderr)
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
