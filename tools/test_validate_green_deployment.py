from __future__ import annotations

import re
import unittest
from pathlib import Path
from typing import Any

import yaml


ROOT = Path(__file__).resolve().parents[1]
ECR_COMPOSE = ROOT / "compose.api.ecr.prod.yaml"
DEPLOY_SCRIPT = ROOT / "tools/deploy_green_ecr.sh"
WORKFLOWS = {
    "web": ROOT / ".github/workflows/deploy-web-green.yml",
    "api": ROOT / ".github/workflows/deploy-api-green.yml",
    "xgb": ROOT / ".github/workflows/deploy-xgb-green.yml",
}

EXPECTED_SERVICES = {"nginx", "api", "xgb-reranker"}
EXPECTED_API_IMAGE = "${API_IMAGE_URI:?API_IMAGE_URI is required}"
EXPECTED_XGB_IMAGE = "${XGB_IMAGE_URI:?XGB_IMAGE_URI is required}"
GREEN_CLOUDFRONT_VARIABLE = "vars.GREEN_CF_DISTRIBUTION_ID"
BLUE_CLOUDFRONT_ID = "EI6MMNZLTTN3H"


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        value = yaml.safe_load(file)
    if not isinstance(value, dict):
        raise AssertionError(f"{path} must contain a YAML mapping")
    return value


def published_ports(service: dict[str, Any]) -> list[Any]:
    ports = service.get("ports", [])
    return ports if isinstance(ports, list) else [ports]


class GreenDeploymentContractTest(unittest.TestCase):
    maxDiff = None

    def test_required_phase8_files_exist(self) -> None:
        required = [ECR_COMPOSE, DEPLOY_SCRIPT, *WORKFLOWS.values()]
        missing = [str(path.relative_to(ROOT)) for path in required if not path.is_file()]
        self.assertEqual([], missing, f"missing Phase 8 files: {missing}")

    def test_ecr_compose_has_only_three_runtime_services(self) -> None:
        compose = load_yaml(ECR_COMPOSE)
        services = compose.get("services")
        self.assertIsInstance(services, dict)
        self.assertEqual(EXPECTED_SERVICES, set(services))

    def test_ecr_compose_uses_required_images_without_build(self) -> None:
        services = load_yaml(ECR_COMPOSE)["services"]

        self.assertNotIn("build", services["api"])
        self.assertNotIn("build", services["xgb-reranker"])
        self.assertEqual(EXPECTED_API_IMAGE, services["api"].get("image"))
        self.assertEqual(EXPECTED_XGB_IMAGE, services["xgb-reranker"].get("image"))

    def test_only_nginx_publishes_host_port_80(self) -> None:
        services = load_yaml(ECR_COMPOSE)["services"]

        self.assertEqual(["80:80"], published_ports(services["nginx"]))
        self.assertEqual([], published_ports(services["api"]))
        self.assertEqual([], published_ports(services["xgb-reranker"]))
        self.assertEqual(["8080"], services["api"].get("expose"))
        self.assertEqual(["8091"], services["xgb-reranker"].get("expose"))

    def test_deploy_script_has_ecr_ssm_runtime_safety_contract(self) -> None:
        script = DEPLOY_SCRIPT.read_text(encoding="utf-8")

        required_markers = [
            "flock",
            "green-images.env",
            "git checkout --detach",
            "aws secretsmanager get-secret-value",
            "aws ecr get-login-password",
            "docker compose",
            "config --quiet",
            "--no-deps",
            "--force-recreate",
            "--no-build",
            "rollback",
            "/api/health",
            "healthy",
        ]
        for marker in required_markers:
            with self.subTest(marker=marker):
                self.assertIn(marker, script)

        self.assertIn("buildgraph-demo-api-green", script)
        self.assertIn("buildgraph-demo-xgb-reranker-green", script)
        self.assertNotIn("set -x", script)
        self.assertNotRegex(script, r"cat\s+[^\n]*\.env\.prod")

    def test_green_workflows_use_oidc_without_long_lived_aws_keys(self) -> None:
        for name, path in WORKFLOWS.items():
            text = path.read_text(encoding="utf-8")
            workflow = load_yaml(path)

            with self.subTest(workflow=name):
                self.assertEqual("read", workflow.get("permissions", {}).get("contents"))
                self.assertEqual("write", workflow.get("permissions", {}).get("id-token"))
                self.assertIn("aws-actions/configure-aws-credentials@v4", text)
                self.assertIn("vars.AWS_DEPLOY_ROLE_ARN", text)
                self.assertNotRegex(text, r"AWS_(ACCESS_KEY_ID|SECRET_ACCESS_KEY)")
                self.assertNotIn("secrets.AWS_", text)
                self.assertNotRegex(text, r"(?m)^\s*environment:\s*")

    def test_api_and_xgb_workflows_use_sha_images_and_ssm(self) -> None:
        for name in ("api", "xgb"):
            text = WORKFLOWS[name].read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("github.sha", text)
                self.assertNotRegex(text, r"(?i)(?:imageTag=|:)\s*latest\b")
                self.assertIn("aws ssm send-command", text)
                self.assertIn("tools/deploy_green_ecr.sh", text)
                self.assertNotRegex(text, r"(?m)^\s*[^#\n]*(ssh|rsync)\b")

    def test_web_workflow_targets_only_green_and_keeps_cache_classes_separate(self) -> None:
        text = WORKFLOWS["web"].read_text(encoding="utf-8")

        self.assertIn(GREEN_CLOUDFRONT_VARIABLE, text)
        self.assertNotIn(BLUE_CLOUDFRONT_ID, text)
        self.assertIn("public,max-age=31536000,immutable", text)
        self.assertIn("no-cache,max-age=0,must-revalidate", text)
        self.assertIn("public,max-age=3600", text)
        self.assertIn("/downloads/pc-agent/latest.json", text)

    def test_push_deployments_are_guarded_by_green_cd_variable(self) -> None:
        for name, path in WORKFLOWS.items():
            text = path.read_text(encoding="utf-8")
            with self.subTest(workflow=name):
                self.assertIn("GREEN_CD_ENABLED", text)
                self.assertIn("workflow_dispatch", text)

    def test_image_example_uses_two_immutable_sha_uris(self) -> None:
        example = (ROOT / ".env.images.example").read_text(encoding="utf-8")
        values = dict(
            line.split("=", 1)
            for line in example.splitlines()
            if line and not line.startswith("#")
        )

        self.assertEqual({"API_IMAGE_URI", "XGB_IMAGE_URI"}, set(values))
        for value in values.values():
            self.assertRegex(value, r"^[^\s:]+(?:/[^\s:]+)+:[0-9a-f]{40}$")


if __name__ == "__main__":
    unittest.main()
