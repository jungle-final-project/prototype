from __future__ import annotations

import json
import queue
import threading
import time
import uuid
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from initial_metrics import (
    AVAILABLE,
    FAILED,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    MetricReading,
    MetricsSnapshot,
)


SESSION_STATES = (
    "RECEIVED",
    "COLLECTING",
    "DIAGNOSING",
    "EVALUATING",
    "COMPLETED",
    "PARTIALLY_COMPLETED",
    "FAILED",
    "CANCELLED",
    "TIMED_OUT",
)
TASK_STATUSES = (
    "PENDING",
    "RUNNING",
    "COMPLETED",
    "UNSUPPORTED",
    "FAILED",
    "TIMED_OUT",
    "CANCELLED",
)
TERMINAL_TASK_STATUSES = {
    "COMPLETED",
    "UNSUPPORTED",
    "FAILED",
    "TIMED_OUT",
    "CANCELLED",
}
EVENT_TYPES = (
    "DIAGNOSIS_STARTED",
    "TASK_STARTED",
    "TASK_COMPLETED",
    "TASK_UNSUPPORTED",
    "TASK_FAILED",
    "TASK_TIMED_OUT",
    "PROGRESS_UPDATED",
    "DIAGNOSIS_EVALUATION_STARTED",
    "DIAGNOSIS_COMPLETED",
    "DIAGNOSIS_FAILED",
    "DIAGNOSIS_CANCELLED",
)
FINAL_SESSION_STATES = {"COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"}
MAX_STORED_EVENTS = 512


@dataclass(frozen=True)
class DiagnosisSettings:
    task_weights: Mapping[str, int] = field(default_factory=lambda: {
        "initial_snapshot": 10,
        "cpu_health": 10,
        "gpu_usage_temperature": 20,
        "gpu_cooling_fan": 20,
        "ram_health": 10,
        "disk_health": 15,
        "thermal_clock": 10,
        "evidence_finalize": 5,
    })
    task_timeout_seconds: float = 8.0
    session_timeout_seconds: float = 60.0
    max_retries: int = 1


DEFAULT_DIAGNOSIS_SETTINGS = DiagnosisSettings()

GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS = (
    ("current_system_status", "system", False),
    ("windows_display_devices", "gpu", False),
    ("windows_display_drivers", "gpu", False),
    ("windows_graphics_events", "gpu", False),
    ("windows_whea_events", "hardware", False),
    ("symptom_correlation", "system", False),
    ("evidence_finalize", "system", True),
    ("final_classification", "system", True),
)

GRAPHICS_DIAGNOSIS_TASK_LABELS = {
    "current_system_status": "현재 시스템 상태 확인",
    "windows_display_devices": "그래픽 장치 PnP 상태 확인",
    "windows_display_drivers": "그래픽 드라이버 정보 확인",
    "windows_graphics_events": "최근 그래픽 응답 중단·복구 기록 확인",
    "windows_whea_events": "최근 WHEA 하드웨어 오류 확인",
    "symptom_correlation": "증상과 오류 시각·구성요소 비교",
    "evidence_finalize": "진단 증거 종합",
    "final_classification": "최종 문제 분류",
}

GRAPHICS_DIAGNOSIS_TASK_WEIGHTS = {
    "current_system_status": 15,
    "windows_display_devices": 20,
    "windows_display_drivers": 10,
    "windows_graphics_events": 10,
    "windows_whea_events": 10,
    "symptom_correlation": 10,
    "evidence_finalize": 10,
    "final_classification": 15,
}


@dataclass(frozen=True)
class DiagnosisTask:
    task_id: str
    component: str
    weight: int
    required: bool = False
    status: str = "PENDING"
    started_at: str | None = None
    completed_at: str | None = None
    evidence: tuple[dict[str, Any], ...] = ()
    error_code: str | None = None
    failure_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "taskId": self.task_id,
            "component": self.component,
            "weight": self.weight,
            "required": self.required,
            "status": self.status,
            "startedAt": self.started_at,
            "completedAt": self.completed_at,
            "evidence": list(self.evidence),
            "errorCode": self.error_code,
            "failureReason": self.failure_reason,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisTask":
        evidence = payload.get("evidence")
        return cls(
            task_id=str(payload["taskId"]),
            component=str(payload["component"]),
            weight=int(payload["weight"]),
            required=bool(payload.get("required")),
            status=str(payload.get("status") or "PENDING"),
            started_at=str(payload["startedAt"]) if payload.get("startedAt") else None,
            completed_at=str(payload["completedAt"]) if payload.get("completedAt") else None,
            evidence=tuple(item for item in evidence if isinstance(item, dict)) if isinstance(evidence, list) else (),
            error_code=str(payload["errorCode"]) if payload.get("errorCode") else None,
            failure_reason=str(payload["failureReason"]) if payload.get("failureReason") else None,
        )


@dataclass(frozen=True)
class DiagnosisEvent:
    diagnosis_id: str
    event_id: str
    event_type: str
    task_id: str | None
    component: str | None
    timestamp: str
    message: str
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "diagnosisId": self.diagnosis_id,
            "eventId": self.event_id,
            "eventType": self.event_type,
            "taskId": self.task_id,
            "component": self.component,
            "timestamp": self.timestamp,
            "message": self.message,
            "metadata": dict(self.metadata),
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisEvent":
        metadata = payload.get("metadata")
        return cls(
            diagnosis_id=str(payload["diagnosisId"]),
            event_id=str(payload["eventId"]),
            event_type=str(payload["eventType"]),
            task_id=str(payload["taskId"]) if payload.get("taskId") else None,
            component=str(payload["component"]) if payload.get("component") else None,
            timestamp=str(payload["timestamp"]),
            message=str(payload.get("message") or ""),
            metadata=dict(metadata) if isinstance(metadata, dict) else {},
        )


@dataclass(frozen=True)
class DiagnosisRunSnapshot:
    diagnosis_id: str | None = None
    mode: str | None = None
    requested_checks: tuple[str, ...] = ()
    state: str = "RECEIVED"
    progress: int = 0
    current_task_id: str | None = None
    tasks: tuple[DiagnosisTask, ...] = ()
    events: tuple[DiagnosisEvent, ...] = ()
    started_at: str | None = None
    completed_at: str | None = None
    transition_allowed: bool = False
    retry_count: int = 0

    def task(self, task_id: str) -> DiagnosisTask | None:
        return next((task for task in self.tasks if task.task_id == task_id), None)

    def component_tasks(self, component: str) -> tuple[DiagnosisTask, ...]:
        return tuple(task for task in self.tasks if task.component == component)

    def to_dict(self) -> dict[str, Any]:
        return {
            "diagnosisId": self.diagnosis_id,
            "mode": self.mode,
            "requestedChecks": list(self.requested_checks),
            "state": self.state,
            "progress": self.progress,
            "currentTaskId": self.current_task_id,
            "tasks": [task.to_dict() for task in self.tasks],
            "events": [event.to_dict() for event in self.events],
            "startedAt": self.started_at,
            "completedAt": self.completed_at,
            "transitionAllowed": self.transition_allowed,
            "retryCount": self.retry_count,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> "DiagnosisRunSnapshot":
        tasks = payload.get("tasks")
        events = payload.get("events")
        requested_checks = payload.get("requestedChecks")
        state = str(payload.get("state") or "RECEIVED")
        if state not in SESSION_STATES:
            raise ValueError("invalid diagnosis state")
        return cls(
            diagnosis_id=str(payload["diagnosisId"]) if payload.get("diagnosisId") else None,
            mode=str(payload["mode"]) if payload.get("mode") else None,
            requested_checks=tuple(str(item) for item in requested_checks) if isinstance(requested_checks, list) else (),
            state=state,
            progress=max(0, min(100, int(payload.get("progress") or 0))),
            current_task_id=str(payload["currentTaskId"]) if payload.get("currentTaskId") else None,
            tasks=tuple(DiagnosisTask.from_dict(item) for item in tasks if isinstance(item, dict)) if isinstance(tasks, list) else (),
            events=tuple(DiagnosisEvent.from_dict(item) for item in events if isinstance(item, dict)) if isinstance(events, list) else (),
            started_at=str(payload["startedAt"]) if payload.get("startedAt") else None,
            completed_at=str(payload["completedAt"]) if payload.get("completedAt") else None,
            transition_allowed=bool(payload.get("transitionAllowed")),
            retry_count=max(0, int(payload.get("retryCount") or 0)),
        )


@dataclass(frozen=True)
class TaskOutcome:
    status: str
    evidence: tuple[dict[str, Any], ...] = ()
    error_code: str | None = None
    failure_reason: str | None = None


class ProgressCalculator:
    def calculate(self, tasks: tuple[DiagnosisTask, ...], previous: int = 0) -> int:
        total = sum(max(0, task.weight) for task in tasks)
        if total <= 0:
            return previous
        terminal_weight = sum(task.weight for task in tasks if task.status in TERMINAL_TASK_STATUSES)
        calculated = min(100, int(terminal_weight * 100 / total))
        final_task = next((task for task in tasks if task.task_id == "evidence_finalize"), None)
        if calculated >= 100 and (final_task is None or final_task.status != "COMPLETED"):
            calculated = 99
        return max(previous, calculated)


class DiagnosisLogStore:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self._lock = threading.Lock()
        self._snapshot = DiagnosisRunSnapshot()
        self._load()

    @property
    def snapshot(self) -> DiagnosisRunSnapshot:
        with self._lock:
            return self._snapshot

    def replace(self, snapshot: DiagnosisRunSnapshot, reset: bool = False) -> DiagnosisRunSnapshot:
        with self._lock:
            if not reset and snapshot.diagnosis_id and snapshot.diagnosis_id == self._snapshot.diagnosis_id:
                seen = {event.event_id for event in self._snapshot.events}
                merged_events = list(self._snapshot.events)
                merged_events.extend(event for event in snapshot.events if event.event_id not in seen)
                snapshot = replace(
                    snapshot,
                    progress=max(self._snapshot.progress, snapshot.progress),
                    events=tuple(merged_events[-MAX_STORED_EVENTS:]),
                )
            self._snapshot = snapshot
            self._save_locked()
            return self._snapshot

    def _load(self) -> None:
        if self.path is None:
            return
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                self._snapshot = DiagnosisRunSnapshot.from_dict(payload)
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
            self._snapshot = DiagnosisRunSnapshot()

    def _save_locked(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(self._snapshot.to_dict(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(self.path)


TaskHandler = Callable[[DiagnosisTask, MetricsSnapshot, tuple[DiagnosisTask, ...]], TaskOutcome]


class DiagnosisOrchestrator:
    TASK_DEFINITIONS = (
        ("initial_snapshot", "system", True),
        ("cpu_health", "cpu", False),
        ("gpu_usage_temperature", "gpu", False),
        ("gpu_cooling_fan", "gpu", False),
        ("ram_health", "ram", False),
        ("disk_health", "disk", False),
        ("thermal_clock", "thermal", False),
        ("evidence_finalize", "system", True),
    )

    TASK_LABELS = {
        "initial_snapshot": "초기 센서 스냅샷 확정",
        "cpu_health": "CPU 상태 검사",
        "gpu_usage_temperature": "GPU 사용률·온도 검사",
        "gpu_cooling_fan": "GPU 냉각·팬 상태 검사",
        "ram_health": "RAM 상태 검사",
        "disk_health": "디스크 상태 검사",
        "thermal_clock": "열 제한·클럭 저하 검사",
        "evidence_finalize": "최종 판정 증거 정리",
        **GRAPHICS_DIAGNOSIS_TASK_LABELS,
    }

    def __init__(
        self,
        metrics_snapshot_provider: Callable[[], MetricsSnapshot],
        store: DiagnosisLogStore,
        settings: DiagnosisSettings = DEFAULT_DIAGNOSIS_SETTINGS,
        progress_calculator: ProgressCalculator | None = None,
        task_handlers: Mapping[str, TaskHandler] | None = None,
        task_definitions: tuple[tuple[str, str, bool], ...] | None = None,
        task_labels: Mapping[str, str] | None = None,
        on_update: Callable[[DiagnosisRunSnapshot], None] | None = None,
        on_complete: Callable[[DiagnosisRunSnapshot], None] | None = None,
        now: Callable[[], datetime] | None = None,
        monotonic: Callable[[], float] | None = None,
    ) -> None:
        self.metrics_snapshot_provider = metrics_snapshot_provider
        self.store = store
        self.settings = settings
        self.progress_calculator = progress_calculator or ProgressCalculator()
        self.task_handlers = dict(task_handlers or {})
        self.task_definitions = tuple(task_definitions or self.TASK_DEFINITIONS)
        self.task_labels = {**self.TASK_LABELS, **dict(task_labels or {})}
        self.on_update = on_update or (lambda snapshot: None)
        self.on_complete = on_complete or (lambda snapshot: None)
        self.now = now or (lambda: datetime.now(timezone.utc))
        self.monotonic = monotonic or time.monotonic
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._cancel = threading.Event()

    def prepare(
        self,
        diagnosis_id: str,
        mode: str,
        requested_checks: tuple[str, ...] = (),
        reset: bool = False,
    ) -> bool:
        normalized_mode = mode.upper()
        if normalized_mode not in {"LIVE", "DEMO"}:
            raise ValueError("mode must be LIVE or DEMO")
        with self._lock:
            existing = self.store.snapshot
            if existing.diagnosis_id == diagnosis_id and not reset:
                return False
            tasks = tuple(
                DiagnosisTask(task_id, component, self.settings.task_weights[task_id], required)
                for task_id, component, required in self.task_definitions
            )
            snapshot = DiagnosisRunSnapshot(
                diagnosis_id=diagnosis_id,
                mode=normalized_mode,
                requested_checks=tuple(requested_checks),
                state="COLLECTING",
                tasks=tasks,
            )
            self._publish(snapshot, reset=reset)
            return True

    def start(self, diagnosis_id: str, mode: str, requested_checks: tuple[str, ...] = ()) -> bool:
        normalized_mode = mode.upper()
        if normalized_mode not in {"LIVE", "DEMO"}:
            raise ValueError("mode must be LIVE or DEMO")
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return False
            existing = self.store.snapshot
            if existing.diagnosis_id == diagnosis_id and existing.state in {"COLLECTING", "DIAGNOSING", "EVALUATING"}:
                tasks = tuple(
                    replace(task, status="PENDING", started_at=None, completed_at=None)
                    if task.status == "RUNNING" else task
                    for task in existing.tasks
                )
                snapshot = replace(
                    existing,
                    state="DIAGNOSING",
                    tasks=tasks,
                    current_task_id=None,
                    started_at=existing.started_at or self._timestamp(),
                )
            else:
                tasks = tuple(
                    DiagnosisTask(task_id, component, self.settings.task_weights[task_id], required)
                    for task_id, component, required in self.task_definitions
                )
                snapshot = DiagnosisRunSnapshot(
                    diagnosis_id=diagnosis_id,
                    mode=normalized_mode,
                    requested_checks=tuple(requested_checks),
                    state="DIAGNOSING",
                    tasks=tasks,
                    started_at=self._timestamp(),
                )
            self._cancel.clear()
            snapshot = self._append_event(
                snapshot,
                "DIAGNOSIS_STARTED",
                "하드웨어 진단을 시작했습니다.",
                metadata={"mode": normalized_mode, "retryCount": snapshot.retry_count},
            )
            self._publish(snapshot)
            self._thread = threading.Thread(
                target=self._run,
                args=(diagnosis_id,),
                name="pc-agent-diagnosis-orchestrator",
                daemon=True,
            )
            self._thread.start()
            return True

    def cancel(self) -> bool:
        with self._lock:
            if self._thread is None or not self._thread.is_alive():
                return False
            self._cancel.set()
            return True

    def retry(self) -> bool:
        snapshot = self.store.snapshot
        if snapshot.state not in {"FAILED", "TIMED_OUT"} or snapshot.retry_count >= self.settings.max_retries:
            return False
        reset_tasks = tuple(
            replace(
                task,
                status="PENDING",
                started_at=None,
                completed_at=None,
                evidence=(),
                error_code=None,
                failure_reason=None,
            )
            if task.status in {"FAILED", "TIMED_OUT", "CANCELLED"} or task.task_id == "evidence_finalize"
            else task
            for task in snapshot.tasks
        )
        self.store.replace(replace(
            snapshot,
            state="DIAGNOSING",
            current_task_id=None,
            completed_at=None,
            transition_allowed=False,
            retry_count=snapshot.retry_count + 1,
            tasks=reset_tasks,
        ))
        return self.start(
            str(snapshot.diagnosis_id),
            str(snapshot.mode),
            snapshot.requested_checks,
        )

    def wait(self, timeout: float | None = None) -> bool:
        thread = self._thread
        if thread is None:
            return True
        thread.join(timeout)
        return not thread.is_alive()

    def _run(self, diagnosis_id: str) -> None:
        started = self.monotonic()
        try:
            while True:
                snapshot = self.store.snapshot
                if snapshot.diagnosis_id != diagnosis_id:
                    return
                if self._cancel.is_set():
                    self._finish_cancelled(snapshot)
                    return
                next_task = next((task for task in snapshot.tasks if task.status == "PENDING"), None)
                if next_task is None:
                    self._finish_session(snapshot)
                    return
                remaining = self.settings.session_timeout_seconds - (self.monotonic() - started)
                if remaining <= 0:
                    self._finish_timed_out(snapshot)
                    return
                if next_task.task_id == "evidence_finalize" and snapshot.state != "EVALUATING":
                    snapshot = replace(snapshot, state="EVALUATING")
                    snapshot = self._append_event(
                        snapshot,
                        "DIAGNOSIS_EVALUATION_STARTED",
                        "진단 증거 정리를 시작했습니다.",
                        task=next_task,
                    )
                started_task = replace(next_task, status="RUNNING", started_at=self._timestamp())
                snapshot = self._replace_task(snapshot, started_task, current_task_id=started_task.task_id)
                snapshot = self._append_event(
                    snapshot,
                    "TASK_STARTED",
                    f"{self.task_labels[started_task.task_id]}을 시작했습니다.",
                    task=started_task,
                )
                self._publish(snapshot)
                timeout = min(self.settings.task_timeout_seconds, remaining)
                outcome = self._execute_task(started_task, snapshot.tasks, timeout)
                if self._cancel.is_set():
                    self._finish_cancelled(self.store.snapshot)
                    return
                completed_task = replace(
                    started_task,
                    status=outcome.status,
                    completed_at=self._timestamp(),
                    evidence=outcome.evidence,
                    error_code=outcome.error_code,
                    failure_reason=outcome.failure_reason,
                )
                snapshot = self._replace_task(self.store.snapshot, completed_task, current_task_id=None)
                event_type, message = self._task_completion_event(completed_task)
                snapshot = self._append_event(snapshot, event_type, message, task=completed_task)
                progress = self.progress_calculator.calculate(snapshot.tasks, snapshot.progress)
                if progress != snapshot.progress:
                    snapshot = replace(snapshot, progress=progress)
                    snapshot = self._append_event(
                        snapshot,
                        "PROGRESS_UPDATED",
                        f"진단 진행률이 {progress}%로 업데이트되었습니다.",
                        task=completed_task,
                        metadata={"progress": progress},
                    )
                self._publish(snapshot)
        except Exception:
            snapshot = self.store.snapshot
            if snapshot.diagnosis_id == diagnosis_id and snapshot.state not in FINAL_SESSION_STATES:
                snapshot = replace(snapshot, state="FAILED", current_task_id=None, completed_at=self._timestamp())
                snapshot = self._append_event(
                    snapshot,
                    "DIAGNOSIS_FAILED",
                    "진단 실행 중 내부 오류가 발생했습니다.",
                    metadata={"errorCode": "DIAGNOSIS_INTERNAL_ERROR"},
                )
                self._publish(snapshot)
                self.on_complete(snapshot)

    def _execute_task(
        self,
        task: DiagnosisTask,
        tasks: tuple[DiagnosisTask, ...],
        timeout: float,
    ) -> TaskOutcome:
        result_queue: queue.Queue[TaskOutcome] = queue.Queue(maxsize=1)

        def execute() -> None:
            try:
                handler = self.task_handlers.get(task.task_id, self._default_task_handler)
                result_queue.put(handler(task, self.metrics_snapshot_provider(), tasks))
            except Exception:
                result_queue.put(TaskOutcome("FAILED", error_code="TASK_FAILED", failure_reason="검사 실행에 실패했습니다."))

        worker = threading.Thread(target=execute, name=f"pc-agent-diagnosis-{task.task_id}", daemon=True)
        worker.start()
        deadline = self.monotonic() + max(0.0, timeout)
        while worker.is_alive() and self.monotonic() < deadline and not self._cancel.is_set():
            worker.join(min(0.02, max(0.0, deadline - self.monotonic())))
        if self._cancel.is_set():
            return TaskOutcome("CANCELLED", error_code="TASK_CANCELLED", failure_reason="사용자가 진단을 취소했습니다.")
        if worker.is_alive():
            return TaskOutcome("TIMED_OUT", error_code="TASK_TIMEOUT", failure_reason="검사 시간이 초과되었습니다.")
        try:
            outcome = result_queue.get_nowait()
        except queue.Empty:
            return TaskOutcome("FAILED", error_code="TASK_FAILED", failure_reason="검사 결과를 확인할 수 없습니다.")
        if outcome.status not in TASK_STATUSES or outcome.status in {"PENDING", "RUNNING"}:
            return TaskOutcome("FAILED", error_code="INVALID_TASK_RESULT", failure_reason="검사 결과 상태가 올바르지 않습니다.")
        return outcome

    def _default_task_handler(
        self,
        task: DiagnosisTask,
        metrics: MetricsSnapshot,
        tasks: tuple[DiagnosisTask, ...],
    ) -> TaskOutcome:
        if task.task_id == "initial_snapshot":
            if metrics.diagnosis_id and metrics.diagnosis_id != self.store.snapshot.diagnosis_id:
                return TaskOutcome("FAILED", error_code="SNAPSHOT_MISMATCH", failure_reason="초기 센서 스냅샷이 현재 진단과 일치하지 않습니다.")
            if not metrics.initial_complete:
                return TaskOutcome("FAILED", error_code="SNAPSHOT_INCOMPLETE", failure_reason="초기 센서 수집이 완료되지 않았습니다.")
            return TaskOutcome("COMPLETED", ({"initialComplete": True, "readingCount": len(metrics.readings)},))
        if task.task_id == "cpu_health":
            return self._evaluate_metrics(metrics, "cpu", ("usage", "temperature", "clock"), require_any=("usage",))
        if task.task_id == "gpu_usage_temperature":
            return self._evaluate_metrics(metrics, "gpu", ("usage", "temperature"), require_any=("usage", "temperature"))
        if task.task_id == "gpu_cooling_fan":
            return self._evaluate_metrics(metrics, "gpu", ("fan_rpm", "fan_percent"), require_any=("fan_rpm", "fan_percent"))
        if task.task_id == "ram_health":
            return self._evaluate_metrics(metrics, "ram", ("usage", "used_bytes", "total_bytes"), require_any=("usage",))
        if task.task_id == "disk_health":
            return self._evaluate_metrics(metrics, "disk", ("activity", "usage", "smart"), require_any=("activity", "usage"))
        if task.task_id == "thermal_clock":
            readings = (
                self._task_readings(metrics, "gpu", ("thermal_throttling",))
                + self._task_readings(metrics, "gpu", ("clock",))
                + self._task_readings(metrics, "cpu", ("clock",))
            )
            return self._outcome_from_readings(readings, ("thermal_throttling", "clock"))
        if task.task_id == "evidence_finalize":
            usable = [
                item
                for item in tasks
                if item.component in {"cpu", "gpu", "ram", "disk", "thermal"}
                and (
                    item.status == "COMPLETED"
                    or any(
                        isinstance(evidence, dict)
                        and evidence.get("availability") == AVAILABLE
                        and evidence.get("value") is not None
                        for evidence in item.evidence
                    )
                )
            ]
            if not usable:
                return TaskOutcome(
                    "FAILED",
                    error_code="INSUFFICIENT_EVIDENCE",
                    failure_reason="판정에 필요한 유효한 하드웨어 증거가 없습니다.",
                )
            evidence = tuple({"taskId": item.task_id, "status": item.status, "evidenceCount": len(item.evidence)} for item in tasks[:-1])
            return TaskOutcome("COMPLETED", evidence)
        return TaskOutcome("FAILED", error_code="UNKNOWN_TASK", failure_reason="지원하지 않는 진단 작업입니다.")

    def _evaluate_metrics(
        self,
        metrics: MetricsSnapshot,
        component: str,
        metric_types: tuple[str, ...],
        require_any: tuple[str, ...],
    ) -> TaskOutcome:
        readings = self._task_readings(metrics, component, metric_types)
        return self._outcome_from_readings(readings, require_any)

    @staticmethod
    def _task_readings(
        metrics: MetricsSnapshot,
        component: str,
        metric_types: tuple[str, ...],
    ) -> tuple[MetricReading, ...]:
        selected: list[MetricReading] = []
        for metric_type in metric_types:
            latest = metrics.latest(component, metric_type)
            if latest is None:
                continue
            if latest.availability != AVAILABLE:
                latest_available = next(
                    (
                        reading
                        for reading in reversed(metrics.readings)
                        if reading.component == component
                        and reading.metric_type == metric_type
                        and reading.availability == AVAILABLE
                    ),
                    None,
                )
                if latest_available is not None and latest_available != latest:
                    selected.append(latest_available)
            selected.append(latest)
        return tuple(selected)

    @staticmethod
    def _outcome_from_readings(
        readings: tuple[MetricReading, ...],
        require_any: tuple[str, ...],
    ) -> TaskOutcome:
        evidence = tuple(reading.to_dict() for reading in readings)
        required = tuple(reading for reading in readings if reading.metric_type in require_any)
        failed = tuple(reading for reading in readings if reading.availability in {FAILED, PERMISSION_REQUIRED})
        available_required = any(reading.availability == AVAILABLE for reading in required)
        if failed:
            first = failed[0]
            return TaskOutcome(
                "FAILED",
                evidence,
                first.error_code or "SENSOR_FAILED",
                first.failure_reason or "센서 조회에 실패했습니다.",
            )
        if not readings or not available_required:
            return TaskOutcome(
                "UNSUPPORTED",
                evidence,
                "SENSOR_UNSUPPORTED",
                "지원되는 센서 측정값이 없습니다.",
            )
        return TaskOutcome("COMPLETED", evidence)

    def _finish_session(self, snapshot: DiagnosisRunSnapshot) -> None:
        required_failure = any(
            task.required and task.status in {"FAILED", "TIMED_OUT", "CANCELLED"}
            for task in snapshot.tasks
        )
        evidence_task = snapshot.task("evidence_finalize")
        if required_failure or evidence_task is None or evidence_task.status != "COMPLETED":
            state = "FAILED"
            event_type = "DIAGNOSIS_FAILED"
            message = "판정에 필요한 필수 진단 증거를 만들지 못했습니다."
            allowed = False
        else:
            partial = any(task.status in {"UNSUPPORTED", "FAILED", "TIMED_OUT"} for task in snapshot.tasks[:-1])
            state = "PARTIALLY_COMPLETED" if partial else "COMPLETED"
            event_type = "DIAGNOSIS_COMPLETED"
            message = "측정 가능한 하드웨어 진단 작업을 완료했습니다."
            allowed = True
        progress = self.progress_calculator.calculate(snapshot.tasks, snapshot.progress)
        snapshot = replace(
            snapshot,
            state=state,
            progress=progress,
            current_task_id=None,
            completed_at=self._timestamp(),
            transition_allowed=allowed,
        )
        snapshot = self._append_event(snapshot, event_type, message, metadata={"progress": progress, "state": state})
        self._publish(snapshot)
        self.on_complete(snapshot)

    def _finish_cancelled(self, snapshot: DiagnosisRunSnapshot) -> None:
        tasks = tuple(
            replace(task, status="CANCELLED", completed_at=self._timestamp(), error_code="TASK_CANCELLED", failure_reason="사용자가 진단을 취소했습니다.")
            if task.status in {"PENDING", "RUNNING"} else task
            for task in snapshot.tasks
        )
        snapshot = replace(
            snapshot,
            state="CANCELLED",
            tasks=tasks,
            current_task_id=None,
            completed_at=self._timestamp(),
            transition_allowed=False,
        )
        snapshot = self._append_event(snapshot, "DIAGNOSIS_CANCELLED", "사용자가 하드웨어 진단을 취소했습니다.")
        self._publish(snapshot)
        self.on_complete(snapshot)

    def _finish_timed_out(self, snapshot: DiagnosisRunSnapshot) -> None:
        tasks = tuple(
            replace(task, status="TIMED_OUT", completed_at=self._timestamp(), error_code="SESSION_TIMEOUT", failure_reason="전체 진단 시간이 초과되었습니다.")
            if task.status in {"PENDING", "RUNNING"} else task
            for task in snapshot.tasks
        )
        snapshot = replace(
            snapshot,
            state="TIMED_OUT",
            tasks=tasks,
            current_task_id=None,
            completed_at=self._timestamp(),
            transition_allowed=False,
        )
        snapshot = self._append_event(
            snapshot,
            "DIAGNOSIS_FAILED",
            "전체 진단 시간이 초과되었습니다.",
            metadata={"errorCode": "SESSION_TIMEOUT"},
        )
        self._publish(snapshot)
        self.on_complete(snapshot)

    def _publish(self, snapshot: DiagnosisRunSnapshot, reset: bool = False) -> DiagnosisRunSnapshot:
        stored = self.store.replace(snapshot, reset=reset)
        self.on_update(stored)
        return stored

    @staticmethod
    def _replace_task(
        snapshot: DiagnosisRunSnapshot,
        replacement: DiagnosisTask,
        current_task_id: str | None,
    ) -> DiagnosisRunSnapshot:
        tasks = tuple(replacement if task.task_id == replacement.task_id else task for task in snapshot.tasks)
        return replace(snapshot, tasks=tasks, current_task_id=current_task_id)

    def _append_event(
        self,
        snapshot: DiagnosisRunSnapshot,
        event_type: str,
        message: str,
        task: DiagnosisTask | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> DiagnosisRunSnapshot:
        event = DiagnosisEvent(
            diagnosis_id=str(snapshot.diagnosis_id or ""),
            event_id=str(uuid.uuid4()),
            event_type=event_type,
            task_id=task.task_id if task else None,
            component=task.component if task else None,
            timestamp=self._timestamp(),
            message=message,
            metadata=dict(metadata or {}),
        )
        return replace(snapshot, events=snapshot.events + (event,))

    def _task_completion_event(self, task: DiagnosisTask) -> tuple[str, str]:
        label = self.task_labels[task.task_id]
        if task.status == "COMPLETED":
            return "TASK_COMPLETED", f"{label}을 완료했습니다."
        if task.status == "UNSUPPORTED":
            return "TASK_UNSUPPORTED", f"{label}은 지원되는 센서가 없어 측정 불가로 처리했습니다."
        if task.status == "TIMED_OUT":
            return "TASK_TIMED_OUT", f"{label} 시간이 초과되었습니다."
        if task.status == "CANCELLED":
            return "DIAGNOSIS_CANCELLED", f"{label}이 취소되었습니다."
        return "TASK_FAILED", f"{label}에 실패했습니다."

    def _timestamp(self) -> str:
        return self.now().astimezone(timezone.utc).isoformat()


def diagnosis_component_state(snapshot: DiagnosisRunSnapshot, component: str) -> str:
    tasks = snapshot.component_tasks(component)
    if not tasks and component in {"cpu", "ram", "disk"}:
        system_task = snapshot.task("current_system_status")
        tasks = (system_task,) if system_task is not None else ()
    if not tasks:
        return "대기"
    statuses = {task.status for task in tasks}
    if "RUNNING" in statuses:
        return "집중 분석 중" if component == "gpu" else "검사 중"
    if "FAILED" in statuses or "TIMED_OUT" in statuses:
        return "실패"
    if statuses and statuses <= {"UNSUPPORTED"}:
        return "측정 불가"
    if statuses and statuses <= TERMINAL_TASK_STATUSES:
        return "완료"
    return "대기"


def diagnosis_current_task_label(snapshot: DiagnosisRunSnapshot) -> str:
    if snapshot.current_task_id:
        return DiagnosisOrchestrator.TASK_LABELS.get(snapshot.current_task_id, "하드웨어 검사")
    if snapshot.state == "EVALUATING":
        return "최종 판정 증거 정리"
    if snapshot.state == "CANCELLED":
        return "진단 취소됨"
    if snapshot.state in {"FAILED", "TIMED_OUT"}:
        return "진단 중단됨"
    if snapshot.state in {"COMPLETED", "PARTIALLY_COMPLETED"}:
        return "진단 완료"
    return "진단 준비 중"
