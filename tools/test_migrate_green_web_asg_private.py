from __future__ import annotations

import base64
import json
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/migrate_green_web_asg_private.sh"

AWS_ACCOUNT_ID = "443915990705"
AWS_REGION = "ap-northeast-2"
VPC_ID = "vpc-06c90b864a62f93a4"
ASG_NAME = "buildgraph-demo-api-green-asg"
LAUNCH_TEMPLATE_ID = "lt-0privateasgtest"
LAUNCH_TEMPLATE_NAME = "buildgraph-demo-api-green-lt"
TARGET_GROUP_ARN = (
    "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:"
    "targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
)

PUBLIC_SUBNETS = ("subnet-public-2a", "subnet-public-2b")
PRIVATE_SUBNETS = ("subnet-private-app-2a", "subnet-private-app-2b")
PRIVATE_SUBNET_NAMES = (
    "buildgraph-demo-subnet-private-app1-ap-northeast-2a",
    "buildgraph-demo-subnet-private-app2-ap-northeast-2b",
)
PRIVATE_SUBNET_CIDRS = ("10.0.34.0/24", "10.0.35.0/24")
PRIVATE_SUBNET_AZS = ("ap-northeast-2a", "ap-northeast-2b")
PRIVATE_ROUTE_TABLES = ("rtb-private-app-2a", "rtb-private-app-2b")
PRIVATE_ROUTE_TABLE_NAMES = (
    "buildgraph-demo-rtb-private-app1-ap-northeast-2a",
    "buildgraph-demo-rtb-private-app2-ap-northeast-2b",
)
NAT_GATEWAY_ID = "nat-regional-private-app"
NAT_GATEWAY_NAME = "buildgraph-demo-nat-regional"
TEST_SECRET = "USER_DATA_SECRET_MUST_NOT_LEAK"

MUTATING_MARKERS = (
    "aws:ec2 create-launch-template-version",
    "aws:ec2 run-instances",
    "aws:ec2 terminate-instances",
    "aws:autoscaling update-auto-scaling-group",
    "aws:autoscaling start-instance-refresh",
    "aws:autoscaling cancel-instance-refresh",
)


class GreenWebAsgPrivateMigrationTest(unittest.TestCase):
    maxDiff = None

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temporary_directory.name)
        self.fake_bin = self.temp_root / "bin"
        self.fake_bin.mkdir()
        self.script_tmp = self.temp_root / "script-tmp"
        self.script_tmp.mkdir()
        self.trace_file = self.temp_root / "trace.log"
        self.asg_state_file = self.temp_root / "asg-state"
        self.refresh_count_file = self.temp_root / "refresh-count"
        self.lost_instance_file = self.temp_root / "lost-instance"
        self.rediscovery_counter_file = self.temp_root / "rediscovery-counter"
        self._write_fake_aws()

        self.environment = os.environ.copy()
        self.environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{self.environment['PATH']}",
                "TMPDIR": str(self.script_tmp),
                "AWS_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "AWS_REGION": AWS_REGION,
                "AWS_DEFAULT_REGION": AWS_REGION,
                "BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED": "true",
                "BUILDGRAPH_ASG_REFRESH_MAX_ATTEMPTS": "3",
                "BUILDGRAPH_ASG_REFRESH_POLL_SECONDS": "0",
                "BUILDGRAPH_PRIVATE_AZ_VALIDATION_MAX_ATTEMPTS": "2",
                "BUILDGRAPH_PRIVATE_AZ_VALIDATION_POLL_SECONDS": "0",
                "BUILDGRAPH_VALIDATION_REDISCOVERY_MAX_ATTEMPTS": "3",
                "BUILDGRAPH_VALIDATION_REDISCOVERY_POLL_SECONDS": "0",
                "FAKE_ACCOUNT_ID": AWS_ACCOUNT_ID,
                "FAKE_ASG_MIN": "1",
                "FAKE_ASG_DESIRED": "1",
                "FAKE_ASG_MAX": "1",
                "FAKE_INITIAL_ASG_STATE": "old",
                "FAKE_ACTIVE_REFRESH_STATUS": "None",
                "FAKE_ASG_UPDATE_RESPONSE_LOST": "false",
                "FAKE_REFRESH_DESCRIBE_ERROR": "false",
                "FAKE_NAT_STATE": "available",
                "FAKE_NAT_AUTO_PROVISION_ZONES": "enabled",
                "FAKE_NAT_AUTO_SCALING_IPS": "enabled",
                "FAKE_ENDPOINT_STATE": "available",
                "FAKE_REFRESH_STATUS": "Successful",
                "FAKE_SSM_COMMAND_STATUS": "Success",
                "FAKE_POST_PUBLIC_IP": "null",
                "FAKE_TRACE_FILE": str(self.trace_file),
                "FAKE_ASG_STATE_FILE": str(self.asg_state_file),
                "FAKE_REFRESH_COUNT_FILE": str(self.refresh_count_file),
                "FAKE_LOST_INSTANCE_FILE": str(self.lost_instance_file),
                "FAKE_REDISCOVERY_COUNTER_FILE": str(
                    self.rediscovery_counter_file
                ),
                "FAKE_REDISCOVERY_EMPTY_ATTEMPTS": "0",
                "FAKE_RUN_INSTANCES_RESPONSE_LOST": "false",
                "FAKE_SOURCE_PUBLIC_IP": "true",
                "FAKE_USER_DATA_SECRET": TEST_SECRET,
            }
        )

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def _run(
        self, *arguments: str, **overrides: str
    ) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        environment.update(overrides)
        return subprocess.run(
            ["bash", str(SCRIPT), *arguments],
            cwd=ROOT,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def _trace_lines(self) -> list[str]:
        if not self.trace_file.exists():
            return []
        return self.trace_file.read_text(encoding="utf-8").splitlines()

    def _mutation_lines(self) -> list[str]:
        return [
            line
            for line in self._trace_lines()
            if any(marker in line for marker in MUTATING_MARKERS)
        ]

    def _assert_secret_absent_from_outputs_and_temp(
        self, result: subprocess.CompletedProcess[str]
    ) -> None:
        self.assertNotIn(TEST_SECRET, result.stdout)
        self.assertNotIn(
            TEST_SECRET,
            self.trace_file.read_text(encoding="utf-8")
            if self.trace_file.exists()
            else "",
        )
        for path in self.script_tmp.rglob("*"):
            if path.is_file():
                with self.subTest(path=path):
                    self.assertNotIn(
                        TEST_SECRET,
                        path.read_text(encoding="utf-8", errors="replace"),
                    )

    def _write_fake_aws(self) -> None:
        path = self.fake_bin / "aws"
        path.write_text(
            "#!/usr/bin/env bash\nset -euo pipefail\n"
            + textwrap.dedent(
                r"""
                trace() {
                  printf 'aws:%s\n' "$*" >>"$FAKE_TRACE_FILE"
                }

                argument_value() {
                  local expected="$1"
                  shift
                  while [[ "$#" -gt 0 ]]; do
                    if [[ "$1" == "$expected" ]]; then
                      printf '%s' "${2:-}"
                      return 0
                    fi
                    shift
                  done
                  return 1
                }

                has_argument_fragment() {
                  local fragment="$1"
                  shift
                  [[ " $* " == *"$fragment"* ]]
                }

                tag_value_from_filters() {
                  local item
                  for item in "$@"; do
                    case "$item" in
                      Name=tag:Name,Values=*)
                        printf '%s' "${item#Name=tag:Name,Values=}"
                        return 0
                        ;;
                    esac
                  done
                  return 1
                }

                filter_value() {
                  local filter_name="$1"
                  shift
                  local item
                  for item in "$@"; do
                    case "$item" in
                      Name="$filter_name",Values=*)
                        printf '%s' "${item#Name="$filter_name",Values=}"
                        return 0
                        ;;
                    esac
                  done
                  return 1
                }

                endpoint() {
                  local name="$1"
                  local service="$2"
                  local type="$3"
                  shift 3
                  jq -n \
                    --arg name "$name" \
                    --arg service "$service" \
                    --arg type "$type" \
                    --arg state "$FAKE_ENDPOINT_STATE" \
                    --arg subnet_a "subnet-private-app-2a" \
                    --arg subnet_b "subnet-private-app-2b" \
                    --arg route_a "rtb-private-app-2a" \
                    --arg route_b "rtb-private-app-2b" \
                    '{
                      VpcEndpointId: ("vpce-" + ($name | gsub("[^a-z0-9]"; ""))),
                      VpcId: "vpc-06c90b864a62f93a4",
                      State: $state,
                      ServiceName: $service,
                      VpcEndpointType: $type,
                      PrivateDnsEnabled: ($type == "Interface"),
                      SubnetIds: (
                        if $type == "Interface" then [$subnet_a, $subnet_b] else [] end
                      ),
                      RouteTableIds: (
                        if $type == "Gateway" then [$route_a, $route_b] else [] end
                      ),
                      Tags: [{Key: "Name", Value: $name}]
                    }'
                }

                trace "$*"

                case "${1:-}:${2:-}" in
                  sts:get-caller-identity)
                    printf '%s\n' "$FAKE_ACCOUNT_ID"
                    ;;

                  autoscaling:describe-auto-scaling-groups)
                    state="$FAKE_INITIAL_ASG_STATE"
                    [[ ! -f "$FAKE_ASG_STATE_FILE" ]] ||
                      state="$(cat "$FAKE_ASG_STATE_FILE")"
                    if [[ "$state" == "private" ]]; then
                      instance_id="i-private-new"
                      instance_zone="ap-northeast-2a"
                    else
                      instance_id="i-public-old"
                      instance_zone="ap-northeast-2b"
                    fi
                    jq -n \
                      --arg asg "buildgraph-demo-api-green-asg" \
                      --argjson min "$FAKE_ASG_MIN" \
                      --argjson desired "$FAKE_ASG_DESIRED" \
                      --argjson max "$FAKE_ASG_MAX" \
                      --arg state "$state" \
                      --arg instance_id "$instance_id" \
                      --arg instance_zone "$instance_zone" \
                      --arg target_group \
                        "arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411" \
                      '{
                        AutoScalingGroups: [{
                          AutoScalingGroupName: $asg,
                          MinSize: $min,
                          DesiredCapacity: $desired,
                          MaxSize: $max,
                          VPCZoneIdentifier: (
                            if $state == "private"
                            then "subnet-private-app-2a,subnet-private-app-2b"
                            else "subnet-public-2a,subnet-public-2b"
                            end
                          ),
                          LaunchTemplate: {
                            LaunchTemplateId: "lt-0privateasgtest",
                            LaunchTemplateName: "buildgraph-demo-api-green-lt",
                            Version: (if $state == "private" then "2" else "1" end)
                          },
                          DefaultInstanceWarmup: 120,
                          HealthCheckGracePeriod: 300,
                          TargetGroupARNs: [$target_group],
                          Instances: [{
                            InstanceId: $instance_id,
                            AvailabilityZone: $instance_zone,
                            LifecycleState: "InService",
                            HealthStatus: "Healthy"
                          }]
                        }]
                      }'
                    ;;

                  autoscaling:update-auto-scaling-group)
                    launch_template="$(argument_value --launch-template "$@")"
                    if [[ "$launch_template" == *"Version=2"* ]]; then
                      printf '%s\n' private >"$FAKE_ASG_STATE_FILE"
                      if [[ "$FAKE_ASG_UPDATE_RESPONSE_LOST" == "true" ]]; then
                        exit 99
                      fi
                    else
                      printf '%s\n' old >"$FAKE_ASG_STATE_FILE"
                    fi
                    ;;

                  autoscaling:start-instance-refresh)
                    count=0
                    [[ ! -f "$FAKE_REFRESH_COUNT_FILE" ]] ||
                      count="$(cat "$FAKE_REFRESH_COUNT_FILE")"
                    count=$((count + 1))
                    printf '%s\n' "$count" >"$FAKE_REFRESH_COUNT_FILE"
                    preferences="$(argument_value --preferences "$@")"
                    case "$preferences" in
                      file://*)
                        printf 'refresh-preferences:%s\n' \
                          "$(jq -c . "${preferences#file://}")" >>"$FAKE_TRACE_FILE"
                        ;;
                    esac
                    if [[ "$count" -eq 1 ]]; then
                      printf '%s\n' refresh-forward
                    else
                      printf '%s\n' refresh-reverse
                    fi
                    ;;

                  autoscaling:describe-instance-refreshes)
                    if ! argument_value --instance-refresh-ids "$@" >/dev/null 2>&1; then
                      printf '%s\n' "$FAKE_ACTIVE_REFRESH_STATUS"
                      exit 0
                    fi
                    if [[ "$FAKE_REFRESH_DESCRIBE_ERROR" == "true" ]]; then
                      exit 77
                    fi
                    if [[ "${FAKE_SIGNAL_ON_REFRESH:-}" == "TERM" ]]; then
                      kill -TERM "$PPID"
                      exit 143
                    fi
                    printf '%s\n' "$FAKE_REFRESH_STATUS"
                    ;;

                  autoscaling:cancel-instance-refresh)
                    exit 0
                    ;;

                  ec2:describe-subnets)
                    if has_argument_fragment " --filters " "$@"; then
                      name="$(tag_value_from_filters "$@")"
                      case "$name" in
                        buildgraph-demo-subnet-private-app1-ap-northeast-2a)
                          printf '%s\n' subnet-private-app-2a
                          ;;
                        buildgraph-demo-subnet-private-app2-ap-northeast-2b)
                          printf '%s\n' subnet-private-app-2b
                          ;;
                        *)
                          printf '%s\n' None
                          ;;
                      esac
                    else
                      jq -n '{
                        Subnets: [
                          {
                            SubnetId: "subnet-private-app-2a",
                            VpcId: "vpc-06c90b864a62f93a4",
                            State: "available",
                            AvailabilityZone: "ap-northeast-2a",
                            CidrBlock: "10.0.34.0/24",
                            MapPublicIpOnLaunch: false,
                            Tags: [{
                              Key: "Name",
                              Value: "buildgraph-demo-subnet-private-app1-ap-northeast-2a"
                            }]
                          },
                          {
                            SubnetId: "subnet-private-app-2b",
                            VpcId: "vpc-06c90b864a62f93a4",
                            State: "available",
                            AvailabilityZone: "ap-northeast-2b",
                            CidrBlock: "10.0.35.0/24",
                            MapPublicIpOnLaunch: false,
                            Tags: [{
                              Key: "Name",
                              Value: "buildgraph-demo-subnet-private-app2-ap-northeast-2b"
                            }]
                          }
                        ]
                      }'
                    fi
                    ;;

                  ec2:describe-nat-gateways)
                    jq -n \
                      --arg state "$FAKE_NAT_STATE" \
                      --arg auto_provision "$FAKE_NAT_AUTO_PROVISION_ZONES" \
                      --arg auto_scaling "$FAKE_NAT_AUTO_SCALING_IPS" \
                      '{
                        NatGateways: [{
                          NatGatewayId: "nat-regional-private-app",
                          VpcId: "vpc-06c90b864a62f93a4",
                          State: $state,
                          ConnectivityType: "public",
                          AvailabilityMode: "regional",
                          AutoProvisionZones: $auto_provision,
                          AutoScalingIps: $auto_scaling,
                          Tags: [{
                            Key: "Name",
                            Value: "buildgraph-demo-nat-regional"
                          }]
                        }]
                      }'
                    ;;

                  ec2:describe-route-tables)
                    if has_argument_fragment "subnet-private-app-2a" "$@"; then
                      route_table_id="rtb-private-app-2a"
                      route_table_name="buildgraph-demo-rtb-private-app1-ap-northeast-2a"
                      subnet_id="subnet-private-app-2a"
                    else
                      route_table_id="rtb-private-app-2b"
                      route_table_name="buildgraph-demo-rtb-private-app2-ap-northeast-2b"
                      subnet_id="subnet-private-app-2b"
                    fi
                    jq -n \
                      --arg route_table_id "$route_table_id" \
                      --arg route_table_name "$route_table_name" \
                      --arg subnet_id "$subnet_id" \
                      '{
                        RouteTables: [{
                          RouteTableId: $route_table_id,
                          VpcId: "vpc-06c90b864a62f93a4",
                          Associations: [{
                            Main: false,
                            SubnetId: $subnet_id
                          }],
                          Routes: [{
                            DestinationCidrBlock: "10.0.0.0/16",
                            GatewayId: "local",
                            State: "active"
                          }, {
                            DestinationCidrBlock: "0.0.0.0/0",
                            NatGatewayId: "nat-regional-private-app",
                            State: "active"
                          }],
                          Tags: [{Key: "Name", Value: $route_table_name}]
                        }]
                      }'
                    ;;

                  ec2:describe-vpc-endpoints)
                    jq -n \
                      --argjson s3 "$(
                        endpoint \
                          buildgraph-demo-vpce-s3-private-app \
                          com.amazonaws.ap-northeast-2.s3 \
                          Gateway
                      )" \
                      --argjson ecr_api "$(
                        endpoint \
                          buildgraph-demo-vpce-ecr-api-private-app \
                          com.amazonaws.ap-northeast-2.ecr.api \
                          Interface
                      )" \
                      --argjson ecr_dkr "$(
                        endpoint \
                          buildgraph-demo-vpce-ecr-dkr-private-app \
                          com.amazonaws.ap-northeast-2.ecr.dkr \
                          Interface
                      )" \
                      --argjson secretsmanager "$(
                        endpoint \
                          buildgraph-demo-vpce-secretsmanager-private-app \
                          com.amazonaws.ap-northeast-2.secretsmanager \
                          Interface
                      )" \
                      --argjson ssm "$(
                        endpoint \
                          buildgraph-demo-vpce-ssm-private-app \
                          com.amazonaws.ap-northeast-2.ssm \
                          Interface
                      )" \
                      --argjson ssmmessages "$(
                        endpoint \
                          buildgraph-demo-vpce-ssmmessages-private-app \
                          com.amazonaws.ap-northeast-2.ssmmessages \
                          Interface
                      )" \
                      '{
                        VpcEndpoints: [
                          $s3,
                          $ecr_api,
                          $ecr_dkr,
                          $secretsmanager,
                          $ssm,
                          $ssmmessages
                        ]
                      }'
                    ;;

                  ec2:describe-launch-template-versions)
                    query="$(argument_value --query "$@")"
                    case "$query" in
                      *NetworkInterfaces*)
                        if [[ "$FAKE_SOURCE_PUBLIC_IP" == "true" ]]; then
                          printf '%s\n' \
                            '[{"AssociatePublicIpAddress":true,"DeleteOnTermination":true,"DeviceIndex":0,"Groups":["sg-private-asg"],"SubnetId":"subnet-public-2b"}]'
                        else
                          printf '%s\n' \
                            '[{"AssociatePublicIpAddress":false,"DeleteOnTermination":true,"DeviceIndex":0,"Groups":["sg-private-asg"]}]'
                        fi
                        ;;
                      *UserData*)
                        printf '%s\n' \
                          "$(printf '%s\n' \
                            '#!/usr/bin/env bash' \
                            'set -Eeuo pipefail' \
                            '/opt/buildgraph/prototype/tools/bootstrap_green_asg.sh' |
                            base64 |
                            tr -d '\n')"
                        ;;
                      *)
                        printf '%s\n' "$FAKE_USER_DATA_SECRET"
                        exit 92
                        ;;
                    esac
                    ;;

                  ec2:create-launch-template-version)
                    launch_template_data="$(
                      argument_value --launch-template-data "$@"
                    )"
                    case "$launch_template_data" in
                      file://*)
                        printf 'launch-template-data:%s\n' \
                          "$(jq -c . "${launch_template_data#file://}")" \
                          >>"$FAKE_TRACE_FILE"
                        ;;
                    esac
                    printf '%s\n' 2
                    ;;

                  ec2:run-instances)
                    if argument_value --subnet-id "$@" >/dev/null 2>&1; then
                      echo "top-level --subnet-id is invalid with Launch Template network interfaces" >&2
                      exit 96
                    fi
                    network_interfaces="$(
                      argument_value --network-interfaces "$@"
                    )"
                    case "$network_interfaces" in
                      file://*)
                        network_interfaces_file="${network_interfaces#file://}"
                        ;;
                      *)
                        echo "network interface override must use file:// JSON" >&2
                        exit 97
                        ;;
                    esac
                    jq -e \
                      'length == 1
                       and .[0].DeviceIndex == 0
                       and .[0].DeleteOnTermination == true
                       and .[0].AssociatePublicIpAddress == false
                       and (.[0].Groups | type == "array" and length == 1)
                       and (.[0].SubnetId | type == "string" and length > 0)' \
                      "$network_interfaces_file" >/dev/null
                    if network_mode="$(
                      stat -c '%a' "$network_interfaces_file" 2>/dev/null
                    )"; then
                      :
                    else
                      network_mode="$(
                        stat -f '%Lp' "$network_interfaces_file"
                      )"
                    fi
                    client_token="$(argument_value --client-token "$@")"
                    printf 'run-network-interfaces:%s:%s:%s\n' \
                      "$client_token" \
                      "$network_mode" \
                      "$(jq -c . "$network_interfaces_file")" \
                      >>"$FAKE_TRACE_FILE"
                    subnet_id="$(
                      jq -r '.[0].SubnetId' "$network_interfaces_file"
                    )"
                    if [[ "$subnet_id" == "subnet-private-app-2a" ]]; then
                      instance_id="i-validation-2a"
                    else
                      instance_id="i-validation-2b"
                    fi
                    if [[ "$FAKE_RUN_INSTANCES_RESPONSE_LOST" == "true" &&
                      "$subnet_id" == "subnet-private-app-2a" ]]
                    then
                      printf '%s\t%s\n' \
                        "$instance_id" \
                        "$client_token" >"$FAKE_LOST_INSTANCE_FILE"
                      exit 98
                    fi
                    printf '%s\n' "$instance_id"
                    ;;

                  ec2:wait)
                    exit 0
                    ;;

                  ec2:describe-instances)
                    query="$(argument_value --query "$@" || true)"
                    if [[ "$query" == *"Reservations[].Instances[].InstanceId"* ]]; then
                      rediscovery_count=0
                      [[ ! -f "$FAKE_REDISCOVERY_COUNTER_FILE" ]] ||
                        rediscovery_count="$(
                          cat "$FAKE_REDISCOVERY_COUNTER_FILE"
                        )"
                      rediscovery_count=$((rediscovery_count + 1))
                      printf '%s\n' "$rediscovery_count" \
                        >"$FAKE_REDISCOVERY_COUNTER_FILE"
                      if ((
                        rediscovery_count <= FAKE_REDISCOVERY_EMPTY_ATTEMPTS
                      ))
                      then
                        exit 0
                      fi
                      client_token="$(
                        filter_value client-token "$@" || true
                      )"
                      case "$client_token" in
                        *ap-northeast-2a*)
                          printf '%s\n' i-validation-2a
                          ;;
                        *ap-northeast-2b*)
                          printf '%s\n' i-validation-2b
                          ;;
                      esac
                      exit 0
                    fi
                    if [[ "$query" == *"length("* ]]; then
                      printf '%s\n' 1
                      exit 0
                    fi
                    instance_id="$(argument_value --instance-ids "$@")"
                    case "$instance_id" in
                      i-private-new)
                        jq -n \
                          --argjson public_ip "$FAKE_POST_PUBLIC_IP" \
                          '{
                            Reservations: [{
                              Instances: [{
                                InstanceId: "i-private-new",
                                State: {Name: "running"},
                                SubnetId: "subnet-private-app-2a",
                                Placement: {AvailabilityZone: "ap-northeast-2a"},
                                PublicIpAddress: $public_ip
                              }]
                            }]
                          }'
                        ;;
                      i-validation-2a)
                        jq -n '{
                          Reservations: [{
                            Instances: [{
                              InstanceId: "i-validation-2a",
                              State: {Name: "running"},
                              SubnetId: "subnet-private-app-2a",
                              Placement: {AvailabilityZone: "ap-northeast-2a"},
                              PublicIpAddress: null
                            }]
                          }]
                        }'
                        ;;
                      i-validation-2b)
                        jq -n '{
                          Reservations: [{
                            Instances: [{
                              InstanceId: "i-validation-2b",
                              State: {Name: "running"},
                              SubnetId: "subnet-private-app-2b",
                              Placement: {AvailabilityZone: "ap-northeast-2b"},
                              PublicIpAddress: null
                            }]
                          }]
                        }'
                        ;;
                      *)
                        printf '%s\n' '{"Reservations":[]}'
                        ;;
                    esac
                    ;;

                  ec2:terminate-instances)
                    exit 0
                    ;;

                  ssm:describe-instance-information)
                    printf '%s\n' Online
                    ;;

                  ssm:send-command)
                    parameters="$(argument_value --parameters "$@")"
                    case "$parameters" in
                      file://*)
                        printf 'ssm-command:%s\n' \
                          "$(jq -c . "${parameters#file://}")" \
                          >>"$FAKE_TRACE_FILE"
                        ;;
                    esac
                    instance_id="$(argument_value --instance-ids "$@")"
                    if [[ "$instance_id" == "i-validation-2a" ]]; then
                      printf '%s\n' command-validation-2a
                    else
                      printf '%s\n' command-validation-2b
                    fi
                    ;;

                  ssm:get-command-invocation)
                    printf '%s\n' "$FAKE_SSM_COMMAND_STATUS"
                    ;;

                  elbv2:describe-target-health)
                    printf '%s\n' healthy
                    ;;

                  *)
                    echo "unexpected fake aws invocation: $*" >&2
                    exit 91
                    ;;
                esac
                """
            ).lstrip(),
            encoding="utf-8",
        )
        path.chmod(0o755)

    def test_script_contract_is_read_only_by_default_and_avoids_sensitive_paths(
        self,
    ) -> None:
        self.assertTrue(SCRIPT.is_file())
        script = SCRIPT.read_text(encoding="utf-8")

        self.assertIn("--apply", script)
        self.assertIn("--validate-private-azs", script)
        self.assertIn("BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED", script)
        self.assertNotIn(
            "BUILDGRAPH_CLOUDFRONT_ALB_ORIGIN_CONFIRMED", script
        )
        self.assertIn("ssm send-command", script)
        self.assertIn("AWS_STS_REGIONAL_ENDPOINTS=regional", script)
        self.assertIn("LaunchTemplateData.UserData", script)
        self.assertNotRegex(script, r"\baws\s+cloudfront\b")
        self.assertNotIn("set -x", script)
        self.assertNotIn("StandardOutputContent", script)
        self.assertNotIn("StandardErrorContent", script)

    def test_default_preflight_performs_zero_mutations(self) -> None:
        result = self._run()

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)
        self.assertIn("read-only", result.stdout.lower())
        self.assertNotIn(TEST_SECRET, result.stdout)

    def test_validation_preflight_without_apply_performs_zero_mutations(
        self,
    ) -> None:
        result = self._run("--validate-private-azs")

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)
        self.assertIn("validation", result.stdout.lower())

    def test_wrong_capacity_aborts_before_any_mutation(self) -> None:
        result = self._run(FAKE_ASG_MAX="2")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("1/1/1", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_active_instance_refresh_aborts_without_cancelling_it(self) -> None:
        result = self._run(FAKE_ACTIVE_REFRESH_STATUS="InProgress")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("active instance refresh", result.stdout.lower())
        lines = self._trace_lines()
        self.assertFalse(
            any("aws:autoscaling cancel-instance-refresh" in line for line in lines)
        )
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_unavailable_dependency_aborts_before_any_mutation(self) -> None:
        result = self._run(FAKE_NAT_STATE="pending")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("NAT", result.stdout)
        self.assertIn("available", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_regional_nat_automation_drift_aborts_before_any_mutation(
        self,
    ) -> None:
        result = self._run(FAKE_NAT_AUTO_SCALING_IPS="disabled")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Regional NAT", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_apply_requires_manual_green_cloudfront_gate(self) -> None:
        result = self._run(
            "--apply",
            BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED="false",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("CloudFront", result.stdout)
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_apply_creates_private_launch_template_then_refreshes_and_validates(
        self,
    ) -> None:
        result = self._run("--apply")

        self.assertEqual(0, result.returncode, result.stdout)
        lines = self._trace_lines()
        create_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ec2 create-launch-template-version" in line
        )
        update_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling update-auto-scaling-group" in line
        )
        refresh_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )
        post_instance_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:ec2 describe-instances" in line
            and "i-private-new" in line
        )
        target_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:elbv2 describe-target-health" in line
        )

        self.assertLess(create_index, update_index)
        self.assertLess(update_index, refresh_index)
        self.assertLess(refresh_index, post_instance_index)
        self.assertLess(post_instance_index, target_index)

        create_line = lines[create_index]
        self.assertIn("--source-version 1", create_line)
        update_line = lines[update_index]
        self.assertIn(
            "--vpc-zone-identifier subnet-private-app-2a,subnet-private-app-2b",
            update_line,
        )
        self.assertIn("Version=2", update_line)

        launch_template_data = next(
            line.removeprefix("launch-template-data:")
            for line in lines
            if line.startswith("launch-template-data:")
        )
        launch_template_override = json.loads(launch_template_data)
        self.assertEqual(
            False,
            launch_template_override["NetworkInterfaces"][0][
                "AssociatePublicIpAddress"
            ],
        )
        self.assertNotIn(
            "SubnetId", launch_template_override["NetworkInterfaces"][0]
        )
        self.assertEqual(
            ["sg-private-asg"],
            launch_template_override["NetworkInterfaces"][0]["Groups"],
        )
        candidate_user_data = base64.b64decode(
            launch_template_override["UserData"]
        ).decode("utf-8")
        self.assertIn(
            "export AWS_STS_REGIONAL_ENDPOINTS=regional",
            candidate_user_data,
        )
        self.assertIn(
            "/opt/buildgraph/prototype/tools/bootstrap_green_asg.sh",
            candidate_user_data,
        )
        self.assertLess(
            candidate_user_data.index(
                "export AWS_STS_REGIONAL_ENDPOINTS=regional"
            ),
            candidate_user_data.index(
                "/opt/buildgraph/prototype/tools/bootstrap_green_asg.sh"
            ),
        )

        preferences = [
            json.loads(line.removeprefix("refresh-preferences:"))
            for line in lines
            if line.startswith("refresh-preferences:")
        ]
        self.assertEqual(
            [
                {
                    "InstanceWarmup": 120,
                    "MaxHealthyPercentage": 200,
                    "MinHealthyPercentage": 100,
                }
            ],
            preferences,
        )
        self._assert_secret_absent_from_outputs_and_temp(result)
        self.assertFalse(
            any("aws:cloudfront" in line for line in lines),
            lines,
        )

    def test_already_private_converged_rerun_is_a_safe_noop(self) -> None:
        result = self._run(
            "--apply",
            BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED="false",
            FAKE_INITIAL_ASG_STATE="private",
            FAKE_SOURCE_PUBLIC_IP="false",
        )

        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("converged", result.stdout.lower())
        self.assertIn("no-op", result.stdout.lower())
        self.assertEqual([], self._mutation_lines(), result.stdout)

    def test_validation_mode_launches_two_owned_instances_and_cleans_only_them(
        self,
    ) -> None:
        result = self._run(
            "--validate-private-azs",
            "--apply",
            BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED="false",
        )

        self.assertEqual(0, result.returncode, result.stdout)
        lines = self._trace_lines()
        run_lines = [
            line for line in lines if "aws:ec2 run-instances" in line
        ]
        self.assertEqual(2, len(run_lines), lines)
        for line in run_lines:
            self.assertIn("BuildGraphInvocation", line)
            self.assertIn("BuildGraphPurpose", line)
            self.assertNotIn("--subnet-id", line)
            self.assertNotIn("--min-count", line)
            self.assertNotIn("--max-count", line)
            self.assertIn("--count 1", line)
            self.assertIn("--network-interfaces file://", line)

        network_interface_records = [
            line.split(":", 3)[1:]
            for line in lines
            if line.startswith("run-network-interfaces:")
        ]
        self.assertEqual(2, len(network_interface_records), lines)
        seen_subnets: set[str] = set()
        for client_token, mode, network_json in network_interface_records:
            self.assertIn(client_token.rsplit("-", 1)[-1], {"2a", "2b"})
            self.assertEqual("600", mode)
            network_interfaces = json.loads(network_json)
            self.assertEqual(1, len(network_interfaces))
            primary = network_interfaces[0]
            self.assertEqual(0, primary["DeviceIndex"])
            self.assertEqual(True, primary["DeleteOnTermination"])
            self.assertEqual(False, primary["AssociatePublicIpAddress"])
            self.assertEqual(["sg-private-asg"], primary["Groups"])
            seen_subnets.add(primary["SubnetId"])
        self.assertEqual(
            {"subnet-private-app-2a", "subnet-private-app-2b"},
            seen_subnets,
        )

        terminate_lines = [
            line for line in lines if "aws:ec2 terminate-instances" in line
        ]
        self.assertEqual(1, len(terminate_lines), lines)
        self.assertIn("i-validation-2a", terminate_lines[0])
        self.assertIn("i-validation-2b", terminate_lines[0])
        self.assertNotIn("i-public-old", terminate_lines[0])
        self.assertNotIn("i-private-new", terminate_lines[0])
        self.assertTrue(
            any(
                "aws:ssm describe-instance-information" in line
                for line in lines
            )
        )
        send_command_lines = [
            line for line in lines if "aws:ssm send-command" in line
        ]
        self.assertEqual(2, len(send_command_lines), lines)
        self.assertTrue(
            any("i-validation-2a" in line for line in send_command_lines)
        )
        self.assertTrue(
            any("i-validation-2b" in line for line in send_command_lines)
        )
        self.assertEqual(
            2,
            sum(
                "aws:ssm get-command-invocation" in line
                for line in lines
            ),
            lines,
        )

        command_documents = [
            json.loads(line.removeprefix("ssm-command:"))
            for line in lines
            if line.startswith("ssm-command:")
        ]
        self.assertEqual(2, len(command_documents), lines)
        for document in command_documents:
            self.assertEqual(["1800"], document["executionTimeout"])
            self.assertEqual(1, len(document["commands"]))
            command = document["commands"][0]
            for required_marker in (
                "/var/lib/buildgraph/asg-bootstrap-success",
                "label=com.docker.compose.service=$service",
                "container_id_for nginx",
                "container_id_for api",
                "container_id_for xgb-reranker",
                "127.0.0.1/api/health",
                "sport = :80",
                "amazon-cloudwatch-agent",
                "3.3.40.0",
                "git ls-remote",
                "github.com/jungle-final-project/prototype.git",
                "https://api.openai.com/",
                "https://accounts.google.com/",
                "https://openapi.naver.com/",
                "https://registry-1.docker.io/",
                "SPRING_DATASOURCE_URL",
                "SPRING_DATA_REDIS_HOST",
                "SPRING_RABBITMQ_ADDRESSES",
                "/dev/tcp/",
                "BUILDGRAPH_PRIVATE_AZ_VALIDATION_OK",
            ):
                with self.subTest(required_marker=required_marker):
                    self.assertIn(required_marker, command)
            for forbidden_marker in (
                ".env.prod",
                "get-secret-value",
                "printenv",
                "set -x",
                TEST_SECRET,
            ):
                with self.subTest(forbidden_marker=forbidden_marker):
                    self.assertNotIn(forbidden_marker, command)
        self.assertFalse(
            any(
                "aws:autoscaling update-auto-scaling-group" in line
                for line in lines
            )
        )
        self.assertNotIn(TEST_SECRET, result.stdout)

    def test_failed_safe_ssm_validation_cleans_only_owned_instances(
        self,
    ) -> None:
        result = self._run(
            "--validate-private-azs",
            "--apply",
            FAKE_SSM_COMMAND_STATUS="Failed",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertEqual(
            1,
            sum("aws:ssm send-command" in line for line in lines),
            lines,
        )
        terminate_lines = [
            line for line in lines if "aws:ec2 terminate-instances" in line
        ]
        self.assertEqual(1, len(terminate_lines), lines)
        self.assertIn("i-validation-2a", terminate_lines[0])
        self.assertNotIn("i-validation-2b", terminate_lines[0])
        self.assertNotIn("i-public-old", terminate_lines[0])
        self.assertNotIn("i-private-new", terminate_lines[0])
        self.assertFalse(
            any(
                "aws:autoscaling update-auto-scaling-group" in line
                for line in lines
            )
        )
        self.assertNotIn(TEST_SECRET, result.stdout)

    def test_lost_run_instances_response_rediscovers_and_cleans_owned_instance(
        self,
    ) -> None:
        result = self._run(
            "--validate-private-azs",
            "--apply",
            BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED="false",
            FAKE_RUN_INSTANCES_RESPONSE_LOST="true",
            FAKE_REDISCOVERY_EMPTY_ATTEMPTS="1",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertTrue(
            any(
                "Name=client-token,Values=" in line
                and "Name=tag:BuildGraphInvocation,Values=" in line
                and "Name=tag:BuildGraphPurpose,Values=private-app-az-validation"
                in line
                and "aws:ec2 describe-instances" in line
                for line in lines
            ),
            lines,
        )
        terminate_lines = [
            line for line in lines if "aws:ec2 terminate-instances" in line
        ]
        self.assertEqual(1, len(terminate_lines), lines)
        self.assertIn("i-validation-2a", terminate_lines[0])
        self.assertNotIn("i-validation-2b", terminate_lines[0])
        self.assertNotIn("i-public-old", terminate_lines[0])
        self.assertNotIn("i-private-new", terminate_lines[0])

    def test_lost_asg_update_response_is_state_checked_and_rolled_back(
        self,
    ) -> None:
        result = self._run(
            "--apply",
            FAKE_ASG_UPDATE_RESPONSE_LOST="true",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        update_lines = [
            line
            for line in lines
            if "aws:autoscaling update-auto-scaling-group" in line
        ]
        self.assertEqual(2, len(update_lines), lines)
        self.assertIn("Version=2", update_lines[0])
        self.assertIn("Version=1", update_lines[1])
        first_update_index = lines.index(update_lines[0])
        rollback_update_index = lines.index(update_lines[1])
        self.assertTrue(
            any(
                "aws:autoscaling describe-auto-scaling-groups" in line
                for line in lines[first_update_index + 1 : rollback_update_index]
            ),
            lines,
        )
        self.assertEqual(
            1,
            sum(
                "aws:autoscaling start-instance-refresh" in line
                for line in lines
            ),
            lines,
        )

    def test_command_substitution_error_rolls_back_only_once(self) -> None:
        result = self._run(
            "--apply",
            FAKE_REFRESH_DESCRIBE_ERROR="true",
        )

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        self.assertEqual(
            2,
            sum(
                "aws:autoscaling update-auto-scaling-group" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual(
            2,
            sum(
                "aws:autoscaling start-instance-refresh" in line
                for line in lines
            ),
            lines,
        )
        self.assertEqual(
            1,
            result.stdout.count(
                "previous ASG contract restored and reverse refresh started"
            ),
            result.stdout,
        )

    def test_failed_refresh_restores_old_subnets_and_version_then_reverses(
        self,
    ) -> None:
        result = self._run("--apply", FAKE_REFRESH_STATUS="Failed")

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        update_lines = [
            line
            for line in lines
            if "aws:autoscaling update-auto-scaling-group" in line
        ]
        refresh_lines = [
            line
            for line in lines
            if "aws:autoscaling start-instance-refresh" in line
        ]
        cancel_index = next(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling cancel-instance-refresh" in line
        )
        rollback_update_index = max(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling update-auto-scaling-group" in line
        )
        rollback_refresh_index = max(
            index
            for index, line in enumerate(lines)
            if "aws:autoscaling start-instance-refresh" in line
        )

        self.assertEqual(2, len(update_lines), lines)
        self.assertIn(
            "--vpc-zone-identifier subnet-public-2a,subnet-public-2b",
            update_lines[-1],
        )
        self.assertIn("Version=1", update_lines[-1])
        self.assertEqual(2, len(refresh_lines), lines)
        self.assertLess(cancel_index, rollback_update_index)
        self.assertLess(rollback_update_index, rollback_refresh_index)
        self.assertIn("rollback", result.stdout.lower())

    def test_term_signal_after_asg_mutation_rolls_back_and_starts_reverse_refresh(
        self,
    ) -> None:
        result = self._run("--apply", FAKE_SIGNAL_ON_REFRESH="TERM")

        self.assertNotEqual(0, result.returncode)
        lines = self._trace_lines()
        update_lines = [
            line
            for line in lines
            if "aws:autoscaling update-auto-scaling-group" in line
        ]
        refresh_lines = [
            line
            for line in lines
            if "aws:autoscaling start-instance-refresh" in line
        ]

        self.assertEqual(2, len(update_lines), lines)
        self.assertIn("Version=2", update_lines[0])
        self.assertIn("Version=1", update_lines[1])
        self.assertEqual(2, len(refresh_lines), lines)
        self.assertIn("signal", result.stdout.lower())
        self.assertIn("rollback", result.stdout.lower())


if __name__ == "__main__":
    unittest.main()
