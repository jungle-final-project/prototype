#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly EXPECTED_AWS_ACCOUNT_ID="443915990705"
readonly EXPECTED_AWS_REGION="ap-northeast-2"
readonly VPC_ID="vpc-06c90b864a62f93a4"
readonly ASG_NAME="buildgraph-demo-api-green-asg"
readonly LAUNCH_TEMPLATE_NAME="buildgraph-demo-api-green-lt"
readonly NAT_GATEWAY_NAME="buildgraph-demo-nat-regional"

readonly PRIVATE_SUBNET_NAME_2A="buildgraph-demo-subnet-private-app1-ap-northeast-2a"
readonly PRIVATE_SUBNET_CIDR_2A="10.0.34.0/24"
readonly PRIVATE_SUBNET_AZ_2A="ap-northeast-2a"
readonly PRIVATE_ROUTE_TABLE_NAME_2A="buildgraph-demo-rtb-private-app1-ap-northeast-2a"

readonly PRIVATE_SUBNET_NAME_2B="buildgraph-demo-subnet-private-app2-ap-northeast-2b"
readonly PRIVATE_SUBNET_CIDR_2B="10.0.35.0/24"
readonly PRIVATE_SUBNET_AZ_2B="ap-northeast-2b"
readonly PRIVATE_ROUTE_TABLE_NAME_2B="buildgraph-demo-rtb-private-app2-ap-northeast-2b"

readonly S3_ENDPOINT_NAME="buildgraph-demo-vpce-s3-private-app"
readonly ECR_API_ENDPOINT_NAME="buildgraph-demo-vpce-ecr-api-private-app"
readonly ECR_DKR_ENDPOINT_NAME="buildgraph-demo-vpce-ecr-dkr-private-app"
readonly SECRETSMANAGER_ENDPOINT_NAME="buildgraph-demo-vpce-secretsmanager-private-app"
readonly SSM_ENDPOINT_NAME="buildgraph-demo-vpce-ssm-private-app"
readonly SSMMESSAGES_ENDPOINT_NAME="buildgraph-demo-vpce-ssmmessages-private-app"

readonly AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-$EXPECTED_AWS_REGION}}"
readonly REFRESH_MAX_ATTEMPTS="${BUILDGRAPH_ASG_REFRESH_MAX_ATTEMPTS:-120}"
readonly REFRESH_POLL_SECONDS="${BUILDGRAPH_ASG_REFRESH_POLL_SECONDS:-15}"
readonly VALIDATION_MAX_ATTEMPTS="${BUILDGRAPH_PRIVATE_AZ_VALIDATION_MAX_ATTEMPTS:-140}"
readonly VALIDATION_POLL_SECONDS="${BUILDGRAPH_PRIVATE_AZ_VALIDATION_POLL_SECONDS:-15}"
readonly REDISCOVERY_MAX_ATTEMPTS="${BUILDGRAPH_VALIDATION_REDISCOVERY_MAX_ATTEMPTS:-6}"
readonly REDISCOVERY_POLL_SECONDS="${BUILDGRAPH_VALIDATION_REDISCOVERY_POLL_SECONDS:-5}"
readonly INVOCATION_ID="$(
  date -u '+%Y%m%dT%H%M%SZ'
)-$$"

APPLY=false
VALIDATE_PRIVATE_AZS=false
CURRENT_STEP="initialization"
TEMP_DIR=""
ASG_MUTATED=false
ASG_MUTATION_INTENT=false
ROLLBACK_STARTED=false
FORWARD_REFRESH_ID=""
FORWARD_REFRESH_INTENT=false
OLD_SUBNET_CSV=""
OLD_LAUNCH_TEMPLATE_VERSION=""
LAUNCH_TEMPLATE_ID=""
REFRESH_PREFERENCES_FILE=""
CANDIDATE_LAUNCH_TEMPLATE_VERSION=""
PRIVATE_SUBNET_ID_2A=""
PRIVATE_SUBNET_ID_2B=""
PRIVATE_ROUTE_TABLE_ID_2A=""
PRIVATE_ROUTE_TABLE_ID_2B=""
TARGET_GROUP_ARNS=()
VALIDATION_INSTANCE_IDS=()
VALIDATION_CLIENT_TOKENS=()

usage() {
  cat <<'EOF'
Usage:
  bash tools/migrate_green_web_asg_private.sh
  bash tools/migrate_green_web_asg_private.sh --apply
  bash tools/migrate_green_web_asg_private.sh --validate-private-azs
  bash tools/migrate_green_web_asg_private.sh --validate-private-azs --apply

The default is a read-only preflight. Only --apply permits AWS mutations.

--validate-private-azs
  Uses the candidate private Launch Template to validate one disposable
  instance in each private AZ. Without --apply, it only prints the validated
  plan. With --apply, it launches and terminates invocation-owned instances
  without changing the Auto Scaling group.

Environment:
  BUILDGRAPH_PRIVATE_APP_SUBNET_IDS
    Optional comma-separated pair of private app subnet IDs. When omitted,
    the script discovers both subnets by their exact Name tags. Supplied IDs
    are still validated against the fixed Name, CIDR, AZ, VPC, and public-IP
    assignment contract.

  BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED=true
    Required for ASG migration with --apply. This acknowledges that
    CloudFront /api/* and /ws/* are already isolated to the documented manual
    Green origin. Private-AZ validation does not require this gate because it
    does not change the ASG. This script never reads or changes CloudFront.
EOF
}

log() {
  printf '%s\n' "buildgraph private ASG migration: $*"
}

die() {
  printf '%s\n' \
    "buildgraph private ASG migration rejected: step=$CURRENT_STEP $*" >&2
  return 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command is missing: $1"
}

aws_cli() {
  aws "$@" \
    --region "$AWS_REGION" \
    --no-cli-pager
}

subnet_csv_sets_equal() {
  local left_csv="$1"
  local right_csv="$2"

  jq -e -n \
    --arg left "$left_csv" \
    --arg right "$right_csv" \
    '($left | split(",") | sort) == ($right | split(",") | sort)' \
    >/dev/null
}

cleanup_files() {
  local status=$?
  [[ "${BASH_SUBSHELL:-0}" -eq 0 ]] || return "$status"
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
  return "$status"
}

terminate_owned_validation_instances() {
  local instance_id owned_count client_token_csv
  local owned_instance_ids=()

  rediscover_owned_validation_instances || return 1
  [[ "${#VALIDATION_INSTANCE_IDS[@]}" -gt 0 ]] || return 0
  [[ "${#VALIDATION_CLIENT_TOKENS[@]}" -gt 0 ]] || return 1

  client_token_csv="$(validation_client_token_csv)"

  for instance_id in "${VALIDATION_INSTANCE_IDS[@]}"; do
    owned_count="$(
      aws_cli ec2 describe-instances \
        --instance-ids "$instance_id" \
        --filters \
          "Name=tag:BuildGraphInvocation,Values=$INVOCATION_ID" \
          "Name=tag:BuildGraphPurpose,Values=private-app-az-validation" \
          "Name=client-token,Values=$client_token_csv" \
        --query 'length(Reservations[].Instances[])' \
        --output text
    )" || return 1
    if [[ "$owned_count" != "1" ]]; then
      log "refusing to terminate instance without the invocation tag: $instance_id"
      return 1
    fi
    owned_instance_ids+=("$instance_id")
  done

  aws_cli ec2 terminate-instances \
    --instance-ids "${owned_instance_ids[@]}" \
    >/dev/null || return 1
  aws_cli ec2 wait instance-terminated \
    --instance-ids "${owned_instance_ids[@]}" ||
    return 1

  VALIDATION_INSTANCE_IDS=()
  VALIDATION_CLIENT_TOKENS=()
  return 0
}

append_validation_instance_id() {
  local candidate_instance_id="$1"
  local existing_instance_id

  if [[ "${#VALIDATION_INSTANCE_IDS[@]}" -gt 0 ]]; then
    for existing_instance_id in "${VALIDATION_INSTANCE_IDS[@]}"; do
      [[ "$existing_instance_id" != "$candidate_instance_id" ]] || return 0
    done
  fi
  VALIDATION_INSTANCE_IDS+=("$candidate_instance_id")
}

validation_client_token_csv() {
  local client_token csv=""

  for client_token in "${VALIDATION_CLIENT_TOKENS[@]}"; do
    if [[ -n "$csv" ]]; then
      csv="$csv,$client_token"
    else
      csv="$client_token"
    fi
  done
  printf '%s' "$csv"
}

rediscover_owned_validation_instances() {
  local client_token discovered_instances discovered_instance_id attempt

  [[ "${#VALIDATION_CLIENT_TOKENS[@]}" -gt 0 ]] || return 0
  for client_token in "${VALIDATION_CLIENT_TOKENS[@]}"; do
    discovered_instances=""
    for ((attempt = 1; attempt <= REDISCOVERY_MAX_ATTEMPTS; attempt++)); do
      discovered_instances="$(
        aws_cli ec2 describe-instances \
          --filters \
            "Name=tag:BuildGraphInvocation,Values=$INVOCATION_ID" \
            "Name=tag:BuildGraphPurpose,Values=private-app-az-validation" \
            "Name=client-token,Values=$client_token" \
            "Name=instance-state-name,Values=pending,running,stopping,stopped" \
          --query 'Reservations[].Instances[].InstanceId' \
          --output text
      )" || return 1
      [[ -z "$discovered_instances" ]] || break
      if [[ "$attempt" -lt "$REDISCOVERY_MAX_ATTEMPTS" ]]; then
        sleep "$REDISCOVERY_POLL_SECONDS"
      fi
    done
    for discovered_instance_id in $discovered_instances; do
      [[ "$discovered_instance_id" =~ ^i-[A-Za-z0-9-]+$ ]] || return 1
      append_validation_instance_id "$discovered_instance_id"
    done
  done
  return 0
}

rollback_asg() {
  local rollback_state_file current_subnets current_version
  local needs_restore=true
  local needs_reverse=false

  [[ "$ASG_MUTATION_INTENT" == "true" ||
    "$ASG_MUTATED" == "true" ]] || return 0
  [[ "$ROLLBACK_STARTED" != "true" ]] || return 0
  ROLLBACK_STARTED=true

  rollback_state_file="$TEMP_DIR/rollback-asg.json"
  if aws_cli autoscaling describe-auto-scaling-groups \
    --auto-scaling-group-names "$ASG_NAME" \
    --output json >"$rollback_state_file"
  then
    current_subnets="$(
      jq -r '.AutoScalingGroups[0].VPCZoneIdentifier // empty' \
        "$rollback_state_file"
    )"
    current_version="$(
      jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' \
        "$rollback_state_file"
    )"
    if [[ -n "$current_subnets" &&
      "$current_version" == "$OLD_LAUNCH_TEMPLATE_VERSION" ]] &&
      subnet_csv_sets_equal "$current_subnets" "$OLD_SUBNET_CSV"
    then
      needs_restore=false
    fi
  else
    log "rollback: ASG state could not be re-described; restoring conservatively"
  fi

  if [[ "$FORWARD_REFRESH_INTENT" == "true" ||
    -n "$FORWARD_REFRESH_ID" ]]
  then
    needs_reverse=true
    log "rollback: cancelling the invocation-owned forward refresh"
    aws_cli autoscaling cancel-instance-refresh \
      --auto-scaling-group-name "$ASG_NAME" \
      >/dev/null 2>&1 || true
  fi

  if [[ "$needs_restore" == "true" ]]; then
    needs_reverse=true
    log "rollback: restoring previous subnets and Launch Template version"
    if ! aws_cli autoscaling update-auto-scaling-group \
      --auto-scaling-group-name "$ASG_NAME" \
      --vpc-zone-identifier "$OLD_SUBNET_CSV" \
      --launch-template \
        "LaunchTemplateId=$LAUNCH_TEMPLATE_ID,Version=$OLD_LAUNCH_TEMPLATE_VERSION"
    then
      log "rollback failed while restoring the ASG configuration"
      return 1
    fi
  fi

  if [[ "$needs_reverse" != "true" ]]; then
    ASG_MUTATED=false
    ASG_MUTATION_INTENT=false
    FORWARD_REFRESH_INTENT=false
    log "rollback: re-description confirmed that the ASG never left the previous contract"
    return 0
  fi

  log "rollback: starting reverse instance refresh"
  if ! aws_cli autoscaling start-instance-refresh \
    --auto-scaling-group-name "$ASG_NAME" \
    --strategy Rolling \
    --preferences "file://$REFRESH_PREFERENCES_FILE" \
    --query InstanceRefreshId \
    --output text \
    >/dev/null
  then
    log "rollback restored the ASG configuration but reverse refresh did not start"
    return 1
  fi

  ASG_MUTATED=false
  ASG_MUTATION_INTENT=false
  FORWARD_REFRESH_INTENT=false
  log "rollback: previous ASG contract restored and reverse refresh started"
  return 0
}

handle_error() {
  local status=$?
  if [[ "${BASH_SUBSHELL:-0}" -gt 0 ]]; then
    trap - ERR
    return "$status"
  fi
  trap - ERR INT TERM
  set +e
  if [[ "$status" -eq 130 || "$status" -eq 143 ]]; then
    log "signal-like failure: step=$CURRENT_STEP status=$status"
  else
    log "failure: step=$CURRENT_STEP status=$status"
  fi
  if ! terminate_owned_validation_instances; then
    log "validation cleanup could not prove ownership for every disposable instance"
  fi
  if ! rollback_asg; then
    log "automatic rollback requires operator follow-up"
  fi
  exit "$status"
}

handle_signal() {
  local signal_name="$1"
  local status=130
  [[ "$signal_name" != "TERM" ]] || status=143
  trap - ERR INT TERM
  set +e
  log "signal received: $signal_name"
  if ! terminate_owned_validation_instances; then
    log "validation cleanup could not prove ownership for every disposable instance"
  fi
  if ! rollback_asg; then
    log "automatic rollback requires operator follow-up"
  fi
  exit "$status"
}

trap cleanup_files EXIT
trap handle_error ERR
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --apply)
      APPLY=true
      ;;
    --validate-private-azs)
      VALIDATE_PRIVATE_AZS=true
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
  shift
done

CURRENT_STEP="validate-local-contract"
for command_name in aws base64 chmod date jq mktemp rm sleep tr; do
  require_command "$command_name"
done

[[ "$AWS_REGION" == "$EXPECTED_AWS_REGION" ]] ||
  die "AWS region must be $EXPECTED_AWS_REGION"
[[ "$REFRESH_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] ||
  die "refresh max attempts must be positive"
[[ "$REFRESH_POLL_SECONDS" =~ ^[0-9]+$ ]] ||
  die "refresh poll seconds must be non-negative"
[[ "$VALIDATION_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] ||
  die "private AZ validation max attempts must be positive"
[[ "$VALIDATION_POLL_SECONDS" =~ ^[0-9]+$ ]] ||
  die "private AZ validation poll seconds must be non-negative"
[[ "$REDISCOVERY_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] ||
  die "validation rediscovery max attempts must be positive"
[[ "$REDISCOVERY_POLL_SECONDS" =~ ^[0-9]+$ ]] ||
  die "validation rediscovery poll seconds must be non-negative"

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buildgraph-private-asg.XXXXXX")"
readonly ASG_STATE_FILE="$TEMP_DIR/asg.json"
readonly SUBNET_STATE_FILE="$TEMP_DIR/subnets.json"
readonly NAT_STATE_FILE="$TEMP_DIR/nat.json"
readonly ENDPOINT_STATE_FILE="$TEMP_DIR/endpoints.json"
readonly SOURCE_NETWORK_INTERFACES_FILE="$TEMP_DIR/source-network-interfaces.json"
readonly LAUNCH_TEMPLATE_OVERRIDE_FILE="$TEMP_DIR/launch-template-override.json"
readonly SSM_VALIDATION_PARAMETERS_FILE="$TEMP_DIR/ssm-validation-parameters.json"
readonly POST_ASG_STATE_FILE="$TEMP_DIR/post-asg.json"
readonly POST_INSTANCE_STATE_FILE="$TEMP_DIR/post-instance.json"
REFRESH_PREFERENCES_FILE="$TEMP_DIR/refresh-preferences.json"

CURRENT_STEP="verify-account"
readonly CALLER_ACCOUNT="$(
  aws_cli sts get-caller-identity \
    --query Account \
    --output text
)"
[[ "$CALLER_ACCOUNT" == "$EXPECTED_AWS_ACCOUNT_ID" ]] ||
  die "caller account must be $EXPECTED_AWS_ACCOUNT_ID"

CURRENT_STEP="inspect-asg"
aws_cli autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names "$ASG_NAME" \
  --output json >"$ASG_STATE_FILE"

[[ "$(jq '.AutoScalingGroups | length' "$ASG_STATE_FILE")" == "1" ]] ||
  die "ASG $ASG_NAME must exist exactly once"

readonly ASG_MIN_SIZE="$(
  jq -r '.AutoScalingGroups[0].MinSize' "$ASG_STATE_FILE"
)"
readonly ASG_DESIRED_CAPACITY="$(
  jq -r '.AutoScalingGroups[0].DesiredCapacity' "$ASG_STATE_FILE"
)"
readonly ASG_MAX_SIZE="$(
  jq -r '.AutoScalingGroups[0].MaxSize' "$ASG_STATE_FILE"
)"
if [[ "$ASG_MIN_SIZE" != "1" ||
  "$ASG_DESIRED_CAPACITY" != "1" ||
  "$ASG_MAX_SIZE" != "1" ]]
then
  die "ASG Min/Desired/Max must be 1/1/1 before migration"
fi

CURRENT_STEP="validate-no-active-instance-refresh"
readonly ACTIVE_INSTANCE_REFRESH_STATUS="$(
  aws_cli autoscaling describe-instance-refreshes \
    --auto-scaling-group-name "$ASG_NAME" \
    --query \
      'InstanceRefreshes[?Status==`Pending` || Status==`InProgress`][0].Status' \
    --output text
)"
case "$ACTIVE_INSTANCE_REFRESH_STATUS" in
  ''|None)
    ;;
  *)
    die "an active instance refresh already exists: $ACTIVE_INSTANCE_REFRESH_STATUS"
    ;;
esac

OLD_SUBNET_CSV="$(
  jq -r '.AutoScalingGroups[0].VPCZoneIdentifier // empty' "$ASG_STATE_FILE"
)"
[[ -n "$OLD_SUBNET_CSV" ]] ||
  die "ASG VPCZoneIdentifier is empty"

LAUNCH_TEMPLATE_ID="$(
  jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateId // empty' \
    "$ASG_STATE_FILE"
)"
readonly CURRENT_LAUNCH_TEMPLATE_NAME="$(
  jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateName // empty' \
    "$ASG_STATE_FILE"
)"
OLD_LAUNCH_TEMPLATE_VERSION="$(
  jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' \
    "$ASG_STATE_FILE"
)"
[[ -n "$LAUNCH_TEMPLATE_ID" ]] ||
  die "ASG must use a direct Launch Template ID"
[[ "$CURRENT_LAUNCH_TEMPLATE_NAME" == "$LAUNCH_TEMPLATE_NAME" ]] ||
  die "ASG Launch Template must be $LAUNCH_TEMPLATE_NAME"
[[ "$OLD_LAUNCH_TEMPLATE_VERSION" =~ ^[1-9][0-9]*$ ]] ||
  die "ASG Launch Template version must be a pinned numeric version"

readonly ASG_DEFAULT_WARMUP="$(
  jq -r '.AutoScalingGroups[0].DefaultInstanceWarmup // empty' \
    "$ASG_STATE_FILE"
)"
readonly ASG_HEALTH_GRACE="$(
  jq -r '.AutoScalingGroups[0].HealthCheckGracePeriod // empty' \
    "$ASG_STATE_FILE"
)"
[[ "$ASG_HEALTH_GRACE" =~ ^[0-9]+$ ]] ||
  die "ASG HealthCheckGracePeriod must be numeric"
if [[ -n "$ASG_DEFAULT_WARMUP" ]]; then
  [[ "$ASG_DEFAULT_WARMUP" =~ ^[0-9]+$ ]] ||
    die "ASG DefaultInstanceWarmup must be numeric"
  readonly REFRESH_INSTANCE_WARMUP="$ASG_DEFAULT_WARMUP"
else
  readonly REFRESH_INSTANCE_WARMUP="$ASG_HEALTH_GRACE"
fi

while IFS= read -r target_group_arn; do
  [[ -n "$target_group_arn" ]] || continue
  TARGET_GROUP_ARNS+=("$target_group_arn")
done < <(
  jq -r '.AutoScalingGroups[0].TargetGroupARNs[]?' "$ASG_STATE_FILE"
)
[[ "${#TARGET_GROUP_ARNS[@]}" -gt 0 ]] ||
  die "ASG must be attached to at least one Target Group"

CURRENT_STEP="discover-private-subnets"
if [[ -n "${BUILDGRAPH_PRIVATE_APP_SUBNET_IDS:-}" ]]; then
  explicit_subnet_id_2a=""
  explicit_subnet_id_2b=""
  unexpected_subnet_id=""
  IFS=',' read -r \
    explicit_subnet_id_2a \
    explicit_subnet_id_2b \
    unexpected_subnet_id \
    <<<"$BUILDGRAPH_PRIVATE_APP_SUBNET_IDS"
  [[ -n "$explicit_subnet_id_2a" &&
    -n "$explicit_subnet_id_2b" &&
    -z "$unexpected_subnet_id" ]] ||
    die "BUILDGRAPH_PRIVATE_APP_SUBNET_IDS must contain exactly two IDs"
  requested_subnet_ids=(
    "$explicit_subnet_id_2a"
    "$explicit_subnet_id_2b"
  )
else
  discovered_subnet_id_2a="$(
    aws_cli ec2 describe-subnets \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=tag:Name,Values=$PRIVATE_SUBNET_NAME_2A" \
      --query 'Subnets[].SubnetId' \
      --output text
  )"
  discovered_subnet_id_2b="$(
    aws_cli ec2 describe-subnets \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=tag:Name,Values=$PRIVATE_SUBNET_NAME_2B" \
      --query 'Subnets[].SubnetId' \
      --output text
  )"
  [[ "$discovered_subnet_id_2a" =~ ^subnet-[A-Za-z0-9-]+$ ]] ||
    die "private app 2a subnet discovery returned an invalid ID"
  [[ "$discovered_subnet_id_2b" =~ ^subnet-[A-Za-z0-9-]+$ ]] ||
    die "private app 2b subnet discovery returned an invalid ID"
  requested_subnet_ids=(
    "$discovered_subnet_id_2a"
    "$discovered_subnet_id_2b"
  )
fi

[[ "${requested_subnet_ids[0]}" != "${requested_subnet_ids[1]}" ]] ||
  die "private app subnet IDs must be distinct"

aws_cli ec2 describe-subnets \
  --subnet-ids "${requested_subnet_ids[@]}" \
  --output json >"$SUBNET_STATE_FILE"

[[ "$(jq '.Subnets | length' "$SUBNET_STATE_FILE")" == "2" ]] ||
  die "exactly two private app subnets must be returned"

validate_subnet_contract() {
  local expected_name="$1"
  local expected_cidr="$2"
  local expected_az="$3"
  local match_count

  match_count="$(
    jq \
      --arg vpc "$VPC_ID" \
      --arg name "$expected_name" \
      --arg cidr "$expected_cidr" \
      --arg az "$expected_az" \
      '[
        .Subnets[]
        | select(.VpcId == $vpc)
        | select(.State == "available")
        | select(.AvailabilityZone == $az)
        | select(.CidrBlock == $cidr)
        | select(.MapPublicIpOnLaunch == false)
        | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
      ] | length' \
      "$SUBNET_STATE_FILE"
  )"
  [[ "$match_count" == "1" ]] ||
    die "private subnet contract mismatch for $expected_name"
}

validate_subnet_contract \
  "$PRIVATE_SUBNET_NAME_2A" \
  "$PRIVATE_SUBNET_CIDR_2A" \
  "$PRIVATE_SUBNET_AZ_2A"
validate_subnet_contract \
  "$PRIVATE_SUBNET_NAME_2B" \
  "$PRIVATE_SUBNET_CIDR_2B" \
  "$PRIVATE_SUBNET_AZ_2B"

PRIVATE_SUBNET_ID_2A="$(
  jq -r \
    --arg name "$PRIVATE_SUBNET_NAME_2A" \
    '.Subnets[]
     | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
     | .SubnetId' \
    "$SUBNET_STATE_FILE"
)"
PRIVATE_SUBNET_ID_2B="$(
  jq -r \
    --arg name "$PRIVATE_SUBNET_NAME_2B" \
    '.Subnets[]
     | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
     | .SubnetId' \
    "$SUBNET_STATE_FILE"
)"
readonly PRIVATE_SUBNET_CSV="$PRIVATE_SUBNET_ID_2A,$PRIVATE_SUBNET_ID_2B"

CURRENT_STEP="validate-regional-nat"
aws_cli ec2 describe-nat-gateways \
  --filter \
    "Name=vpc-id,Values=$VPC_ID" \
    "Name=state,Values=pending,available,failed,deleting" \
  --output json >"$NAT_STATE_FILE"

readonly AVAILABLE_NAT_COUNT="$(
  jq \
    --arg vpc "$VPC_ID" \
    --arg name "$NAT_GATEWAY_NAME" \
    '[
      .NatGateways[]
      | select(.VpcId == $vpc)
      | select(.State == "available")
      | select(.ConnectivityType == "public")
      | select(.AvailabilityMode == "regional")
      | select(.AutoProvisionZones == "enabled")
      | select(.AutoScalingIps == "enabled")
      | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
    ] | length' \
    "$NAT_STATE_FILE"
)"
[[ "$(jq '.NatGateways | length' "$NAT_STATE_FILE")" == "1" &&
  "$AVAILABLE_NAT_COUNT" == "1" ]] ||
  die "Regional NAT $NAT_GATEWAY_NAME must be available"

readonly NAT_GATEWAY_ID="$(
  jq -r \
    --arg name "$NAT_GATEWAY_NAME" \
    '.NatGateways[]
     | select(.State == "available")
     | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
     | .NatGatewayId' \
    "$NAT_STATE_FILE"
)"

validate_private_route_table() {
  local subnet_id="$1"
  local expected_name="$2"
  local output_file="$3"
  local match_count route_table_id

  aws_cli ec2 describe-route-tables \
    --filters "Name=association.subnet-id,Values=$subnet_id" \
    --output json >"$output_file"

  match_count="$(
    jq \
      --arg vpc "$VPC_ID" \
      --arg subnet "$subnet_id" \
      --arg name "$expected_name" \
      --arg nat "$NAT_GATEWAY_ID" \
      '[
        .RouteTables[]
        | select(.VpcId == $vpc)
        | select(any(.Associations[]?; .SubnetId == $subnet))
        | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
        | select(any(
            .Routes[]?;
            .DestinationCidrBlock == "0.0.0.0/0"
            and .NatGatewayId == $nat
            and .State == "active"
          ))
      ] | length' \
      "$output_file"
  )"
  [[ "$match_count" == "1" ]] ||
    die "private route table $expected_name must use the available Regional NAT"

  route_table_id="$(
    jq -r \
      --arg name "$expected_name" \
      '.RouteTables[]
       | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
       | .RouteTableId' \
      "$output_file"
  )"
  printf '%s' "$route_table_id"
}

PRIVATE_ROUTE_TABLE_ID_2A="$(
  validate_private_route_table \
    "$PRIVATE_SUBNET_ID_2A" \
    "$PRIVATE_ROUTE_TABLE_NAME_2A" \
    "$TEMP_DIR/route-2a.json"
)"
PRIVATE_ROUTE_TABLE_ID_2B="$(
  validate_private_route_table \
    "$PRIVATE_SUBNET_ID_2B" \
    "$PRIVATE_ROUTE_TABLE_NAME_2B" \
    "$TEMP_DIR/route-2b.json"
)"

CURRENT_STEP="validate-vpc-endpoints"
aws_cli ec2 describe-vpc-endpoints \
  --filters "Name=vpc-id,Values=$VPC_ID" \
  --output json >"$ENDPOINT_STATE_FILE"

validate_gateway_endpoint() {
  local endpoint_name="$1"
  local service_name="$2"

  jq -e \
    --arg vpc "$VPC_ID" \
    --arg name "$endpoint_name" \
    --arg service "$service_name" \
    --arg route_2a "$PRIVATE_ROUTE_TABLE_ID_2A" \
    --arg route_2b "$PRIVATE_ROUTE_TABLE_ID_2B" \
    '[
      .VpcEndpoints[]
      | select(.VpcId == $vpc)
      | select(.State == "available")
      | select(.VpcEndpointType == "Gateway")
      | select(.ServiceName == $service)
      | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
      | select(
          (.RouteTableIds | sort) ==
          ([$route_2a, $route_2b] | sort)
        )
    ] | length == 1' \
    "$ENDPOINT_STATE_FILE" >/dev/null ||
    die "Gateway VPC endpoint $endpoint_name must be available on both private route tables"
}

validate_interface_endpoint() {
  local endpoint_name="$1"
  local service_name="$2"

  jq -e \
    --arg vpc "$VPC_ID" \
    --arg name "$endpoint_name" \
    --arg service "$service_name" \
    --arg subnet_2a "$PRIVATE_SUBNET_ID_2A" \
    --arg subnet_2b "$PRIVATE_SUBNET_ID_2B" \
    '[
      .VpcEndpoints[]
      | select(.VpcId == $vpc)
      | select(.State == "available")
      | select(.VpcEndpointType == "Interface")
      | select(.ServiceName == $service)
      | select(.PrivateDnsEnabled == true)
      | select(any(.Tags[]?; .Key == "Name" and .Value == $name))
      | select(
          (.SubnetIds | sort) ==
          ([$subnet_2a, $subnet_2b] | sort)
        )
    ] | length == 1' \
    "$ENDPOINT_STATE_FILE" >/dev/null ||
    die "Interface VPC endpoint $endpoint_name must be available in both private subnets"
}

validate_gateway_endpoint \
  "$S3_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.s3"
validate_interface_endpoint \
  "$ECR_API_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.ecr.api"
validate_interface_endpoint \
  "$ECR_DKR_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.ecr.dkr"
validate_interface_endpoint \
  "$SECRETSMANAGER_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.secretsmanager"
validate_interface_endpoint \
  "$SSM_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.ssm"
validate_interface_endpoint \
  "$SSMMESSAGES_ENDPOINT_NAME" \
  "com.amazonaws.$AWS_REGION.ssmmessages"

CURRENT_STEP="prepare-launch-template-override"
aws_cli ec2 describe-launch-template-versions \
  --launch-template-id "$LAUNCH_TEMPLATE_ID" \
  --versions "$OLD_LAUNCH_TEMPLATE_VERSION" \
  --query \
    'LaunchTemplateVersions[0].LaunchTemplateData.NetworkInterfaces' \
  --output json >"$SOURCE_NETWORK_INTERFACES_FILE"

validate_converged_private_runtime() {
  local instance_id instance_state_file target_group_arn target_health

  jq -e \
    --arg subnet_2a "$PRIVATE_SUBNET_ID_2A" \
    --arg subnet_2b "$PRIVATE_SUBNET_ID_2B" \
    --arg version "$OLD_LAUNCH_TEMPLATE_VERSION" \
    '(
       .AutoScalingGroups[0].VPCZoneIdentifier
       | split(",")
       | sort
     ) == ([$subnet_2a, $subnet_2b] | sort)
     and .AutoScalingGroups[0].LaunchTemplate.Version == $version
     and (.AutoScalingGroups[0].Instances | length) == 1
     and .AutoScalingGroups[0].Instances[0].LifecycleState == "InService"
     and .AutoScalingGroups[0].Instances[0].HealthStatus == "Healthy"' \
    "$ASG_STATE_FILE" >/dev/null ||
    die "already-private ASG runtime state is not converged"

  instance_id="$(
    jq -r '.AutoScalingGroups[0].Instances[0].InstanceId' \
      "$ASG_STATE_FILE"
  )"
  [[ "$instance_id" =~ ^i-[A-Za-z0-9-]+$ ]] ||
    die "already-private ASG instance ID is invalid"

  instance_state_file="$TEMP_DIR/converged-private-instance.json"
  aws_cli ec2 describe-instances \
    --instance-ids "$instance_id" \
    --output json >"$instance_state_file"
  jq -e \
    --arg instance "$instance_id" \
    --arg subnet_2a "$PRIVATE_SUBNET_ID_2A" \
    --arg subnet_2b "$PRIVATE_SUBNET_ID_2B" \
    '[.Reservations[].Instances[]]
     | length == 1
       and .[0].InstanceId == $instance
       and .[0].State.Name == "running"
       and (
         .[0].SubnetId == $subnet_2a
         or .[0].SubnetId == $subnet_2b
       )
       and .[0].PublicIpAddress == null' \
    "$instance_state_file" >/dev/null ||
    die "already-private ASG instance network state is not converged"

  for target_group_arn in "${TARGET_GROUP_ARNS[@]}"; do
    target_health="$(
      aws_cli elbv2 describe-target-health \
        --target-group-arn "$target_group_arn" \
        --targets "Id=$instance_id" \
        --query 'TargetHealthDescriptions[0].TargetHealth.State' \
        --output text
    )"
    [[ "$target_health" == "healthy" ]] ||
      die "already-private ASG instance is not Target Group healthy"
  done
}

if subnet_csv_sets_equal "$OLD_SUBNET_CSV" "$PRIVATE_SUBNET_CSV"; then
  jq -e \
    'length == 1
     and .[0].DeviceIndex == 0
     and (.[0].Groups | type == "array" and length > 0)
     and .[0].AssociatePublicIpAddress == false
     and (.[0] | has("SubnetId") | not)' \
    "$SOURCE_NETWORK_INTERFACES_FILE" >/dev/null ||
    die "already-private ASG Launch Template network interface is not converged"
  validate_converged_private_runtime
  log "already converged: private subnets, pinned private Launch Template, no public IPv4, and healthy Target"
  log "safe no-op: no Launch Template, ASG, refresh, validation instance, or CloudFront mutation was performed"
  exit 0
fi

SOURCE_USER_DATA_BASE64="$(
  aws_cli ec2 describe-launch-template-versions \
    --launch-template-id "$LAUNCH_TEMPLATE_ID" \
    --versions "$OLD_LAUNCH_TEMPLATE_VERSION" \
    --query 'LaunchTemplateVersions[0].LaunchTemplateData.UserData' \
    --output text
)"
[[ -n "$SOURCE_USER_DATA_BASE64" && "$SOURCE_USER_DATA_BASE64" != "None" ]] ||
  die "source Launch Template user data is missing"
if ! SOURCE_USER_DATA="$(
  printf '%s' "$SOURCE_USER_DATA_BASE64" |
    base64 --decode 2>/dev/null
)"
then
  die "source Launch Template user data is not valid base64"
fi

patch_source_user_data() {
  local source_user_data="$1"
  local line
  local bootstrap_count=0
  local regional_export_count=0
  local bootstrap_seen=false
  local candidate_user_data=""

  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      '#!/usr/bin/env bash'|'#!/bin/bash'|'set -Eeuo pipefail'|'')
        candidate_user_data="${candidate_user_data}${line}"$'\n'
        ;;
      'export AWS_STS_REGIONAL_ENDPOINTS=regional')
        [[ "$bootstrap_seen" != "true" ]] ||
          die "regional STS export must precede the bootstrap call"
        regional_export_count=$((regional_export_count + 1))
        [[ "$regional_export_count" -eq 1 ]] ||
          die "source Launch Template has duplicate regional STS exports"
        candidate_user_data="${candidate_user_data}${line}"$'\n'
        ;;
      '/opt/buildgraph/prototype/tools/bootstrap_green_asg.sh'|\
      'bash /opt/buildgraph/prototype/tools/bootstrap_green_asg.sh'|\
      '/usr/bin/env bash /opt/buildgraph/prototype/tools/bootstrap_green_asg.sh')
        bootstrap_count=$((bootstrap_count + 1))
        [[ "$bootstrap_count" -eq 1 ]] ||
          die "source Launch Template must call the bootstrap exactly once"
        if [[ "$regional_export_count" -eq 0 ]]; then
          candidate_user_data="${candidate_user_data}"\
'export AWS_STS_REGIONAL_ENDPOINTS=regional'$'\n'
          regional_export_count=1
        fi
        candidate_user_data="${candidate_user_data}${line}"$'\n'
        bootstrap_seen=true
        ;;
      *)
        die "source Launch Template user data contains a non-approved command"
        ;;
    esac
  done <<<"$source_user_data"

  [[ "$bootstrap_count" -eq 1 && "$regional_export_count" -eq 1 ]] ||
    die "source Launch Template user data is not the approved bootstrap pattern"
  CANDIDATE_USER_DATA="$candidate_user_data"
}

patch_source_user_data "$SOURCE_USER_DATA"
CANDIDATE_USER_DATA_BASE64="$(
  printf '%s' "$CANDIDATE_USER_DATA" |
    base64 |
    tr -d '\r\n'
)"
[[ -n "$CANDIDATE_USER_DATA_BASE64" ]] ||
  die "candidate Launch Template user data encoding is empty"

jq -e \
  'length == 1
   and .[0].DeviceIndex == 0
   and (.[0].Groups | type == "array" and length > 0)
   and .[0].AssociatePublicIpAddress == true' \
  "$SOURCE_NETWORK_INTERFACES_FILE" >/dev/null ||
  die "source Launch Template must have one primary public network interface"

jq \
  --arg user_data "$CANDIDATE_USER_DATA_BASE64" \
  '{
    NetworkInterfaces: [
      .[0]
      | del(.SubnetId)
      | .AssociatePublicIpAddress = false
    ],
    UserData: $user_data
  }' \
  "$SOURCE_NETWORK_INTERFACES_FILE" >"$LAUNCH_TEMPLATE_OVERRIDE_FILE"

jq -e \
  '(.NetworkInterfaces | length) == 1
   and .NetworkInterfaces[0].AssociatePublicIpAddress == false
   and (.NetworkInterfaces[0] | has("SubnetId") | not)
   and (.UserData | type == "string" and length > 0)' \
  "$LAUNCH_TEMPLATE_OVERRIDE_FILE" >/dev/null ||
  die "candidate Launch Template network override is invalid"

unset \
  SOURCE_USER_DATA \
  SOURCE_USER_DATA_BASE64 \
  CANDIDATE_USER_DATA \
  CANDIDATE_USER_DATA_BASE64

jq -n \
  --argjson instance_warmup "$REFRESH_INSTANCE_WARMUP" \
  '{
    MinHealthyPercentage: 100,
    MaxHealthyPercentage: 200,
    InstanceWarmup: $instance_warmup
  }' >"$REFRESH_PREFERENCES_FILE"

log "preflight passed: ASG capacity=1/1/1"
log "preflight passed: private subnets=$PRIVATE_SUBNET_CSV"
log "preflight passed: Regional NAT and required S3/ECR/Secrets/SSM endpoints are available"
log "preflight passed: pinned source Launch Template version=$OLD_LAUNCH_TEMPLATE_VERSION"
log "preflight preserved: default warmup=${ASG_DEFAULT_WARMUP:-unset}s health grace=${ASG_HEALTH_GRACE}s"
if [[ "$VALIDATE_PRIVATE_AZS" == "true" ]]; then
  log "CloudFront is outside private-AZ validation and will not be changed"
else
  log "CloudFront manual Green isolation is the required apply precondition and will not be changed"
fi

if [[ "$APPLY" != "true" ]]; then
  if [[ "$VALIDATE_PRIVATE_AZS" == "true" ]]; then
    log "read-only validation plan: one invocation-owned disposable instance per private AZ"
  else
    log "read-only migration plan: create a private Launch Template version, update the ASG, and start an instance refresh"
  fi
  exit 0
fi

if [[ "$VALIDATE_PRIVATE_AZS" != "true" &&
  "${BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED:-false}" != "true" ]]
then
  die "CloudFront /api/* and /ws/* must already use the manual Green origin before ASG migration"
fi

CURRENT_STEP="create-private-launch-template-version"
CANDIDATE_LAUNCH_TEMPLATE_VERSION="$(
  aws_cli ec2 create-launch-template-version \
    --launch-template-id "$LAUNCH_TEMPLATE_ID" \
    --source-version "$OLD_LAUNCH_TEMPLATE_VERSION" \
    --version-description "buildgraph-private-app-$INVOCATION_ID" \
    --launch-template-data "file://$LAUNCH_TEMPLATE_OVERRIDE_FILE" \
    --query 'LaunchTemplateVersion.VersionNumber' \
    --output text
)"
[[ "$CANDIDATE_LAUNCH_TEMPLATE_VERSION" =~ ^[1-9][0-9]*$ ]] ||
  die "candidate Launch Template version must be numeric"
[[ "$CANDIDATE_LAUNCH_TEMPLATE_VERSION" != "$OLD_LAUNCH_TEMPLATE_VERSION" ]] ||
  die "candidate Launch Template version must differ from the source"

prepare_safe_ssm_validation_parameters() {
  local safe_validation_command=""

  IFS= read -r -d '' safe_validation_command <<'SSM_COMMAND' || true
/usr/bin/env bash <<'BUILDGRAPH_SAFE_VALIDATION'
set -Eeuo pipefail
set +x
umask 077
exec 3>&1
exec >/dev/null 2>&1

health_file=""
cleanup() {
  if [[ -n "$health_file" && -f "$health_file" ]]; then
    rm -f "$health_file"
  fi
}
trap cleanup EXIT

for required_command in \
  bash \
  curl \
  docker \
  dpkg \
  git \
  grep \
  head \
  jq \
  mktemp \
  sed \
  ss \
  systemctl \
  timeout
do
  command -v "$required_command" >/dev/null 2>&1
done

bootstrap_ready=false
for ((attempt = 1; attempt <= 120; attempt++)); do
  if [[ -s /var/lib/buildgraph/asg-bootstrap-success ]]; then
    bootstrap_ready=true
    break
  fi
  sleep 10
done
[[ "$bootstrap_ready" == "true" ]]

container_id_for() {
  local service="$1"
  docker ps \
    --filter "label=com.docker.compose.project=buildgraph-green" \
    --filter "label=com.docker.compose.service=$service" \
    --filter status=running \
    --format '{{.ID}}' |
    head -n 1
}

nginx_container="$(container_id_for nginx)"
api_container="$(container_id_for api)"
xgb_container="$(container_id_for xgb-reranker)"
[[ -n "$nginx_container" ]]
[[ -n "$api_container" ]]
[[ -n "$xgb_container" ]]

health_file="$(mktemp "${TMPDIR:-/tmp}/buildgraph-private-health.XXXXXX")"
health_code="$(
  curl \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 15 \
    --output "$health_file" \
    --write-out '%{http_code}' \
    http://127.0.0.1/api/health
)"
[[ "$health_code" == "200" ]]
jq -e '.status == "UP" and .database == "UP"' "$health_file" >/dev/null
ss -H -ltn 'sport = :80' | grep -q .
systemctl is-active --quiet amazon-cloudwatch-agent

ssm_agent_binary=""
if command -v amazon-ssm-agent >/dev/null 2>&1; then
  ssm_agent_binary="$(command -v amazon-ssm-agent)"
elif [[ -x /snap/amazon-ssm-agent/current/amazon-ssm-agent ]]; then
  ssm_agent_binary="/snap/amazon-ssm-agent/current/amazon-ssm-agent"
fi
[[ -n "$ssm_agent_binary" ]]
ssm_agent_version="$(
  "$ssm_agent_binary" -version 2>&1 |
    grep -Eo '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' |
    head -n 1
)"
[[ -n "$ssm_agent_version" ]]
dpkg --compare-versions "$ssm_agent_version" ge "3.3.40.0"

git ls-remote \
  --exit-code \
  https://github.com/jungle-final-project/prototype.git \
  HEAD >/dev/null
for https_endpoint in \
  https://api.openai.com/v1/models \
  https://accounts.google.com/.well-known/openid-configuration \
  https://openapi.naver.com/ \
  https://registry-1.docker.io/v2/
do
  curl \
    --silent \
    --show-error \
    --connect-timeout 10 \
    --max-time 20 \
    --output /dev/null \
    "$https_endpoint"
done

api_environment="$(
  docker inspect \
    --format '{{range .Config.Env}}{{println .}}{{end}}' \
    "$api_container"
)"
ENVIRONMENT_VALUE=""
environment_value() {
  local key="$1"
  local remaining="$api_environment"
  local entry=""
  ENVIRONMENT_VALUE=""

  while [[ -n "$remaining" ]]; do
    if [[ "$remaining" == *$'\n'* ]]; then
      entry="${remaining%%$'\n'*}"
      remaining="${remaining#*$'\n'}"
    else
      entry="$remaining"
      remaining=""
    fi
    case "$entry" in
      "$key="*)
        ENVIRONMENT_VALUE="${entry#*=}"
        ;;
    esac
  done
  [[ -n "$ENVIRONMENT_VALUE" ]]
}

environment_value SPRING_DATASOURCE_URL
database_url="$ENVIRONMENT_VALUE"
database_authority="${database_url#jdbc:postgresql://}"
[[ "$database_authority" != "$database_url" ]]
database_authority="${database_authority%%/*}"
database_host="${database_authority%%:*}"
database_port="5432"
if [[ "$database_authority" == *:* ]]; then
  database_port="${database_authority##*:}"
fi

environment_value SPRING_DATA_REDIS_HOST
redis_host="$ENVIRONMENT_VALUE"
redis_port="6379"
if environment_value SPRING_DATA_REDIS_PORT; then
  redis_port="$ENVIRONMENT_VALUE"
fi

environment_value SPRING_RABBITMQ_ADDRESSES
rabbit_addresses="$ENVIRONMENT_VALUE"
rabbit_address="${rabbit_addresses%%,*}"
rabbit_authority="${rabbit_address#*://}"
rabbit_authority="${rabbit_authority##*@}"
rabbit_authority="${rabbit_authority%%/*}"
rabbit_host="${rabbit_authority%%:*}"
rabbit_port="5671"
if [[ "$rabbit_authority" == *:* ]]; then
  rabbit_port="${rabbit_authority##*:}"
fi

[[ -n "$database_host" && "$database_port" =~ ^[0-9]+$ ]]
[[ -n "$redis_host" && "$redis_port" =~ ^[0-9]+$ ]]
[[ -n "$rabbit_host" && "$rabbit_port" =~ ^[0-9]+$ ]]

tcp_check() {
  local host="$1"
  local port="$2"
  HOST="$host" PORT="$port" timeout 5 \
    bash -c 'exec 4<>"/dev/tcp/${HOST}/${PORT}"; exec 4>&-'
}

tcp_check "$database_host" "$database_port"
tcp_check "$redis_host" "$redis_port"
tcp_check "$rabbit_host" "$rabbit_port"

unset \
  ENVIRONMENT_VALUE \
  api_environment \
  database_url \
  database_authority \
  database_host \
  database_port \
  redis_host \
  redis_port \
  rabbit_addresses \
  rabbit_address \
  rabbit_authority \
  rabbit_host \
  rabbit_port

printf '%s\n' BUILDGRAPH_PRIVATE_AZ_VALIDATION_OK >&3
BUILDGRAPH_SAFE_VALIDATION
SSM_COMMAND

  jq -n \
    --arg command "$safe_validation_command" \
    '{
      commands: [$command],
      executionTimeout: ["1800"]
    }' >"$SSM_VALIDATION_PARAMETERS_FILE"
  unset safe_validation_command
}

run_safe_ssm_validation() {
  local instance_id="$1"
  local command_id attempt command_status

  command_id="$(
    aws_cli ssm send-command \
      --instance-ids "$instance_id" \
      --document-name AWS-RunShellScript \
      --comment "BuildGraph private AZ validation $INVOCATION_ID" \
      --timeout-seconds 1800 \
      --parameters "file://$SSM_VALIDATION_PARAMETERS_FILE" \
      --query 'Command.CommandId' \
      --output text
  )"
  [[ -n "$command_id" && "$command_id" != "None" ]] ||
    die "safe SSM validation did not return a command ID"

  for ((attempt = 1; attempt <= VALIDATION_MAX_ATTEMPTS; attempt++)); do
    if ! command_status="$(
      aws_cli ssm get-command-invocation \
        --command-id "$command_id" \
        --instance-id "$instance_id" \
        --query Status \
        --output text 2>/dev/null
    )"
    then
      command_status="Pending"
    fi
    case "$command_status" in
      Success)
        return 0
        ;;
      Pending|InProgress|Delayed)
        if [[ "$attempt" -lt "$VALIDATION_MAX_ATTEMPTS" ]]; then
          sleep "$VALIDATION_POLL_SECONDS"
        fi
        ;;
      *)
        die "safe SSM validation entered terminal status: $command_status"
        ;;
    esac
  done

  die "safe SSM validation did not finish within the configured attempts"
}

if [[ "$VALIDATE_PRIVATE_AZS" == "true" ]]; then
  prepare_safe_ssm_validation_parameters
fi

validate_disposable_instance() {
  local subnet_id="$1"
  local expected_az="$2"
  local instance_id instance_state_file attempt ping_status
  local client_token network_interfaces_file

  client_token="$INVOCATION_ID-$expected_az"
  network_interfaces_file="$TEMP_DIR/validation-network-$expected_az.json"
  jq \
    --arg subnet "$subnet_id" \
    '[.NetworkInterfaces[0] + {SubnetId: $subnet}]' \
    "$LAUNCH_TEMPLATE_OVERRIDE_FILE" >"$network_interfaces_file"
  chmod 0600 "$network_interfaces_file"
  jq -e \
    --arg subnet "$subnet_id" \
    'length == 1
     and .[0].DeviceIndex == 0
     and .[0].DeleteOnTermination == true
     and .[0].AssociatePublicIpAddress == false
     and (.[0].Groups | type == "array" and length > 0)
     and .[0].SubnetId == $subnet' \
    "$network_interfaces_file" >/dev/null ||
    die "private AZ validation network interface override is invalid"

  VALIDATION_CLIENT_TOKENS+=("$client_token")

  instance_id="$(
    aws_cli ec2 run-instances \
      --launch-template \
        "LaunchTemplateId=$LAUNCH_TEMPLATE_ID,Version=$CANDIDATE_LAUNCH_TEMPLATE_VERSION" \
      --network-interfaces "file://$network_interfaces_file" \
      --count 1 \
      --client-token "$client_token" \
      --tag-specifications \
        "ResourceType=instance,Tags=[{Key=Name,Value=buildgraph-private-az-validation},{Key=BuildGraphInvocation,Value=$INVOCATION_ID},{Key=BuildGraphValidationToken,Value=$client_token},{Key=BuildGraphPurpose,Value=private-app-az-validation}]" \
      --query 'Instances[0].InstanceId' \
      --output text
  )"
  [[ "$instance_id" =~ ^i-[A-Za-z0-9-]+$ ]] ||
    die "private AZ validation returned an invalid instance ID"
  append_validation_instance_id "$instance_id"

  aws_cli ec2 wait instance-running \
    --instance-ids "$instance_id"
  aws_cli ec2 wait instance-status-ok \
    --instance-ids "$instance_id"

  instance_state_file="$TEMP_DIR/validation-$instance_id.json"
  aws_cli ec2 describe-instances \
    --instance-ids "$instance_id" \
    --output json >"$instance_state_file"
  jq -e \
    --arg instance "$instance_id" \
    --arg subnet "$subnet_id" \
    --arg az "$expected_az" \
    '[.Reservations[].Instances[]]
     | length == 1
       and .[0].InstanceId == $instance
       and .[0].State.Name == "running"
       and .[0].SubnetId == $subnet
       and .[0].Placement.AvailabilityZone == $az
       and .[0].PublicIpAddress == null' \
    "$instance_state_file" >/dev/null ||
    die "private AZ validation instance has an unexpected network identity"

  ping_status=""
  for ((attempt = 1; attempt <= VALIDATION_MAX_ATTEMPTS; attempt++)); do
    ping_status="$(
      aws_cli ssm describe-instance-information \
        --filters "Key=InstanceIds,Values=$instance_id" \
        --query 'InstanceInformationList[0].PingStatus' \
        --output text
    )"
    [[ "$ping_status" != "Online" ]] || break
    if [[ "$attempt" -lt "$VALIDATION_MAX_ATTEMPTS" ]]; then
      sleep "$VALIDATION_POLL_SECONDS"
    fi
  done

  [[ "$ping_status" == "Online" ]] ||
    die "private AZ validation instance did not become SSM Online"
  run_safe_ssm_validation "$instance_id"
}

if [[ "$VALIDATE_PRIVATE_AZS" == "true" ]]; then
  CURRENT_STEP="validate-private-az-2a"
  validate_disposable_instance \
    "$PRIVATE_SUBNET_ID_2A" \
    "$PRIVATE_SUBNET_AZ_2A"
  CURRENT_STEP="validate-private-az-2b"
  validate_disposable_instance \
    "$PRIVATE_SUBNET_ID_2B" \
    "$PRIVATE_SUBNET_AZ_2B"

  CURRENT_STEP="terminate-private-az-validation-instances"
  terminate_owned_validation_instances ||
    die "failed to terminate every invocation-owned validation instance"
  log "private AZ validation passed; ASG and CloudFront were not changed"
  exit 0
fi

CURRENT_STEP="update-asg-private-network"
ASG_MUTATION_INTENT=true
if ! aws_cli autoscaling update-auto-scaling-group \
  --auto-scaling-group-name "$ASG_NAME" \
  --vpc-zone-identifier "$PRIVATE_SUBNET_CSV" \
  --launch-template \
    "LaunchTemplateId=$LAUNCH_TEMPLATE_ID,Version=$CANDIDATE_LAUNCH_TEMPLATE_VERSION"
then
  die "ASG update failed or its response was lost"
fi
ASG_MUTATED=true

CURRENT_STEP="start-private-instance-refresh"
FORWARD_REFRESH_INTENT=true
FORWARD_REFRESH_ID="$(
  aws_cli autoscaling start-instance-refresh \
    --auto-scaling-group-name "$ASG_NAME" \
    --strategy Rolling \
    --preferences "file://$REFRESH_PREFERENCES_FILE" \
    --query InstanceRefreshId \
    --output text
)"
[[ -n "$FORWARD_REFRESH_ID" && "$FORWARD_REFRESH_ID" != "None" ]] ||
  die "instance refresh did not return an ID"

wait_for_instance_refresh() {
  local refresh_id="$1"
  local attempt status

  for ((attempt = 1; attempt <= REFRESH_MAX_ATTEMPTS; attempt++)); do
    status="$(
      aws_cli autoscaling describe-instance-refreshes \
        --auto-scaling-group-name "$ASG_NAME" \
        --instance-refresh-ids "$refresh_id" \
        --query 'InstanceRefreshes[0].Status' \
        --output text
    )"
    case "$status" in
      Successful)
        return 0
        ;;
      Pending|InProgress)
        if [[ "$attempt" -lt "$REFRESH_MAX_ATTEMPTS" ]]; then
          sleep "$REFRESH_POLL_SECONDS"
        fi
        ;;
      *)
        die "instance refresh entered terminal status: $status"
        ;;
    esac
  done

  die "instance refresh did not finish within the configured attempts"
}

CURRENT_STEP="wait-for-private-instance-refresh"
wait_for_instance_refresh "$FORWARD_REFRESH_ID"

CURRENT_STEP="validate-private-asg-instance"
aws_cli autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names "$ASG_NAME" \
  --output json >"$POST_ASG_STATE_FILE"

jq -e \
  --arg subnet_2a "$PRIVATE_SUBNET_ID_2A" \
  --arg subnet_2b "$PRIVATE_SUBNET_ID_2B" \
  --arg version "$CANDIDATE_LAUNCH_TEMPLATE_VERSION" \
  '(.AutoScalingGroups | length) == 1
   and (
     (.AutoScalingGroups[0].VPCZoneIdentifier | split(",") | sort) ==
     ([$subnet_2a, $subnet_2b] | sort)
   )
   and .AutoScalingGroups[0].LaunchTemplate.Version == $version
   and (.AutoScalingGroups[0].Instances | length) == 1
   and .AutoScalingGroups[0].Instances[0].LifecycleState == "InService"
   and .AutoScalingGroups[0].Instances[0].HealthStatus == "Healthy"' \
  "$POST_ASG_STATE_FILE" >/dev/null ||
  die "post-refresh ASG contract is not private and InService"

readonly PRIVATE_INSTANCE_ID="$(
  jq -r '.AutoScalingGroups[0].Instances[0].InstanceId' \
    "$POST_ASG_STATE_FILE"
)"
[[ "$PRIVATE_INSTANCE_ID" =~ ^i-[A-Za-z0-9-]+$ ]] ||
  die "post-refresh ASG instance ID is invalid"

aws_cli ec2 describe-instances \
  --instance-ids "$PRIVATE_INSTANCE_ID" \
  --output json >"$POST_INSTANCE_STATE_FILE"
jq -e \
  --arg instance "$PRIVATE_INSTANCE_ID" \
  --arg subnet_2a "$PRIVATE_SUBNET_ID_2A" \
  --arg subnet_2b "$PRIVATE_SUBNET_ID_2B" \
  '[.Reservations[].Instances[]]
   | length == 1
     and .[0].InstanceId == $instance
     and .[0].State.Name == "running"
     and (
       .[0].SubnetId == $subnet_2a
       or .[0].SubnetId == $subnet_2b
     )
     and .[0].PublicIpAddress == null' \
  "$POST_INSTANCE_STATE_FILE" >/dev/null ||
  die "InService ASG instance must have no public IPv4 address"

CURRENT_STEP="validate-target-health"
for target_group_arn in "${TARGET_GROUP_ARNS[@]}"; do
  target_health="$(
    aws_cli elbv2 describe-target-health \
      --target-group-arn "$target_group_arn" \
      --targets "Id=$PRIVATE_INSTANCE_ID" \
      --query 'TargetHealthDescriptions[0].TargetHealth.State' \
      --output text
  )"
  [[ "$target_health" == "healthy" ]] ||
    die "InService ASG instance is not healthy in Target Group"
done

ASG_MUTATED=false
ASG_MUTATION_INTENT=false
FORWARD_REFRESH_INTENT=false
trap - ERR INT TERM
log "migration completed: private ASG instance is InService, has no public IPv4, and is Target Group healthy"
log "CloudFront remains unchanged on the operator-confirmed manual Green origin; return it to the ALB only through the documented manual gate"
