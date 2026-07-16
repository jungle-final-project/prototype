from __future__ import annotations

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PREPARE_SCRIPT = ROOT / "tools/prepare_green_asg_builder.sh"
WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/deploy-api-green.yml",
    ROOT / ".github/workflows/deploy-xgb-green.yml",
)


class GreenAsgBuilderPreparationTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.source_repo = self.temp_root / "source"
        self.app_root = self.temp_root / "app"
        self.fake_bin = self.temp_root / "bin"
        self.state_dir = self.temp_root / "state"
        self.os_release = self.temp_root / "os-release"
        self.trace_file = self.temp_root / "trace.log"
        self.fake_bin.mkdir()
        self.state_dir.mkdir()
        self._create_source_repository()
        self._install_fake_commands()
        self._write_os_release("ubuntu", "24.04")

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _run(self, git_sha: str | None = None, **overrides: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.fake_bin}:{environment['PATH']}",
                "AWS_ACCOUNT_ID": "443915990705",
                "AWS_REGION": "ap-northeast-2",
                "BUILDGRAPH_ALLOW_NON_ROOT_FOR_TESTS": "true",
                "BUILDGRAPH_BUILDER_SKIP_INSTALL_FOR_TESTS": "true",
                "BUILDGRAPH_APP_ROOT": str(self.app_root),
                "BUILDGRAPH_BUILDER_STATE_DIR": str(self.state_dir),
                "BUILDGRAPH_FILE_OWNER": f"{os.getuid()}:{os.getgid()}",
                "BUILDGRAPH_OS_RELEASE_FILE": str(self.os_release),
                "BUILDGRAPH_REPOSITORY_URL": str(self.source_repo),
                "BUILDGRAPH_GREEN_IMAGE_MANIFEST": str(self.temp_root / "green-images.env"),
                "BUILDGRAPH_ASG_RUNTIME_ENV": str(self.temp_root / "asg-runtime.env"),
                "BUILDGRAPH_AWS_CREDENTIALS_ROOT": str(self.temp_root / "root-credentials"),
                "BUILDGRAPH_AWS_CREDENTIALS_APP": str(self.temp_root / "app-credentials"),
                "FAKE_ACCOUNT": "443915990705",
                "FAKE_ARCH": "x86_64",
                "FAKE_REGION": "ap-northeast-2",
                "FAKE_TRACE_FILE": str(self.trace_file),
            }
        )
        environment.update(overrides)
        arguments = ["bash", str(PREPARE_SCRIPT), git_sha or self.git_sha]
        return subprocess.run(
            arguments,
            cwd=ROOT,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def _create_source_repository(self) -> None:
        subprocess.run(
            ["git", "init", "-q", "-b", "main", str(self.source_repo)],
            check=True,
        )
        required_files = {
            "compose.api.ecr.prod.yaml": "services: {}\n",
            "infra/nginx/api.conf": "server { listen 80; }\n",
            "infra/nginx/nginx.conf": "events {}\nhttp {}\n",
            "tools/bootstrap_green_asg.sh": "#!/usr/bin/env bash\nexit 0\n",
        }
        for relative_path, content in required_files.items():
            path = self.source_repo / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        (self.source_repo / "tools/bootstrap_green_asg.sh").chmod(0o755)
        subprocess.run(["git", "-C", str(self.source_repo), "add", "."], check=True)
        commit_environment = os.environ.copy()
        commit_environment.update(
            {
                "GIT_AUTHOR_NAME": "BuildGraph Test",
                "GIT_AUTHOR_EMAIL": "test@example.com",
                "GIT_COMMITTER_NAME": "BuildGraph Test",
                "GIT_COMMITTER_EMAIL": "test@example.com",
            }
        )
        subprocess.run(
            ["git", "-C", str(self.source_repo), "commit", "-q", "-m", "fixture"],
            check=True,
            env=commit_environment,
        )
        self.git_sha = subprocess.run(
            ["git", "-C", str(self.source_repo), "rev-parse", "HEAD"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()

    def _install_fake_commands(self) -> None:
        self._write_executable(
            "curl",
            """
            case "$*" in
              *latest/api/token*) printf '%s' 'test-imds-token' ;;
              *latest/meta-data/placement/region*) printf '%s' "${FAKE_REGION}" ;;
              *) echo "unexpected curl call: $*" >&2; exit 91 ;;
            esac
            """,
        )
        self._write_executable(
            "aws",
            """
            printf '%s\n' "aws:$*" >> "$FAKE_TRACE_FILE"
            if [[ "$1 $2" == "sts get-caller-identity" ]]; then
              printf '%s\n' "$FAKE_ACCOUNT"
              exit 0
            fi
            echo "unexpected aws call: $*" >&2
            exit 92
            """,
        )
        self._write_executable(
            "docker",
            """
            printf '%s\n' "docker:$*" >> "$FAKE_TRACE_FILE"
            case "$*" in
              "ps -aq"|"image ls -q") exit 0 ;;
              "version"|"compose version") printf '%s\n' 'test-version' ;;
              *) echo "unexpected docker call: $*" >&2; exit 93 ;;
            esac
            """,
        )
        self._write_executable(
            "uname",
            """
            [[ "${1:-}" == "-m" ]] || exit 94
            printf '%s\n' "$FAKE_ARCH"
            """,
        )
        self._write_executable(
            "apt-get",
            """
            printf '%s\n' "apt-get:$*" >> "$FAKE_TRACE_FILE"
            [[ "${FAKE_APT_FAIL:-false}" != "true" ]] || exit 42
            exit 0
            """,
        )
        for command_name in ("dpkg", "dpkg-query", "jq", "runuser", "systemctl", "usermod"):
            self._write_executable(command_name, "exit 0\n")

    def _write_executable(self, name: str, body: str) -> None:
        path = self.fake_bin / name
        path.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            + textwrap.dedent(body).lstrip(),
            encoding="utf-8",
        )
        path.chmod(0o755)

    def _write_os_release(self, os_id: str, version_id: str) -> None:
        self.os_release.write_text(
            f'ID="{os_id}"\nVERSION_ID="{version_id}"\n',
            encoding="utf-8",
        )

    def test_script_exists_and_ci_runs_contract(self) -> None:
        self.assertTrue(PREPARE_SCRIPT.is_file())
        for workflow in WORKFLOWS:
            with self.subTest(workflow=workflow.name):
                self.assertIn(
                    "tools.test_prepare_green_asg_builder",
                    workflow.read_text(encoding="utf-8"),
                )

    def test_builder_script_never_fetches_secrets_or_starts_runtime(self) -> None:
        script = PREPARE_SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("secretsmanager get-secret-value", script)
        self.assertNotRegex(script, r"docker\s+(pull|run)")
        self.assertNotRegex(script, r"docker\s+compose\s+.*\bup\b")
        self.assertNotIn("set -x", script)
        self.assertIn("amazon-ssm-agent", script)

    def test_prepares_exact_commit_without_runtime_artifacts(self) -> None:
        result = self._run()
        self.assertEqual(0, result.returncode, result.stdout)
        actual_sha = subprocess.run(
            ["git", "-C", str(self.app_root), "rev-parse", "HEAD"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        ).stdout.strip()
        self.assertEqual(self.git_sha, actual_sha)
        self.assertTrue((self.state_dir / "builder-prepared").is_file())
        self.assertFalse((self.app_root / ".env.prod").exists())
        trace = self.trace_file.read_text(encoding="utf-8")
        self.assertNotIn("secretsmanager", trace)
        self.assertNotIn("docker:pull", trace)
        self.assertNotIn("docker:run", trace)

    def test_second_run_is_idempotent(self) -> None:
        first = self._run()
        second = self._run()
        self.assertEqual(0, first.returncode, first.stdout)
        self.assertEqual(0, second.returncode, second.stdout)

    def test_rejects_invalid_git_sha(self) -> None:
        result = self._run("not-a-commit")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("40-character", result.stdout)

    def test_rejects_wrong_account(self) -> None:
        result = self._run(FAKE_ACCOUNT="000000000000")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("account", result.stdout.lower())

    def test_rejects_wrong_region(self) -> None:
        result = self._run(FAKE_REGION="us-east-1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("region", result.stdout.lower())

    def test_rejects_wrong_operating_system(self) -> None:
        self._write_os_release("debian", "12")
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Ubuntu 24.04", result.stdout)

    def test_rejects_wrong_architecture(self) -> None:
        result = self._run(FAKE_ARCH="aarch64")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("x86_64", result.stdout)

    def test_install_failure_stops_preparation(self) -> None:
        result = self._run(
            BUILDGRAPH_BUILDER_SKIP_INSTALL_FOR_TESTS="false",
            FAKE_APT_FAIL="true",
        )
        self.assertEqual(42, result.returncode, result.stdout)
        self.assertFalse((self.state_dir / "builder-prepared").exists())

    def test_rejects_existing_secret_artifact(self) -> None:
        self.app_root.mkdir()
        (self.app_root / ".env.prod").write_text(
            "DB_PASSWORD=must-not-be-read\n",
            encoding="utf-8",
        )
        result = self._run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtime or credential artifact", result.stdout)
        self.assertNotIn("must-not-be-read", result.stdout)


if __name__ == "__main__":
    unittest.main()
