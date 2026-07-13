import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from diagnosis_request_agent import (
    AgentDiagnosisWebSocketClient,
    DiagnosisRequestProcessor,
    DiagnosisSessionStore,
    diagnosis_websocket_url,
)


NOW = datetime(2026, 7, 13, 1, 0, tzinfo=timezone.utc)


def request_payload(diagnosis_id="diagnosis-1", device_id="device-1", expires_at=None, mode="LIVE"):
    return {
        "diagnosisId": diagnosis_id,
        "deviceId": device_id,
        "symptom": "게임 실행 후 프레임이 급격히 저하됨",
        "requestedChecks": ["cpu", "gpu", "memory", "disk", "cooling"],
        "requestedAt": NOW.isoformat(),
        "expiresAt": (expires_at or NOW + timedelta(minutes=2)).isoformat(),
        "mode": mode,
    }


class DiagnosisRequestProcessorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "diagnosis-request-state.json"
        self.shown = []
        self.metrics_triggers = []
        self.store = DiagnosisSessionStore(self.path)
        self.processor = DiagnosisRequestProcessor(
            self.store,
            device_id="device-1",
            now=lambda: NOW,
            on_request=self.shown.append,
            on_initial_metrics_requested=self.metrics_triggers.append,
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_accepts_and_persists_request_received_session(self):
        decision = self.processor.process(request_payload(), authenticated=True)

        self.assertEqual("ACCEPTED", decision.status)
        self.assertEqual("REQUEST_RECEIVED", self.store.session.agent_state)
        self.assertEqual("게임 실행 후 프레임이 급격히 저하됨", self.store.session.request.symptom)
        self.assertEqual(1, len(self.shown))
        self.assertEqual(1, len(self.metrics_triggers))

    def test_rejects_auth_device_expiry_and_busy_cases(self):
        self.assertEqual("AUTH_FAILED", self.processor.process(request_payload(), authenticated=False).status)
        self.assertEqual("DEVICE_MISMATCH", self.processor.process(request_payload(device_id="other"), True).status)
        self.assertEqual(
            "EXPIRED",
            self.processor.process(request_payload(expires_at=NOW - timedelta(seconds=1)), True).status,
        )
        self.assertEqual("ACCEPTED", self.processor.process(request_payload(), True).status)
        self.assertEqual("BUSY", self.processor.process(request_payload(diagnosis_id="diagnosis-2"), True).status)

    def test_duplicate_is_detected_before_busy_and_after_restart(self):
        self.assertEqual("ACCEPTED", self.processor.process(request_payload(), True).status)
        self.assertEqual("DUPLICATE", self.processor.process(request_payload(), True).status)
        restarted = DiagnosisRequestProcessor(DiagnosisSessionStore(self.path), "device-1", now=lambda: NOW)
        self.assertEqual("DUPLICATE", restarted.process(request_payload(), True).status)

    def test_demo_mode_is_only_taken_from_request(self):
        decision = self.processor.process(request_payload(mode="DEMO"), True)
        self.assertEqual("DEMO", decision.session.request.mode)


class FakeSocket:
    def __init__(self):
        self.sent = []
        self.closed = False

    def send(self, payload):
        self.sent.append(json.loads(payload))

    def close(self):
        self.closed = True


class AgentDiagnosisWebSocketClientTest(unittest.TestCase):
    def test_ready_authenticates_and_request_gets_real_response(self):
        with tempfile.TemporaryDirectory() as directory:
            states = []
            devices = []
            shown = []
            processor = DiagnosisRequestProcessor(
                DiagnosisSessionStore(Path(directory) / "state.json"),
                now=lambda: NOW,
                on_request=shown.append,
            )
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "secret",
                processor,
                on_state_changed=states.append,
                on_device_identified=devices.append,
                websocket_factory=lambda *args, **kwargs: None,
            )
            socket = FakeSocket()
            client._on_open(socket)
            client._on_message(socket, json.dumps({"type": "READY", "detail": {"deviceId": "device-1"}}))
            client._on_message(socket, json.dumps({"type": "DIAGNOSIS_REQUEST", "detail": request_payload()}))

            self.assertEqual("AUTH", socket.sent[0]["type"])
            self.assertEqual("ACCEPTED", socket.sent[1]["status"])
            self.assertEqual(["device-1"], devices)
            self.assertEqual("REQUEST_RECEIVED", states[-1])
            self.assertEqual(1, len(shown))

    def test_disconnect_and_auth_failure_are_distinct(self):
        with tempfile.TemporaryDirectory() as directory:
            states = []
            client = AgentDiagnosisWebSocketClient(
                "http://localhost:8080",
                "bad-token",
                DiagnosisRequestProcessor(DiagnosisSessionStore(Path(directory) / "state.json")),
                on_state_changed=states.append,
                websocket_factory=lambda *args, **kwargs: None,
            )
            socket = FakeSocket()
            client._on_close(socket, 1006, "network lost")
            self.assertEqual("DISCONNECTED", states[-1])
            client._on_message(socket, json.dumps({"type": "ERROR", "code": "AUTH_FAILED"}))
            self.assertEqual("FAILED", states[-1])
            self.assertTrue(socket.closed)

    def test_websocket_url_is_outbound_and_secure_when_api_is_https(self):
        self.assertEqual("ws://localhost:8080/ws/pc-agent/diagnosis", diagnosis_websocket_url("http://localhost:8080"))
        self.assertEqual("wss://api.example.com/ws/pc-agent/diagnosis", diagnosis_websocket_url("https://api.example.com"))


if __name__ == "__main__":
    unittest.main()
