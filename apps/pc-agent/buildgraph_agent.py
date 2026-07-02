from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import mimetypes
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
import webbrowser
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Sequence

try:
    import psutil
except Exception:  # pragma: no cover - optional for prototype environments
    psutil = None

KST = timezone(timedelta(hours=9))
DEFAULT_CONFIG_PATH = Path("agent-config.json")
DEFAULT_LOG_DIR = Path("out/logs")
DEFAULT_LOG_FILE = "agent-metrics.jsonl"
DEFAULT_RANGE_MINUTES = 30
DEFAULT_SCHEMA_VERSION = 1
REGISTER_PATH = "/api/agent/devices/register"
LOG_UPLOAD_PATH = "/api/agent/log-uploads"
REGISTERED_STATUS = "REGISTERED"
UNREGISTERED_STATUS = "UNREGISTERED"


class ConfigError(ValueError):
    pass


class RegisterError(RuntimeError):
    pass


class AgentError(RuntimeError):
    pass


class UploadError(AgentError):
    pass


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
        with path.open("r", encoding="utf-8") as file:
            data = json.load(file)
    except json.JSONDecodeError as exception:
        raise ConfigError(f"Config file is not valid JSON: {path}: {exception.msg}") from exception
    if not isinstance(data, dict):
        raise ConfigError("Config file root must be a JSON object.")
    return data


def load_config(path: Path) -> AgentConfig:
    data = read_config_json(path)
    return AgentConfig.from_dict(data)


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


def write_sample(out: Path, count: int, interval_seconds: int) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    start = datetime.now(KST) - timedelta(seconds=count * interval_seconds)
    with out.open("w", encoding="utf-8") as file:
        for index in range(count):
            row = metric_snapshot(start + timedelta(seconds=index * interval_seconds), index)
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def read_recent_rows(source: Path, minutes: int) -> list[dict]:
    cutoff = datetime.now(KST) - timedelta(minutes=minutes)
    rows: list[dict] = []
    with source.open("r", encoding="utf-8") as file:
        for line in file:
            if not line.strip():
                continue
            row = json.loads(line)
            try:
                ts = datetime.fromisoformat(row["timestamp"])
            except ValueError:
                continue
            if ts >= cutoff:
                rows.append(row)
    return rows


def export_recent(source: Path, out: Path, minutes: int) -> None:
    rows = read_recent_rows(source, minutes)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")


def append_metric(config: AgentConfig, index: int = 0) -> Path:
    path = log_file(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    row = metric_snapshot(datetime.now(KST), index)
    row["agentVersion"] = config.agent_version
    row["policyVersion"] = config.policy_version
    with path.open("a", encoding="utf-8") as file:
        file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return path


def gzip_recent(source: Path, out: Path, minutes: int = DEFAULT_RANGE_MINUTES) -> int:
    rows = read_recent_rows(source, minutes)
    out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out, "wt", encoding="utf-8") as file:
        for row in rows:
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
    return out.stat().st_size


def support_url(api_base_url: str, ticket_id: str) -> str:
    base = api_base_url.rstrip("/")
    if base.endswith(":8080"):
        base = base[:-5] + ":5173"
    return f"{base}/support/{ticket_id}"


def build_multipart(fields: dict[str, str], file_field: str, file_path: Path) -> tuple[bytes, str]:
    boundary = f"----buildgraph-agent-{uuid.uuid4().hex}"
    parts: list[bytes] = []
    for name, value in fields.items():
        parts.append(
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
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
) -> dict:
    if not config.agent_token:
        raise UploadError("agentToken is missing. Run register first or wait for Goal 10 token storage.")
    if gzip_path.stat().st_size == 0:
        raise UploadError(f"gzip file is empty: {gzip_path}")

    fields = {
        "rangeMinutes": str(DEFAULT_RANGE_MINUTES),
        "schemaVersion": str(config.schema_version),
    }
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


def upload_recent(
    config: AgentConfig,
    work_dir: Path,
    symptom: str | None,
    idempotency_key: str | None,
    open_browser: bool,
) -> None:
    source = log_file(config)
    if not source.exists():
        raise AgentError(f"log file does not exist: {source}")
    key = idempotency_key or f"agent-upload-{uuid.uuid4()}"
    gzip_path = work_dir / "recent-30m.jsonl.gz"
    size = gzip_recent(source, gzip_path, DEFAULT_RANGE_MINUTES)
    print(f"created gzip: {gzip_path} ({size} bytes)")
    print(f"upload path: {LOG_UPLOAD_PATH}")
    print(f"rangeMinutes: {DEFAULT_RANGE_MINUTES}")
    print(f"Idempotency-Key: {key}")
    print("Replay the same command with this Idempotency-Key to verify duplicate ticket prevention.")
    result = upload_gzip(config, gzip_path, key, symptom)
    ticket_id = str(result["ticketId"])
    url = support_url(config.api_base_url, ticket_id)
    print(f"ticketId: {ticket_id}")
    print(f"supportUrl: {url}")
    if open_browser:
        webbrowser.open(url)
        print("opened support ticket in default browser")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="BuildGraph AI PC Agent prototype CLI")
    sub = parser.add_subparsers(dest="command", required=True)

    sample = sub.add_parser("sample", help="generate sample JSONL hardware metrics")
    sample.add_argument("--out", type=Path, default=Path("sample-agent-log.jsonl"))
    sample.add_argument("--count", type=int, default=24)
    sample.add_argument("--interval-seconds", type=int, default=5)

    export = sub.add_parser("export", help="export recent JSONL rows")
    export.add_argument("--source", type=Path, required=True)
    export.add_argument("--out", type=Path, default=Path("recent-30m.jsonl"))
    export.add_argument("--minutes", type=int, default=30)

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

    upload = sub.add_parser("upload", help="gzip recent 30 minute JSONL rows and upload to Agent AS API")
    upload.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH)
    upload.add_argument("--work-dir", type=Path, default=Path("out"))
    upload.add_argument("--symptom", default=None)
    upload.add_argument("--idempotency-key", default=None)
    upload.add_argument("--no-open", action="store_true", help="do not open /support/{ticketId} in the default browser")

    args = parser.parse_args(argv)

    try:
        if args.command == "sample":
            write_sample(args.out, args.count, args.interval_seconds)
            print(f"wrote {args.out}")
        elif args.command == "export":
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
            upload_recent(config, args.work_dir, args.symptom, args.idempotency_key, not args.no_open)
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
