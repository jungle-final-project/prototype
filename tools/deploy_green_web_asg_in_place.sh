#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly APPROVED_AWS_ACCOUNT_ID="443915990705"
readonly APPROVED_AWS_REGION="ap-northeast-2"
readonly ECR_REGISTRY="${APPROVED_AWS_ACCOUNT_ID}.dkr.ecr.${APPROVED_AWS_REGION}.amazonaws.com"
readonly API_REPOSITORY="buildgraph-demo-api-green"
readonly XGB_REPOSITORY="buildgraph-demo-xgb-reranker-green"
readonly ASG_NAME="buildgraph-demo-api-green-asg"
readonly LAUNCH_TEMPLATE_NAME="buildgraph-demo-api-green-lt"
readonly TARGET_GROUP_ARN="arn:aws:elasticloadbalancing:ap-northeast-2:443915990705:targetgroup/buildgraph-demo-api-green-tg/f905e7669645a411"
readonly APP_ROOT="/opt/buildgraph/prototype"
readonly APP_USER="ubuntu"
readonly RELEASE_MANIFEST_MARKER="BUILDGRAPH_RELEASE_MANIFEST_B64"
readonly RELEASE_GIT_SHA_MARKER="BUILDGRAPH_RELEASE_GIT_SHA"

readonly AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$APPROVED_AWS_ACCOUNT_ID}"
readonly AWS_REGION="${AWS_REGION:-$APPROVED_AWS_REGION}"
readonly SSM_MAX_ATTEMPTS="${BUILDGRAPH_SSM_MAX_ATTEMPTS:-120}"
readonly SSM_POLL_SECONDS="${BUILDGRAPH_SSM_POLL_SECONDS:-5}"
readonly TARGET_HEALTH_MAX_ATTEMPTS="${BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS:-30}"
readonly TARGET_HEALTH_POLL_SECONDS="${BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS:-5}"

SERVICE=""
GIT_SHA=""
IMAGE_TAG=""
APPLY=false
TEMP_DIR=""
SOURCE_LT_ID=""
SOURCE_LT_VERSION=""
SOURCE_GIT_SHA=""
INSTANCE_ID=""
NEW_LT_VERSION=""
DEPLOYMENT_ID=""
CURRENT_COMMAND_ID=""
CURRENT_COMMAND_ACTION=""
REMOTE_PREPARED=false
ASG_UPDATE_INTENT=false
COMPENSATING=false
DEPLOYMENT_COMMITTED=false

log() {
  printf '%s\n' "buildgraph Green ASG Fast Deploy: $*"
}

die() {
  printf '%s\n' "buildgraph Green ASG Fast Deploy rejected: $*" >&2
  return 1
}

usage() {
  cat <<'USAGE' >&2
Usage:
  tools/deploy_green_web_asg_in_place.sh \
    --service api|xgb-reranker \
    --git-sha <exact-current-main-40-char-sha> \
    --image-tag <same-40-char-sha> \
    [--apply]

The default mode is read-only. --apply updates the single existing Green ASG
instance through SSM, verifies health, and advances only the ASG's pinned
numeric Launch Template version. It never creates or replaces an EC2 instance
and never changes CloudFront.
USAGE
}

cleanup() {
  [[ -z "$TEMP_DIR" || ! -d "$TEMP_DIR" ]] || rm -rf "$TEMP_DIR"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command is missing: $1"
}

require_positive_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || die "$name must be a positive integer"
}

require_non_negative_integer() {
  local name="$1"
  local value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name must be a non-negative integer"
}

aws_cli() {
  AWS_PAGER="" aws "$@" --region "$AWS_REGION" --no-cli-pager
}

read_manifest_value() {
  local key="$1"
  local file="$2"
  local count value

  count="$(grep -c "^${key}=" "$file" || true)"
  [[ "$count" -eq 1 ]] || die "release manifest must contain exactly one $key"
  value="$(sed -n "s/^${key}=//p" "$file")"
  [[ -n "$value" ]] || die "release manifest value is empty: $key"
  printf '%s' "$value"
}

validate_ecr_digest_image() {
  local image_uri="$1"
  local repository="$2"
  local prefix="${ECR_REGISTRY}/${repository}@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "release image must use the approved digest repository: $repository"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] ||
    die "release image digest is invalid: $repository"
}

validate_nginx_digest_image() {
  local image_uri="$1"
  local prefix="docker.io/library/nginx@sha256:"
  local digest

  [[ "$image_uri" == "$prefix"* ]] ||
    die "Nginx release image must use the approved official digest"
  digest="${image_uri#"$prefix"}"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || die "Nginx release digest is invalid"
}

canonicalize_manifest() {
  local source_file="$1"
  local destination_file="$2"
  local line key count=0
  local api_image xgb_image nginx_image scheduling

  [[ -s "$source_file" ]] || die "release manifest is empty"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -n "$line" ]] || continue
    [[ "$line" != \#* ]] || continue
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.+)$ ]] ||
      die "release manifest contains an invalid line"
    key="${BASH_REMATCH[1]}"
    case "$key" in
      API_IMAGE_URI|XGB_IMAGE_URI|NGINX_IMAGE_URI|BUILDGRAPH_SCHEDULING_ENABLED)
        ;;
      *)
        die "release manifest contains an unexpected key: $key"
        ;;
    esac
    count=$((count + 1))
  done <"$source_file"
  [[ "$count" -eq 4 ]] || die "release manifest must contain four values"

  api_image="$(read_manifest_value API_IMAGE_URI "$source_file")"
  xgb_image="$(read_manifest_value XGB_IMAGE_URI "$source_file")"
  nginx_image="$(read_manifest_value NGINX_IMAGE_URI "$source_file")"
  scheduling="$(read_manifest_value BUILDGRAPH_SCHEDULING_ENABLED "$source_file")"
  validate_ecr_digest_image "$api_image" "$API_REPOSITORY"
  validate_ecr_digest_image "$xgb_image" "$XGB_REPOSITORY"
  validate_nginx_digest_image "$nginx_image"
  [[ "$scheduling" == "false" ]] ||
    die "ASG web release must keep BUILDGRAPH_SCHEDULING_ENABLED=false"

  printf '%s\n' \
    "API_IMAGE_URI=$api_image" \
    "XGB_IMAGE_URI=$xgb_image" \
    "NGINX_IMAGE_URI=$nginx_image" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" >"$destination_file"
}

load_source_release() {
  local source_user_data_base64="$1"
  local decoded_user_data="$TEMP_DIR/source-user-data.sh"
  local embedded_manifest="$TEMP_DIR/source-release-embedded.env"
  local marker_count sha_count marker_value

  [[ -n "$source_user_data_base64" && "$source_user_data_base64" != "null" ]] ||
    die "source Launch Template has no UserData"
  printf '%s' "$source_user_data_base64" |
    base64 --decode >"$decoded_user_data" 2>/dev/null ||
    die "source Launch Template UserData is not valid base64"

  marker_count="$(grep -c "^# ${RELEASE_MANIFEST_MARKER}=" "$decoded_user_data" || true)"
  sha_count="$(grep -c "^# ${RELEASE_GIT_SHA_MARKER}=" "$decoded_user_data" || true)"
  [[ "$marker_count" -eq 1 && "$sha_count" -eq 1 ]] ||
    die "source Launch Template must contain one release manifest and Git SHA marker; run Release Green Web ASG once"

  marker_value="$(sed -n "s/^# ${RELEASE_MANIFEST_MARKER}=//p" "$decoded_user_data")"
  SOURCE_GIT_SHA="$(sed -n "s/^# ${RELEASE_GIT_SHA_MARKER}=//p" "$decoded_user_data")"
  [[ "$SOURCE_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
    die "source Launch Template Git SHA marker is invalid"
  printf '%s' "$marker_value" |
    base64 --decode >"$embedded_manifest" 2>/dev/null ||
    die "source Launch Template manifest marker is invalid"
  canonicalize_manifest "$embedded_manifest" "$SOURCE_MANIFEST_FILE"
}

replace_service_image() {
  local immutable_image="$1"
  local api_image xgb_image nginx_image

  api_image="$(read_manifest_value API_IMAGE_URI "$SOURCE_MANIFEST_FILE")"
  xgb_image="$(read_manifest_value XGB_IMAGE_URI "$SOURCE_MANIFEST_FILE")"
  nginx_image="$(read_manifest_value NGINX_IMAGE_URI "$SOURCE_MANIFEST_FILE")"
  case "$SERVICE" in
    api) api_image="$immutable_image" ;;
    xgb-reranker) xgb_image="$immutable_image" ;;
  esac
  printf '%s\n' \
    "API_IMAGE_URI=$api_image" \
    "XGB_IMAGE_URI=$xgb_image" \
    "NGINX_IMAGE_URI=$nginx_image" \
    "BUILDGRAPH_SCHEDULING_ENABLED=false" >"$TARGET_MANIFEST_FILE.raw"
  canonicalize_manifest "$TARGET_MANIFEST_FILE.raw" "$TARGET_MANIFEST_FILE"
}

render_user_data() {
  local manifest_base64
  manifest_base64="$(base64 <"$TARGET_MANIFEST_FILE" | tr -d '\n')"
  [[ -n "$manifest_base64" ]] || die "failed to encode target release manifest"

  cat >"$USER_DATA_FILE" <<EOF
#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

# ${RELEASE_GIT_SHA_MARKER}=${GIT_SHA}
# ${RELEASE_MANIFEST_MARKER}=${manifest_base64}
readonly AWS_ACCOUNT_ID="${APPROVED_AWS_ACCOUNT_ID}"
readonly AWS_REGION="${APPROVED_AWS_REGION}"
readonly AWS_STS_REGIONAL_ENDPOINTS="regional"
readonly APP_ROOT="${APP_ROOT}"
readonly APP_USER="${APP_USER}"
readonly RELEASE_GIT_SHA="${GIT_SHA}"
readonly RELEASE_MANIFEST="/opt/buildgraph/releases/green-release-${GIT_SHA}.env"

export AWS_ACCOUNT_ID AWS_REGION AWS_STS_REGIONAL_ENDPOINTS
export BUILDGRAPH_RELEASE_MANIFEST="\${RELEASE_MANIFEST}"

[[ -d "\${APP_ROOT}/.git" ]]
runuser -u "\${APP_USER}" -- git -C "\${APP_ROOT}" fetch --no-tags origin \
  "+refs/heads/main:refs/remotes/origin/main"
runuser -u "\${APP_USER}" -- git -C "\${APP_ROOT}" cat-file -e \
  "\${RELEASE_GIT_SHA}^{commit}"
runuser -u "\${APP_USER}" -- git -C "\${APP_ROOT}" merge-base --is-ancestor \
  "\${RELEASE_GIT_SHA}" origin/main
runuser -u "\${APP_USER}" -- git -C "\${APP_ROOT}" checkout --detach \
  "\${RELEASE_GIT_SHA}"

install -d -m 0700 /opt/buildgraph/releases
cat >"\${RELEASE_MANIFEST}" <<'BUILDGRAPH_RELEASE_MANIFEST'
$(cat "$TARGET_MANIFEST_FILE")
BUILDGRAPH_RELEASE_MANIFEST
chmod 600 "\${RELEASE_MANIFEST}"

"\${APP_ROOT}/tools/bootstrap_green_asg.sh"
EOF
  [[ "$(grep -c '/tools/bootstrap_green_asg.sh' "$USER_DATA_FILE")" -eq 1 ]] ||
    die "rendered UserData must invoke bootstrap exactly once"
  if grep -Eq \
    'secretsmanager[[:space:]]+get-secret-value|ssm[[:space:]]+(send-command|get-command-invocation)' \
    "$USER_DATA_FILE"; then
    die "rendered UserData must not contain Secret or SSM deployment commands"
  fi
}

describe_asg() {
  aws_cli autoscaling describe-auto-scaling-groups \
    --auto-scaling-group-names "$ASG_NAME" \
    --output json
}

reject_active_instance_refresh() {
  local status
  status="$(
    aws_cli autoscaling describe-instance-refreshes \
      --auto-scaling-group-name "$ASG_NAME" \
      --max-records 20 \
      --query 'InstanceRefreshes[?contains(`["Pending","InProgress","Baking","Cancelling","RollbackInProgress"]`, Status)] | [0].Status' \
      --output text
  )"
  case "$status" in
    ""|None|null|Successful|Failed|Cancelled|RollbackFailed|RollbackSuccessful)
      ;;
    Pending|InProgress|Baking|Cancelling|RollbackInProgress)
      die "an active Instance Refresh already exists with status: $status"
      ;;
    *)
      die "unable to prove that no active Instance Refresh exists: $status"
      ;;
  esac
}

validate_asg_contract() {
  local asg_file="$1"
  local expected_pointer="$2"
  local expected_instance="$3"
  local discovered_instance

  [[ "$(jq -r '.AutoScalingGroups | length' "$asg_file")" == "1" ]] ||
    die "expected exactly one approved Auto Scaling Group"
  [[ "$(jq -r '.AutoScalingGroups[0].MinSize' "$asg_file")" == "1" &&
    "$(jq -r '.AutoScalingGroups[0].DesiredCapacity' "$asg_file")" == "1" &&
    "$(jq -r '.AutoScalingGroups[0].MaxSize' "$asg_file")" == "1" ]] ||
    die "Green web ASG must remain Min 1 / Desired 1 / Max 1 for Fast Deploy"
  [[ "$(jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateName // empty' "$asg_file")" == "$LAUNCH_TEMPLATE_NAME" ]] ||
    die "Auto Scaling Group Launch Template name does not match"
  [[ "$(jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' "$asg_file")" == "$expected_pointer" ]] ||
    die "Auto Scaling Group Launch Template pointer drifted"
  [[ "$(jq -r '.AutoScalingGroups[0].TargetGroupARNs | length' "$asg_file")" == "1" &&
    "$(jq -r '.AutoScalingGroups[0].TargetGroupARNs[0]' "$asg_file")" == "$TARGET_GROUP_ARN" ]] ||
    die "Auto Scaling Group Target Group does not match"
  jq -e '
    .AutoScalingGroups[0].Instances as $instances
    | ($instances | length) == 1
    and $instances[0].LifecycleState == "InService"
    and $instances[0].HealthStatus == "Healthy"
  ' "$asg_file" >/dev/null ||
    die "Fast Deploy requires exactly one InService and Healthy instance"
  discovered_instance="$(jq -r '.AutoScalingGroups[0].Instances[0].InstanceId' "$asg_file")"
  [[ "$discovered_instance" =~ ^i-[0-9a-f]{8,17}$ ]] ||
    die "discovered ASG instance ID is invalid"
  [[ -z "$expected_instance" || "$discovered_instance" == "$expected_instance" ]] ||
    die "ASG instance changed during Fast Deploy"
  printf '%s' "$discovered_instance"
}

verify_target_healthy() {
  local expected_instance="$1"
  local attempt target_json

  for ((attempt = 1; attempt <= TARGET_HEALTH_MAX_ATTEMPTS; attempt++)); do
    if target_json="$(
      aws_cli elbv2 describe-target-health \
        --target-group-arn "$TARGET_GROUP_ARN" \
        --output json
    )" && jq -e --arg id "$expected_instance" '
      .TargetHealthDescriptions as $targets
      | ($targets | length) == 1
      and $targets[0].Target.Id == $id
      and $targets[0].Target.Port == 80
      and $targets[0].TargetHealth.State == "healthy"
    ' <<<"$target_json" >/dev/null; then
      return 0
    fi
    [[ "$attempt" -ge "$TARGET_HEALTH_MAX_ATTEMPTS" ]] ||
      sleep "$TARGET_HEALTH_POLL_SECONDS"
  done
  die "Target Group must contain the same single healthy ASG instance"
}

verify_ssm_online() {
  local expected_instance="$1"
  local ssm_json
  ssm_json="$(
    aws_cli ssm describe-instance-information \
      --filters "Key=InstanceIds,Values=$expected_instance" \
      --output json
  )"
  jq -e --arg id "$expected_instance" '
    .InstanceInformationList as $nodes
    | ($nodes | length) == 1
    and $nodes[0].InstanceId == $id
    and $nodes[0].PingStatus == "Online"
  ' <<<"$ssm_json" >/dev/null ||
    die "the discovered ASG instance must be SSM Online"
}

validate_candidate_launch_template() {
  local expected_user_data="$1"
  local source_without_user_data candidate_without_user_data candidate_user_data

  [[ "$(jq -r '.LaunchTemplateVersions | length' "$CANDIDATE_LT_JSON_FILE")" == "1" ]] ||
    die "candidate Launch Template version was not found"
  [[ "$(jq -r '.LaunchTemplateVersions[0].VersionNumber | tostring' "$CANDIDATE_LT_JSON_FILE")" == "$NEW_LT_VERSION" ]] ||
    die "candidate Launch Template version does not match"
  source_without_user_data="$(jq -S -c '.LaunchTemplateVersions[0].LaunchTemplateData | del(.UserData)' "$SOURCE_LT_JSON_FILE")"
  candidate_without_user_data="$(jq -S -c '.LaunchTemplateVersions[0].LaunchTemplateData | del(.UserData)' "$CANDIDATE_LT_JSON_FILE")"
  [[ "$source_without_user_data" == "$candidate_without_user_data" ]] ||
    die "candidate Launch Template changed a field other than UserData"
  candidate_user_data="$(jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.UserData // empty' "$CANDIDATE_LT_JSON_FILE")"
  [[ "$candidate_user_data" == "$expected_user_data" ]] ||
    die "candidate Launch Template UserData does not match"
}

write_ssm_parameters() {
  local action="$1"
  local destination="$2"
  local source_manifest_b64 target_manifest_b64 remote_command

  source_manifest_b64="$(base64 <"$SOURCE_MANIFEST_FILE" | tr -d '\n')"
  target_manifest_b64="$(base64 <"$TARGET_MANIFEST_FILE" | tr -d '\n')"
  if [[ "$action" == "prepare" ]]; then
    remote_command="$(cat <<EOF
/usr/bin/env bash <<'BUILDGRAPH_FAST_DEPLOY_REMOTE'
set -Eeuo pipefail
umask 077
readonly app_root='${APP_ROOT}'
readonly app_user='${APP_USER}'
readonly helper_sha='${GIT_SHA}'
readonly helper_path="\$(mktemp /tmp/buildgraph-fast-deploy.XXXXXX)"
cleanup_fast_helper() { rm -f "\$helper_path"; }
trap cleanup_fast_helper EXIT
runuser -u "\$app_user" -- git -C "\$app_root" fetch --no-tags origin '+refs/heads/main:refs/remotes/origin/main'
runuser -u "\$app_user" -- git -C "\$app_root" cat-file -e "\${helper_sha}^{commit}"
runuser -u "\$app_user" -- git -C "\$app_root" show "\${helper_sha}:tools/apply_green_asg_release_in_place.sh" >"\$helper_path"
chmod 700 "\$helper_path"
"\$helper_path" prepare --deployment-id '${DEPLOYMENT_ID}' --service '${SERVICE}' --source-git-sha '${SOURCE_GIT_SHA}' --target-git-sha '${GIT_SHA}' --source-manifest-b64 '${source_manifest_b64}' --target-manifest-b64 '${target_manifest_b64}'
BUILDGRAPH_FAST_DEPLOY_REMOTE
EOF
)"
  else
    remote_command="$(cat <<EOF
/usr/bin/env bash <<'BUILDGRAPH_FAST_DEPLOY_REMOTE'
set -Eeuo pipefail
umask 077
readonly app_root='${APP_ROOT}'
readonly app_user='${APP_USER}'
readonly helper_sha='${GIT_SHA}'
readonly helper_path="\$(mktemp /tmp/buildgraph-fast-deploy.XXXXXX)"
cleanup_fast_helper() { rm -f "\$helper_path"; }
trap cleanup_fast_helper EXIT
if [[ '${action}' == 'rollback' ]]; then
  readonly transaction_root='/var/lib/buildgraph/fast-deploy'
  readonly cancellation_fence="\$transaction_root/.cancelled-${DEPLOYMENT_ID}"
  readonly fence_candidate="\${cancellation_fence}.tmp.\$\$"
  install -d -m 0700 "\$transaction_root"
  printf '%s\\n' "cancelled_at=\$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"\$fence_candidate"
  chmod 600 "\$fence_candidate"
  mv -f "\$fence_candidate" "\$cancellation_fence"
fi
runuser -u "\$app_user" -- git -C "\$app_root" show "\${helper_sha}:tools/apply_green_asg_release_in_place.sh" >"\$helper_path"
chmod 700 "\$helper_path"
"\$helper_path" '${action}' --deployment-id '${DEPLOYMENT_ID}'
BUILDGRAPH_FAST_DEPLOY_REMOTE
EOF
)"
  fi
  jq -n --arg command "$remote_command" '{commands: [$command]}' >"$destination"
  chmod 600 "$destination"
}

send_remote_action() {
  local action="$1"
  local parameters_file="$TEMP_DIR/ssm-${action}.json"
  local command_id

  write_ssm_parameters "$action" "$parameters_file"
  command_id="$(
    aws_cli ssm send-command \
      --document-name AWS-RunShellScript \
      --instance-ids "$INSTANCE_ID" \
      --comment "fast-deploy-${action}-${DEPLOYMENT_ID}" \
      --parameters "file://${parameters_file}" \
      --timeout-seconds 600 \
      --max-concurrency 1 \
      --max-errors 0 \
      --query 'Command.CommandId' \
      --output text
  )"
  [[ "$command_id" =~ ^[A-Za-z0-9-]{8,64}$ ]] ||
    die "SSM $action returned an invalid command ID"
  CURRENT_COMMAND_ID="$command_id"
  printf '%s' "$command_id"
}

wait_for_ssm_command() {
  local command_id="$1"
  local action="$2"
  local attempt status

  for ((attempt = 1; attempt <= SSM_MAX_ATTEMPTS; attempt++)); do
    status="$(
      aws_cli ssm get-command-invocation \
        --command-id "$command_id" \
        --instance-id "$INSTANCE_ID" \
        --query Status \
        --output text 2>/dev/null || true
    )"
    case "$status" in
      Success)
        CURRENT_COMMAND_ID=""
        CURRENT_COMMAND_ACTION=""
        return 0
        ;;
      Failed|Cancelled|TimedOut|DeliveryTimedOut|ExecutionTimedOut|Undeliverable|Terminated)
        CURRENT_COMMAND_ID=""
        CURRENT_COMMAND_ACTION=""
        die "SSM $action reached terminal status: $status"
        return 1
        ;;
      Pending|InProgress|Delayed|Cancelling|"")
        ;;
      *)
        die "SSM $action returned an unexpected status: $status"
        return 1
        ;;
    esac
    [[ "$attempt" -ge "$SSM_MAX_ATTEMPTS" ]] || sleep "$SSM_POLL_SECONDS"
  done
  die "SSM $action did not finish within the approved wait window"
}

rollback_remote() {
  local rollback_id
  [[ "$REMOTE_PREPARED" == "true" ]] || return 0
  log "restoring the previous runtime on $INSTANCE_ID"
  rollback_id="$(send_remote_action rollback)" || return 1
  CURRENT_COMMAND_ID="$rollback_id"
  CURRENT_COMMAND_ACTION="rollback"
  wait_for_ssm_command "$rollback_id" rollback || return 1
  REMOTE_PREPARED=false
}

restore_asg_pointer() {
  local current_asg current_version
  [[ "$ASG_UPDATE_INTENT" == "true" ]] || return 0
  current_asg="$(describe_asg)" || return 1
  current_version="$(jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' <<<"$current_asg")"
  if [[ "$current_version" != "$SOURCE_LT_VERSION" ]]; then
    log "restoring the ASG Launch Template pointer to version $SOURCE_LT_VERSION"
    aws_cli autoscaling update-auto-scaling-group \
      --auto-scaling-group-name "$ASG_NAME" \
      --launch-template "LaunchTemplateId=$SOURCE_LT_ID,Version=$SOURCE_LT_VERSION" ||
      return 1
  fi
  ASG_UPDATE_INTENT=false
}

compensate() {
  local failed=0
  [[ "$COMPENSATING" != "true" ]] || return 0
  COMPENSATING=true
  if [[ "$DEPLOYMENT_COMMITTED" == "true" ]]; then
    log "deployment is committed; compensation is not required"
    return 0
  fi
  restore_asg_pointer || failed=1
  rollback_remote || failed=1
  [[ "$failed" -eq 0 ]] ||
    log "automatic compensation was incomplete; manual recovery is required"
}

cancel_current_command() {
  local command_id="$CURRENT_COMMAND_ID"
  local action="${CURRENT_COMMAND_ACTION:-command}"

  [[ -n "$command_id" ]] || return 0
  if aws_cli ssm cancel-command --command-id "$command_id" >/dev/null 2>&1; then
    log "requested cancellation for SSM $action"
  else
    log "warning: SSM $action cancellation request failed; rollback fencing will still be attempted"
  fi
  CURRENT_COMMAND_ID=""
  CURRENT_COMMAND_ACTION=""
}

handle_error() {
  local status=$?
  trap - ERR
  if [[ -n "$CURRENT_COMMAND_ID" ]]; then
    cancel_current_command
  fi
  compensate
  exit "$status"
}

handle_signal() {
  local signal_name="$1"
  local status=130
  [[ "$signal_name" != "TERM" ]] || status=143
  trap - ERR INT TERM
  if [[ -n "$CURRENT_COMMAND_ID" ]]; then
    cancel_current_command
  fi
  compensate
  exit "$status"
}

trap cleanup EXIT
trap handle_error ERR
trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --service)
      SERVICE="${2:-}"
      shift 2
      ;;
    --git-sha)
      GIT_SHA="${2:-}"
      shift 2
      ;;
    --image-tag)
      IMAGE_TAG="${2:-}"
      shift 2
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ "$SERVICE" == "api" || "$SERVICE" == "xgb-reranker" ]] ||
  die "--service must be api or xgb-reranker"
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
  die "--git-sha must be 40 lowercase hexadecimal characters"
[[ "$IMAGE_TAG" =~ ^[0-9a-f]{40}$ ]] ||
  die "--image-tag must be 40 lowercase hexadecimal characters"
[[ "$GIT_SHA" == "$IMAGE_TAG" ]] ||
  die "--git-sha and --image-tag must match"
require_positive_integer BUILDGRAPH_SSM_MAX_ATTEMPTS "$SSM_MAX_ATTEMPTS"
require_non_negative_integer BUILDGRAPH_SSM_POLL_SECONDS "$SSM_POLL_SECONDS"
require_positive_integer BUILDGRAPH_TARGET_HEALTH_MAX_ATTEMPTS "$TARGET_HEALTH_MAX_ATTEMPTS"
require_non_negative_integer BUILDGRAPH_TARGET_HEALTH_POLL_SECONDS "$TARGET_HEALTH_POLL_SECONDS"
for command_name in aws base64 git grep jq mktemp sed tr; do
  require_command "$command_name"
done

case "$SERVICE" in
  api) readonly ECR_REPOSITORY="$API_REPOSITORY" ;;
  xgb-reranker) readonly ECR_REPOSITORY="$XGB_REPOSITORY" ;;
esac

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buildgraph-fast-deploy.XXXXXX")"
readonly ASG_JSON_FILE="$TEMP_DIR/asg.json"
readonly POST_ASG_JSON_FILE="$TEMP_DIR/asg-post.json"
readonly SOURCE_LT_JSON_FILE="$TEMP_DIR/source-lt.json"
readonly CANDIDATE_LT_JSON_FILE="$TEMP_DIR/candidate-lt.json"
readonly SOURCE_MANIFEST_FILE="$TEMP_DIR/source-release.env"
readonly TARGET_MANIFEST_FILE="$TEMP_DIR/target-release.env"
readonly USER_DATA_FILE="$TEMP_DIR/user-data.sh"
readonly LT_OVERRIDE_FILE="$TEMP_DIR/launch-template-data.json"

readonly CALLER_ACCOUNT="$(aws_cli sts get-caller-identity --query Account --output text)"
[[ "$CALLER_ACCOUNT" == "$APPROVED_AWS_ACCOUNT_ID" ]] ||
  die "AWS caller account does not match the approved account"
git fetch --quiet --no-tags origin \
  "+refs/heads/main:refs/remotes/origin/main"
readonly MAIN_SHA="$(git rev-parse origin/main)"
[[ "$MAIN_SHA" == "$GIT_SHA" ]] ||
  die "Fast Deploy must use the exact current origin/main SHA: $MAIN_SHA"

readonly REPOSITORY_JSON="$(
  aws_cli ecr describe-repositories \
    --repository-names "$ECR_REPOSITORY" \
    --output json
)"
[[ "$(jq -r '.repositories | length' <<<"$REPOSITORY_JSON")" == "1" ]] ||
  die "approved ECR repository was not found"
[[ "$(jq -r '.repositories[0].imageTagMutability' <<<"$REPOSITORY_JSON")" == "IMMUTABLE" ]] ||
  die "approved ECR repository must use immutable tags"
readonly IMAGE_DIGEST="$(
  aws_cli ecr describe-images \
    --repository-name "$ECR_REPOSITORY" \
    --image-ids "imageTag=$IMAGE_TAG" \
    --query 'imageDetails[0].imageDigest' \
    --output text
)"
[[ "$IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  die "ECR tag did not resolve to one valid immutable image digest; publish it first"
readonly IMMUTABLE_IMAGE="${ECR_REGISTRY}/${ECR_REPOSITORY}@${IMAGE_DIGEST}"

describe_asg >"$ASG_JSON_FILE"
SOURCE_LT_ID="$(jq -r '.AutoScalingGroups[0].LaunchTemplate.LaunchTemplateId // empty' "$ASG_JSON_FILE")"
SOURCE_LT_VERSION="$(jq -r '.AutoScalingGroups[0].LaunchTemplate.Version // empty' "$ASG_JSON_FILE")"
[[ -n "$SOURCE_LT_ID" && "$SOURCE_LT_VERSION" =~ ^[0-9]+$ ]] ||
  die "source Launch Template must use an ID and numeric version"
INSTANCE_ID="$(validate_asg_contract "$ASG_JSON_FILE" "$SOURCE_LT_VERSION" "")"
reject_active_instance_refresh
verify_target_healthy "$INSTANCE_ID"
verify_ssm_online "$INSTANCE_ID"

aws_cli ec2 describe-launch-template-versions \
  --launch-template-id "$SOURCE_LT_ID" \
  --versions "$SOURCE_LT_VERSION" \
  --output json >"$SOURCE_LT_JSON_FILE"
[[ "$(jq -r '.LaunchTemplateVersions | length' "$SOURCE_LT_JSON_FILE")" == "1" ]] ||
  die "source Launch Template version was not found"
readonly SOURCE_AMI_ID="$(jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.ImageId // empty' "$SOURCE_LT_JSON_FILE")"
[[ -n "$SOURCE_AMI_ID" ]] || die "source Launch Template has no AMI"
readonly SOURCE_AMI_JSON="$(aws_cli ec2 describe-images --image-ids "$SOURCE_AMI_ID" --output json)"
[[ "$(jq -r '.Images | length' <<<"$SOURCE_AMI_JSON")" == "1" &&
  "$(jq -r '.Images[0].State' <<<"$SOURCE_AMI_JSON")" == "available" &&
  "$(jq -r '[.Images[0].Tags[]? | select(.Key == "Validation")][0].Value // empty' <<<"$SOURCE_AMI_JSON")" == "passed" ]] ||
  die "source AMI must be available and Validation=passed"
readonly SOURCE_USER_DATA_BASE64="$(jq -r '.LaunchTemplateVersions[0].LaunchTemplateData.UserData // empty' "$SOURCE_LT_JSON_FILE")"
load_source_release "$SOURCE_USER_DATA_BASE64"
git merge-base --is-ancestor "$SOURCE_GIT_SHA" "$GIT_SHA" ||
  die "target Git SHA must descend from the deployed source SHA"
while IFS= read -r changed_path; do
  case "$changed_path" in
    compose.api.ecr.prod.yaml|infra/nginx/*|tools/bootstrap_green_asg.sh|tools/prepare_green_asg_builder.sh|infra/asg/green-release.env)
      die "bootstrap or base release contract changed; run Release Green Web ASG instead"
      ;;
  esac
done < <(git diff --name-only "$SOURCE_GIT_SHA" "$GIT_SHA")

case "$SERVICE" in
  api)
    [[ "$(read_manifest_value API_IMAGE_URI "$SOURCE_MANIFEST_FILE")" != "$IMMUTABLE_IMAGE" ]] ||
      die "the requested API digest is already deployed"
    ;;
  xgb-reranker)
    [[ "$(read_manifest_value XGB_IMAGE_URI "$SOURCE_MANIFEST_FILE")" != "$IMMUTABLE_IMAGE" ]] ||
      die "the requested XGB digest is already deployed"
    ;;
esac

replace_service_image "$IMMUTABLE_IMAGE"
render_user_data
log "validated service=$SERVICE git_sha=$GIT_SHA instance=$INSTANCE_ID"
log "resolved immutable image=$IMMUTABLE_IMAGE"
log "source Launch Template=$SOURCE_LT_ID version=$SOURCE_LT_VERSION"

if [[ "$APPLY" != "true" ]]; then
  log "read-only validation complete; no AWS resources were changed"
  exit 0
fi

DEPLOYMENT_ID="fast-${GIT_SHA:0:12}-${GITHUB_RUN_ID:-0}-${GITHUB_RUN_ATTEMPT:-0}"
PREPARE_COMMAND_ID="$(send_remote_action prepare)"
readonly PREPARE_COMMAND_ID
CURRENT_COMMAND_ID="$PREPARE_COMMAND_ID"
CURRENT_COMMAND_ACTION="prepare"
REMOTE_PREPARED=true
wait_for_ssm_command "$PREPARE_COMMAND_ID" prepare
verify_target_healthy "$INSTANCE_ID"

describe_asg >"$POST_ASG_JSON_FILE"
validate_asg_contract "$POST_ASG_JSON_FILE" "$SOURCE_LT_VERSION" "$INSTANCE_ID" >/dev/null
reject_active_instance_refresh

readonly USER_DATA_BASE64="$(base64 <"$USER_DATA_FILE" | tr -d '\n')"
jq -n --arg user_data "$USER_DATA_BASE64" '{UserData: $user_data}' >"$LT_OVERRIDE_FILE"
NEW_LT_VERSION="$(
  aws_cli ec2 create-launch-template-version \
    --launch-template-id "$SOURCE_LT_ID" \
    --source-version "$SOURCE_LT_VERSION" \
    --version-description "fast-${SERVICE}-${GIT_SHA:0:12}" \
    --launch-template-data "file://${LT_OVERRIDE_FILE}" \
    --query 'LaunchTemplateVersion.VersionNumber' \
    --output text
)"
[[ "$NEW_LT_VERSION" =~ ^[0-9]+$ && "$NEW_LT_VERSION" != "$SOURCE_LT_VERSION" ]] ||
  die "new Launch Template version must be a different numeric version"
aws_cli ec2 describe-launch-template-versions \
  --launch-template-id "$SOURCE_LT_ID" \
  --versions "$NEW_LT_VERSION" \
  --output json >"$CANDIDATE_LT_JSON_FILE"
validate_candidate_launch_template "$USER_DATA_BASE64"

ASG_UPDATE_INTENT=true
aws_cli autoscaling update-auto-scaling-group \
  --auto-scaling-group-name "$ASG_NAME" \
  --launch-template "LaunchTemplateId=$SOURCE_LT_ID,Version=$NEW_LT_VERSION"
describe_asg >"$POST_ASG_JSON_FILE"
validate_asg_contract "$POST_ASG_JSON_FILE" "$NEW_LT_VERSION" "$INSTANCE_ID" >/dev/null
verify_target_healthy "$INSTANCE_ID"

COMMIT_COMMAND_ID="$(send_remote_action commit)"
readonly COMMIT_COMMAND_ID
CURRENT_COMMAND_ID="$COMMIT_COMMAND_ID"
CURRENT_COMMAND_ACTION="commit"
wait_for_ssm_command "$COMMIT_COMMAND_ID" commit
DEPLOYMENT_COMMITTED=true
ASG_UPDATE_INTENT=false
REMOTE_PREPARED=false

if cleanup_id="$(send_remote_action cleanup)"; then
  CURRENT_COMMAND_ID="$cleanup_id"
  CURRENT_COMMAND_ACTION="cleanup"
  if ! wait_for_ssm_command "$cleanup_id" cleanup; then
    log "warning: committed transaction cleanup must be retried"
  fi
  CURRENT_COMMAND_ID=""
  CURRENT_COMMAND_ACTION=""
else
  log "warning: committed transaction cleanup could not be started"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf '%s\n' \
    "instance_id=$INSTANCE_ID" \
    "launch_template_version=$NEW_LT_VERSION" >>"$GITHUB_OUTPUT"
fi
log "completed without EC2 replacement: instance=$INSTANCE_ID LT-version=$NEW_LT_VERSION"
