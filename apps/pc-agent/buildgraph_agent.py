from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import mimetypes
import os
import platform
import random
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
from io import TextIOWrapper
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
            try:
                ts = datetime.fromisoformat(row["timestamp"])
            except (KeyError, TypeError, ValueError):
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


def parse_log_timestamp(row: dict[str, Any]) -> datetime | None:
    value = row.get("timestamp")
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


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


def powershell_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def show_log_viewer_powershell(config_path: Path) -> None:
    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    viewer_path = app_data_dir() / "log-viewer.ps1"
    viewer_path.parent.mkdir(parents=True, exist_ok=True)
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$logPath = {powershell_string(str(log_file(config).resolve()))}
$logDir = {powershell_string(str(config.log_dir.resolve()))}
$supportUrl = {powershell_string(support_new_url(config))}

function Get-FilteredLogText {{
  param([string]$DateText, [int]$Hour)
  if (-not (Test-Path -LiteralPath $logPath)) {{
    return "No log file yet.`r`n$logPath"
  }}
  $output = New-Object System.Collections.Generic.List[string]
  try {{
    $start = [datetime]::ParseExact($DateText, "yyyy-MM-dd", $null).AddHours($Hour)
  }} catch {{
    return "Invalid date. Use yyyy-MM-dd."
  }}
  $end = $start.AddHours(1)
  Get-Content -LiteralPath $logPath | ForEach-Object {{
    try {{
      $row = $_ | ConvertFrom-Json
      $observedAt = [datetime]::Parse([string]$row.timestamp)
      if ($observedAt -ge $start -and $observedAt -lt $end) {{
        $timestamp = if ($row.timestamp) {{ $row.timestamp }} else {{ "-" }}
        $cpu = if ($null -ne $row.cpuUsage) {{ "{{0:N1}}%" -f [double]$row.cpuUsage }} else {{ "-" }}
        $memory = if ($null -ne $row.memoryUsage) {{ "{{0:N1}}%" -f [double]$row.memoryUsage }} else {{ "-" }}
        $eventType = if ($row.eventType) {{ $row.eventType }} else {{ "-" }}
        $message = if ($row.message) {{ $row.message }} else {{ "-" }}
        $output.Add("$timestamp`tCPU $cpu`tMEM $memory`t$eventType`t$message")
      }}
    }} catch {{}}
  }}
  if ($output.Count -eq 0) {{
    return "No logs for $DateText $($Hour.ToString('00')):00 - $($Hour.ToString('00')):59.`r`n$logPath"
  }}
  if ($output.Count -gt 500) {{
    $output = $output.GetRange($output.Count - 500, 500)
  }}
  return ($output -join "`r`n")
}}

$form = New-Object System.Windows.Forms.Form
$form.Text = "BuildGraph PC Agent"
$form.Size = New-Object System.Drawing.Size(820, 460)
$form.StartPosition = "CenterScreen"

$label = New-Object System.Windows.Forms.Label
$label.Text = "Collected logs by 1-hour range"
$label.AutoSize = $true
$label.Location = New-Object System.Drawing.Point(12, 12)
$form.Controls.Add($label)

$dateLabel = New-Object System.Windows.Forms.Label
$dateLabel.Text = "Date"
$dateLabel.AutoSize = $true
$dateLabel.Location = New-Object System.Drawing.Point(12, 42)
$form.Controls.Add($dateLabel)

$dateInput = New-Object System.Windows.Forms.TextBox
$dateInput.Location = New-Object System.Drawing.Point(54, 38)
$dateInput.Size = New-Object System.Drawing.Size(96, 22)
$dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
$form.Controls.Add($dateInput)

$hourLabel = New-Object System.Windows.Forms.Label
$hourLabel.Text = "Hour"
$hourLabel.AutoSize = $true
$hourLabel.Location = New-Object System.Drawing.Point(166, 42)
$form.Controls.Add($hourLabel)

$hourSelect = New-Object System.Windows.Forms.ComboBox
$hourSelect.DropDownStyle = "DropDownList"
$hourSelect.Location = New-Object System.Drawing.Point(208, 38)
$hourSelect.Size = New-Object System.Drawing.Size(72, 22)
0..23 | ForEach-Object {{ [void]$hourSelect.Items.Add(($_.ToString("00")) + ":00") }}
$hourSelect.SelectedIndex = [int](Get-Date).Hour
$form.Controls.Add($hourSelect)

$textbox = New-Object System.Windows.Forms.TextBox
$textbox.Multiline = $true
$textbox.ScrollBars = "Both"
$textbox.ReadOnly = $true
$textbox.Font = New-Object System.Drawing.Font("Consolas", 9)
$textbox.Location = New-Object System.Drawing.Point(12, 72)
$textbox.Size = New-Object System.Drawing.Size(780, 288)
$textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
$form.Controls.Add($textbox)

$refresh = New-Object System.Windows.Forms.Button
$refresh.Text = "Load hour"
$refresh.Location = New-Object System.Drawing.Point(292, 37)
$refresh.Size = New-Object System.Drawing.Size(88, 24)
$refresh.Add_Click({{ $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex }})
$form.Controls.Add($refresh)

$today = New-Object System.Windows.Forms.Button
$today.Text = "Now"
$today.Location = New-Object System.Drawing.Point(388, 37)
$today.Size = New-Object System.Drawing.Size(64, 24)
$today.Add_Click({{
  $dateInput.Text = (Get-Date).ToString("yyyy-MM-dd")
  $hourSelect.SelectedIndex = [int](Get-Date).Hour
  $textbox.Text = Get-FilteredLogText $dateInput.Text $hourSelect.SelectedIndex
}})
$form.Controls.Add($today)

$folder = New-Object System.Windows.Forms.Button
$folder.Text = "Open log folder"
$folder.Location = New-Object System.Drawing.Point(12, 374)
$folder.Size = New-Object System.Drawing.Size(120, 24)
$folder.Add_Click({{ Start-Process -FilePath $logDir }})
$form.Controls.Add($folder)

$support = New-Object System.Windows.Forms.Button
$support.Text = "Open AS page"
$support.Location = New-Object System.Drawing.Point(144, 374)
$support.Size = New-Object System.Drawing.Size(112, 24)
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


def show_log_viewer(config_path: Path) -> None:
    if tk is None or ttk is None:
        show_log_viewer_powershell(config_path)
        return

    config = load_config(config_path)
    config.log_dir.mkdir(parents=True, exist_ok=True)
    path = log_file(config)

    root = tk.Tk()
    root.title("BuildGraph PC Agent")
    root.geometry("760x420")
    root.minsize(640, 340)

    status = tk.StringVar(value="Loading recent logs...")
    header = ttk.Frame(root, padding=(12, 10, 12, 6))
    header.pack(fill="x")

    ttk.Label(header, text="BuildGraph PC Agent", font=("Segoe UI", 13, "bold")).pack(anchor="w")
    ttk.Label(header, textvariable=status).pack(anchor="w", pady=(4, 0))

    filters = ttk.Frame(root, padding=(12, 4, 12, 4))
    filters.pack(fill="x")
    ttk.Label(filters, text="Date").pack(side="left")
    date_value = tk.StringVar(value=datetime.now(KST).strftime("%Y-%m-%d"))
    date_entry = ttk.Entry(filters, textvariable=date_value, width=12)
    date_entry.pack(side="left", padx=(6, 14))
    ttk.Label(filters, text="Hour").pack(side="left")
    hour_value = tk.StringVar(value=f"{datetime.now(KST).hour:02d}:00")
    hour_select = ttk.Combobox(
        filters,
        textvariable=hour_value,
        values=[f"{hour:02d}:00" for hour in range(24)],
        width=7,
        state="readonly",
    )
    hour_select.pack(side="left", padx=(6, 14))

    columns = ("timestamp", "cpu", "memory", "event", "message")
    table_frame = ttk.Frame(root, padding=(12, 4, 12, 4))
    table_frame.pack(fill="both", expand=True)
    tree = ttk.Treeview(table_frame, columns=columns, show="headings", height=12)
    tree.heading("timestamp", text="Timestamp")
    tree.heading("cpu", text="CPU")
    tree.heading("memory", text="Memory")
    tree.heading("event", text="Event")
    tree.heading("message", text="Message")
    tree.column("timestamp", width=190, anchor="w")
    tree.column("cpu", width=70, anchor="e")
    tree.column("memory", width=80, anchor="e")
    tree.column("event", width=160, anchor="w")
    tree.column("message", width=240, anchor="w")

    scrollbar = ttk.Scrollbar(table_frame, orient="vertical", command=tree.yview)
    tree.configure(yscrollcommand=scrollbar.set)
    tree.pack(side="left", fill="both", expand=True)
    scrollbar.pack(side="right", fill="y")

    def refresh() -> None:
        try:
            selected_hour = int(hour_value.get().split(":", 1)[0])
        except ValueError:
            selected_hour = datetime.now(KST).hour
        rows = read_log_hour(path, date_value.get(), selected_hour, 500)
        tree.delete(*tree.get_children())
        for row in rows:
            tree.insert(
                "",
                "end",
                values=(
                    str(row.get("timestamp", "-")),
                    format_percent(row.get("cpuUsage")),
                    format_percent(row.get("memoryUsage")),
                    str(row.get("eventType", "-")),
                    str(row.get("message", "-")),
                ),
            )
        token_status = "present" if config.agent_token else "missing"
        status.set(
            f"Status: running | Agent token: {token_status} | "
            f"Range: {date_value.get()} {selected_hour:02d}:00 | Rows shown: {len(rows)} | Log file: {path}"
        )

    def set_current_hour() -> None:
        now = datetime.now(KST)
        date_value.set(now.strftime("%Y-%m-%d"))
        hour_value.set(f"{now.hour:02d}:00")
        refresh()

    buttons = ttk.Frame(root, padding=(12, 6, 12, 12))
    buttons.pack(fill="x")
    ttk.Button(buttons, text="Load hour", command=refresh).pack(side="left")
    ttk.Button(buttons, text="Now", command=set_current_hour).pack(side="left", padx=(8, 0))
    ttk.Button(buttons, text="Open log folder", command=lambda: open_log_folder(config_path)).pack(side="left", padx=(8, 0))
    ttk.Button(buttons, text="Open AS page", command=lambda: open_support_page(config_path)).pack(side="left", padx=(8, 0))

    refresh()
    root.mainloop()


def open_support_page(config_path: Path) -> None:
    config = load_config(config_path)
    webbrowser.open(support_new_url(config))


def collect_background_loop(config_path: Path, runtime: AgentRuntime, interval_seconds: int) -> None:
    while runtime.running:
        try:
            config = load_config(config_path)
            append_metric(config, runtime.index)
            runtime.index += 1
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
            "BuildGraph PC Agent",
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

    parser = argparse.ArgumentParser(description="BuildGraph AI PC Agent prototype CLI")
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
