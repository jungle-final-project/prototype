from __future__ import annotations

import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/provision_green_private_app_network.sh"

AWS_ACCOUNT_ID = "443915990705"
AWS_REGION = "ap-northeast-2"
VPC_ID = "vpc-06c90b864a62f93a4"
ASG_SECURITY_GROUP_ID = "sg-0a0a2fe0e54027420"

SUBNET_A_NAME = "buildgraph-demo-subnet-private-app1-ap-northeast-2a"
SUBNET_A_CIDR = "10.0.34.0/24"
SUBNET_A_AZ = "ap-northeast-2a"
SUBNET_A_ID = "subnet-private-app-a"

SUBNET_B_NAME = "buildgraph-demo-subnet-private-app2-ap-northeast-2b"
SUBNET_B_CIDR = "10.0.35.0/24"
SUBNET_B_AZ = "ap-northeast-2b"
SUBNET_B_ID = "subnet-private-app-b"

ROUTE_TABLE_A_NAME = "buildgraph-demo-rtb-private-app1-ap-northeast-2a"
ROUTE_TABLE_A_ID = "rtb-private-app-a"
ROUTE_TABLE_B_NAME = "buildgraph-demo-rtb-private-app2-ap-northeast-2b"
ROUTE_TABLE_B_ID = "rtb-private-app-b"

NAT_NAME = "buildgraph-demo-nat-regional"
NAT_ID = "nat-regional"
ENDPOINT_SECURITY_GROUP_NAME = "buildgraph-demo-private-app-endpoints-sg"
ENDPOINT_SECURITY_GROUP_ID = "sg-private-app-endpoints"

ENDPOINT_NAMES = {
    "s3": "buildgraph-demo-vpce-s3-private-app",
    "ecr.api": "buildgraph-demo-vpce-ecr-api-private-app",
    "ecr.dkr": "buildgraph-demo-vpce-ecr-dkr-private-app",
    "secretsmanager": "buildgraph-demo-vpce-secretsmanager-private-app",
    "ssm": "buildgraph-demo-vpce-ssm-private-app",
    "ssmmessages": "buildgraph-demo-vpce-ssmmessages-private-app",
}
ENDPOINT_IDS = {
    suffix: "vpce-" + suffix.replace(".", "-") for suffix in ENDPOINT_NAMES
}
PROJECT_TAG_MARKERS = (
    "{Key=Stack,Value=green}",
    "{Key=Service,Value=api}",
    "{Key=ManagedBy,Value=private-app-network}",
)

MUTATING_EC2_COMMANDS = {
    "associate-route-table",
    "authorize-security-group-ingress",
    "create-nat-gateway",
    "create-route",
    "create-route-table",
    "create-security-group",
    "create-subnet",
    "create-tags",
    "create-vpc-endpoint",
    "delete-nat-gateway",
    "delete-route",
    "delete-route-table",
    "delete-security-group",
    "delete-subnet",
    "delete-vpc-endpoints",
    "disassociate-route-table",
    "modify-subnet-attribute",
    "modify-vpc-endpoint",
    "replace-route",
    "replace-route-table-association",
    "revoke-security-group-ingress",
}


class GreenPrivateAppNetworkTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.fake_bin = self.temp_root / "bin"
        self.trace_file = self.temp_root / "aws-trace.jsonl"
        self.fake_bin.mkdir()
        self._install_fake_aws()

        self.environment = os.environ.copy()
        self.environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{self.environment['PATH']}",
                "AWS_REGION": AWS_REGION,
                "BUILDGRAPH_VPCE_WAIT_MAX_ATTEMPTS": "2",
                "BUILDGRAPH_VPCE_WAIT_POLL_SECONDS": "0",
                "FAKE_AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "FAKE_AWS_SCENARIO": "empty",
                "FAKE_AWS_TRACE_FILE": str(self.trace_file),
            }
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _install_fake_aws(self) -> None:
        fake_aws = self.fake_bin / "aws"
        fake_aws.write_text(
            textwrap.dedent(
                f"""\
                #!/usr/bin/env python3
                import json
                import os
                import sys

                args = sys.argv[1:]
                trace_file = os.environ["FAKE_AWS_TRACE_FILE"]
                with open(trace_file, "a", encoding="utf-8") as trace:
                    trace.write(json.dumps(args) + "\\n")

                scenario = os.environ.get("FAKE_AWS_SCENARIO", "empty")
                converged = scenario in {{
                    "converged",
                    "endpoint_drift",
                    "pending_endpoints",
                    "pending_nat",
                }}
                has_private_subnets = converged or scenario == "route_drift"

                def option(name):
                    try:
                        return args[args.index(name) + 1]
                    except (ValueError, IndexError):
                        return ""

                def filter_value(name):
                    prefix = "Name=" + name + ",Values="
                    for argument in args:
                        if argument.startswith(prefix):
                            return argument[len(prefix):]
                    return ""

                def emit(*rows):
                    for row in rows:
                        if isinstance(row, (tuple, list)):
                            print("\\t".join(str(value) for value in row))
                        else:
                            print(row)

                def endpoint_suffix(service_name):
                    prefix = "com.amazonaws.{AWS_REGION}."
                    if not service_name.startswith(prefix):
                        return ""
                    return service_name[len(prefix):]

                command = tuple(args[:2])
                query = option("--query")

                if command == ("sts", "get-caller-identity"):
                    emit(os.environ.get("FAKE_AWS_ACCOUNT_ID", "{AWS_ACCOUNT_ID}"))
                elif command == ("ec2", "describe-vpcs"):
                    emit(("{VPC_ID}", "available"))
                elif command == ("ec2", "describe-vpc-attribute"):
                    attribute = option("--attribute")
                    if attribute == "enableDnsSupport":
                        emit("False" if scenario == "dns_false" else "True")
                    elif attribute == "enableDnsHostnames":
                        emit("True")
                    else:
                        sys.exit("unexpected VPC attribute: " + attribute)
                elif command == ("ec2", "describe-security-groups"):
                    group_ids = option("--group-ids")
                    group_name = filter_value("group-name")
                    if group_ids == "{ASG_SECURITY_GROUP_ID}":
                        emit(("{ASG_SECURITY_GROUP_ID}", "{VPC_ID}"))
                    elif group_ids == "{ENDPOINT_SECURITY_GROUP_ID}" or (
                        group_name == "{ENDPOINT_SECURITY_GROUP_NAME}" and converged
                    ):
                        emit(
                            (
                                "{ENDPOINT_SECURITY_GROUP_ID}",
                                "{VPC_ID}",
                                "{ENDPOINT_SECURITY_GROUP_NAME}",
                                "{ENDPOINT_SECURITY_GROUP_NAME}",
                            )
                        )
                elif command == ("ec2", "describe-security-group-rules"):
                    if converged:
                        emit(
                            (
                                "tcp",
                                "443",
                                "443",
                                "{ASG_SECURITY_GROUP_ID}",
                                "None",
                                "None",
                                "None",
                            )
                        )
                elif command == ("ec2", "describe-subnets"):
                    name = filter_value("tag:Name")
                    if name:
                        if name == "{SUBNET_A_NAME}" and has_private_subnets:
                            emit(
                                (
                                    "{SUBNET_A_ID}",
                                    "{SUBNET_A_CIDR}",
                                    "{SUBNET_A_AZ}",
                                    "False",
                                    "available",
                                    "{VPC_ID}",
                                )
                            )
                        elif name == "{SUBNET_B_NAME}" and has_private_subnets:
                            emit(
                                (
                                    "{SUBNET_B_ID}",
                                    "{SUBNET_B_CIDR}",
                                    "{SUBNET_B_AZ}",
                                    "False",
                                    "available",
                                    "{VPC_ID}",
                                )
                            )
                        elif name == "{SUBNET_A_NAME}" and scenario == "subnet_drift":
                            emit(
                                (
                                    "{SUBNET_A_ID}",
                                    "10.0.36.0/24",
                                    "{SUBNET_A_AZ}",
                                    "False",
                                    "available",
                                    "{VPC_ID}",
                                )
                            )
                    else:
                        rows = [
                            ("subnet-public-a", "10.0.0.0/20"),
                            ("subnet-public-b", "10.0.16.0/20"),
                            ("subnet-data-a", "10.0.32.0/24"),
                            ("subnet-data-b", "10.0.33.0/24"),
                        ]
                        if has_private_subnets:
                            rows.extend(
                                [
                                    ("{SUBNET_A_ID}", "{SUBNET_A_CIDR}"),
                                    ("{SUBNET_B_ID}", "{SUBNET_B_CIDR}"),
                                ]
                            )
                        elif scenario == "overlap":
                            rows.append(("subnet-overlap", "10.0.34.128/25"))
                        elif scenario == "subnet_drift":
                            rows.append(("{SUBNET_A_ID}", "10.0.36.0/24"))
                        emit(*rows)
                elif command == ("ec2", "describe-route-tables"):
                    route_table_id = option("--route-table-ids")
                    name = filter_value("tag:Name")
                    associated_subnet = filter_value("association.subnet-id")
                    if name and converged:
                        if name == "{ROUTE_TABLE_A_NAME}":
                            emit(("{ROUTE_TABLE_A_ID}", "{VPC_ID}", "0"))
                        elif name == "{ROUTE_TABLE_B_NAME}":
                            emit(("{ROUTE_TABLE_B_ID}", "{VPC_ID}", "0"))
                    elif route_table_id and "Associations" in query and converged:
                        if route_table_id == "{ROUTE_TABLE_A_ID}":
                            emit("{SUBNET_A_ID}")
                        elif route_table_id == "{ROUTE_TABLE_B_ID}":
                            emit("{SUBNET_B_ID}")
                    elif route_table_id and "Routes" in query and converged:
                        emit(("{NAT_ID}", "None", "None", "None", "active"))
                    elif associated_subnet and scenario == "route_drift":
                        emit("rtb-unexpected")
                elif command == ("ec2", "describe-nat-gateways"):
                    if converged:
                        emit(
                            (
                                "{NAT_ID}",
                                "{VPC_ID}",
                                "pending" if scenario == "pending_nat" else "available",
                                "public",
                                "regional",
                                "enabled",
                                "enabled",
                                "{NAT_NAME}",
                            )
                        )
                elif command == ("ec2", "describe-vpc-endpoints"):
                    endpoint_id = option("--vpc-endpoint-ids")
                    service_name = filter_value("service-name")
                    if endpoint_id:
                        suffix = endpoint_id.removeprefix("vpce-").replace("-", ".")
                        if query == "VpcEndpoints[0].State":
                            emit("available")
                        elif "RouteTableIds" in query:
                            emit("{ROUTE_TABLE_A_ID}", "{ROUTE_TABLE_B_ID}")
                        elif "SubnetIds" in query:
                            emit("{SUBNET_A_ID}", "{SUBNET_B_ID}")
                        elif "Groups" in query:
                            emit("{ENDPOINT_SECURITY_GROUP_ID}")
                        else:
                            sys.exit("unexpected endpoint detail query: " + query)
                    elif service_name and converged:
                        suffix = endpoint_suffix(service_name)
                        endpoint_type = "Gateway" if suffix == "s3" else "Interface"
                        private_dns = (
                            "False"
                            if suffix == "s3"
                            or (scenario == "endpoint_drift" and suffix == "ecr.api")
                            else "True"
                        )
                        endpoint_id = "vpce-" + suffix.replace(".", "-")
                        endpoint_name = {json.dumps(ENDPOINT_NAMES)}[suffix]
                        emit(
                            (
                                endpoint_id,
                                service_name,
                                endpoint_type,
                                (
                                    "pending"
                                    if scenario == "pending_endpoints"
                                    else "available"
                                ),
                                private_dns,
                                endpoint_name,
                            )
                        )
                elif command == ("ec2", "create-subnet"):
                    cidr = option("--cidr-block")
                    emit(
                        "{SUBNET_A_ID}"
                        if cidr == "{SUBNET_A_CIDR}"
                        else "{SUBNET_B_ID}"
                    )
                elif command == ("ec2", "modify-subnet-attribute"):
                    pass
                elif command == ("ec2", "create-route-table"):
                    tags = option("--tag-specifications")
                    emit(
                        "{ROUTE_TABLE_A_ID}"
                        if "{ROUTE_TABLE_A_NAME}" in tags
                        else "{ROUTE_TABLE_B_ID}"
                    )
                elif command == ("ec2", "associate-route-table"):
                    emit("rtbassoc-test")
                elif command == ("ec2", "create-nat-gateway"):
                    emit("{NAT_ID}")
                elif command == ("ec2", "wait"):
                    pass
                elif command == ("ec2", "create-route"):
                    emit("True")
                elif command == ("ec2", "create-security-group"):
                    emit("{ENDPOINT_SECURITY_GROUP_ID}")
                elif command == ("ec2", "authorize-security-group-ingress"):
                    pass
                elif command == ("ec2", "create-vpc-endpoint"):
                    suffix = endpoint_suffix(option("--service-name"))
                    emit("vpce-" + suffix.replace(".", "-"))
                else:
                    print("unexpected fake aws invocation: " + " ".join(args), file=sys.stderr)
                    sys.exit(94)
                """
            ),
            encoding="utf-8",
        )
        fake_aws.chmod(0o755)

    def _run(
        self, *arguments: str, **environment_overrides: str
    ) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        environment.update(environment_overrides)
        return subprocess.run(
            ["bash", str(SCRIPT), *arguments],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )

    def _commands(self) -> list[list[str]]:
        if not self.trace_file.exists():
            return []
        return [
            json.loads(line)
            for line in self.trace_file.read_text(encoding="utf-8").splitlines()
            if line
        ]

    def _mutations(self) -> list[list[str]]:
        return [
            command
            for command in self._commands()
            if len(command) >= 2
            and command[0] == "ec2"
            and command[1] in MUTATING_EC2_COMMANDS
        ]

    @staticmethod
    def _option(command: list[str], name: str) -> str:
        return command[command.index(name) + 1]

    @staticmethod
    def _option_values(command: list[str], name: str) -> list[str]:
        start = command.index(name) + 1
        values: list[str] = []
        for value in command[start:]:
            if value.startswith("--"):
                break
            values.append(value)
        return values

    def _assert_project_tags(
        self, command: list[str], expected_name: str
    ) -> None:
        tag_specification = self._option(command, "--tag-specifications")
        self.assertIn(f"{{Key=Name,Value={expected_name}}}", tag_specification)
        for marker in PROJECT_TAG_MARKERS:
            self.assertIn(marker, tag_specification)

    def test_script_declares_fixed_scope_and_never_targets_asg_or_cloudfront(self) -> None:
        self.assertTrue(SCRIPT.is_file())
        script = SCRIPT.read_text(encoding="utf-8")
        for marker in (
            AWS_ACCOUNT_ID,
            AWS_REGION,
            VPC_ID,
            ASG_SECURITY_GROUP_ID,
            SUBNET_A_NAME,
            SUBNET_A_CIDR,
            SUBNET_B_NAME,
            SUBNET_B_CIDR,
            NAT_NAME,
            ENDPOINT_SECURITY_GROUP_NAME,
            "--apply",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, script)
        self.assertNotIn("aws autoscaling", script)
        self.assertNotIn("aws cloudfront", script)
        self.assertNotIn("set -x", script)

    def test_default_run_is_read_only(self) -> None:
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("read-only", (result.stdout + result.stderr).lower())
        self.assertEqual([], self._mutations())

    def test_wrong_account_fails_before_any_mutation(self) -> None:
        result = self._run("--apply", FAKE_AWS_ACCOUNT_ID="000000000000")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("account", (result.stdout + result.stderr).lower())
        self.assertEqual([], self._mutations())

    def test_wrong_region_fails_before_aws_access(self) -> None:
        result = self._run("--apply", AWS_REGION="us-east-1")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("region", (result.stdout + result.stderr).lower())
        self.assertEqual([], self._commands())

    def test_vpc_dns_disabled_fails_before_any_mutation(self) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="dns_false")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("dns", (result.stdout + result.stderr).lower())
        self.assertEqual([], self._mutations())

    def test_cidr_overlap_fails_before_any_mutation(self) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="overlap")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("overlap", (result.stdout + result.stderr).lower())
        self.assertEqual([], self._mutations())

    def test_named_subnet_cidr_drift_fails_before_any_mutation(self) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="subnet_drift")

        self.assertNotEqual(0, result.returncode)
        output = (result.stdout + result.stderr).lower()
        self.assertIn("drift", output)
        self.assertIn("cidr", output)
        self.assertEqual([], self._mutations())

    def test_existing_endpoint_drift_fails_before_any_mutation(self) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="endpoint_drift")

        self.assertNotEqual(0, result.returncode)
        output = (result.stdout + result.stderr).lower()
        self.assertIn("drift", output)
        self.assertIn("private dns", output)
        self.assertEqual([], self._mutations())

    def test_existing_subnet_route_table_drift_fails_before_any_mutation(
        self,
    ) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="route_drift")

        self.assertNotEqual(0, result.returncode)
        output = (result.stdout + result.stderr).lower()
        self.assertIn("route table", output)
        self.assertIn("drift", output)
        self.assertEqual([], self._mutations())

    def test_apply_uses_the_fixed_creation_contract(self) -> None:
        result = self._run("--apply")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        mutations = self._mutations()
        mutation_counts = {
            name: sum(command[1] == name for command in mutations)
            for name in {command[1] for command in mutations}
        }
        self.assertEqual(
            {
                "associate-route-table": 2,
                "authorize-security-group-ingress": 1,
                "create-nat-gateway": 1,
                "create-route": 2,
                "create-route-table": 2,
                "create-security-group": 1,
                "create-subnet": 2,
                "create-vpc-endpoint": 6,
                "modify-subnet-attribute": 2,
            },
            mutation_counts,
        )

        subnet_commands = [
            command for command in mutations if command[1] == "create-subnet"
        ]
        self.assertEqual(2, len(subnet_commands))
        self.assertEqual(
            {
                (SUBNET_A_CIDR, SUBNET_A_AZ, SUBNET_A_NAME),
                (SUBNET_B_CIDR, SUBNET_B_AZ, SUBNET_B_NAME),
            },
            {
                (
                    self._option(command, "--cidr-block"),
                    self._option(command, "--availability-zone"),
                    SUBNET_A_NAME
                    if SUBNET_A_NAME in self._option(
                        command, "--tag-specifications"
                    )
                    else SUBNET_B_NAME,
                )
                for command in subnet_commands
            },
        )
        for command in subnet_commands:
            self.assertEqual(VPC_ID, self._option(command, "--vpc-id"))
            expected_name = (
                SUBNET_A_NAME
                if self._option(command, "--cidr-block") == SUBNET_A_CIDR
                else SUBNET_B_NAME
            )
            self._assert_project_tags(command, expected_name)

        subnet_attribute_commands = [
            command
            for command in mutations
            if command[1] == "modify-subnet-attribute"
        ]
        self.assertEqual(2, len(subnet_attribute_commands))
        self.assertEqual(
            {SUBNET_A_ID, SUBNET_B_ID},
            {
                self._option(command, "--subnet-id")
                for command in subnet_attribute_commands
            },
        )
        for command in subnet_attribute_commands:
            self.assertIn("--no-map-public-ip-on-launch", command)

        route_table_commands = [
            command for command in mutations if command[1] == "create-route-table"
        ]
        self.assertEqual(2, len(route_table_commands))
        self.assertEqual(
            {ROUTE_TABLE_A_NAME, ROUTE_TABLE_B_NAME},
            {
                ROUTE_TABLE_A_NAME
                if ROUTE_TABLE_A_NAME
                in self._option(command, "--tag-specifications")
                else ROUTE_TABLE_B_NAME
                for command in route_table_commands
            },
        )
        for command in route_table_commands:
            expected_name = (
                ROUTE_TABLE_A_NAME
                if ROUTE_TABLE_A_NAME
                in self._option(command, "--tag-specifications")
                else ROUTE_TABLE_B_NAME
            )
            self._assert_project_tags(command, expected_name)

        nat_commands = [
            command for command in mutations if command[1] == "create-nat-gateway"
        ]
        self.assertEqual(1, len(nat_commands))
        nat_command = nat_commands[0]
        self.assertEqual(VPC_ID, self._option(nat_command, "--vpc-id"))
        self.assertEqual(
            "regional", self._option(nat_command, "--availability-mode")
        )
        self.assertIn(NAT_NAME, self._option(nat_command, "--tag-specifications"))
        self._assert_project_tags(nat_command, NAT_NAME)
        for prohibited_option in (
            "--subnet-id",
            "--allocation-id",
            "--availability-zone-addresses",
        ):
            self.assertNotIn(prohibited_option, nat_command)

        association_commands = [
            command
            for command in mutations
            if command[1] == "associate-route-table"
        ]
        self.assertEqual(
            {
                (ROUTE_TABLE_A_ID, SUBNET_A_ID),
                (ROUTE_TABLE_B_ID, SUBNET_B_ID),
            },
            {
                (
                    self._option(command, "--route-table-id"),
                    self._option(command, "--subnet-id"),
                )
                for command in association_commands
            },
        )

        route_commands = [
            command for command in mutations if command[1] == "create-route"
        ]
        self.assertEqual(2, len(route_commands))
        self.assertEqual(
            {ROUTE_TABLE_A_ID, ROUTE_TABLE_B_ID},
            {
                self._option(command, "--route-table-id")
                for command in route_commands
            },
        )
        for command in route_commands:
            self.assertEqual(
                "0.0.0.0/0",
                self._option(command, "--destination-cidr-block"),
            )
            self.assertEqual(NAT_ID, self._option(command, "--nat-gateway-id"))

        security_group_commands = [
            command
            for command in mutations
            if command[1] == "create-security-group"
        ]
        self.assertEqual(1, len(security_group_commands))
        security_group_command = security_group_commands[0]
        self.assertEqual(
            ENDPOINT_SECURITY_GROUP_NAME,
            self._option(security_group_command, "--group-name"),
        )
        self.assertEqual(VPC_ID, self._option(security_group_command, "--vpc-id"))
        self._assert_project_tags(
            security_group_command, ENDPOINT_SECURITY_GROUP_NAME
        )

        ingress_commands = [
            command
            for command in mutations
            if command[1] == "authorize-security-group-ingress"
        ]
        self.assertEqual(1, len(ingress_commands))
        ingress_command = ingress_commands[0]
        self.assertEqual(
            ENDPOINT_SECURITY_GROUP_ID,
            self._option(ingress_command, "--group-id"),
        )
        self.assertEqual("tcp", self._option(ingress_command, "--protocol"))
        self.assertEqual("443", self._option(ingress_command, "--port"))
        self.assertEqual(
            ASG_SECURITY_GROUP_ID,
            self._option(ingress_command, "--source-group"),
        )
        self.assertNotIn("--cidr", ingress_command)

        endpoint_commands = [
            command for command in mutations if command[1] == "create-vpc-endpoint"
        ]
        self.assertEqual(6, len(endpoint_commands))
        endpoints_by_service = {
            self._option(command, "--service-name"): command
            for command in endpoint_commands
        }
        expected_services = {
            f"com.amazonaws.{AWS_REGION}.{suffix}" for suffix in ENDPOINT_NAMES
        }
        self.assertEqual(expected_services, set(endpoints_by_service))

        s3_command = endpoints_by_service[
            f"com.amazonaws.{AWS_REGION}.s3"
        ]
        self.assertEqual(
            "Gateway", self._option(s3_command, "--vpc-endpoint-type")
        )
        self.assertEqual(
            {ROUTE_TABLE_A_ID, ROUTE_TABLE_B_ID},
            set(self._option_values(s3_command, "--route-table-ids")),
        )
        self.assertIn(
            ENDPOINT_NAMES["s3"],
            self._option(s3_command, "--tag-specifications"),
        )
        self._assert_project_tags(s3_command, ENDPOINT_NAMES["s3"])

        for suffix in (
            "ecr.api",
            "ecr.dkr",
            "secretsmanager",
            "ssm",
            "ssmmessages",
        ):
            with self.subTest(service=suffix):
                command = endpoints_by_service[
                    f"com.amazonaws.{AWS_REGION}.{suffix}"
                ]
                self.assertEqual(
                    "Interface",
                    self._option(command, "--vpc-endpoint-type"),
                )
                self.assertEqual(
                    {SUBNET_A_ID, SUBNET_B_ID},
                    set(self._option_values(command, "--subnet-ids")),
                )
                self.assertEqual(
                    [ENDPOINT_SECURITY_GROUP_ID],
                    self._option_values(command, "--security-group-ids"),
                )
                self.assertIn("--private-dns-enabled", command)
                self.assertIn(
                    ENDPOINT_NAMES[suffix],
                    self._option(command, "--tag-specifications"),
                )
                self._assert_project_tags(command, ENDPOINT_NAMES[suffix])

        flattened = " ".join(" ".join(command) for command in self._commands())
        self.assertNotIn("autoscaling", flattened)
        self.assertNotIn("cloudfront", flattened)

    def test_apply_is_idempotent_when_state_already_matches(self) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="converged")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], self._mutations())
        self.assertIn("already converged", (result.stdout + result.stderr).lower())

    def test_apply_waits_for_pending_nat_instead_of_reporting_converged(
        self,
    ) -> None:
        result = self._run("--apply", FAKE_AWS_SCENARIO="pending_nat")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], self._mutations())
        waiters = [
            command
            for command in self._commands()
            if command[:3] == ["ec2", "wait", "nat-gateway-available"]
        ]
        self.assertEqual(1, len(waiters))
        self.assertEqual(
            [NAT_ID],
            self._option_values(waiters[0], "--nat-gateway-ids"),
        )
        self.assertNotIn(
            "already converged", (result.stdout + result.stderr).lower()
        )

    def test_apply_waits_for_pending_endpoints_instead_of_reporting_converged(
        self,
    ) -> None:
        result = self._run(
            "--apply", FAKE_AWS_SCENARIO="pending_endpoints"
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([], self._mutations())
        endpoint_state_checks = [
            command
            for command in self._commands()
            if command[:2] == ["ec2", "describe-vpc-endpoints"]
            and self._option(command, "--query") == "VpcEndpoints[0].State"
        ]
        self.assertEqual(
            set(ENDPOINT_IDS.values()),
            {
                self._option(command, "--vpc-endpoint-ids")
                for command in endpoint_state_checks
            },
        )
        self.assertFalse(
            any(
                command[:3]
                == ["ec2", "wait", "vpc-endpoint-available"]
                for command in self._commands()
            )
        )
        self.assertNotIn(
            "already converged", (result.stdout + result.stderr).lower()
        )


if __name__ == "__main__":
    unittest.main()
