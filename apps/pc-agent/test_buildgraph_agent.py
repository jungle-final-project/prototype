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

    def test_append_metric_with_row_returns_written_issue_event(self) -> None:
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

            log_path, row = agent.append_metric_with_row(config, index=7)

            self.assertEqual(log_path, config.log_dir / "agent-metrics.jsonl")
            self.assertEqual(row["eventType"], "DISPLAY_DRIVER_WARNING")
            self.assertEqual(row["message"], "Display driver warning observed.")

    def test_issue_notification_is_throttled(self) -> None:
        runtime = agent.AgentRuntime()
        warning = {"eventType": "DISPLAY_DRIVER_WARNING", "message": "Display driver warning observed."}
        normal = {"eventType": "DEMO_METRIC", "message": "Demo metric collected."}

        self.assertFalse(agent.should_show_issue_notification(normal, runtime, now=1000))
        self.assertTrue(agent.should_show_issue_notification(warning, runtime, now=1000))
        self.assertFalse(agent.should_show_issue_notification(warning, runtime, now=1059))
        self.assertTrue(agent.should_show_issue_notification(warning, runtime, now=1060))

    def test_issue_macro_maps_display_driver_warning_to_remote_draft(self) -> None:
        macro = agent.issue_macro({"eventType": "DISPLAY_DRIVER_WARNING", "message": "Display driver warning observed."})

        self.assertEqual(macro.symptom_type, "REMOTE_DRIVER_OS")
        self.assertEqual(macro.support_request_kind, "REMOTE_REQUESTED")
        self.assertIn("디스플레이", macro.title)
        self.assertIn("Display driver warning observed.", macro.symptom)

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

    def test_create_as_draft_sends_prefill_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            upload_file = Path(directory) / "incident-window.jsonl.gz"
            upload_file.write_bytes(b"gzip-bytes")
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            window = agent.default_incident_window(
                "REMOTE_DRIVER_OS",
                detected_at=detected,
                trigger_type="AGENT_DETECTED",
                incident_id="incident-1",
                selected_by_user=False,
                consent_id="consent-1",
            )
            macro = agent.IssueDraftMacro(
                symptom_type="REMOTE_DRIVER_OS",
                title="디스플레이 드라이버 경고가 감지되었습니다",
                detail="PC Agent가 경고를 감지했습니다.",
                symptom="PC Agent 자동 감지: Display driver warning observed.",
                support_request_kind="REMOTE_REQUESTED",
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
            response.__enter__.return_value.read.return_value = b'{"draftId":"draft-public-id","logUploadId":"log-id"}'
            response.__exit__.return_value = None

            with patch("buildgraph_agent.urllib.request.urlopen", return_value=response) as urlopen:
                result = agent.create_as_draft(config, upload_file, "draft-key", macro, window)

            request = urlopen.call_args.args[0]
            self.assertEqual(result["draftId"], "draft-public-id")
            self.assertEqual(request.full_url, "http://localhost:8080/api/agent/as-drafts")
            self.assertEqual(request.headers["Authorization"], "Bearer token")
            self.assertEqual(request.headers["Idempotency-key"], "draft-key")
            self.assertIn(b'name="title"', request.data)
            self.assertIn("디스플레이 드라이버".encode("utf-8"), request.data)
            self.assertIn(b'name="supportRequestKind"', request.data)
            self.assertIn(b"\r\nREMOTE_REQUESTED\r\n", request.data)

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

    def test_read_log_hour_accepts_envelope_collected_at(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            rows = [
                {"collectedAt": "2026-07-02T04:59:59Z", "kind": "SYSTEM_METRIC", "message": "before"},
                {
                    "collectedAt": "2026-07-02T05:30:00Z",
                    "kind": "SYSTEM_METRIC",
                    "payload": {"cpuUsagePercent": 11.5, "message": "inside"},
                },
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            selected = agent.read_log_hour(path, "2026-07-02", 14)

            self.assertEqual(len(selected), 1)
            self.assertEqual(selected[0]["payload"]["message"], "inside")

    def test_read_log_hour_rejects_invalid_date_or_hour(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text('{"timestamp":"2026-07-02T14:00:00+09:00"}\n', encoding="utf-8")

            self.assertEqual(agent.read_log_hour(path, "bad-date", 14), [])
            self.assertEqual(agent.read_log_hour(path, "2026-07-02", 24), [])

    def test_status_log_summary_uses_recent_rows_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            now = datetime.now(agent.KST)
            rows = [
                {"timestamp": (now - timedelta(hours=2)).isoformat(), "message": "old"},
                {"timestamp": (now - timedelta(minutes=20)).isoformat(), "message": "recent-1"},
                {"timestamp": (now - timedelta(minutes=10)).isoformat(), "message": "recent-2"},
            ]
            path.write_text("\n".join(json.dumps(row) for row in rows) + "\n", encoding="utf-8")

            summary = agent.read_status_log_summary_rows(path, 6)

            self.assertEqual([row["message"] for row in summary], ["recent-1", "recent-2"])

    def test_display_log_summary_values_hide_sensitive_values(self) -> None:
        row = {
            "collectedAt": "2026-07-02T05:30:00Z",
            "kind": "AGENT_HEALTH",
            "payload": {
                "cpuUsagePercent": 20.0,
                "memoryUsedPercent": 40.0,
                "diskUsedPercent": 50.0,
                "processList": ["secret.exe"],
                "message": "upload failed token=secret C:\\Users\\me\\raw.log",
            },
        }

        values = agent.display_log_summary_values(row)
        joined = " ".join(values).lower()

        self.assertEqual(values[4], "-")
        self.assertIn("agent 상태", joined)
        self.assertNotIn("token", joined)
        self.assertNotIn("c:\\users", joined)
        self.assertNotIn("secret.exe", joined)

    def test_detect_recent_signals_uses_final_scenarios_without_simple_usage_noise(self) -> None:
        rows = [
            {
                "timestamp": "2026-07-02T14:00:00+09:00",
                "kind": "SYSTEM_METRIC",
                "cpuUsage": 99.0,
                "memoryUsage": 94.0,
                "message": "High CPU and memory usage only.",
            },
            {
                "timestamp": "2026-07-02T14:05:00+09:00",
                "kind": "DISPLAY_DRIVER_WARNING",
                "message": "Display driver warning observed.",
            },
            {
                "timestamp": "2026-07-02T14:10:00+09:00",
                "kind": "EVENT_LOG",
                "message": "Kernel-Power unexpected shutdown repeated.",
            },
        ]

        signals = agent.detect_recent_signals(rows)

        self.assertEqual([signal["code"] for signal in signals], ["VISIT_POWER_SHUTDOWN", "REMOTE_DRIVER_OS"])

    def test_event_panel_model_uses_only_clear_user_safe_signals(self) -> None:
        detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
        signals = [
            {
                "code": "REMOTE_STORAGE_MEMORY",
                "title": "memory pressure token=secret C:\\Users\\me\\raw.log",
                "timestamp": detected,
                "date": "2026-07-02",
                "hour": 14,
            },
            {
                "code": "REMOTE_DRIVER_OS",
                "title": "driver token=secret C:\\Users\\me\\raw.log",
                "timestamp": detected,
                "date": "2026-07-02",
                "hour": 14,
            },
            {
                "code": "VISIT_WHEA_BSOD",
                "title": "WHEA",
                "timestamp": detected + timedelta(minutes=1),
                "date": "2026-07-02",
                "hour": 14,
            },
        ]

        model = agent.event_panel_model(signals)

        self.assertIsNotNone(model)
        assert model is not None
        joined = " ".join(model["summaries"]).lower()
        self.assertIn("드라이버", joined)
        self.assertIn("블루스크린", joined)
        self.assertEqual(model["detectedTime"], "2026-07-02 14:00")
        self.assertIn("드라이버", model["signalTitle"])
        self.assertEqual(model["windowText"], "13:45 ~ 14:05 (20분)")
        self.assertNotIn("memory pressure", joined)
        self.assertNotIn("token", joined)
        self.assertNotIn("c:\\users", joined)

    def test_event_panel_failure_message_hides_raw_error_detail(self) -> None:
        message = agent.event_panel_failure_message(
            agent.UploadError("upload failed: HTTP 400 token=secret C:\\Users\\me\\raw.log consentAccepted=false")
        )

        self.assertIn("동의", message)
        self.assertNotIn("token", message.lower())
        self.assertNotIn("c:\\users", message.lower())

    def test_upload_event_panel_request_uses_existing_incident_upload_flow(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_dir = root / "logs"
            log_dir.mkdir()
            source = log_dir / "agent-metrics.jsonl"
            detected = datetime(2026, 7, 2, 14, 0, tzinfo=agent.KST)
            source.write_text(
                json.dumps(
                    {
                        "timestamp": detected.isoformat(),
                        "kind": "DISPLAY_DRIVER_WARNING",
                        "message": "Display driver warning observed.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="agent-token",
                log_dir=log_dir,
                agent_version="test-agent",
                policy_version="test-policy",
                web_base_url="http://localhost:5173",
            )
            signals = [
                {
                    "code": "REMOTE_DRIVER_OS",
                    "title": "드라이버/OS 오류",
                    "timestamp": detected,
                    "date": "2026-07-02",
                    "hour": 14,
                }
            ]

            with patch("buildgraph_agent.upload_gzip", return_value={"ticketId": "ticket-public-id"}) as upload:
                ticket_id, url = agent.upload_event_panel_request(config, source, signals, "방문 접수")

            self.assertEqual(ticket_id, "ticket-public-id")
            self.assertEqual(url, "http://localhost:5173/support/ticket-public-id")
            upload_args = upload.call_args.args
            self.assertEqual(upload_args[0], config)
            self.assertTrue(upload_args[1].name.endswith(".jsonl.gz"))
            self.assertTrue(upload_args[2].startswith("agent-panel-"))
            self.assertIn("방문 접수", upload_args[3])
            self.assertIn("드라이버", upload_args[3])
            self.assertEqual(upload_args[4].trigger_type, "SYSTEM_DETECTED")
            self.assertEqual(upload_args[4].symptom_type, "REMOTE_DRIVER_OS")
            self.assertTrue(upload_args[4].selected_by_user)

    def test_display_log_table_values_hide_sensitive_values(self) -> None:
        row = {
            "timestamp": "2026-07-02T14:00:00+09:00",
            "kind": "AGENT_HEALTH",
            "payload": {
                "cpuUsagePercent": 20.0,
                "processList": ["secret.exe", "other.exe"],
                "message": "upload failed token=secret C:\\Users\\me\\raw.log",
            },
        }

        values = agent.display_log_table_values(row)
        joined = " ".join(values).lower()

        self.assertIn("agent_health", joined)
        self.assertIn("[hidden]", joined)
        self.assertIn("[path hidden]", joined)
        self.assertNotIn("secret.exe", joined)
        self.assertNotIn("c:\\users\\me", joined)

    def test_status_home_model_does_not_expose_agent_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "agent-metrics.jsonl"
            path.write_text("", encoding="utf-8")
            config = agent.AgentConfig(
                api_base_url="http://localhost:8080",
                activation_token="activation-token",
                device_fingerprint_hash="fingerprint",
                os_version="Windows 11",
                agent_token="raw-agent-token",
                log_dir=Path(directory),
                agent_version="test-agent",
                policy_version="test-policy",
            )

            model = agent.status_home_model(config, path)

            self.assertEqual(model["agentStatus"], "정상 실행 중")
            self.assertNotIn("raw-agent-token", json.dumps(model, ensure_ascii=False))

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
