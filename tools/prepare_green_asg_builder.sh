#!/usr/bin/env bash
set -Eeuo pipefail

umask 022

readonly TARGET_GIT_SHA="${1:-}"
readonly AWS_REGION="${AWS_REGION:-ap-northeast-2}"
readonly AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-443915990705}"
readonly REPOSITORY_URL="${BUILDGRAPH_REPOSITORY_URL:-https://github.com/jungle-final-project/prototype.git}"
readonly APP_ROOT="${BUILDGRAPH_APP_ROOT:-/opt/buildgraph/prototype}"
readonly APP_USER="${BUILDGRAPH_APP_USER:-ubuntu}"
readonly FILE_OWNER="${BUILDGRAPH_FILE_OWNER:-ubuntu:ubuntu}"
readonly STATE_DIR="${BUILDGRAPH_BUILDER_STATE_DIR:-/var/lib/buildgraph}"
readonly SUCCESS_MARKER="$STATE_DIR/builder-prepared"
readonly OS_RELEASE_FILE="${BUILDGRAPH_OS_RELEASE_FILE:-/etc/os-release}"
readonly APT_SOURCES_LIST="${BUILDGRAPH_APT_SOURCES_LIST:-/etc/apt/sources.list}"
readonly UBUNTU_SOURCES="${BUILDGRAPH_UBUNTU_SOURCES:-/etc/apt/sources.list.d/ubuntu.sources}"
readonly GREEN_IMAGE_MANIFEST="${BUILDGRAPH_GREEN_IMAGE_MANIFEST:-/opt/buildgraph/green-images.env}"
readonly ASG_RUNTIME_ENV="${BUILDGRAPH_ASG_RUNTIME_ENV:-/opt/buildgraph/asg-runtime.env}"
readonly AWS_CREDENTIALS_ROOT="${BUILDGRAPH_AWS_CREDENTIALS_ROOT:-/root/.aws/credentials}"
readonly AWS_CREDENTIALS_APP="${BUILDGRAPH_AWS_CREDENTIALS_APP:-/home/ubuntu/.aws/credentials}"
readonly CLOUDWATCH_CONFIG="${BUILDGRAPH_CLOUDWATCH_CONFIG:-/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json}"
readonly ALLOW_NON_ROOT_FOR_TESTS="${BUILDGRAPH_ALLOW_NON_ROOT_FOR_TESTS:-false}"
readonly SKIP_INSTALL_FOR_TESTS="${BUILDGRAPH_BUILDER_SKIP_INSTALL_FOR_TESTS:-false}"

CURRENT_STEP="initialization"
TEMP_DIR=""

log() {
  printf '%s\n' "buildgraph ASG builder: $*"
}

die() {
  printf '%s\n' "buildgraph ASG builder rejected: $*" >&2
  return 1
}

cleanup() {
  if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
    rm -rf "$TEMP_DIR"
  fi
}

handle_error() {
  local status=$?
  trap - ERR
  rm -f "$SUCCESS_MARKER"
  printf '%s\n' \
    "buildgraph ASG builder failed: step=$CURRENT_STEP status=$status" >&2
  exit "$status"
}

trap cleanup EXIT
trap handle_error ERR

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is missing: $1"
}

assert_no_runtime_artifacts() {
  local artifact
  local artifacts=(
    "$APP_ROOT/.env.prod"
    "$GREEN_IMAGE_MANIFEST"
    "$ASG_RUNTIME_ENV"
    "$AWS_CREDENTIALS_ROOT"
    "$AWS_CREDENTIALS_APP"
  )

  for artifact in "${artifacts[@]}"; do
    [[ ! -e "$artifact" ]] ||
      die "runtime or credential artifact exists on the clean builder: $artifact"
  done
}

run_git() {
  if [[ "$ALLOW_NON_ROOT_FOR_TESTS" == "true" ]]; then
    git "$@"
  else
    runuser -u "$APP_USER" -- git "$@"
  fi
}

configure_ubuntu_https_sources() {
  local source_file candidate
  local source_index=0
  local source_files=(
    "$APT_SOURCES_LIST"
    "$UBUNTU_SOURCES"
  )

  for source_file in "${source_files[@]}"; do
    [[ -f "$source_file" ]] || continue
    source_index=$((source_index + 1))
    candidate="$TEMP_DIR/apt-source-$source_index"
    sed -E \
      's#http://([A-Za-z0-9.-]*ubuntu\.com)#https://\1#g' \
      "$source_file" >"$candidate"
    install -m 0644 "$candidate" "$source_file"
  done
}

install_builder_dependencies() {
  local docker_arch ubuntu_codename aws_install_mode

  configure_ubuntu_https_sources
  DEBIAN_FRONTEND=noninteractive apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    ca-certificates \
    curl \
    git \
    jq \
    unzip
  for command_name in curl git jq unzip; do
    require_command "$command_name"
  done

  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  docker_arch="$(dpkg --print-architecture)"
  ubuntu_codename="$(. "$OS_RELEASE_FILE"; printf '%s' "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
  printf '%s\n' \
    "deb [arch=$docker_arch signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $ubuntu_codename stable" |
    tee /etc/apt/sources.list.d/docker.list >/dev/null
  DEBIAN_FRONTEND=noninteractive apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin
  systemctl enable --now docker
  usermod -aG docker "$APP_USER"

  curl -fsSL \
    https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip \
    -o "$TEMP_DIR/awscliv2.zip"
  unzip -q "$TEMP_DIR/awscliv2.zip" -d "$TEMP_DIR"
  aws_install_mode=""
  if command -v aws >/dev/null 2>&1; then
    aws_install_mode="--update"
  fi
  "$TEMP_DIR/aws/install" $aws_install_mode

  if ! dpkg-query -W -f='${Status}' amazon-cloudwatch-agent 2>/dev/null |
    grep -q 'install ok installed'; then
    curl -fsSL \
      https://amazoncloudwatch-agent.s3.amazonaws.com/ubuntu/amd64/latest/amazon-cloudwatch-agent.deb \
      -o "$TEMP_DIR/amazon-cloudwatch-agent.deb"
    dpkg -i "$TEMP_DIR/amazon-cloudwatch-agent.deb"
  fi

  install -m 0755 -d "$(dirname "$CLOUDWATCH_CONFIG")"
  tee "$TEMP_DIR/amazon-cloudwatch-agent.json" >/dev/null <<'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/lib/docker/containers/*/*.log",
            "log_group_name": "/buildgraph/demo/api-green/docker",
            "log_stream_name": "{instance_id}"
          }
        ]
      }
    }
  }
}
EOF
  jq -e . "$TEMP_DIR/amazon-cloudwatch-agent.json" >/dev/null
  install -m 0644 "$TEMP_DIR/amazon-cloudwatch-agent.json" "$CLOUDWATCH_CONFIG"
  systemctl disable --now amazon-cloudwatch-agent >/dev/null 2>&1 || true
}

verify_ssm_agent() {
  systemctl is-active --quiet snap.amazon-ssm-agent.amazon-ssm-agent.service && return 0
  systemctl is-active --quiet amazon-ssm-agent && return 0
  die "Amazon SSM Agent must be active before preparing the builder"
}

prepare_repository() {
  local remote_url actual_sha

  mkdir -p "$(dirname "$APP_ROOT")"
  if [[ "$ALLOW_NON_ROOT_FOR_TESTS" != "true" ]]; then
    chown "$FILE_OWNER" "$(dirname "$APP_ROOT")"
  fi

  if [[ ! -e "$APP_ROOT" ]]; then
    run_git clone --no-checkout --origin origin "$REPOSITORY_URL" "$APP_ROOT"
  elif [[ ! -d "$APP_ROOT/.git" ]]; then
    die "application path exists but is not a Git repository: $APP_ROOT"
  fi

  remote_url="$(run_git -C "$APP_ROOT" remote get-url origin)"
  [[ "$remote_url" == "$REPOSITORY_URL" ]] ||
    die "application repository origin does not match the approved repository"
  run_git -C "$APP_ROOT" fetch --quiet origin main
  run_git -C "$APP_ROOT" cat-file -e "$TARGET_GIT_SHA^{commit}"
  run_git -C "$APP_ROOT" merge-base --is-ancestor "$TARGET_GIT_SHA" origin/main
  run_git -C "$APP_ROOT" checkout --quiet --detach "$TARGET_GIT_SHA"

  actual_sha="$(run_git -C "$APP_ROOT" rev-parse HEAD)"
  [[ "$actual_sha" == "$TARGET_GIT_SHA" ]] || die "checked out Git SHA does not match"
  [[ -z "$(run_git -C "$APP_ROOT" status --porcelain)" ]] ||
    die "application repository is not clean"

  [[ -s "$APP_ROOT/compose.api.ecr.prod.yaml" ]] || die "ECR Compose file is missing"
  [[ -s "$APP_ROOT/infra/nginx/api.conf" ]] || die "Nginx API config is missing"
  [[ -s "$APP_ROOT/infra/nginx/nginx.conf" ]] || die "Nginx main config is missing"
  [[ -x "$APP_ROOT/tools/bootstrap_green_asg.sh" ]] || die "runtime bootstrap is missing or not executable"
}

[[ "$TARGET_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
  die "the target must be a 40-character lowercase Git SHA"
[[ -s "$OS_RELEASE_FILE" ]] || die "OS release metadata is missing"

if [[ "$(id -u)" -ne 0 && "$ALLOW_NON_ROOT_FOR_TESTS" != "true" ]]; then
  die "builder preparation must run as root"
fi
if [[ "$SKIP_INSTALL_FOR_TESTS" == "true" && "$ALLOW_NON_ROOT_FOR_TESTS" != "true" ]]; then
  die "dependency installation cannot be skipped outside tests"
fi

for command_name in date dirname id mkdir mktemp mv rm uname; do
  require_command "$command_name"
done
if [[ "$SKIP_INSTALL_FOR_TESTS" != "true" ]]; then
  for command_name in apt-get chmod chown dpkg dpkg-query grep install runuser sed systemctl tee usermod; do
    require_command "$command_name"
  done
fi

CURRENT_STEP="verify-platform"
os_id="$(. "$OS_RELEASE_FILE"; printf '%s' "${ID:-}")"
os_version="$(. "$OS_RELEASE_FILE"; printf '%s' "${VERSION_ID:-}")"
[[ "$os_id" == "ubuntu" && "$os_version" == "24.04" ]] ||
  die "builder must use Ubuntu 24.04"
[[ "$(uname -m)" == "x86_64" ]] || die "builder must use x86_64"

CURRENT_STEP="verify-clean-builder"
assert_no_runtime_artifacts

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/buildgraph-asg-builder.XXXXXX")"
mkdir -p "$STATE_DIR"
rm -f "$SUCCESS_MARKER"

CURRENT_STEP="install-dependencies"
if [[ "$SKIP_INSTALL_FOR_TESTS" != "true" ]]; then
  install_builder_dependencies
  verify_ssm_agent
fi

for command_name in aws curl docker git; do
  require_command "$command_name"
done

CURRENT_STEP="verify-instance-region"
imds_token="$(
  curl -fsS \
    -X PUT \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
    http://169.254.169.254/latest/api/token
)"
[[ -n "$imds_token" ]] || die "IMDSv2 token request returned an empty value"
instance_region="$(
  curl -fsS \
    -H "X-aws-ec2-metadata-token: $imds_token" \
    http://169.254.169.254/latest/meta-data/placement/region
)"
[[ "$instance_region" == "$AWS_REGION" ]] ||
  die "instance region does not match the approved region"

CURRENT_STEP="verify-account"
caller_account="$(
  aws sts get-caller-identity \
    --query Account \
    --output text \
    --region "$AWS_REGION" \
    --no-cli-pager
)"
[[ "$caller_account" == "$AWS_ACCOUNT_ID" ]] ||
  die "caller account does not match the approved account"

CURRENT_STEP="checkout-source"
prepare_repository

CURRENT_STEP="verify-empty-runtime"
assert_no_runtime_artifacts
[[ -z "$(docker ps -aq)" ]] || die "builder must not contain Docker containers"
[[ -z "$(docker image ls -q)" ]] || die "builder must not contain Docker images"
docker version >/dev/null
docker compose version >/dev/null

CURRENT_STEP="write-success-marker"
printf '%s\n' \
  "git_sha=$TARGET_GIT_SHA" \
  "prepared_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$TEMP_DIR/builder-prepared"
chmod 0644 "$TEMP_DIR/builder-prepared"
if [[ "$ALLOW_NON_ROOT_FOR_TESTS" != "true" ]]; then
  chown root:root "$TEMP_DIR/builder-prepared"
fi
mv -f "$TEMP_DIR/builder-prepared" "$SUCCESS_MARKER"

trap - ERR
log "completed successfully"
