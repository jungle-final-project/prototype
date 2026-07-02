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
            self.assertIn(b"\r\n30\r\n", body)
            self.assertIn(b'name="schemaVersion"', body)
            self.assertIn(b'name="symptom"', body)
            self.assertIn(b'name="file"; filename="recent-30m.jsonl.gz"', body)

    def test_support_url_maps_api_port_to_web_port(self) -> None:
        self.assertEqual(
            agent.support_url("http://localhost:8080", "ticket-1"),
            "http://localhost:5173/support/ticket-1",
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


if __name__ == "__main__":
    unittest.main()
