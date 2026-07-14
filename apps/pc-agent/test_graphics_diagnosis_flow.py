from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

import buildgraph_agent as agent
from diagnosis_request_agent import DiagnosisRequest, DiagnosisSession
from windows_graphics_diagnostics import (
    DEVICE_REPORTED_PROBLEM,
    DISABLED,
    NO_RESULTS,
    OK,
    PowerShellQueryResult,
    WindowsDisplayDevice,
    WindowsGraphicsDiagnosticsSnapshot,
)


STARTED_AT = datetime(2026, 7, 14, 8, 0, tzinfo=timezone.utc)


def readings_at(sampled_at: datetime, value: float) -> tuple[agent.MetricReading, ...]:
    stamp = sampled_at.isoformat()
    return (
        agent.MetricReading("cpu", "usage", value, "%", "AVAILABLE", "NORMAL", "psutil", stamp),
        agent.MetricReading("gpu", "usage", value, "%", "AVAILABLE", "NORMAL", "windows-performance-counter", stamp),
        agent.MetricReading("ram", "usage", value, "%", "AVAILABLE", "NORMAL", "psutil", stamp),
        agent.MetricReading("disk", "activity", value, "%", "AVAILABLE", "NORMAL", "windows-performance-counter", stamp),
    )


def windows_snapshot(problem_code: int | None) -> WindowsGraphicsDiagnosticsSnapshot:
    queried_at = (STARTED_AT + timedelta(seconds=1)).isoformat()
    if problem_code is not None:
        problem_device = WindowsDisplayDevice(
            device_name=f"Test Problem {problem_code} Display Adapter",
            instance_id="PCI\\VEN_1234&DEV_5678",
            pnp_status="Error",
            problem_code=problem_code,
            problem_code_query_status=OK,
            device_class="Display",
            manufacturer="Test Manufacturer",
            driver_provider="Test Driver Provider",
            driver_version=f"31.0.0.{problem_code}",
            driver_date="2026-07-01T00:00:00+00:00",
            driver_signed=True,
            signer="Microsoft Windows Hardware Compatibility Publisher",
            inf_name=f"oem{problem_code}.inf",
            status=DISABLED if problem_code == 22 else DEVICE_REPORTED_PROBLEM,
            queried_at=queried_at,
            device_source="Win32_PnPEntity",
        )
        devices = (problem_device,)
        if problem_code == 43:
            devices = (WindowsDisplayDevice(
                device_name="Test Healthy Display Adapter",
                instance_id="PCI\\VEN_0000&DEV_0000",
                pnp_status="OK",
                problem_code=0,
                problem_code_query_status=OK,
                device_class="Display",
                manufacturer="Test Manufacturer",
                driver_provider="Test Driver Provider",
                driver_version="31.0.0.0",
                driver_date="2026-07-01T00:00:00+00:00",
                driver_signed=True,
                signer="Microsoft Windows Hardware Compatibility Publisher",
                inf_name="oem0.inf",
                status=OK,
                queried_at=queried_at,
                device_source="Win32_PnPEntity",
            ), problem_device)
        query_items = tuple({"instanceId": device.instance_id} for device in devices)
        device_query = PowerShellQueryResult(OK, query_items)
        driver_query = PowerShellQueryResult(OK, query_items)
    else:
        devices = ()
        device_query = PowerShellQueryResult(NO_RESULTS)
        driver_query = PowerShellQueryResult(NO_RESULTS)
    no_events = PowerShellQueryResult(NO_RESULTS)
    return WindowsGraphicsDiagnosticsSnapshot(
        queried_at=queried_at,
        devices=devices,
        graphics_events=(),
        whea_events=(),
        kernel_power_events=(),
        device_query=device_query,
        driver_query=driver_query,
        graphics_event_query=no_events,
        whea_event_query=no_events,
        kernel_power_event_query=no_events,
    )


class GraphicsDiagnosisFlowTest(unittest.TestCase):
    def run_flow(self, problem_code: int | None):
        diagnosis_id = "diagnosis-graphics-flow"
        request = DiagnosisRequest(
            diagnosis_id=diagnosis_id,
            device_id="device-1",
            symptom="게임 중 검은 화면이 나타났다가 화면이 복구됩니다.",
            requested_checks=("gpu",),
            requested_at=STARTED_AT.isoformat(),
            expires_at=(STARTED_AT + timedelta(minutes=5)).isoformat(),
            mode="LIVE",
        )
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        session_store = agent.DiagnosisSessionStore(Path(temporary.name) / "session.json")
        session_store.accept(DiagnosisSession(request))
        result_store = agent.DiagnosisResultStore(Path(temporary.name) / "result.json")
        result_store.save(agent.DiagnosisResult(
            diagnosis_id=diagnosis_id,
            severity="NORMAL",
            title="stale result",
            summary="stale result",
            evidence=(),
            findings=(),
            suspected_causes=(),
            recommended_actions=(),
            resolution_type="NONE",
            can_auto_recover=False,
            unsupported_checks=(),
            evaluated_at=(STARTED_AT - timedelta(minutes=1)).isoformat(),
        ))
        metrics_store = agent.MetricsStore(Path(temporary.name) / "metrics.json")
        metrics_store.begin(diagnosis_id, "LIVE")
        metrics_store.append(diagnosis_id, readings_at(STARTED_AT - timedelta(seconds=1), 10.0))
        metrics_store.complete(diagnosis_id)
        log_store = agent.DiagnosisLogStore(Path(temporary.name) / "progress.json")
        log_store.replace(agent.DiagnosisRunSnapshot(
            diagnosis_id=diagnosis_id,
            mode="LIVE",
            state="COMPLETED",
            progress=100,
            transition_allowed=True,
        ))
        provider_calls: list[str] = []
        actual_windows_snapshot = windows_snapshot(problem_code)

        def collect_windows() -> WindowsGraphicsDiagnosticsSnapshot:
            provider_calls.append(diagnosis_id)
            return actual_windows_snapshot

        updates: list[agent.DiagnosisRunSnapshot] = []
        handlers = agent.graphics_diagnosis_task_handlers(
            lambda: session_store.session,
            lambda: metrics_store.snapshot,
            lambda: log_store.snapshot,
            collect_windows,
            observation_timeout_seconds=0.5,
        )
        orchestrator = agent.DiagnosisOrchestrator(
            lambda: metrics_store.snapshot,
            log_store,
            settings=agent.DiagnosisSettings(
                task_weights=agent.GRAPHICS_DIAGNOSIS_TASK_WEIGHTS,
                task_timeout_seconds=2.0,
                session_timeout_seconds=10.0,
                max_retries=1,
            ),
            task_handlers=handlers,
            task_definitions=agent.GRAPHICS_DIAGNOSIS_TASK_DEFINITIONS,
            task_labels=agent.GRAPHICS_DIAGNOSIS_TASK_LABELS,
            on_update=updates.append,
            now=lambda: STARTED_AT,
        )

        started_session = agent.start_diagnosis_once(
            session_store.session,
            session_store,
            metrics_store,
            orchestrator,
            result_store,
        )
        self.assertIsNotNone(started_session)
        result_cleared = result_store.result is None
        for index in range(1, 4):
            metrics_store.append(
                diagnosis_id,
                readings_at(STARTED_AT + timedelta(seconds=index), 20.0 + index),
            )
        self.assertTrue(orchestrator.wait(5.0))
        snapshot = log_store.snapshot
        result = agent.DiagnosisRuleEngine().evaluate(metrics_store.snapshot, snapshot)
        result_store.save(result)
        return session_store.session, metrics_store.snapshot, snapshot, result, updates, provider_calls, result_cleared

    def test_black_screen_and_code_22_complete_real_task_event_and_result_flow(self) -> None:
        session, metrics, snapshot, result, updates, provider_calls, result_cleared = self.run_flow(22)

        self.assertTrue(result_cleared)
        self.assertEqual(0, updates[0].progress)
        self.assertEqual((), updates[0].events)
        self.assertTrue(all(task.status == "PENDING" and not task.evidence for task in updates[0].tasks))
        self.assertEqual("COMPLETED", snapshot.state)
        self.assertEqual(100, snapshot.progress)
        self.assertTrue(all(task.status == "COMPLETED" for task in snapshot.tasks))
        self.assertTrue(any(update.progress < 100 for update in updates))
        self.assertEqual("DIAGNOSIS_STARTED", snapshot.events[0].event_type)
        self.assertEqual("DIAGNOSIS_COMPLETED", snapshot.events[-1].event_type)
        self.assertEqual(
            [task.task_id for task in snapshot.tasks],
            [event.task_id for event in snapshot.events if event.event_type == "TASK_STARTED"],
        )
        progress_values = [event.metadata["progress"] for event in snapshot.events if event.event_type == "PROGRESS_UPDATED"]
        self.assertEqual(100, progress_values[-1])
        self.assertTrue(all(left < right for left, right in zip(progress_values, progress_values[1:])))
        observation = next(
            item for item in snapshot.task("current_system_status").evidence
            if item.get("metricType") == "observation_window"
        )
        self.assertEqual(3, observation["value"]["sampleCount"])
        self.assertEqual(1, len(provider_calls))
        self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", result.diagnosis_type)
        self.assertFalse(result.can_auto_recover)
        self.assertTrue(result.remote_as_recommended)
        self.assertEqual("PHYSICAL_INSPECTION", result.resolution_type)
        self.assertTrue(agent.can_offer_as(result, snapshot, session))
        self.assertEqual(
            "DIAGNOSING",
            agent.diagnosis_session_ui_state(session, metrics, snapshot, result, diagnosis_started=True),
        )
        self.assertEqual(
            "DIAGNOSIS_RESULT",
            agent.diagnosis_session_ui_state(
                session, metrics, snapshot, result, diagnosis_started=True, result_requested=True,
            ),
        )

    def test_black_screen_without_clear_device_evidence_is_insufficient(self) -> None:
        _, _, snapshot, result, _, provider_calls, _ = self.run_flow(None)

        self.assertEqual("PARTIALLY_COMPLETED", snapshot.state)
        self.assertEqual("INSUFFICIENT_EVIDENCE", result.diagnosis_type)
        self.assertFalse(result.can_auto_recover)
        self.assertFalse(result.remote_as_recommended)
        self.assertEqual("UNKNOWN", result.resolution_type)
        self.assertFalse(agent.can_offer_as(result, snapshot))
        self.assertNotIn("원격 AS 기사 점검 권장", result.recommended_actions)
        self.assertEqual(1, len(provider_calls))

    def test_problem_code_wording_uses_actual_device_and_exact_windows_state(self) -> None:
        expected = {
            22: ("그래픽 장치 비활성 상태가 확인되었습니다", "Windows에서 장치가 비활성 상태입니다."),
            43: ("그래픽 장치 오류 상태가 확인되었습니다", "장치가 문제를 보고하여 Windows가 장치를 중지한 상태입니다."),
        }
        for problem_code, (title, state_description) in expected.items():
            with self.subTest(problem_code=problem_code):
                _, _, _, result, _, _, _ = self.run_flow(problem_code)
                rendered = "\n".join((
                    result.title,
                    result.summary,
                    *(finding.summary for finding in result.findings),
                    *result.recommended_actions,
                ))
                self.assertEqual("DEVICE_DRIVER_CONFIGURATION_ISSUE", result.diagnosis_type)
                self.assertEqual(title, result.title)
                self.assertIn(f"Test Problem {problem_code} Display Adapter", rendered)
                self.assertIn(f"problem code {problem_code}", rendered)
                self.assertIn(state_description, rendered)
                if problem_code == 43:
                    self.assertNotIn("비활성 상태", rendered)
                    self.assertNotIn("구성 문제", rendered)
                self.assertNotIn("GPU가 고장 났습니다", rendered)
                self.assertNotIn(f"검은 화면의 원인은 Code {problem_code}입니다", rendered)
                self.assertNotIn("메인 GPU가 고장 났습니다", rendered)
                self.assertNotIn("자동으로 복구했습니다", rendered)

    def test_reset_returns_agent_to_idle_and_accepts_a_new_web_request(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        session_store = agent.DiagnosisSessionStore(root / "session.json")
        request = DiagnosisRequest(
            "diagnosis-completed",
            "device-1",
            "게임 중 검은 화면이 나타났다가 화면이 복구됩니다.",
            ("gpu",),
            STARTED_AT.isoformat(),
            (STARTED_AT + timedelta(minutes=5)).isoformat(),
            "LIVE",
        )
        session_store.accept(DiagnosisSession(request, "COMPLETED"))
        metrics_store = agent.MetricsStore(root / "metrics.json")
        metrics_store.begin(request.diagnosis_id, "LIVE")
        metrics_store.append(request.diagnosis_id, readings_at(STARTED_AT, 25.0))
        log_store = agent.DiagnosisLogStore(root / "progress.json")
        log_store.replace(agent.DiagnosisRunSnapshot(
            diagnosis_id=request.diagnosis_id,
            mode="LIVE",
            state="COMPLETED",
            progress=100,
            transition_allowed=True,
        ))
        result_store = agent.DiagnosisResultStore(root / "result.json")
        result_store.save(agent.DiagnosisResult(
            request.diagnosis_id, "WARNING", "완료", "완료", (), (), (), (),
            "UNKNOWN", False, (), STARTED_AT.isoformat(),
        ))

        processor = agent.DiagnosisRequestProcessor(
            session_store,
            device_id="device-1",
            now=lambda: STARTED_AT,
        )
        states: list[str] = []
        client = agent.AgentDiagnosisWebSocketClient(
            "http://localhost:8080",
            "agent-token",
            processor,
            on_state_changed=states.append,
        )
        client.authenticated = True

        agent.reset_diagnosis_session_state(session_store, metrics_store, log_store, result_store)
        self.assertTrue(client.mark_idle())
        self.assertEqual("IDLE", client.state)
        self.assertIsNone(session_store.session)
        self.assertIsNone(metrics_store.snapshot.diagnosis_id)
        self.assertEqual((), metrics_store.snapshot.readings)
        self.assertIsNone(log_store.snapshot.diagnosis_id)
        self.assertIsNone(result_store.result)

        decision = processor.process({
            "diagnosisId": "diagnosis-next",
            "deviceId": "device-1",
            "symptom": "화면이 꺼졌다가 다시 복구됩니다.",
            "requestedChecks": ["gpu"],
            "requestedAt": STARTED_AT.isoformat(),
            "expiresAt": (STARTED_AT + timedelta(minutes=5)).isoformat(),
            "mode": "LIVE",
        }, authenticated=True)
        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("diagnosis-next", session_store.session.request.diagnosis_id)


if __name__ == "__main__":
    unittest.main()
