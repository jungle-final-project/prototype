from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import json
import mimetypes
import os
import platform
import random
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import webbrowser
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from io import BytesIO, TextIOWrapper
from pathlib import Path
from typing import Any, Sequence

try:
    import tkinter as tk
    from tkinter import ttk
except Exception:  # pragma: no cover - optional in minimal packaged runtimes
    tk = None
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
LOG_UPLOAD_PATH = "/api/agent/log-uploads"
REGISTERED_STATUS = "REGISTERED"
UNREGISTERED_STATUS = "UNREGISTERED"
APP_NAME = "BuildGraphAgent"
DEFAULT_AGENT_VERSION = "0.1.0"
DEFAULT_POLICY_VERSION = "policy-v1"
STATUS_HOME_SIGNAL_LIMIT = 3
LOG_TABLE_LIMIT = 500
EVENT_PANEL_SIGNAL_LIMIT = 3
STATUS_LOG_SUMMARY_LIMIT = 6
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


class AgentRuntime:
    def __init__(self) -> None:
        self.running = True
        self.index = 0
        self.last_event_panel_signature: str | None = None

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
        )

    def registration_status(self) -> str:
        if self.agent_token:
            return REGISTERED_STATUS
        return UNREGISTERED_STATUS


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


def app_data_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA")
    if root:
        return Path(root) / APP_NAME
    return Path.home() / f".{APP_NAME.lower()}"


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


def log_file(config: AgentConfig) -> Path:
    return config.log_dir / DEFAULT_LOG_FILE


def print_status(config_path: Path) -> None:
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
    print(REGISTERED_STATUS)
    print(f"agentToken: saved to {config_path}")


def metric_snapshot(ts: datetime, index: int) -> dict:
    if psutil:
        cpu_usage = psutil.cpu_percent(interval=0.05)
        memory_usage = psutil.virtual_memory().percent
        disk_usage = psutil.disk_usage("/").percent
    else:
        cpu_usage = 38 + index * 3 + random.random() * 8
        memory_usage = 62 + index * 2 + random.random() * 6
        disk_usage = 49 + random.random()

    event_type = "DISPLAY_DRIVER_WARNING" if index % 7 == 0 else "DEMO_METRIC"
    message = "Display driver warning observed." if event_type != "DEMO_METRIC" else "Demo metric collected."
    return {
        "timestamp": ts.isoformat(),
        "cpuUsage": round(cpu_usage, 1),
        "memoryUsage": round(memory_usage, 1),
        "ramUsage": round(memory_usage, 1),
        "eventType": event_type,
        "message": message,
        "gpuUsage": round(min(98, 64 + index * 4 + random.random() * 8), 1),
        "vramUsage": round(min(95, 58 + index * 3 + random.random() * 5), 1),
        "gpuTemp": round(min(91, 70 + index * 1.8 + random.random() * 3), 1),
        "cpuTemp": round(min(86, 62 + index * 1.2 + random.random() * 2), 1),
        "diskUsage": round(disk_usage, 1),
        "osErrorEvent": None if event_type == "DEMO_METRIC" else "Display driver warning",
        "topCpuProcess": "game.exe" if index % 2 else "ide64.exe",
        "topRamProcess": "game.exe",
    }


def metric_log_row(ts: datetime, index: int, schema_version: int, agent_id: str) -> dict:
    payload = metric_snapshot(ts, index)
    return {
        **payload,
        "schemaVersion": schema_version,
        "collectedAt": ts.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
        "agentId": agent_id,
        "sequence": index,
        "kind": payload.get("eventType") or "DEMO_METRIC",
        "payload": payload,
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
            row = metric_log_row(
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


def append_metric(config: AgentConfig, index: int = 0) -> Path:
    path = log_file(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = metric_log_row(
        datetime.now(KST),
        index,
        config.schema_version,
        config.device_fingerprint_hash,
    )
    row["agentVersion"] = config.agent_version
    row["policyVersion"] = config.policy_version
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
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


def collect_metrics(config: AgentConfig, iterations: int | None, interval_seconds: int) -> None:
    index = 0
    while iterations is None or index < iterations:
        path = append_metric(config, index)
        print(f"appended demo metric to {path}")
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


def executable_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" run-background'
    script = Path(__file__).resolve()
    return f'"{sys.executable}" "{script}" run-background'


def register_startup() -> Path:
    directory = startup_dir()
    directory.mkdir(parents=True, exist_ok=True)
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


def create_tray_image() -> object | None:
    if Image is None or ImageDraw is None:
        return None
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
    return rows[-limit:]


def format_percent(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}%"
    return "-"


def format_temperature(value: Any) -> str:
    if isinstance(value, int | float):
        return f"{value:.1f}C"
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
        "AGENT_HEALTH": "Agent 상태",
    }
    if kind != "-":
        return labels.get(kind.upper(), sanitize_display_text(kind, 80))
    return sanitize_display_text(display_log_message(row), 80)


def display_log_table_values(row: dict[str, Any]) -> tuple[str, ...]:
    return (
        format_log_time(row),
        display_log_kind(row),
        format_percent(log_value(row, "cpuUsage", "cpuUsagePercent")),
        format_percent(log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")),
        format_percent(log_value(row, "diskUsage", "diskUsedPercent")),
        format_percent(log_value(row, "gpuUsage", "gpuUsagePercent")),
        format_percent(log_value(row, "vramUsage", "vramUsagePercent")),
        format_temperature(log_value(row, "cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius")),
        format_temperature(log_value(row, "gpuTemp", "gpuTempCelsius", "gpuTemperatureCelsius")),
        display_log_message(row),
    )


def display_log_summary_values(row: dict[str, Any]) -> tuple[str, ...]:
    return (
        format_log_time(row),
        format_percent(log_value(row, "cpuUsage", "cpuUsagePercent")),
        format_percent(log_value(row, "memoryUsage", "ramUsage", "memoryUsedPercent")),
        format_percent(log_value(row, "diskUsage", "diskUsedPercent")),
        format_percent(log_value(row, "gpuUsage", "gpuUsagePercent")),
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
        return "PC Agent가 확인이 필요한 이벤트를 감지했습니다."
    return " / ".join(summaries[:EVENT_PANEL_SIGNAL_LIMIT])


def event_panel_failure_message(exception: Exception) -> str:
    text = str(exception).lower()
    if "agenttoken is missing" in text or "http 401" in text or "http 403" in text:
        return "Agent 등록 상태를 확인해야 전송할 수 있습니다."
    if "consent" in text or "consentaccepted" in text:
        return "서버 업로드 동의가 필요해 전송할 수 없습니다."
    if "no log rows" in text or "log file does not exist" in text:
        return "전송할 이벤트 로그가 아직 없습니다. 잠시 후 다시 시도해 주세요."
    if "timed out" in text or "connection" in text or "refused" in text or "unreachable" in text:
        return "서버 연결을 확인할 수 없어 전송하지 못했습니다."
    return "전송하지 못했습니다. 등록, 동의, 서버 연결 상태를 확인해 주세요."


def latest_upload_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "upload failed" in text or "upload_failed" in text:
            return f"실패 {format_log_time(row)}"
        if "upload succeeded" in text or "upload success" in text or "upload_succeeded" in text:
            return f"성공 {format_log_time(row)}"
    return "기록 없음"


def latest_server_status(rows: Sequence[dict[str, Any]]) -> str:
    for row in reversed(list(rows)):
        text = signal_search_text(row)
        if "heartbeat failed" in text or "heartbeat missing" in text:
            return f"실패 {format_log_time(row)}"
        if "heartbeat succeeded" in text or "heartbeat success" in text:
            return f"연결 기록 {format_log_time(row)}"
    return "확인 전"


def status_home_model(config: AgentConfig, path: Path) -> dict[str, Any]:
    rows = read_log_tail(path, LOG_TABLE_LIMIT)
    return {
        "agentStatus": "정상 실행 중" if config.agent_token else "등록 필요",
        "serverStatus": latest_server_status(rows),
        "lastUpload": latest_upload_status(rows),
        "version": config.agent_version,
        "policyVersion": config.policy_version,
        "signals": detect_recent_signals(rows),
    }


def powershell_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


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
$agentStatus = {powershell_string(model["agentStatus"])}
$serverStatus = {powershell_string(model["serverStatus"])}
$lastUpload = {powershell_string(model["lastUpload"])}
$versionText = {powershell_string(str(model["version"]))}
$policyText = {powershell_string(str(model["policyVersion"]))}
$signalsText = {powershell_string(signals_text)}

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
  Get-Content -LiteralPath $logPath | ForEach-Object {{
    try {{
      $row = $_ | ConvertFrom-Json
      $observedAt = Get-ObservedAt $row
      if ($observedAt -ge $start -and $observedAt -lt $end) {{
        $timeText = $observedAt.ToString("HH:mm:ss")
        $kind = Hide-SensitiveText (Get-RowValue $row @("kind", "eventType"))
        $cpu = Format-Percent (Get-RowValue $row @("cpuUsage", "cpuUsagePercent"))
        $memory = Format-Percent (Get-RowValue $row @("memoryUsage", "ramUsage", "memoryUsedPercent"))
        $disk = Format-Percent (Get-RowValue $row @("diskUsage", "diskUsedPercent"))
        $gpu = Format-Percent (Get-RowValue $row @("gpuUsage", "gpuUsagePercent"))
        $cpuTemp = Format-Temp (Get-RowValue $row @("cpuTemp", "cpuTempCelsius", "cpuTemperatureCelsius"))
        $message = Hide-SensitiveText (Get-RowValue $row @("message", "osErrorEvent", "status", "summary"))
        $output.Add(("{0,-8} {1,-24} CPU {2,7} MEM {3,7} DISK {4,7} GPU {5,7} CPU-T {6,7}  {7}" -f $timeText, $kind, $cpu, $memory, $disk, $gpu, $cpuTemp, $message))
      }}
    }} catch {{}}
  }}
  if ($output.Count -eq 0) {{
    return "선택한 1시간 구간에 표시할 로그가 없습니다."
  }}
  if ($output.Count -gt 500) {{
    $output = $output.GetRange($output.Count - 500, 500)
  }}
  return ($output -join "`r`n")
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "PC Agent"
$form.Size = New-Object System.Drawing.Size(980, 640)
$form.StartPosition = "CenterScreen"

$sidebar = New-Object System.Windows.Forms.Panel
$sidebar.Location = New-Object System.Drawing.Point(0, 0)
$sidebar.Size = New-Object System.Drawing.Size(118, 640)
$sidebar.BackColor = [System.Drawing.Color]::FromArgb(239, 250, 248)
$form.Controls.Add($sidebar)

$brand = New-Object System.Windows.Forms.Label
$brand.Text = ""
$brand.Font = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Bold)
$brand.Location = New-Object System.Drawing.Point(14, 18)
$brand.Size = New-Object System.Drawing.Size(92, 48)
$sidebar.Controls.Add($brand)

$navStatus = New-Object System.Windows.Forms.Button
$navStatus.Text = "● 상태"
$navStatus.Location = New-Object System.Drawing.Point(14, 82)
$navStatus.Size = New-Object System.Drawing.Size(90, 32)
$sidebar.Controls.Add($navStatus)

$navLog = New-Object System.Windows.Forms.Button
$navLog.Text = "≡ 로그"
$navLog.Location = New-Object System.Drawing.Point(14, 124)
$navLog.Size = New-Object System.Drawing.Size(90, 32)
$navLog.Add_Click({{ $textbox.Focus() }})
$sidebar.Controls.Add($navLog)

$navSupport = New-Object System.Windows.Forms.Button
$navSupport.Text = "+ AS 접수"
$navSupport.Location = New-Object System.Drawing.Point(14, 166)
$navSupport.Size = New-Object System.Drawing.Size(90, 32)
$navSupport.Add_Click({{ Start-Process -FilePath $supportUrl }})
$sidebar.Controls.Add($navSupport)

$navSettings = New-Object System.Windows.Forms.Button
$navSettings.Text = "○ 설정"
$navSettings.Location = New-Object System.Drawing.Point(14, 208)
$navSettings.Size = New-Object System.Drawing.Size(90, 32)
$navSettings.Add_Click({{ Start-Process -FilePath $logDir }})
$sidebar.Controls.Add($navSettings)

$title = New-Object System.Windows.Forms.Label
$title.Text = ""
$title.Font = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
$title.AutoSize = $true
$title.Location = New-Object System.Drawing.Point(138, 18)
$form.Controls.Add($title)

function Add-Card {{
  param([int]$X, [string]$Title, [string]$Value, [string]$Detail)
  $card = New-Object System.Windows.Forms.Panel
  $card.Location = New-Object System.Drawing.Point($X, 64)
  $card.Size = New-Object System.Drawing.Size(190, 82)
  $card.BorderStyle = "FixedSingle"
  $form.Controls.Add($card)
  $cardTitle = New-Object System.Windows.Forms.Label
  $cardTitle.Text = $Title
  $cardTitle.Font = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
  $cardTitle.Location = New-Object System.Drawing.Point(12, 10)
  $cardTitle.Size = New-Object System.Drawing.Size(160, 20)
  $card.Controls.Add($cardTitle)
  $cardValue = New-Object System.Windows.Forms.Label
  $cardValue.Text = $Value
  $cardValue.Font = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Bold)
  $cardValue.Location = New-Object System.Drawing.Point(12, 34)
  $cardValue.Size = New-Object System.Drawing.Size(160, 20)
  $card.Controls.Add($cardValue)
  $cardDetail = New-Object System.Windows.Forms.Label
  $cardDetail.Text = $Detail
  $cardDetail.Location = New-Object System.Drawing.Point(12, 58)
  $cardDetail.Size = New-Object System.Drawing.Size(160, 18)
  $card.Controls.Add($cardDetail)
}}

Add-Card 138 "Agent 상태" $agentStatus "백그라운드 수집"
Add-Card 340 "서버 연결" $serverStatus "heartbeat 미호출"
Add-Card 542 "마지막 업로드" $lastUpload "로컬 기록 기준"
Add-Card 744 "버전" $versionText "정책 $policyText"

$signalLabel = New-Object System.Windows.Forms.Label
$signalLabel.Text = "최근 신호"
$signalLabel.Font = New-Object System.Drawing.Font("Segoe UI", 10, [System.Drawing.FontStyle]::Bold)
$signalLabel.Location = New-Object System.Drawing.Point(138, 164)
$signalLabel.AutoSize = $true
$form.Controls.Add($signalLabel)

$signalsBox = New-Object System.Windows.Forms.TextBox
$signalsBox.Multiline = $true
$signalsBox.ReadOnly = $true
$signalsBox.BorderStyle = "FixedSingle"
$signalsBox.Location = New-Object System.Drawing.Point(138, 190)
$signalsBox.Size = New-Object System.Drawing.Size(796, 70)
$signalsBox.Text = $signalsText
$form.Controls.Add($signalsBox)

$dateLabel = New-Object System.Windows.Forms.Label
$dateLabel.Text = "날짜"
$dateLabel.AutoSize = $true
$dateLabel.Location = New-Object System.Drawing.Point(138, 282)
$form.Controls.Add($dateLabel)

$dateInput = New-Object System.Windows.Forms.TextBox
$dateInput.Location = New-Object System.Drawing.Point(180, 278)
$dateInput.Size = New-Object System.Drawing.Size(96, 22)
$dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
$form.Controls.Add($dateInput)

$hourLabel = New-Object System.Windows.Forms.Label
$hourLabel.Text = "시간"
$hourLabel.AutoSize = $true
$hourLabel.Location = New-Object System.Drawing.Point(292, 282)
$form.Controls.Add($hourLabel)

$hourSelect = New-Object System.Windows.Forms.ComboBox
$hourSelect.DropDownStyle = "DropDownList"
$hourSelect.Location = New-Object System.Drawing.Point(334, 278)
$hourSelect.Size = New-Object System.Drawing.Size(72, 22)
0..23 | ForEach-Object {{ [void]$hourSelect.Items.Add(($_.ToString("00")) + ":00") }}
$hourSelect.SelectedIndex = [int](Get-Date).Hour
$form.Controls.Add($hourSelect)

$textbox = New-Object System.Windows.Forms.TextBox
$textbox.Multiline = $true
$textbox.ScrollBars = "Both"
$textbox.ReadOnly = $true
$textbox.Font = New-Object System.Drawing.Font("Consolas", 9)
$textbox.Location = New-Object System.Drawing.Point(138, 312)
$textbox.Size = New-Object System.Drawing.Size(796, 220)
$textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
$form.Controls.Add($textbox)

$refresh = New-Object System.Windows.Forms.Button
$refresh.Text = "1시간 로그"
$refresh.Location = New-Object System.Drawing.Point(420, 277)
$refresh.Size = New-Object System.Drawing.Size(88, 24)
$refresh.Add_Click({{ $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex }})
$form.Controls.Add($refresh)

$today = New-Object System.Windows.Forms.Button
$today.Text = "현재"
$today.Location = New-Object System.Drawing.Point(516, 277)
$today.Size = New-Object System.Drawing.Size(64, 24)
$today.Add_Click({{
  $dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
  $hourSelect.SelectedIndex = [int](Get-Date).Hour
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
}})
$form.Controls.Add($today)

$folder = New-Object System.Windows.Forms.Button
$folder.Text = "로그 폴더"
$folder.Location = New-Object System.Drawing.Point(138, 550)
$folder.Size = New-Object System.Drawing.Size(100, 28)
$folder.Add_Click({{ Start-Process -FilePath $logDir }})
$form.Controls.Add($folder)

$support = New-Object System.Windows.Forms.Button
$support.Text = "AS 페이지"
$support.Location = New-Object System.Drawing.Point(250, 550)
$support.Size = New-Object System.Drawing.Size(100, 28)
$support.Add_Click({{ Start-Process -FilePath $supportUrl }})
$form.Controls.Add($support)

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
) -> None:
    if tk is None or ttk is None:
        show_log_viewer_powershell(config_path)
        return

    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    path = log_file(config)

    root = tk.Tk()
    root.title("PC Agent")
    root.geometry("1000x640")
    root.minsize(860, 560)
    colors = {
        "app_bg": "#f5f7f8",
        "sidebar_bg": "#e7f2ef",
        "sidebar_active": "#ffffff",
        "sidebar_active_bar": "#1f8a70",
        "text": "#172b3a",
        "muted": "#5c6b73",
        "subtle": "#7b8a92",
        "border": "#d7e0e3",
        "card_bg": "#ffffff",
        "section_bg": "#ffffff",
        "table_header": "#edf3f4",
        "row_alt": "#f8fbfb",
        "signal_bg": "#f8fbfb",
        "signal_hover": "#edf7f4",
    }

    def create_round_rect(
        canvas: tk.Canvas,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        radius: int,
        **kwargs: Any,
    ) -> None:
        points = [
            x1 + radius,
            y1,
            x2 - radius,
            y1,
            x2,
            y1,
            x2,
            y1 + radius,
            x2,
            y2 - radius,
            x2,
            y2,
            x2 - radius,
            y2,
            x1 + radius,
            y2,
            x1,
            y2,
            x1,
            y2 - radius,
            x1,
            y1 + radius,
            x1,
            y1,
        ]
        canvas.create_polygon(points, smooth=True, **kwargs)

    def rounded_container(
        parent: tk.Misc,
        height: int,
        padding: tuple[int, int] = (14, 12),
        radius: int = 16,
    ) -> tuple[tk.Canvas, tk.Frame]:
        canvas = tk.Canvas(
            parent,
            height=height,
            background=colors["app_bg"],
            highlightthickness=0,
            borderwidth=0,
        )
        inner = tk.Frame(canvas, background=colors["card_bg"])
        window_id = canvas.create_window(padding[0], padding[1], anchor="nw", window=inner)

        def redraw(event: tk.Event) -> None:
            canvas.delete("rounded-bg")
            width = max(2, event.width)
            actual_height = max(2, event.height)
            create_round_rect(
                canvas,
                1,
                1,
                width - 1,
                actual_height - 1,
                radius,
                fill=colors["card_bg"],
                outline=colors["border"],
                tags="rounded-bg",
            )
            canvas.tag_lower("rounded-bg")
            canvas.coords(window_id, padding[0], padding[1])
            canvas.itemconfigure(
                window_id,
                width=max(1, width - padding[0] * 2),
                height=max(1, actual_height - padding[1] * 2),
            )

        canvas.bind("<Configure>", redraw)
        return canvas, inner

    def rounded_button(
        parent: tk.Misc,
        text: str,
        command: Any,
        variant: str = "secondary",
        width: int = 112,
        height: int = 32,
    ) -> tk.Canvas:
        is_primary = variant == "primary"
        fill = "#197b8f" if is_primary else "#eef7f7"
        hover = "#156a7a" if is_primary else "#dcefed"
        foreground = "#ffffff" if is_primary else colors["text"]
        try:
            parent_bg = str(parent.cget("background"))  # type: ignore[attr-defined]
        except Exception:
            parent_bg = colors["app_bg"]
        canvas = tk.Canvas(
            parent,
            width=width,
            height=height,
            background=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            cursor="hand2",
        )

        def draw(background: str) -> None:
            canvas.delete("all")
            create_round_rect(canvas, 1, 1, width - 1, height - 1, 12, fill=background, outline=background)
            canvas.create_text(
                width // 2,
                height // 2,
                text=text,
                fill=foreground,
                font=("Segoe UI", 9, "bold" if is_primary else "normal"),
            )

        def invoke(event: object = None) -> None:
            command()

        draw(fill)
        canvas.bind("<Button-1>", invoke)
        canvas.bind("<Enter>", lambda event: draw(hover))
        canvas.bind("<Leave>", lambda event: draw(fill))
        return canvas

    root.configure(background=colors["app_bg"])
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure("Agent.TEntry", padding=(6, 3))
    style.configure("Agent.TCombobox", padding=(6, 3))
    style.configure("Agent.Toolbar.TButton", padding=(12, 5), font=("Segoe UI", 9))
    style.configure(
        "Agent.Treeview",
        background=colors["section_bg"],
        fieldbackground=colors["section_bg"],
        foreground=colors["text"],
        bordercolor=colors["border"],
        rowheight=28,
        font=("Segoe UI", 9),
    )
    style.configure(
        "Agent.Treeview.Heading",
        background=colors["table_header"],
        foreground=colors["muted"],
        relief="flat",
        font=("Segoe UI", 9, "bold"),
    )
    style.map(
        "Agent.Treeview",
        background=[("selected", "#dcefed")],
        foreground=[("selected", colors["text"])],
    )

    range_status = tk.StringVar(value="")
    agent_status = tk.StringVar(value="-")
    server_status = tk.StringVar(value="-")
    upload_status = tk.StringVar(value="-")
    version_status = tk.StringVar(value="-")
    selected_nav = tk.StringVar(value="상태")

    shell = tk.Frame(root, background=colors["app_bg"])
    shell.pack(fill="both", expand=True)

    sidebar = tk.Frame(shell, width=150, background=colors["sidebar_bg"])
    sidebar.pack(side="left", fill="y")
    sidebar.pack_propagate(False)
    tk.Frame(sidebar, height=22, background=colors["sidebar_bg"]).pack(fill="x")
    nav_items: list[tuple[str, tk.Frame]] = []

    def render_nav() -> None:
        for name, item in nav_items:
            active = selected_nav.get() == name
            item.configure(
                background=colors["sidebar_active"] if active else colors["sidebar_bg"],
                highlightbackground=colors["border"] if active else colors["sidebar_bg"],
            )
            for child in item.winfo_children():
                role = getattr(child, "_agent_nav_role", "")
                if role == "bar":
                    child.configure(background=colors["sidebar_active_bar"] if active else colors["sidebar_bg"])
                else:
                    child.configure(
                        background=colors["sidebar_active"] if active else colors["sidebar_bg"],
                        foreground=colors["text"] if active else colors["muted"],
                    )

    def add_nav_item(name: str, icon: str, command: Any) -> None:
        item = tk.Frame(
            sidebar,
            height=44,
            background=colors["sidebar_bg"],
            highlightthickness=1,
            highlightbackground=colors["sidebar_bg"],
        )
        item.pack(fill="x", padx=12, pady=5)
        item.pack_propagate(False)
        bar = tk.Frame(item, width=4, background=colors["sidebar_bg"])
        bar._agent_nav_role = "bar"  # type: ignore[attr-defined]
        bar.pack(side="left", fill="y")
        label = tk.Label(
            item,
            text=f"{icon}  {name}",
            font=("Segoe UI", 10, "bold" if name == "상태" else "normal"),
            anchor="w",
            padx=12,
            background=colors["sidebar_bg"],
            foreground=colors["muted"],
        )
        label.pack(side="left", fill="both", expand=True)

        def activate(event: object = None) -> None:
            selected_nav.set(name)
            render_nav()
            command()

        for widget in (item, bar, label):
            widget.configure(cursor="hand2")
            widget.bind("<Button-1>", activate)
        nav_items.append((name, item))

    add_nav_item("상태", "●", lambda: show_status_tab())
    add_nav_item("로그", "≡", lambda: show_log_tab())
    add_nav_item("AS 접수", "+", lambda: show_support_tab())
    add_nav_item("설정", "○", lambda: open_log_folder(config_path))
    render_nav()

    content = tk.Frame(shell, background=colors["app_bg"], padx=14, pady=8)
    content.pack(side="left", fill="both", expand=True)

    header = tk.Frame(content, background=colors["app_bg"])
    header.pack(fill="x")
    range_badge = tk.Label(
        header,
        textvariable=range_status,
        font=("Segoe UI", 9),
        foreground=colors["muted"],
        background="#e9f3f1",
        padx=12,
        pady=5,
    )

    view_stack = tk.Frame(content, background=colors["app_bg"])
    view_stack.pack(fill="both", expand=True)
    status_view = tk.Frame(view_stack, background=colors["app_bg"])
    log_view = tk.Frame(view_stack, background=colors["app_bg"])
    support_view = tk.Frame(view_stack, background=colors["app_bg"])

    status_header = tk.Frame(status_view, background=colors["app_bg"])
    status_header.pack(fill="x", pady=(0, 14))
    tk.Label(
        status_header,
        text="상태 홈",
        font=("Segoe UI", 15, "bold"),
        foreground=colors["text"],
        background=colors["app_bg"],
        anchor="w",
    ).pack(fill="x")
    tk.Label(
        status_header,
        text="PC Agent가 시스템을 안전하게 보호하고 있습니다.",
        font=("Segoe UI", 9),
        foreground=colors["muted"],
        background=colors["app_bg"],
        anchor="w",
    ).pack(fill="x", pady=(4, 0))

    cards = tk.Frame(status_view, background=colors["app_bg"])
    cards.pack(fill="x", pady=(0, 10))
    for index in range(4):
        cards.columnconfigure(index, weight=1, uniform="status-card")

    card_icon_images: list[tk.PhotoImage] = []

    def make_card_icon(parent: tk.Misc, kind: str) -> tk.Widget:
        teal = colors["sidebar_active_bar"]
        soft = "#eef8f5"
        line = "#cae6df"
        if Image is not None and ImageDraw is not None:
            try:
                scale = 3
                size = 46
                canvas_size = size * scale

                def x(value: int) -> int:
                    return value * scale

                def color(value: str) -> tuple[int, int, int, int]:
                    value = value.lstrip("#")
                    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)

                image = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
                draw = ImageDraw.Draw(image)
                draw.rounded_rectangle((x(3), x(3), x(43), x(43)), radius=x(12), fill=color(soft), outline=color(line), width=x(1))
                stroke = color(teal)
                width = x(2)
                if kind == "agent":
                    draw.line([(x(23), x(10)), (x(34), x(16)), (x(32), x(30)), (x(23), x(37)), (x(14), x(30)), (x(12), x(16)), (x(23), x(10))], fill=stroke, width=width)
                    draw.line([(x(18), x(24)), (x(22), x(28)), (x(29), x(20))], fill=stroke, width=width)
                elif kind == "server":
                    draw.rounded_rectangle((x(12), x(15), x(34), x(29)), radius=x(2), outline=stroke, width=width)
                    draw.line([(x(15), x(24)), (x(19), x(24)), (x(21), x(20)), (x(25), x(29)), (x(27), x(24)), (x(31), x(24))], fill=stroke, width=width)
                    draw.line([(x(23), x(29)), (x(23), x(34)), (x(17), x(34)), (x(29), x(34))], fill=stroke, width=width)
                elif kind == "upload":
                    draw.arc((x(13), x(13), x(33), x(33)), start=35, end=315, fill=stroke, width=width)
                    draw.line([(x(33), x(16)), (x(33), x(10)), (x(39), x(10))], fill=stroke, width=width)
                    draw.line([(x(23), x(34)), (x(23), x(21)), (x(17), x(27))], fill=stroke, width=width)
                    draw.line([(x(23), x(21)), (x(29), x(27))], fill=stroke, width=width)
                else:
                    draw.ellipse((x(14), x(10), x(32), x(28)), outline=stroke, width=width)
                    draw.line([(x(23), x(18)), (x(23), x(25))], fill=stroke, width=width)
                    draw.ellipse((x(22), x(14), x(24), x(16)), fill=stroke)
                    draw.line([(x(16), x(34)), (x(30), x(34))], fill=stroke, width=width)
                resampling = getattr(getattr(Image, "Resampling", Image), "LANCZOS", Image.LANCZOS)
                image = image.resize((size, size), resampling)
                buffer = BytesIO()
                image.save(buffer, format="PNG")
                photo = tk.PhotoImage(data=base64.b64encode(buffer.getvalue()).decode("ascii"))
                card_icon_images.append(photo)
                return tk.Label(parent, image=photo, background=colors["card_bg"], borderwidth=0, highlightthickness=0)
            except Exception:
                pass
        fallback_text = {"agent": "OK", "server": "PC", "upload": "UP", "version": "i"}.get(kind, "i")
        return tk.Label(
            parent,
            text=fallback_text,
            width=4,
            height=2,
            font=("Segoe UI", 10, "bold"),
            foreground=teal,
            background=soft,
            borderwidth=0,
            highlightthickness=1,
            highlightbackground=line,
        )

    def add_card(parent: tk.Frame, index: int, title: str, value: tk.StringVar, detail: str, icon_kind: str) -> None:
        card_canvas, card = rounded_container(parent, 92, padding=(14, 12), radius=16)
        card_canvas.grid(row=0, column=index, sticky="nsew", padx=(0 if index == 0 else 8, 0))
        card.columnconfigure(0, weight=0)
        card.columnconfigure(1, weight=1)
        make_card_icon(card, icon_kind).grid(row=0, column=0, rowspan=3, sticky="nw", padx=(0, 12))
        tk.Label(
            card,
            text=title,
            font=("Segoe UI", 9, "bold"),
            foreground=colors["muted"],
            background=colors["card_bg"],
            anchor="w",
        ).grid(row=0, column=1, sticky="ew", pady=(0, 2))
        tk.Label(
            card,
            textvariable=value,
            font=("Segoe UI", 12, "bold"),
            foreground=colors["text"],
            background=colors["card_bg"],
            anchor="w",
        ).grid(row=1, column=1, sticky="ew")
        tk.Label(
            card,
            text=detail,
            font=("Segoe UI", 8),
            foreground=colors["subtle"],
            background=colors["card_bg"],
            anchor="w",
        ).grid(row=2, column=1, sticky="ew", pady=(5, 0))

    add_card(cards, 0, "Agent 상태", agent_status, "백그라운드 수집", "agent")
    add_card(cards, 1, "서버 연결", server_status, "heartbeat 미호출", "server")
    add_card(cards, 2, "마지막 업로드", upload_status, "로컬 기록 기준", "upload")
    add_card(cards, 3, "버전", version_status, "agent / policy", "version")

    signals_section, signals_inner = rounded_container(status_view, 124, padding=(14, 10), radius=16)
    signals_section.pack(fill="x", pady=(0, 10))
    tk.Label(
        signals_inner,
        text="최근 감지 신호",
        font=("Segoe UI", 10, "bold"),
        foreground=colors["text"],
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x", pady=(0, 5))
    signals_body = tk.Frame(signals_inner, background=colors["card_bg"])
    signals_body.pack(fill="both", expand=True)

    summary_section, summary_inner = rounded_container(status_view, 260, padding=(14, 10), radius=16)
    summary_section.pack(fill="both", expand=True)
    tk.Label(
        summary_inner,
        text="1시간 로그 요약",
        font=("Segoe UI", 10, "bold"),
        foreground=colors["text"],
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x", pady=(0, 6))
    summary_columns = ("time", "cpu", "memory", "disk", "gpu", "event")
    summary_table = ttk.Treeview(
        summary_inner,
        columns=summary_columns,
        show="headings",
        height=6,
        style="Agent.Treeview",
    )
    summary_table.heading("time", text="시간")
    summary_table.heading("cpu", text="CPU")
    summary_table.heading("memory", text="메모리")
    summary_table.heading("disk", text="디스크")
    summary_table.heading("gpu", text="GPU")
    summary_table.heading("event", text="이벤트")
    summary_table.column("time", width=86, minwidth=72, anchor="w", stretch=False)
    summary_table.column("cpu", width=78, minwidth=64, anchor="e", stretch=False)
    summary_table.column("memory", width=86, minwidth=72, anchor="e", stretch=False)
    summary_table.column("disk", width=82, minwidth=68, anchor="e", stretch=False)
    summary_table.column("gpu", width=78, minwidth=64, anchor="e", stretch=False)
    summary_table.column("event", width=260, minwidth=180, anchor="w")
    summary_table.tag_configure("odd", background=colors["row_alt"])
    summary_table.tag_configure("even", background=colors["section_bg"])
    summary_table.pack(fill="both", expand=True)
    summary_empty = tk.Frame(summary_inner, background=colors["card_bg"])
    tk.Label(
        summary_empty,
        text="표시할 로그가 없습니다",
        font=("Segoe UI", 10, "bold"),
        foreground=colors["muted"],
        background=colors["card_bg"],
    ).pack(pady=(36, 3))
    tk.Label(
        summary_empty,
        text="백그라운드 수집이 시작되면 최근 로그가 표시됩니다",
        font=("Segoe UI", 9),
        foreground=colors["subtle"],
        background=colors["card_bg"],
    ).pack()

    filters = tk.Frame(log_view, background=colors["app_bg"])
    filters.pack(fill="x", pady=(0, 8))
    tk.Label(
        filters,
        text="날짜",
        font=("Segoe UI", 9, "bold"),
        foreground=colors["muted"],
        background=colors["app_bg"],
    ).pack(side="left")
    date_value = tk.StringVar(value=datetime.now(KST).strftime("%Y-%m-%d"))
    date_entry = ttk.Entry(filters, textvariable=date_value, width=12, style="Agent.TEntry")
    date_entry.pack(side="left", padx=(6, 14))
    tk.Label(
        filters,
        text="시간",
        font=("Segoe UI", 9, "bold"),
        foreground=colors["muted"],
        background=colors["app_bg"],
    ).pack(side="left")
    hour_value = tk.StringVar(value=f"{datetime.now(KST).hour:02d}:00")
    hour_select = ttk.Combobox(
        filters,
        textvariable=hour_value,
        values=[f"{hour:02d}:00" for hour in range(24)],
        width=7,
        state="readonly",
        style="Agent.TCombobox",
    )
    hour_select.pack(side="left", padx=(6, 14))
    rounded_button(filters, "1시간 로그", lambda: refresh_log(), "primary", width=104, height=32).pack(side="left")
    rounded_button(filters, "현재", lambda: set_current_hour(), "secondary", width=76, height=32).pack(
        side="left",
        padx=(8, 0),
    )

    columns = ("time", "kind", "cpu", "memory", "disk", "gpu", "vram", "cpu_temp", "gpu_temp", "message")
    table_frame = tk.Frame(
        log_view,
        background=colors["section_bg"],
        highlightthickness=1,
        highlightbackground=colors["border"],
    )
    table_frame.pack(fill="both", expand=True)
    tk.Label(
        table_frame,
        text="1시간 로그",
        font=("Segoe UI", 10, "bold"),
        foreground=colors["text"],
        background=colors["section_bg"],
        anchor="w",
    ).grid(row=0, column=0, columnspan=2, sticky="ew", padx=12, pady=(10, 6))
    tree = ttk.Treeview(table_frame, columns=columns, show="headings", height=12, style="Agent.Treeview")
    tree.heading("time", text="시간")
    tree.heading("kind", text="이벤트")
    tree.heading("cpu", text="CPU")
    tree.heading("memory", text="메모리")
    tree.heading("disk", text="디스크")
    tree.heading("gpu", text="GPU")
    tree.heading("vram", text="VRAM")
    tree.heading("cpu_temp", text="CPU 온도")
    tree.heading("gpu_temp", text="GPU 온도")
    tree.heading("message", text="메시지")
    tree.column("time", width=78, minwidth=70, anchor="w", stretch=False)
    tree.column("kind", width=138, minwidth=120, anchor="w", stretch=False)
    tree.column("cpu", width=62, minwidth=58, anchor="e", stretch=False)
    tree.column("memory", width=74, minwidth=68, anchor="e", stretch=False)
    tree.column("disk", width=70, minwidth=64, anchor="e", stretch=False)
    tree.column("gpu", width=62, minwidth=58, anchor="e", stretch=False)
    tree.column("vram", width=64, minwidth=60, anchor="e", stretch=False)
    tree.column("cpu_temp", width=76, minwidth=70, anchor="e", stretch=False)
    tree.column("gpu_temp", width=76, minwidth=70, anchor="e", stretch=False)
    tree.column("message", width=280, minwidth=220, anchor="w")
    tree.tag_configure("odd", background=colors["row_alt"])
    tree.tag_configure("even", background=colors["section_bg"])

    vertical = ttk.Scrollbar(table_frame, orient="vertical", command=tree.yview)
    horizontal = ttk.Scrollbar(table_frame, orient="horizontal", command=tree.xview)
    tree.configure(yscrollcommand=vertical.set, xscrollcommand=horizontal.set)
    tree.grid(row=1, column=0, sticky="nsew", padx=(12, 0), pady=(0, 0))
    vertical.grid(row=1, column=1, sticky="ns", padx=(0, 12), pady=(0, 0))
    horizontal.grid(row=2, column=0, sticky="ew", padx=(12, 0), pady=(0, 12))
    table_frame.columnconfigure(0, weight=1)
    table_frame.rowconfigure(1, weight=1)
    log_empty_label = tk.Label(
        table_frame,
        text="표시할 로그가 없습니다",
        font=("Segoe UI", 10),
        foreground=colors["subtle"],
        background=colors["section_bg"],
    )

    support_card, support_inner = rounded_container(support_view, 520, padding=(18, 16), radius=16)
    support_card.pack(fill="both", expand=True, pady=(6, 0))
    tk.Label(
        support_inner,
        text="AS 접수",
        font=("Segoe UI", 16, "bold"),
        foreground=colors["text"],
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x")
    tk.Label(
        support_inner,
        text="증상과 PC Agent 로그를 함께 보내면 담당자가 더 정확히 확인할 수 있습니다.",
        font=("Segoe UI", 9),
        foreground=colors["muted"],
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x", pady=(4, 14))

    symptom_title = tk.StringVar(value="")
    symptom_type = tk.StringVar(value="REMOTE_DRIVER_OS")
    symptom_time = tk.StringVar(value="")
    support_mode = tk.StringVar(value="우선 진단만 받기")
    support_status = tk.StringVar(value="")
    incident_window_value = tk.StringVar(value="전송 로그 범위는 증상 유형과 발생 시각 기준으로 계산됩니다.")
    support_signal_value: dict[str, Any] | None = None

    def add_form_label(parent: tk.Misc, text: str) -> None:
        tk.Label(
            parent,
            text=text,
            font=("Segoe UI", 9, "bold"),
            foreground=colors["muted"],
            background=colors["card_bg"],
            anchor="w",
        ).pack(fill="x", pady=(0, 4))

    def add_form_field(parent: tk.Misc, label: str, widget: tk.Widget) -> None:
        block = tk.Frame(parent, background=colors["card_bg"])
        block.pack(fill="x", pady=(0, 10))
        add_form_label(block, label)
        widget.pack(fill="x")

    form_grid = tk.Frame(support_inner, background=colors["card_bg"])
    form_grid.pack(fill="x")
    left_form = tk.Frame(form_grid, background=colors["card_bg"])
    right_form = tk.Frame(form_grid, background=colors["card_bg"])
    left_form.pack(side="left", fill="both", expand=True, padx=(0, 10))
    right_form.pack(side="left", fill="both", expand=True, padx=(10, 0))
    add_form_field(left_form, "증상 제목", ttk.Entry(left_form, textvariable=symptom_title, style="Agent.TEntry"))
    type_select = ttk.Combobox(
        left_form,
        textvariable=symptom_type,
        values=sorted(REMOTE_SYMPTOM_TYPES | VISIT_SYMPTOM_TYPES),
        state="readonly",
        style="Agent.TCombobox",
    )
    add_form_field(left_form, "증상 유형", type_select)
    add_form_field(right_form, "증상 발생 시각", ttk.Entry(right_form, textvariable=symptom_time, style="Agent.TEntry"))

    detail_block = tk.Frame(support_inner, background=colors["card_bg"])
    detail_block.pack(fill="both", expand=True, pady=(0, 10))
    add_form_label(detail_block, "증상 상세")
    symptom_detail = tk.Text(
        detail_block,
        height=5,
        wrap="word",
        relief="flat",
        borderwidth=1,
        highlightthickness=1,
        highlightbackground=colors["border"],
        font=("Segoe UI", 9),
    )
    symptom_detail.pack(fill="both", expand=True)

    mode_block = tk.Frame(support_inner, background=colors["card_bg"])
    mode_block.pack(fill="x", pady=(0, 8))
    add_form_label(mode_block, "지원 신청 방식")
    for label in ("우선 진단만 받기", "원격지원 신청", "방문지원 신청"):
        tk.Radiobutton(
            mode_block,
            text=label,
            variable=support_mode,
            value=label,
            foreground=colors["text"],
            background=colors["card_bg"],
            activebackground=colors["card_bg"],
            selectcolor=colors["card_bg"],
            font=("Segoe UI", 9),
        ).pack(side="left", padx=(0, 16))

    tk.Label(
        support_inner,
        textvariable=incident_window_value,
        font=("Segoe UI", 8),
        foreground=colors["subtle"],
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x", pady=(0, 8))
    tk.Label(
        support_inner,
        textvariable=support_status,
        font=("Segoe UI", 8),
        foreground="#16766b",
        background=colors["card_bg"],
        anchor="w",
    ).pack(fill="x", pady=(0, 8))

    support_actions = tk.Frame(support_inner, background=colors["card_bg"])
    support_actions.pack(fill="x")

    def select_signal(signal: dict[str, Any]) -> None:
        date_value.set(str(signal["date"]))
        hour_value.set(f"{int(signal['hour']):02d}:00")
        show_log_tab()

    def refresh_signals(signals: Sequence[dict[str, Any]]) -> None:
        for child in signals_body.winfo_children():
            child.destroy()
        if not signals:
            empty = tk.Frame(signals_body, background=colors["card_bg"])
            empty.pack(fill="both", expand=True)
            tk.Label(
                empty,
                text="최근 감지 신호 없음",
                font=("Segoe UI", 9, "bold"),
                foreground=colors["muted"],
                background=colors["card_bg"],
                anchor="w",
            ).pack(fill="x", pady=(5, 2))
            tk.Label(
                empty,
                text="단순 CPU/RAM/GPU 고사용률은 AS 알림에서 제외됩니다",
                font=("Segoe UI", 8),
                foreground=colors["subtle"],
                background=colors["card_bg"],
                anchor="w",
            ).pack(fill="x")
            return
        for signal in signals:
            row = tk.Frame(signals_body, height=24, background=colors["signal_bg"])
            row.pack(fill="x", pady=(0, 4))
            row.grid_propagate(False)
            row.columnconfigure(2, weight=1)
            time_label = tk.Label(
                row,
                text=str(signal["time"]),
                font=("Segoe UI", 9),
                foreground=colors["subtle"],
                background=colors["signal_bg"],
                anchor="w",
                width=8,
            )
            time_label.grid(row=0, column=0, sticky="nsw", padx=(8, 8))
            level_label = tk.Label(
                row,
                text=str(signal["level"]),
                font=("Segoe UI", 8, "bold"),
                foreground="#1f6f5d",
                background=colors["signal_bg"],
                anchor="w",
                width=7,
            )
            level_label.grid(row=0, column=1, sticky="nsw", padx=(0, 8))
            title_label = tk.Label(
                row,
                text=sanitize_display_text(signal["title"], 72),
                font=("Segoe UI", 9),
                foreground=colors["text"],
                background=colors["signal_bg"],
                anchor="w",
            )
            title_label.grid(row=0, column=2, sticky="nsew")

            def paint_signal(target: tk.Frame, background: str) -> None:
                target.configure(background=background)
                for widget in target.winfo_children():
                    widget.configure(background=background)

            def open_signal(event: object = None, current: dict[str, Any] = signal) -> None:
                selected_nav.set("로그")
                render_nav()
                select_signal(current)

            for widget in (row, time_label, level_label, title_label):
                widget.configure(cursor="hand2")
                widget.bind("<Button-1>", open_signal)
                widget.bind("<Enter>", lambda event, target=row: paint_signal(target, colors["signal_hover"]))
                widget.bind("<Leave>", lambda event, target=row: paint_signal(target, colors["signal_bg"]))

    def support_detail_text() -> str:
        return symptom_detail.get("1.0", "end").strip()

    def set_support_detail(text: str) -> None:
        symptom_detail.delete("1.0", "end")
        symptom_detail.insert("1.0", text)

    def fill_support_from_signal(signal: dict[str, Any] | None) -> None:
        nonlocal support_signal_value
        support_signal_value = signal
        if signal is None:
            now = datetime.now(KST)
            symptom_time.set(now.strftime("%Y-%m-%d %H:%M:%S"))
            incident_window_value.set("전송 로그 범위는 증상 유형과 발생 시각 기준으로 계산됩니다.")
            return
        code = str(signal.get("code") or "REMOTE_DRIVER_OS")
        detected_at = event_panel_detected_at(signal)
        window = default_incident_window(code, detected_at=detected_at, trigger_type="USER_REQUEST")
        symptom_type.set(code if code in REMOTE_SYMPTOM_TYPES or code in VISIT_SYMPTOM_TYPES else "REMOTE_DRIVER_OS")
        symptom_time.set(detected_at.strftime("%Y-%m-%d %H:%M:%S"))
        symptom_title.set(event_panel_signal_summary(signal))
        if not support_detail_text():
            set_support_detail(
                "PC Agent가 확인이 필요한 이벤트를 감지했습니다.\n"
                f"- {event_panel_signal_summary(signal)}\n"
                "로그 전송 후 담당자가 내용을 검토합니다."
            )
        incident_window_value.set(
            f"전송 로그 범위: {window.started_at.strftime('%Y-%m-%d %H:%M:%S')} ~ "
            f"{window.ended_at.strftime('%Y-%m-%d %H:%M:%S')}"
        )

    def submit_support_request() -> None:
        try:
            detected_at = parse_datetime(symptom_time.get(), "symptomTime") if symptom_time.get().strip() else datetime.now(KST)
            selected_type = symptom_type.get() or "REMOTE_DRIVER_OS"
            window = default_incident_window(selected_type, detected_at=detected_at, trigger_type="USER_REQUEST")
            gzip_path = config.log_dir.parent / "uploads" / f"{window.incident_id}.jsonl.gz"
            gzip_window(path, gzip_path, window)
            symptom_parts = [
                symptom_title.get().strip() or "PC Agent AS 접수",
                support_mode.get(),
                support_detail_text(),
            ]
            result = upload_gzip(
                config,
                gzip_path,
                f"agent-support-{uuid.uuid4()}",
                " / ".join(part for part in symptom_parts if part),
                window,
            )
            ticket_id = str(result["ticketId"])
            support_status.set(f"AS 접수 신청이 전송되었습니다. 티켓 {ticket_id}")
            webbrowser.open(support_url(config.api_base_url, ticket_id, config.web_base_url))
        except Exception as exception:
            support_status.set(event_panel_failure_message(exception))

    def refresh_log_summary() -> None:
        rows = read_status_log_summary_rows(path, STATUS_LOG_SUMMARY_LIMIT)
        summary_table.delete(*summary_table.get_children())
        if not rows:
            summary_table.pack_forget()
            if not summary_empty.winfo_ismapped():
                summary_empty.pack(fill="both", expand=True)
            return
        summary_empty.pack_forget()
        if not summary_table.winfo_ismapped():
            summary_table.pack(fill="both", expand=True)
        for index, row in enumerate(rows):
            summary_table.insert(
                "",
                "end",
                values=display_log_summary_values(row),
                tags=("odd" if index % 2 else "even",),
            )

    def refresh_status() -> None:
        model = status_home_model(config, path)
        agent_status.set(str(model["agentStatus"]))
        server_status.set(str(model["serverStatus"]))
        upload_status.set(str(model["lastUpload"]))
        version_status.set(f"{model['version']} / {model['policyVersion']}")
        refresh_signals(model["signals"])
        refresh_log_summary()

    def refresh_log() -> None:
        try:
            selected_hour = int(hour_value.get().split(":", 1)[0])
        except ValueError:
            selected_hour = datetime.now(KST).hour
        rows = read_log_hour(path, date_value.get(), selected_hour, LOG_TABLE_LIMIT)
        tree.delete(*tree.get_children())
        for index, row in enumerate(rows):
            tree.insert("", "end", values=display_log_table_values(row), tags=("odd" if index % 2 else "even",))
        if rows:
            log_empty_label.place_forget()
        else:
            log_empty_label.place(relx=0.5, rely=0.52, anchor="center")
            log_empty_label.lift()
        range_status.set(f"범위 {date_value.get()} {selected_hour:02d}:00 | 표시 {len(rows)}개")

    def show_status_tab() -> None:
        selected_nav.set("상태")
        render_nav()
        log_view.pack_forget()
        support_view.pack_forget()
        status_view.pack(fill="both", expand=True)
        range_badge.pack_forget()
        range_status.set("")
        refresh_status()

    def show_log_tab() -> None:
        selected_nav.set("로그")
        render_nav()
        status_view.pack_forget()
        support_view.pack_forget()
        log_view.pack(fill="both", expand=True)
        if not range_badge.winfo_ismapped():
            range_badge.pack(side="right")
        refresh_log()
        tree.focus_set()

    def show_support_tab(signal: dict[str, Any] | None = None) -> None:
        selected_nav.set("AS 접수")
        render_nav()
        status_view.pack_forget()
        log_view.pack_forget()
        support_view.pack(fill="both", expand=True)
        range_badge.pack_forget()
        range_status.set("")
        fill_support_from_signal(signal)

    def set_current_hour() -> None:
        now = datetime.now(KST)
        date_value.set(now.strftime("%Y-%m-%d"))
        hour_value.set(f"{now.hour:02d}:00")
        refresh_log()

    buttons = tk.Frame(log_view, background=colors["app_bg"], pady=8)
    buttons.pack(fill="x")
    rounded_button(buttons, "로그 폴더", lambda: open_log_folder(config_path), "secondary", width=104, height=32).pack(
        side="left",
    )
    rounded_button(
        buttons,
        "AS 페이지",
        lambda: open_support_page(config_path),
        "secondary",
        width=104,
        height=32,
    ).pack(side="left", padx=(8, 0))

    rounded_button(
        support_actions,
        "AS 접수 신청",
        submit_support_request,
        "primary",
        width=132,
        height=34,
    ).pack(side="right")

    if support_signal:
        show_support_tab(support_signal)
    elif focus_signal:
        select_signal(focus_signal)
    else:
        show_status_tab()
    root.mainloop()


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
    icon.create_text(14, 14, text="!", fill=colors["teal"], font=("Segoe UI", 11, "bold"))
    title_box = tk.Frame(header, background=colors["card"])
    title_box.pack(side="left", fill="x", expand=True)
    tk.Label(
        title_box,
        text="확인이 필요한 신호가 감지되었습니다",
        font=("Segoe UI", 12, "bold"),
        foreground=colors["deep"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x")
    tk.Label(
        title_box,
        text="로그에서 자세히 확인할 수 있습니다.",
        font=("Segoe UI", 8),
        foreground=colors["muted"],
        background=colors["card"],
        anchor="w",
    ).pack(fill="x", pady=(2, 0))
    close_label = tk.Label(
        header,
        text="×",
        font=("Segoe UI", 14),
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
            font=("Segoe UI", 8, "bold"),
            foreground=colors["muted"],
            background=colors["soft"],
            anchor="w",
            width=8,
        ).pack(side="left")
        tk.Label(
            row,
            text=value,
            font=("Segoe UI", 8),
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
        text="PC Agent가 관련 경고 이벤트를 감지했습니다. 필요하면 자동으로 정리된 제목과 로그 구간으로 AS 접수를 진행할 수 있습니다.",
        font=("Segoe UI", 9),
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
        font=("Segoe UI", 8, "bold"),
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
            font=("Segoe UI", 8),
            foreground=colors["deep"],
            background=colors["card"],
            activebackground=colors["card"],
            selectcolor=colors["card"],
        ).pack(side="left", padx=(0, 10))

    tk.Label(
        body,
        textvariable=status_text,
        font=("Segoe UI", 8),
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
            font=("Segoe UI", 9, "bold" if primary else "normal"),
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
        font=("Segoe UI", 8, "underline"),
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


def collect_background_loop(config_path: Path, runtime: AgentRuntime, interval_seconds: int) -> None:
    while runtime.running:
        try:
            config = load_config(config_path)
            append_metric(config, runtime.index)
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


def run_background(config_path: Path | None = None, interval_seconds: int = 5, with_tray: bool = True) -> int:
    path = ensure_default_config(config_path or default_background_config_path())
    register_startup()
    hide_console_window()
    write_pid()
    runtime = AgentRuntime()

    import threading

    worker = threading.Thread(target=collect_background_loop, args=(path, runtime, interval_seconds), daemon=True)
    worker.start()

    if with_tray and pystray is not None:
        def stop(icon: object, item: object = None) -> None:
            runtime.stop()
            remove_pid()
            icon.stop()

        icon = pystray.Icon(
            APP_NAME,
            create_tray_image(),
            "PC Agent",
            menu=pystray.Menu(
                pystray.MenuItem("Open log viewer", lambda icon, item: show_log_viewer(path), default=True),
                pystray.MenuItem("Open log folder", lambda icon, item: open_log_folder(path)),
                pystray.MenuItem("Open AS page", lambda icon, item: open_support_page(path)),
                pystray.MenuItem("Stop", stop),
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
    remove_pid()
    return 0


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


def main(argv: Sequence[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        return run_background()

    parser = argparse.ArgumentParser(description="PC Agent prototype CLI")
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

    collect = sub.add_parser("collect", help="append demo metrics every 5 seconds")
    collect.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    collect.add_argument("--iterations", type=int, default=1, help="number of demo rows to append; use 0 for forever")
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
