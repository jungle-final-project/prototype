"""진단 완주 후 재접속 루프 회귀 테스트.

서버 WebSocket 텍스트 프레임 한계를 넘는 결과 프레임을 보내면 서버가 연결을 끊는다.
클라이언트가 그 프레임을 계속 보관하고 재접속마다 다시 보내면 무한 재접속 루프가 된다.
"""
import json
import tempfile
import unittest
from pathlib import Path

from diagnosis_request_agent import (
    AgentDiagnosisWebSocketClient,
    DiagnosisRequestProcessor,
    DiagnosisSessionStore,
)
from diagnosis_result import (
    MAX_WINDOWS_EVENT_EVIDENCE_PER_CATEGORY,
    DiagnosisEvidence,
    DiagnosisFinding,
    DiagnosisResult,
    compact_result_evidence,
)


STAMP = "2026-07-14T01:01:30Z"


def windows_event_evidence(index: int, category: str = "DRIVER") -> DiagnosisEvidence:
    return DiagnosisEvidence(
        task_id="windows_graphics_events",
        component="gpu",
        metric_type="windows_event",
        value={
            "eventId": 4101,
            "occurredAt": STAMP,
            "description": f"디스플레이 드라이버가 응답을 멈췄다가 복구되었습니다. ({index}) " + "설명" * 60,
        },
        unit="",
        availability="AVAILABLE",
        status="ABNORMAL",
        source="windows_event_log",
        sampled_at=STAMP,
        category=category,
    )


def metric_evidence(metric_type: str = "temperature", value: float = 91.0) -> DiagnosisEvidence:
    return DiagnosisEvidence(
        task_id="gpu_metrics",
        component="gpu",
        metric_type=metric_type,
        value=value,
        unit="C",
        availability="AVAILABLE",
        status="ABNORMAL",
        source="hardware_sensor",
        sampled_at=STAMP,
    )


def result_with(*evidence: DiagnosisEvidence, findings: tuple[DiagnosisFinding, ...] = ()) -> DiagnosisResult:
    return DiagnosisResult(
        "diagnosis-1",
        "WARNING",
        "GPU 상태 확인",
        "측정 근거를 정리했습니다.",
        tuple(evidence),
        findings,
        (),
        ("원격 기사 점검",),
        "PHYSICAL_INSPECTION",
        False,
        (),
        STAMP,
    )


class CompactResultEvidenceTest(unittest.TestCase):
    def test_caps_windows_event_evidence_per_category(self) -> None:
        events = tuple(windows_event_evidence(index) for index in range(40))
        result = result_with(metric_evidence(), *events)

        compacted = compact_result_evidence(result)

        kept_events = [item for item in compacted.evidence if item.metric_type == "windows_event"]
        self.assertEqual(MAX_WINDOWS_EVENT_EVIDENCE_PER_CATEGORY, len(kept_events))
        self.assertIn(metric_evidence().key, {item.key for item in compacted.evidence})

    def test_keeps_frame_within_server_text_limit(self) -> None:
        events = tuple(
            windows_event_evidence(index, category)
            for category in ("DRIVER", "HARDWARE", "SYSTEM")
            for index in range(100)
        )
        result = result_with(metric_evidence(), *events)

        raw = len(json.dumps(result.to_dict(), ensure_ascii=False).encode("utf-8"))
        compacted = len(json.dumps(compact_result_evidence(result).to_dict(), ensure_ascii=False).encode("utf-8"))

        self.assertGreater(raw, 8192)
        self.assertLess(compacted, 60_000)

    def test_never_drops_evidence_referenced_by_findings(self) -> None:
        event = windows_event_evidence(0)
        finding = DiagnosisFinding(
            "GPU_EVENT",
            "WARNING",
            "이벤트 감지",
            "이벤트 로그 근거로 판정했습니다.",
            (event.key,),
            (),
            ("원격 기사 점검",),
            "PHYSICAL_INSPECTION",
        )
        events = tuple(windows_event_evidence(index) for index in range(40))
        result = result_with(*events, findings=(finding,))

        compacted = compact_result_evidence(result)

        self.assertEqual(len(events), len([item for item in compacted.evidence if item.key == event.key]))


class FakeSocket:
    def __init__(self, fail: bool = False) -> None:
        self.sent: list[dict] = []
        self.fail = fail

    def send(self, raw: str) -> None:
        if self.fail:
            raise OSError("서버가 연결을 끊었습니다.")
        self.sent.append(json.loads(raw))

    def close(self) -> None:
        pass


def make_client(store_path: Path) -> AgentDiagnosisWebSocketClient:
    client = AgentDiagnosisWebSocketClient(
        "https://api.example.com",
        "token",
        DiagnosisRequestProcessor(DiagnosisSessionStore(store_path)),
        websocket_factory=lambda *args, **kwargs: FakeSocket(),
    )
    client.authenticated = True
    return client


class SyncBufferTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.store_path = Path(self._temp.name) / "diagnosis-request-state.json"

    def client(self) -> AgentDiagnosisWebSocketClient:
        return make_client(self.store_path)

    def status_detail(self, event_id: str = "event-1") -> dict:
        return {
            "diagnosisId": "diagnosis-1",
            "eventId": event_id,
            "eventType": "PROGRESS",
            "sessionState": "DIAGNOSING",
            "progress": 50,
        }

    def test_acknowledged_frames_are_not_resent(self) -> None:
        client = self.client()
        client._socket = FakeSocket()
        client.send_diagnosis_status(self.status_detail())

        client._on_message(client._socket, json.dumps({
            "type": "DIAGNOSIS_STATUS_ACK",
            "detail": {"diagnosisId": "diagnosis-1", "eventId": "event-1"},
        }))
        client._on_message(client._socket, json.dumps({
            "type": "READY",
            "detail": {"deviceId": "device-1", "agentState": "IDLE"},
        }))

        self.assertEqual({}, dict(client._status_frames))
        self.assertEqual(set(), client._pending_status_event_ids)

    def test_repeatedly_failing_frame_is_dropped(self) -> None:
        client = self.client()
        client._socket = FakeSocket(fail=True)

        for _ in range(AgentDiagnosisWebSocketClient.MAX_FRAME_SEND_FAILURES):
            client.send_diagnosis_status(self.status_detail())

        self.assertEqual({}, dict(client._status_frames))
        self.assertEqual(set(), client._pending_status_event_ids)

    def test_reset_sync_state_clears_buffers(self) -> None:
        client = self.client()
        client._socket = FakeSocket(fail=True)
        client.send_diagnosis_status(self.status_detail())
        client.send_diagnosis_result({"diagnosisId": "diagnosis-1", "resultId": "result-1"})

        client.reset_sync_state()

        self.assertEqual({}, dict(client._status_frames))
        self.assertEqual({}, dict(client._result_frames))
        self.assertEqual(set(), client._pending_status_event_ids)
        self.assertEqual(set(), client._pending_result_ids)


if __name__ == "__main__":
    unittest.main()
