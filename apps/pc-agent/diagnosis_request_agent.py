from __future__ import annotations

import json
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse, urlunparse

try:
    import websocket
except Exception:  # pragma: no cover - optional until packaged dependencies are installed
    websocket = None


AGENT_STATES = (
    "DISCONNECTED",
    "CONNECTING",
    "IDLE",
    "REQUEST_RECEIVED",
    "RUNNING",
    "COMPLETED",
    "FAILED",
)
ACTIVE_DIAGNOSIS_STATES = {"REQUEST_RECEIVED", "RUNNING"}
REQUEST_RESPONSE_STATUSES = {
    "ACCEPTED",
    "DUPLICATE",
    "EXPIRED",
    "DEVICE_MISMATCH",
    "AUTH_FAILED",
    "BUSY",
    "REJECTED",
}
DIAGNOSIS_SOCKET_PATH = "/ws/pc-agent/diagnosis"
PROCESSED_DIAGNOSIS_LIMIT = 200


def parse_server_datetime(value: Any) -> datetime | None:
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
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def diagnosis_websocket_url(api_base_url: str) -> str:
    parsed = urlparse(api_base_url.rstrip("/"))
    scheme = "wss" if parsed.scheme == "https" else "ws"
    return urlunparse((scheme, parsed.netloc, DIAGNOSIS_SOCKET_PATH, "", "", ""))


@dataclass(frozen=True)
class DiagnosisRequest:
    diagnosis_id: str
    device_id: str
    symptom: str
    requested_checks: tuple[str, ...]
    requested_at: str
    expires_at: str
    mode: str

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "DiagnosisRequest":
        requested_checks = payload.get("requestedChecks")
        if not isinstance(requested_checks, list) or not all(
            isinstance(value, str) and value.strip() for value in requested_checks
        ):
            raise ValueError("requestedChecks must be a non-empty string list")
        values = {
            "diagnosis_id": payload.get("diagnosisId"),
            "device_id": payload.get("deviceId"),
            "symptom": payload.get("symptom"),
            "requested_at": payload.get("requestedAt"),
            "expires_at": payload.get("expiresAt"),
            "mode": payload.get("mode"),
        }
        if any(not isinstance(value, str) or not value.strip() for value in values.values()):
            raise ValueError("diagnosis request has a missing text field")
        mode = str(values["mode"]).strip().upper()
        if mode not in {"LIVE", "DEMO"}:
            raise ValueError("mode must be LIVE or DEMO")
        if parse_server_datetime(values["requested_at"]) is None or parse_server_datetime(values["expires_at"]) is None:
            raise ValueError("requestedAt and expiresAt must be ISO-8601 timestamps")
        return cls(
            diagnosis_id=str(values["diagnosis_id"]).strip(),
            device_id=str(values["device_id"]).strip(),
            symptom=str(values["symptom"]).strip(),
            requested_checks=tuple(value.strip().lower() for value in requested_checks),
            requested_at=str(values["requested_at"]).strip(),
            expires_at=str(values["expires_at"]).strip(),
            mode=mode,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "diagnosisId": self.diagnosis_id,
            "deviceId": self.device_id,
            "symptom": self.symptom,
            "requestedChecks": list(self.requested_checks),
            "requestedAt": self.requested_at,
            "expiresAt": self.expires_at,
            "mode": self.mode,
        }


@dataclass(frozen=True)
class DiagnosisSession:
    request: DiagnosisRequest
    agent_state: str = "REQUEST_RECEIVED"

    def to_dict(self) -> dict[str, Any]:
        return {"agentState": self.agent_state, "request": self.request.to_dict()}

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisSession":
        request = payload.get("request")
        state = payload.get("agentState")
        if not isinstance(request, dict) or state not in AGENT_STATES:
            raise ValueError("invalid diagnosis session")
        return cls(DiagnosisRequest.from_payload(request), str(state))


@dataclass(frozen=True)
class DiagnosisDecision:
    status: str
    diagnosis_id: str | None
    message: str
    session: DiagnosisSession | None = None

    def response_frame(self) -> dict[str, Any]:
        frame: dict[str, Any] = {
            "type": "DIAGNOSIS_RESPONSE",
            "status": self.status,
            "message": self.message,
        }
        if self.diagnosis_id:
            frame["diagnosisId"] = self.diagnosis_id
        return frame


class DiagnosisSessionStore:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._processed_ids: list[str] = []
        self._session: DiagnosisSession | None = None
        self._load()

    @property
    def session(self) -> DiagnosisSession | None:
        with self._lock:
            return self._session

    def contains(self, diagnosis_id: str) -> bool:
        with self._lock:
            return diagnosis_id in self._processed_ids

    def is_busy(self) -> bool:
        with self._lock:
            return self._session is not None and self._session.agent_state in ACTIVE_DIAGNOSIS_STATES

    def accept(self, session: DiagnosisSession) -> None:
        with self._lock:
            self._session = session
            if session.request.diagnosis_id not in self._processed_ids:
                self._processed_ids.append(session.request.diagnosis_id)
                self._processed_ids = self._processed_ids[-PROCESSED_DIAGNOSIS_LIMIT:]
            self._save_locked()

    def update_state(self, state: str) -> None:
        if state not in AGENT_STATES:
            raise ValueError(f"unsupported Agent state: {state}")
        with self._lock:
            if self._session is None:
                return
            self._session = DiagnosisSession(self._session.request, state)
            self._save_locked()

    def _load(self) -> None:
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if not isinstance(payload, dict):
            return
        processed = payload.get("processedDiagnosisIds")
        if isinstance(processed, list):
            self._processed_ids = [value for value in processed if isinstance(value, str)][-PROCESSED_DIAGNOSIS_LIMIT:]
        session = payload.get("currentSession")
        if isinstance(session, dict):
            try:
                self._session = DiagnosisSession.from_dict(session)
            except ValueError:
                self._session = None

    def _save_locked(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = {
            "processedDiagnosisIds": self._processed_ids,
            "currentSession": self._session.to_dict() if self._session else None,
        }
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)


class DiagnosisRequestProcessor:
    def __init__(
        self,
        store: DiagnosisSessionStore,
        device_id: str | None = None,
        now: Callable[[], datetime] | None = None,
        on_request: Callable[[DiagnosisSession], None] | None = None,
        on_initial_metrics_requested: Callable[[DiagnosisSession], None] | None = None,
    ) -> None:
        self.store = store
        self.device_id = device_id
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.on_request = on_request or (lambda session: None)
        self.on_initial_metrics_requested = on_initial_metrics_requested or (lambda session: None)

    def bind_authenticated_device(self, device_id: str) -> bool:
        value = device_id.strip()
        if not value:
            return False
        if self.device_id and self.device_id != value:
            return False
        self.device_id = value
        return True

    def process(self, payload: dict[str, Any], authenticated: bool) -> DiagnosisDecision:
        diagnosis_id = payload.get("diagnosisId") if isinstance(payload.get("diagnosisId"), str) else None
        if not authenticated:
            return DiagnosisDecision("AUTH_FAILED", diagnosis_id, "인증되지 않은 요청입니다.")
        try:
            request = DiagnosisRequest.from_payload(payload)
        except ValueError as error:
            return DiagnosisDecision("REJECTED", diagnosis_id, str(error))
        if not self.device_id or request.device_id != self.device_id:
            return DiagnosisDecision("DEVICE_MISMATCH", request.diagnosis_id, "요청 장치가 현재 Agent와 일치하지 않습니다.")
        if self.store.contains(request.diagnosis_id):
            return DiagnosisDecision("DUPLICATE", request.diagnosis_id, "이미 처리한 진단 요청입니다.")
        expires_at = parse_server_datetime(request.expires_at)
        if expires_at is None or expires_at <= self.now().astimezone(timezone.utc):
            return DiagnosisDecision("EXPIRED", request.diagnosis_id, "만료된 진단 요청입니다.")
        if self.store.is_busy():
            return DiagnosisDecision("BUSY", request.diagnosis_id, "다른 진단을 처리 중입니다.")
        session = DiagnosisSession(request=request)
        self.store.accept(session)
        self.on_request(session)
        self.on_initial_metrics_requested(session)
        return DiagnosisDecision("ACCEPTED", request.diagnosis_id, "진단 요청을 수신했습니다.", session)


class AgentDiagnosisWebSocketClient:
    BACKOFF_SECONDS = (1, 2, 5, 10, 30)

    def __init__(
        self,
        api_base_url: str,
        agent_token: str,
        processor: DiagnosisRequestProcessor,
        on_state_changed: Callable[[str], None] | None = None,
        on_device_identified: Callable[[str], None] | None = None,
        websocket_factory: Callable[..., Any] | None = None,
    ) -> None:
        self.url = diagnosis_websocket_url(api_base_url)
        self.agent_token = agent_token
        self.processor = processor
        self.on_state_changed = on_state_changed or (lambda state: None)
        self.on_device_identified = on_device_identified or (lambda device_id: None)
        self.websocket_factory = websocket_factory or (websocket.WebSocketApp if websocket is not None else None)
        self.stop_event = threading.Event()
        self.authenticated = False
        self.ready_once = False
        self.state = "DISCONNECTED"
        self._thread: threading.Thread | None = None
        self._socket: Any = None

    def start(self) -> None:
        if self.websocket_factory is None:
            self._set_state("FAILED")
            return
        if self._thread and self._thread.is_alive():
            return
        self.stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="pc-agent-diagnosis-websocket", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self.stop_event.set()
        socket_app = self._socket
        if socket_app is not None:
            try:
                socket_app.close()
            except Exception:
                pass
        self._set_state("DISCONNECTED")

    def _run(self) -> None:
        attempt = 0
        while not self.stop_event.is_set():
            self.authenticated = False
            self.ready_once = False
            self._set_state("CONNECTING")
            socket_app = self.websocket_factory(
                self.url,
                on_open=self._on_open,
                on_message=self._on_message,
                on_error=self._on_error,
                on_close=self._on_close,
            )
            self._socket = socket_app
            try:
                socket_app.run_forever(ping_interval=30, ping_timeout=10, suppress_origin=True)
            except Exception:
                self._set_state("DISCONNECTED")
            if self.stop_event.is_set():
                break
            delay = self.BACKOFF_SECONDS[min(attempt, len(self.BACKOFF_SECONDS) - 1)]
            attempt = 0 if self.ready_once else attempt + 1
            self.stop_event.wait(delay)

    def _on_open(self, socket_app: Any) -> None:
        socket_app.send(json.dumps({"type": "AUTH", "agentToken": self.agent_token}))

    def _on_message(self, socket_app: Any, raw_message: str) -> None:
        try:
            frame = json.loads(raw_message)
        except (TypeError, json.JSONDecodeError):
            return
        if not isinstance(frame, dict):
            return
        frame_type = frame.get("type")
        if frame_type == "READY":
            detail = frame.get("detail")
            device_id = detail.get("deviceId") if isinstance(detail, dict) else None
            if not isinstance(device_id, str) or not self.processor.bind_authenticated_device(device_id):
                self._set_state("FAILED")
                socket_app.close()
                return
            self.on_device_identified(device_id)
            self.authenticated = True
            self.ready_once = True
            self._set_state("REQUEST_RECEIVED" if self.processor.store.is_busy() else "IDLE")
            return
        if frame_type == "DIAGNOSIS_REQUEST":
            detail = frame.get("detail")
            payload = detail if isinstance(detail, dict) else {}
            decision = self.processor.process(payload, self.authenticated)
            socket_app.send(json.dumps(decision.response_frame(), ensure_ascii=False))
            if decision.status == "ACCEPTED":
                self._set_state("REQUEST_RECEIVED")
            return
        if frame_type == "ERROR" and frame.get("code") in {"AUTH_FAILED", "AGENT_FORBIDDEN"}:
            self.authenticated = False
            self._set_state("FAILED")
            socket_app.close()

    def _on_error(self, socket_app: Any, error: Any) -> None:
        if self.state != "FAILED":
            self._set_state("DISCONNECTED")

    def _on_close(self, socket_app: Any, status_code: Any, message: Any) -> None:
        self.authenticated = False
        if self.state != "FAILED":
            self._set_state("DISCONNECTED")

    def _set_state(self, state: str) -> None:
        if state not in AGENT_STATES:
            raise ValueError(f"unsupported Agent state: {state}")
        self.state = state
        self.on_state_changed(state)


class BackgroundViewerController:
    def __init__(
        self,
        config_path: Path,
        diagnosis_session_provider: Callable[[], DiagnosisSession | None],
        connection_state_provider: Callable[[], str],
        show_viewer: Callable[..., None],
    ) -> None:
        self.config_path = config_path
        self.diagnosis_session_provider = diagnosis_session_provider
        self.connection_state_provider = connection_state_provider
        self.show_viewer = show_viewer
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._request_focus: Any = None
        self._request_apply_session: Any = None
        self._request_destroy: Any = None

    def show(self, session: DiagnosisSession | None = None) -> None:
        with self._lock:
            request_focus = self._request_focus
            request_apply_session = self._request_apply_session
            if request_focus is None:
                if self._thread is None or not self._thread.is_alive():
                    self._thread = threading.Thread(target=self._run, name="pc-agent-viewer", daemon=True)
                    self._thread.start()
                return
        if session is not None and request_apply_session is not None:
            request_apply_session(session)
        request_focus()

    def shutdown(self) -> None:
        with self._lock:
            request_destroy = self._request_destroy
        if request_destroy is not None:
            request_destroy()

    def _run(self) -> None:
        self.show_viewer(
            self.config_path,
            background_mode=True,
            diagnosis_session_provider=self.diagnosis_session_provider,
            connection_state_provider=self.connection_state_provider,
            on_window_ready=self._on_window_ready,
            on_window_closed=self._on_window_closed,
        )

    def _on_window_ready(self, request_focus: Any, request_apply_session: Any, request_destroy: Any) -> None:
        with self._lock:
            self._request_focus = request_focus
            self._request_apply_session = request_apply_session
            self._request_destroy = request_destroy
        session = self.diagnosis_session_provider()
        if isinstance(session, DiagnosisSession):
            request_apply_session(session)
        request_focus()

    def _on_window_closed(self) -> None:
        with self._lock:
            self._request_focus = None
            self._request_apply_session = None
            self._request_destroy = None


class ViewerRequestSignal:
    def __init__(self, path: Path, restrict_file: Callable[[Path], None]) -> None:
        self.path = path
        self.restrict_file = restrict_file

    def signal(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f"{self.path.name}.{uuid.uuid4().hex}.tmp")
        try:
            temporary.write_text(json.dumps({"requestId": str(uuid.uuid4())}) + "\n", encoding="utf-8")
            temporary.replace(self.path)
        finally:
            temporary.unlink(missing_ok=True)
        self.restrict_file(self.path)

    def monitor(self, runtime: Any, controller: BackgroundViewerController) -> None:
        try:
            last_request = self.path.read_text(encoding="utf-8") if self.path.exists() else ""
        except OSError:
            last_request = ""
        while runtime.running:
            try:
                current_request = self.path.read_text(encoding="utf-8") if self.path.exists() else ""
                if current_request and current_request != last_request:
                    last_request = current_request
                    controller.show()
            except OSError:
                pass
            time.sleep(0.25)
