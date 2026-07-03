from __future__ import annotations

import gzip
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

import buildgraph_agent as agent


class AgentGoal1112Test(unittest.TestCase):
    def test_append_metric_writes_required_demo_jsonl_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )

            log_path = agent.append_metric(config)

            row = json.loads(log_path.read_text(encoding="utf-8").strip())
            self.assertIn("timestamp", row)
            self.assertIn("cpuUsage", row)
            self.assertIn("memoryUsage", row)
            self.assertIn("eventType", row)
            self.assertIn("message", row)
            self.assertEqual(row["schemaVersion"], 1)
            self.assertIn("collectedAt", row)
            self.assertEqual(row["agentId"], "fingerprint")
            self.assertEqual(row["sequence"], 0)
            self.assertEqual(row["kind"], row["eventType"])
            self.assertEqual(row["payload"]["eventType"], row["eventType"])
            self.assertEqual(row["privacyFlags"], {"containsRawPath": False, "masked": True})

    def test_gzip_recent_selects_recent_rows_and_writes_non_empty_gzip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "recent-30m.jsonl.gz"
            now = datetime.now(timezone(timedelta(hours=9)))
            rows = [
                {"timestamp": (now - timedelta(minutes=40)).isoformat(), "message": "old"},
                {"timestamp": (now - timedelta(minutes=5)).isoformat(), "message": "recent"},
            ]
            source.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            size = agent.gzip_recent(source, out, 30)

            self.assertGreater(size, 0)
            with gzip.open(out, "rt", encoding="utf-8") as file:
                payload = file.read()
            self.assertIn("recent", payload)
            self.assertNotIn("old", payload)

    def test_default_incident_window_uses_symptom_policy(self) -> None:
        detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)

        remote = agent.default_incident_window("REMOTE_DRIVER_OS", detected_at=detected)
        visit = agent.default_incident_window("VISIT_DISK_FAILURE", detected_at=detected)

        self.assertEqual(remote.range_minutes(), 20)
        self.assertEqual(remote.started_at, detected - timedelta(minutes=15))
        self.assertEqual(remote.ended_at, detected + timedelta(minutes=5))
        self.assertEqual(visit.range_minutes(), 40)
        self.assertEqual(visit.started_at, detected - timedelta(minutes=30))
        self.assertEqual(visit.ended_at, detected + timedelta(minutes=10))

    def test_gzip_window_selects_incident_window_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "incident-window.jsonl.gz"
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                incident_id="incident-1",
                consent_id="consent-1",
            )
            rows = [
                {"timestamp": (detected - timedelta(minutes=20)).isoformat(), "message": "before"},
                {"timestamp": (detected - timedelta(minutes=10)).isoformat(), "message": "inside"},
                {"timestamp": (detected + timedelta(minutes=6)).isoformat(), "message": "after"},
            ]
            source.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            size = agent.gzip_window(source, out, window)

            self.assertGreater(size, 0)
            with gzip.open(out, "rt", encoding="utf-8") as file:
                payload = file.read()
            self.assertIn("inside", payload)
            self.assertNotIn("before", payload)
            self.assertNotIn("after", payload)

    def test_multipart_contains_agent_upload_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "recent-30m.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")

            body, content_type = agent.build_multipart(
                {"rangeMinutes": "30", "schemaVersion": "1", "symptom": "demo"},
                "file",
                upload_file,
            )

            self.assertIn("multipart/form-data", content_type)
            self.assertIn(b'name="rangeMinutes"', body)
            self.assertIn(b"Content-Type: text/plain; charset=utf-8", body)
            self.assertIn(b"\r\n30\r\n", body)
            self.assertIn(b'name="schemaVersion"', body)
            self.assertIn(b'name="symptom"', body)
            self.assertIn(b'name="file"; filename="recent-30m.jsonl.gz"', body)

    def test_support_url_maps_api_port_to_web_port(self) -> None:
        self.assertEqual(
            agent.support_url("http://localhost:8080", "ticket-1"),
            "http://localhost:5173/support/ticket-1",
        )

    def test_support_url_prefers_configured_web_base_url(self) -> None:
        self.assertEqual(
            agent.support_url("https://api.example.com", "ticket-1", "https://app.example.com"),
            "https://app.example.com/support/ticket-1",
        )

    def test_upload_gzip_parses_ticket_id_from_response(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "recent-30m.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"ticketId":"ticket-public-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.upload_gzip(config, upload_file, "idem-key", "demo symptom")

            request = urlopen.call_args.args[0]
            self.assertEqual(result["ticketId"], "ticket-public-id")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/log-uploads")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertEqual(request.headers["Idempotency-key"], "idem-key")

    def test_upload_gzip_sends_incident_window_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "incident-window.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                trigger_type="USER_REQUEST",
                incident_id="incident-1",
                consent_id="consent-1",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )
            response = MagicMock()
            response.__enter__.return_value.read.return_value = b'{"ticketId":"ticket-public-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                agent.upload_gzip(config, upload_file, "idem-key", "demo symptom", window)

            body = urlopen.call_args.args[0].data
            self.assertIn(b'name="incidentId"', body)
            self.assertIn(b"\r\nincident-1\r\n", body)
            self.assertIn(b'name="symptomType"', body)
            self.assertIn(b"\r\nREMOTE_DRIVER_OS\r\n", body)
            self.assertIn(b'name="rangeMinutes"', body)
            self.assertIn(b"\r\n20\r\n", body)
            self.assertIn(b'name="consentId"', body)

    def test_ensure_default_config_creates_background_config(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-config.json"

            with patch("buildgraph_agent.restrict_file_to_current_user") as restrict:
                created = agent.ensure_default_config(path)
            config = agent.load_config(created)

            self.assertEqual(created, path)
            self.assertEqual(config.api_base_url, "http://localhost:8080")
            self.assertEqual(config.web_base_url, "http://localhost:5173")
            self.assertEqual(config.environment, "local")
            self.assertEqual(config.activation_token, "demo-agent-activation-token")
            self.assertEqual(config.log_dir, Path(directory) / "logs")
            self.assertIsNone(config.agent_token)
            restrict.assert_called_once_with(path)

    def test_save_agent_token_restricts_config_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-config.json"
            path.write_text(
                json.dumps(
                    {
                        "apiBaseUrl": "http://localhost:8080",
                        "activationToken": "activation-token",
                        "deviceFingerprintHash": "fingerprint",
                        "osVersion": "Windows 11",
                        "agentVersion": "test-agent",
                        "policyVersion": "test-policy",
                    }
                ),
                encoding="utf-8",
            )

            with patch("buildgraph_agent.restrict_file_to_current_user") as restrict:
                agent.save_agent_token(path, "agent-token")

            self.assertEqual(agent.load_config(path).agent_token, "agent-token")
            restrict.assert_called_once_with(path)

    def test_gzip_recent_fails_when_recent_window_is_empty(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "agent-metrics.jsonl"
            out = Path(directory) / "recent-30m.jsonl.gz"
            old = datetime.now(timezone(timedelta(hours=9))) - timedelta(hours=2)
            source.write_text(json.dumps({"timestamp": old.isoformat(), "message": "old"}) + "\n", encoding="utf-8")

            with self.assertRaises(agent.AgentError) as error:
                agent.gzip_recent(source, out, 30)

            self.assertIn("no log rows found", str(error.exception))

    def test_read_log_tail_returns_recent_valid_rows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [{"message": f"row-{index}"} for index in range(4)]
            path.write_text(
                "\n".join(json.dumps(row) for row in rows[:2])
                + "\nnot-json\n"
                + "\n".join(json.dumps(row) for row in rows[2:])
                + "\n",
                encoding="utf-8",
            )

            tail = agent.read_log_tail(path, 2)

            self.assertEqual([row["message"] for row in tail], ["row-2", "row-3"])

    def test_read_log_tail_returns_empty_when_file_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing.jsonl"

            self.assertEqual(agent.read_log_tail(path), [])

    def test_read_log_hour_filters_selected_day_and_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {"timestamp": "2026-07-02T13:59:59+09:00", "message": "before"},
                {"timestamp": "2026-07-02T14:00:00+09:00", "message": "start"},
                {"timestamp": "2026-07-02T14:30:00+09:00", "message": "middle"},
                {"timestamp": "2026-07-02T15:00:00+09:00", "message": "after"},
                {"timestamp": "2026-07-03T14:30:00+09:00", "message": "other-day"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            selected = agent.read_log_hour(path, "2026-07-02", 14)

            self.assertEqual([row["message"] for row in selected], ["start", "middle"])

    def test_read_log_hour_rejects_invalid_date_or_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text('{"timestamp":"2026-07-02T14:00:00+09:00"}\n', encoding="utf-8")

            self.assertEqual(agent.read_log_hour(path, "bad-date", 14), [])
            self.assertEqual(agent.read_log_hour(path, "2026-07-02", 24), [])

    def test_powershell_string_escapes_single_quotes(self) -> None:
        self.assertEqual(agent.powershell_string("C:\\Users\\O'Brien"), "'C:\\Users\\O''Brien'")

    def test_run_background_is_available_as_cli_command(self) -> None:
        with patch("buildgraph_agent.run_background", return_value=0) as run_background:
            exit_code = agent.main(["run-background", "--interval-seconds", "1", "--no-tray"])

        self.assertEqual(exit_code, 0)
        self.assertEqual(run_background.call_args.args[1], 1)
        self.assertFalse(run_background.call_args.args[2])


if __name__ == "__main__":
    unittest.main()
