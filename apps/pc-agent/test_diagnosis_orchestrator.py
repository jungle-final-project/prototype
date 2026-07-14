from __future__ import annotations

import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path

from diagnosis_orchestrator import (
    DiagnosisEvent,
    DiagnosisLogStore,
    DiagnosisOrchestrator,
    DiagnosisSettings,
    DiagnosisTask,
    ProgressCalculator,
    TaskOutcome,
    diagnosis_component_state,
)
from initial_metrics import (
    AVAILABLE,
    FAILED,
    NORMAL,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    MetricReading,
    MetricsSnapshot,
)


def metric(
    component: str,
    metric_type: str,
    value: float | str | None,
    unit: str = "",
    availability: str = AVAILABLE,
    error_code: str | None = None,
    reason: str | None = None,
) -> MetricReading:
    status = NORMAL if availability == AVAILABLE else "ERROR" if availability == FAILED else "UNAVAILABLE"
    return MetricReading(
        component,
        metric_type,
        value if availability == AVAILABLE else None,
        unit,
        availability,
        status,
        "test",
        "2026-07-13T12:00:00+00:00",
        error_code,
        reason,
    )


def complete_metrics(
    diagnosis_id: str = "diag-1",
    mode: str = "LIVE",
    overrides: dict[tuple[str, str], MetricReading] | None = None,
    initial_complete: bool = True,
) -> MetricsSnapshot:
    readings = {
        ("cpu", "usage"): metric("cpu", "usage", 35.0, "%"),
        ("cpu", "temperature"): metric("cpu", "temperature", 60.0, "°C"),
        ("cpu", "clock"): metric("cpu", "clock", 4200.0, "MHz"),
        ("gpu", "usage"): metric("gpu", "usage", 86.0, "%"),
        ("gpu", "temperature"): metric("gpu", "temperature", 88.0, "°C"),
        ("gpu", "fan_rpm"): metric("gpu", "fan_rpm", 0.0, "RPM"),
        ("gpu", "fan_percent"): metric("gpu", "fan_percent", 0.0, "%"),
        ("gpu", "clock"): metric("gpu", "clock", 1800.0, "MHz"),
        ("gpu", "thermal_throttling"): metric("gpu", "thermal_throttling", "active"),
        ("ram", "usage"): metric("ram", "usage", 62.0, "%"),
        ("ram", "used_bytes"): metric("ram", "used_bytes", 10 * 1024**3, "bytes"),
        ("ram", "total_bytes"): metric("ram", "total_bytes", 16 * 1024**3, "bytes"),
        ("disk", "activity"): metric("disk", "activity", 26.0, "%"),
        ("disk", "usage"): metric("disk", "usage", 48.0, "%"),
        ("disk", "smart"): metric("disk", "smart", "정상"),
    }
    readings.update(overrides or {})
    return MetricsSnapshot(diagnosis_id, mode, initial_complete, tuple(readings.values()))


class DiagnosisOrchestratorTest(unittest.TestCase):
    def run_diagnosis(
        self,
        metrics: MetricsSnapshot,
        *,
        settings: DiagnosisSettings | None = None,
        handlers: dict | None = None,
        updates: list | None = None,
        store: DiagnosisLogStore | None = None,
    ):
        selected_store = store or DiagnosisLogStore()
        orchestrator = DiagnosisOrchestrator(
            lambda: metrics,
            selected_store,
            settings=settings or DiagnosisSettings(),
            task_handlers=handlers,
            on_update=(updates.append if updates is not None else None),
        )
        self.assertTrue(orchestrator.start("diag-1", str(metrics.mode), ("cpu", "gpu", "memory", "disk", "cooling")))
        self.assertTrue(orchestrator.wait(2))
        return orchestrator, selected_store.snapshot

    def test_all_supported_tasks_complete_and_progress_reaches_100(self) -> None:
        updates: list = []
        _, snapshot = self.run_diagnosis(complete_metrics(), updates=updates)

        self.assertEqual("COMPLETED", snapshot.state)
        self.assertEqual(100, snapshot.progress)
        self.assertTrue(snapshot.transition_allowed)
        self.assertTrue(all(task.status == "COMPLETED" for task in snapshot.tasks))
        progress_values = [item.progress for item in updates]
        self.assertEqual(sorted(progress_values), progress_values)
        self.assertEqual(100, progress_values[-1])
        self.assertTrue(all(item.progress < 100 for item in updates if item.task("evidence_finalize").status != "COMPLETED"))

    def test_gpu_running_state_is_focused_analysis(self) -> None:
        updates: list = []
        self.run_diagnosis(complete_metrics(), updates=updates)

        running = next(
            snapshot
            for snapshot in updates
            if snapshot.current_task_id == "gpu_usage_temperature"
        )
        self.assertEqual("집중 분석 중", diagnosis_component_state(running, "gpu"))

    def test_unsupported_fan_is_not_reported_as_success(self) -> None:
        unsupported = {
            ("gpu", "fan_rpm"): metric("gpu", "fan_rpm", None, "RPM", UNSUPPORTED, "SENSOR_UNSUPPORTED", "fan unsupported"),
            ("gpu", "fan_percent"): metric("gpu", "fan_percent", None, "%", UNSUPPORTED, "SENSOR_UNSUPPORTED", "fan unsupported"),
        }
        _, snapshot = self.run_diagnosis(complete_metrics(overrides=unsupported))

        self.assertEqual("UNSUPPORTED", snapshot.task("gpu_cooling_fan").status)
        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)
        self.assertTrue(snapshot.transition_allowed)

    def test_cpu_temperature_failure_allows_valid_partial_result(self) -> None:
        failed_temp = metric("cpu", "temperature", None, "°C", FAILED, "SENSOR_FAILED", "temperature lookup failed")
        _, snapshot = self.run_diagnosis(complete_metrics(overrides={("cpu", "temperature"): failed_temp}))

        self.assertEqual("FAILED", snapshot.task("cpu_health").status)
        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)
        self.assertTrue(snapshot.transition_allowed)

    def test_disk_failure_allows_valid_partial_result(self) -> None:
        failed_disk = metric("disk", "activity", None, "%", FAILED, "SENSOR_FAILED", "disk lookup failed")
        _, snapshot = self.run_diagnosis(complete_metrics(overrides={("disk", "activity"): failed_disk}))

        self.assertEqual("FAILED", snapshot.task("disk_health").status)
        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)

    def test_available_evidence_allows_partial_result_when_every_component_task_has_a_failure(self) -> None:
        available = complete_metrics()
        failed_latest = tuple(
            metric(
                reading.component,
                reading.metric_type,
                None,
                reading.unit,
                FAILED,
                "SENSOR_FAILED",
            )
            for reading in available.readings
        )
        metrics = MetricsSnapshot(
            available.diagnosis_id,
            available.mode,
            available.initial_complete,
            available.readings + failed_latest,
        )
        _, snapshot = self.run_diagnosis(metrics)

        component_tasks = snapshot.tasks[1:-1]
        self.assertTrue(all(task.status == "FAILED" for task in component_tasks))
        self.assertTrue(
            all(
                any(evidence["availability"] == AVAILABLE for evidence in task.evidence)
                for task in component_tasks
            )
        )
        self.assertEqual("COMPLETED", snapshot.task("evidence_finalize").status)
        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)
        self.assertEqual(100, snapshot.progress)
        self.assertTrue(snapshot.transition_allowed)

    def test_permission_required_is_failed_not_unsupported(self) -> None:
        permission = metric(
            "cpu", "temperature", None, "°C", PERMISSION_REQUIRED, "PERMISSION_REQUIRED", "administrator required"
        )
        _, snapshot = self.run_diagnosis(complete_metrics(overrides={("cpu", "temperature"): permission}))

        self.assertEqual("FAILED", snapshot.task("cpu_health").status)
        self.assertEqual("PERMISSION_REQUIRED", snapshot.task("cpu_health").error_code)

    def test_required_initial_snapshot_failure_blocks_transition(self) -> None:
        _, snapshot = self.run_diagnosis(complete_metrics(initial_complete=False))

        self.assertEqual("FAILED", snapshot.task("initial_snapshot").status)
        self.assertEqual("FAILED", snapshot.state)
        self.assertFalse(snapshot.transition_allowed)
        self.assertEqual(100, snapshot.progress)

    def test_session_timeout_stops_without_success_transition(self) -> None:
        def slow_cpu(*_args):
            time.sleep(0.05)
            return TaskOutcome("COMPLETED")

        settings = DiagnosisSettings(task_timeout_seconds=0.02, session_timeout_seconds=0.02)
        _, snapshot = self.run_diagnosis(
            complete_metrics(),
            settings=settings,
            handlers={"cpu_health": slow_cpu},
        )

        self.assertEqual("TIMED_OUT", snapshot.state)
        self.assertFalse(snapshot.transition_allowed)
        self.assertTrue(any(task.status == "TIMED_OUT" for task in snapshot.tasks))

    def test_user_cancel_marks_pending_tasks_cancelled(self) -> None:
        def slow_initial(*_args):
            time.sleep(0.2)
            return TaskOutcome("COMPLETED")

        store = DiagnosisLogStore()
        orchestrator = DiagnosisOrchestrator(
            lambda: complete_metrics(),
            store,
            task_handlers={"initial_snapshot": slow_initial},
        )
        self.assertTrue(orchestrator.start("diag-1", "LIVE"))
        time.sleep(0.02)
        self.assertTrue(orchestrator.cancel())
        self.assertTrue(orchestrator.wait(1))

        self.assertEqual("CANCELLED", store.snapshot.state)
        self.assertFalse(store.snapshot.transition_allowed)
        self.assertIn("DIAGNOSIS_CANCELLED", [event.event_type for event in store.snapshot.events])

    def test_failed_required_task_can_retry_once_without_duplicate_weight(self) -> None:
        attempts = {"count": 0}

        def flaky_initial(*_args):
            attempts["count"] += 1
            if attempts["count"] == 1:
                return TaskOutcome("FAILED", error_code="SNAPSHOT_FAILED", failure_reason="first failure")
            return TaskOutcome("COMPLETED", ({"initialComplete": True},))

        store = DiagnosisLogStore()
        orchestrator, first = self.run_diagnosis(
            complete_metrics(),
            handlers={"initial_snapshot": flaky_initial},
            store=store,
        )
        self.assertEqual("FAILED", first.state)
        self.assertTrue(orchestrator.retry())
        self.assertTrue(orchestrator.wait(2))

        second = store.snapshot
        self.assertIn(second.state, {"COMPLETED", "PARTIALLY_COMPLETED"})
        self.assertEqual(100, second.progress)
        self.assertEqual(1, second.retry_count)
        self.assertFalse(orchestrator.retry())

    def test_live_and_demo_use_the_same_task_and_event_path(self) -> None:
        _, live = self.run_diagnosis(complete_metrics(mode="LIVE"))
        _, demo = self.run_diagnosis(complete_metrics(mode="DEMO"))

        self.assertEqual([task.task_id for task in live.tasks], [task.task_id for task in demo.tasks])
        self.assertEqual([event.event_type for event in live.events], [event.event_type for event in demo.events])


class DiagnosisLogStoreTest(unittest.TestCase):
    def test_persists_progress_tasks_and_events_for_window_restore(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "diagnosis.json"
            store = DiagnosisLogStore(path)
            orchestrator = DiagnosisOrchestrator(lambda: complete_metrics(), store)
            self.assertTrue(orchestrator.start("diag-1", "LIVE"))
            self.assertTrue(orchestrator.wait(2))
            reopened = DiagnosisLogStore(path).snapshot

            self.assertEqual(store.snapshot.progress, reopened.progress)
            self.assertEqual(store.snapshot.tasks, reopened.tasks)
            self.assertEqual(store.snapshot.events, reopened.events)

    def test_duplicate_event_id_is_stored_once(self) -> None:
        store = DiagnosisLogStore()
        event = DiagnosisEvent(
            "diag-1",
            "event-1",
            "TASK_STARTED",
            "cpu_health",
            "cpu",
            "2026-07-13T12:00:00+00:00",
            "CPU 상태 검사를 시작했습니다.",
            {},
        )
        first = replace(store.snapshot, diagnosis_id="diag-1", events=(event,))
        store.replace(first)
        store.replace(replace(first, events=(event, event)))

        self.assertEqual(1, len(store.snapshot.events))

    def test_progress_calculator_never_returns_100_before_evidence_finalize(self) -> None:
        settings = DiagnosisSettings()
        tasks = tuple(
            DiagnosisTask(
                task_id,
                component,
                settings.task_weights[task_id],
                required,
                "FAILED" if task_id == "evidence_finalize" else "COMPLETED",
            )
            for task_id, component, required in DiagnosisOrchestrator.TASK_DEFINITIONS
        )
        self.assertEqual(99, ProgressCalculator().calculate(tasks))


if __name__ == "__main__":
    unittest.main()
