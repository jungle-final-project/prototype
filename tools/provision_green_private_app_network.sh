#!/usr/bin/env bash
set -Eeuo pipefail

umask 022

readonly APPROVED_AWS_ACCOUNT_ID="443915990705"
readonly APPROVED_AWS_REGION="ap-northeast-2"
readonly TARGET_AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-$APPROVED_AWS_REGION}}"
readonly VPC_ID="vpc-06c90b864a62f93a4"
readonly ASG_SECURITY_GROUP_ID="sg-0a0a2fe0e54027420"

readonly SUBNET_A_NAME="buildgraph-demo-subnet-private-app1-ap-northeast-2a"
readonly SUBNET_A_CIDR="10.0.34.0/24"
readonly SUBNET_A_AZ="ap-northeast-2a"
readonly SUBNET_B_NAME="buildgraph-demo-subnet-private-app2-ap-northeast-2b"
readonly SUBNET_B_CIDR="10.0.35.0/24"
readonly SUBNET_B_AZ="ap-northeast-2b"

readonly ROUTE_TABLE_A_NAME="buildgraph-demo-rtb-private-app1-ap-northeast-2a"
readonly ROUTE_TABLE_B_NAME="buildgraph-demo-rtb-private-app2-ap-northeast-2b"
readonly NAT_NAME="buildgraph-demo-nat-regional"
readonly ENDPOINT_SECURITY_GROUP_NAME="buildgraph-demo-private-app-endpoints-sg"
readonly ENDPOINT_SECURITY_GROUP_DESCRIPTION="HTTPS from the BuildGraph Green ASG to private AWS service endpoints"
readonly VPCE_WAIT_MAX_ATTEMPTS="${BUILDGRAPH_VPCE_WAIT_MAX_ATTEMPTS:-60}"
readonly VPCE_WAIT_POLL_SECONDS="${BUILDGRAPH_VPCE_WAIT_POLL_SECONDS:-10}"

readonly S3_ENDPOINT_NAME="buildgraph-demo-vpce-s3-private-app"
readonly ECR_API_ENDPOINT_NAME="buildgraph-demo-vpce-ecr-api-private-app"
readonly ECR_DKR_ENDPOINT_NAME="buildgraph-demo-vpce-ecr-dkr-private-app"
readonly SECRETSMANAGER_ENDPOINT_NAME="buildgraph-demo-vpce-secretsmanager-private-app"
readonly SSM_ENDPOINT_NAME="buildgraph-demo-vpce-ssm-private-app"
readonly SSMMESSAGES_ENDPOINT_NAME="buildgraph-demo-vpce-ssmmessages-private-app"
readonly PROJECT_TAGS="{Key=Stack,Value=green},{Key=Service,Value=api},{Key=ManagedBy,Value=private-app-network}"

APPLY=false
PLAN_COUNT=0

SUBNET_A_ID=""
SUBNET_B_ID=""
ROUTE_TABLE_A_ID=""
ROUTE_TABLE_B_ID=""
NAT_ID=""
ENDPOINT_SECURITY_GROUP_ID=""

CREATE_SUBNET_A=false
CREATE_SUBNET_B=false
CREATE_ROUTE_TABLE_A=false
CREATE_ROUTE_TABLE_B=false
ASSOCIATE_ROUTE_TABLE_A=false
ASSOCIATE_ROUTE_TABLE_B=false
CREATE_NAT=false
WAIT_FOR_NAT=false
CREATE_DEFAULT_ROUTE_A=false
CREATE_DEFAULT_ROUTE_B=false
CREATE_ENDPOINT_SECURITY_GROUP=false
AUTHORIZE_ENDPOINT_HTTPS=false
CREATE_S3_ENDPOINT=false
CREATE_ECR_API_ENDPOINT=false
CREATE_ECR_DKR_ENDPOINT=false
CREATE_SECRETSMANAGER_ENDPOINT=false
CREATE_SSM_ENDPOINT=false
CREATE_SSMMESSAGES_ENDPOINT=false

DISCOVERED_ID=""
DISCOVERED_MISSING=false
DISCOVERED_NEEDS_ASSOCIATION=false
DISCOVERED_NEEDS_ROUTE=false
DISCOVERED_ENDPOINT_MISSING=false
PENDING_ENDPOINT_IDS=()

log() {
  printf '%s\n' "buildgraph private app network: $*"
}

die() {
  printf '%s\n' "buildgraph private app network rejected: $*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: tools/provision_green_private_app_network.sh [--apply]

Without arguments, the script performs read-only discovery, drift validation,
and prints the planned changes. Pass --apply to create only missing resources.
EOF
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "required command is missing: $1"
}

aws_cli() {
  aws "$@" \
    --region "$TARGET_AWS_REGION" \
    --no-cli-pager
}

aws_ec2() {
  aws_cli ec2 "$@"
}

is_true() {
  case "$1" in
    True|true)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_false() {
  case "$1" in
    False|false)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_none() {
  case "$1" in
    ""|None|null)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

ensure_single_row() {
  local resource="$1"
  local output="$2"

  [[ "$output" != *$'\n'* ]] ||
    die "$resource drift: more than one matching resource exists"
}

normalize_id_list() {
  local value="$1"

  printf '%s' "$value" |
    tr '[:space:]' '\n' |
    sed -e '/^$/d' -e '/^None$/d' -e '/^null$/d' |
    sort -u |
    paste -sd' ' -
}

expected_id_list() {
  printf '%s\n' "$@" |
    sed -e '/^$/d' |
    sort -u |
    paste -sd' ' -
}

id_sets_equal() {
  local actual="$1"
  shift

  [[ "$(normalize_id_list "$actual")" == "$(expected_id_list "$@")" ]]
}

record_plan() {
  PLAN_COUNT=$((PLAN_COUNT + 1))
  log "plan: $*"
}

discover_subnet() {
  local name="$1"
  local expected_cidr="$2"
  local expected_az="$3"
  local output subnet_id cidr az map_public_ip state vpc_id

  output="$(
    aws_ec2 describe-subnets \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=tag:Name,Values=$name" \
      --query \
        'Subnets[].[SubnetId,CidrBlock,AvailabilityZone,MapPublicIpOnLaunch,State,VpcId]' \
      --output text
  )"
  ensure_single_row "subnet $name" "$output"

  if [[ -z "$output" ]]; then
    DISCOVERED_ID=""
    DISCOVERED_MISSING=true
    return 0
  fi

  IFS=$'\t' read -r subnet_id cidr az map_public_ip state vpc_id <<<"$output"
  [[ "$vpc_id" == "$VPC_ID" ]] ||
    die "subnet $name drift: VPC is $vpc_id, expected $VPC_ID"
  [[ "$cidr" == "$expected_cidr" ]] ||
    die "subnet $name CIDR drift: found $cidr, expected $expected_cidr"
  [[ "$az" == "$expected_az" ]] ||
    die "subnet $name drift: AZ is $az, expected $expected_az"
  is_false "$map_public_ip" ||
    die "subnet $name drift: MapPublicIpOnLaunch must be false"
  case "$state" in
    pending|available)
      ;;
    *)
      die "subnet $name drift: unsupported state $state"
      ;;
  esac

  DISCOVERED_ID="$subnet_id"
  DISCOVERED_MISSING=false
}

validate_cidr_availability() {
  local rows overlap_error

  rows="$(
    aws_ec2 describe-subnets \
      --filters "Name=vpc-id,Values=$VPC_ID" \
      --query 'Subnets[].[SubnetId,CidrBlock]' \
      --output text
  )"

  if ! overlap_error="$(
    CIDR_ROWS="$rows" \
      EXISTING_SUBNET_A_ID="$SUBNET_A_ID" \
      EXISTING_SUBNET_B_ID="$SUBNET_B_ID" \
      DESIRED_SUBNET_A_CIDR="$SUBNET_A_CIDR" \
      DESIRED_SUBNET_B_CIDR="$SUBNET_B_CIDR" \
      python3 - <<'PY'
import ipaddress
import os
import sys

rows = os.environ.get("CIDR_ROWS", "")
existing_ids = {
    value
    for value in (
        os.environ.get("EXISTING_SUBNET_A_ID", ""),
        os.environ.get("EXISTING_SUBNET_B_ID", ""),
    )
    if value
}
desired = (
    ipaddress.ip_network(os.environ["DESIRED_SUBNET_A_CIDR"], strict=True),
    ipaddress.ip_network(os.environ["DESIRED_SUBNET_B_CIDR"], strict=True),
)

for line in rows.splitlines():
    if not line:
        continue
    fields = line.split("\t")
    if len(fields) != 2:
        print(f"CIDR validation failed closed on malformed subnet row: {line}")
        sys.exit(1)
    subnet_id, cidr = fields
    if subnet_id in existing_ids:
        continue
    try:
        network = ipaddress.ip_network(cidr, strict=True)
    except ValueError:
        print(f"CIDR validation failed closed for subnet {subnet_id}: {cidr}")
        sys.exit(1)
    for expected in desired:
        if network.overlaps(expected):
            print(
                "CIDR overlap: "
                f"existing subnet {subnet_id} ({network}) overlaps desired {expected}"
            )
            sys.exit(1)
PY
  )"; then
    die "$overlap_error"
  fi
}

discover_route_table() {
  local name="$1"
  local expected_subnet_id="$2"
  local output route_table_id vpc_id main_associations associations

  output="$(
    aws_ec2 describe-route-tables \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=tag:Name,Values=$name" \
      --query \
        'RouteTables[].[RouteTableId,VpcId,length(Associations[?Main==`true`])]' \
      --output text
  )"
  ensure_single_row "route table $name" "$output"

  if [[ -z "$output" ]]; then
    DISCOVERED_ID=""
    DISCOVERED_MISSING=true
    DISCOVERED_NEEDS_ASSOCIATION=true
    return 0
  fi

  IFS=$'\t' read -r route_table_id vpc_id main_associations <<<"$output"
  [[ "$vpc_id" == "$VPC_ID" ]] ||
    die "route table $name drift: VPC is $vpc_id, expected $VPC_ID"
  [[ "$main_associations" == "0" ]] ||
    die "route table $name drift: the VPC main route table cannot be used"

  associations="$(
    aws_ec2 describe-route-tables \
      --route-table-ids "$route_table_id" \
      --query 'RouteTables[0].Associations[?Main==`false`].SubnetId' \
      --output text
  )"
  if [[ -z "$(normalize_id_list "$associations")" ]]; then
    DISCOVERED_NEEDS_ASSOCIATION=true
  elif [[ -n "$expected_subnet_id" ]] &&
    id_sets_equal "$associations" "$expected_subnet_id"; then
    DISCOVERED_NEEDS_ASSOCIATION=false
  else
    die "route table $name association drift: expected only the matching private app subnet"
  fi

  DISCOVERED_ID="$route_table_id"
  DISCOVERED_MISSING=false
}

validate_subnet_route_table_binding() {
  local subnet_name="$1"
  local subnet_id="$2"
  local expected_route_table_id="$3"
  local current_route_tables

  [[ "$DISCOVERED_NEEDS_ASSOCIATION" == "true" && -n "$subnet_id" ]] ||
    return 0

  current_route_tables="$(
    aws_ec2 describe-route-tables \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=association.subnet-id,Values=$subnet_id" \
      --query 'RouteTables[].RouteTableId' \
      --output text
  )"
  if [[ -z "$(normalize_id_list "$current_route_tables")" ]]; then
    return 0
  fi
  if [[ -n "$expected_route_table_id" ]] &&
    id_sets_equal "$current_route_tables" "$expected_route_table_id"; then
    DISCOVERED_NEEDS_ASSOCIATION=false
    return 0
  fi

  die "subnet $subnet_name route table drift: an unexpected explicit association exists"
}

discover_nat_gateway() {
  local output nat_id vpc_id state connectivity availability_mode
  local auto_provision_zones auto_scaling_ips name

  output="$(
    aws_ec2 describe-nat-gateways \
      --filter \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=state,Values=pending,available,failed,deleting" \
      --query \
        'NatGateways[].[NatGatewayId,VpcId,State,ConnectivityType,AvailabilityMode,AutoProvisionZones,AutoScalingIps,Tags[?Key==`Name`]|[0].Value]' \
      --output text
  )"
  ensure_single_row "regional NAT gateway" "$output"

  if [[ -z "$output" ]]; then
    NAT_ID=""
    CREATE_NAT=true
    WAIT_FOR_NAT=false
    return 0
  fi

  IFS=$'\t' read -r \
    nat_id \
    vpc_id \
    state \
    connectivity \
    availability_mode \
    auto_provision_zones \
    auto_scaling_ips \
    name <<<"$output"

  [[ "$name" == "$NAT_NAME" ]] ||
    die "regional NAT gateway drift: active VPC NAT must be named $NAT_NAME"
  [[ "$vpc_id" == "$VPC_ID" ]] ||
    die "regional NAT gateway drift: VPC is $vpc_id, expected $VPC_ID"
  case "$state" in
    pending)
      WAIT_FOR_NAT=true
      ;;
    available)
      WAIT_FOR_NAT=false
      ;;
    *)
      die "regional NAT gateway drift: unsupported state $state"
      ;;
  esac
  [[ "$connectivity" == "public" ]] ||
    die "regional NAT gateway drift: connectivity must be public"
  [[ "$availability_mode" == "regional" ]] ||
    die "regional NAT gateway drift: availability mode must be regional"
  [[ "$auto_provision_zones" == "enabled" ]] ||
    die "regional NAT gateway drift: automatic AZ provisioning must be enabled"
  [[ "$auto_scaling_ips" == "enabled" ]] ||
    die "regional NAT gateway drift: automatic IP scaling must be enabled"

  NAT_ID="$nat_id"
  CREATE_NAT=false
}

inspect_default_route() {
  local name="$1"
  local route_table_id="$2"
  local output route_nat gateway network_interface transit_gateway state

  if [[ -z "$route_table_id" ]]; then
    DISCOVERED_NEEDS_ROUTE=true
    return 0
  fi

  output="$(
    aws_ec2 describe-route-tables \
      --route-table-ids "$route_table_id" \
      --query \
        'RouteTables[0].Routes[?DestinationCidrBlock==`0.0.0.0/0`].[NatGatewayId,GatewayId,NetworkInterfaceId,TransitGatewayId,State]' \
      --output text
  )"
  ensure_single_row "route table $name default route" "$output"

  if [[ -z "$output" ]]; then
    DISCOVERED_NEEDS_ROUTE=true
    return 0
  fi

  IFS=$'\t' read -r \
    route_nat \
    gateway \
    network_interface \
    transit_gateway \
    state <<<"$output"
  [[ -n "$NAT_ID" ]] ||
    die "route table $name drift: a default route exists without the approved NAT"
  [[ "$route_nat" == "$NAT_ID" ]] ||
    die "route table $name drift: default route targets $route_nat, expected $NAT_ID"
  is_none "$gateway" &&
    is_none "$network_interface" &&
    is_none "$transit_gateway" ||
    die "route table $name drift: default route has an unexpected target"
  [[ "$state" == "active" ]] ||
    die "route table $name drift: default route state is $state"

  DISCOVERED_NEEDS_ROUTE=false
}

discover_endpoint_security_group() {
  local output group_id vpc_id group_name name_tag rules
  local protocol from_port to_port source_group cidr_ipv4 cidr_ipv6 prefix_list

  output="$(
    aws_ec2 describe-security-groups \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=group-name,Values=$ENDPOINT_SECURITY_GROUP_NAME" \
      --query \
        'SecurityGroups[].[GroupId,VpcId,GroupName,Tags[?Key==`Name`]|[0].Value]' \
      --output text
  )"
  ensure_single_row "endpoint security group" "$output"

  if [[ -z "$output" ]]; then
    ENDPOINT_SECURITY_GROUP_ID=""
    CREATE_ENDPOINT_SECURITY_GROUP=true
    AUTHORIZE_ENDPOINT_HTTPS=true
    return 0
  fi

  IFS=$'\t' read -r group_id vpc_id group_name name_tag <<<"$output"
  [[ "$vpc_id" == "$VPC_ID" ]] ||
    die "endpoint security group drift: VPC is $vpc_id, expected $VPC_ID"
  [[ "$group_name" == "$ENDPOINT_SECURITY_GROUP_NAME" ]] ||
    die "endpoint security group drift: group name does not match"
  [[ "$name_tag" == "$ENDPOINT_SECURITY_GROUP_NAME" ]] ||
    die "endpoint security group drift: Name tag does not match"

  rules="$(
    aws_ec2 describe-security-group-rules \
      --filters "Name=group-id,Values=$group_id" \
      --query \
        'SecurityGroupRules[?IsEgress==`false`].[IpProtocol,FromPort,ToPort,ReferencedGroupInfo.GroupId,CidrIpv4,CidrIpv6,PrefixListId]' \
      --output text
  )"
  ensure_single_row "endpoint security group ingress" "$rules"

  if [[ -z "$rules" ]]; then
    AUTHORIZE_ENDPOINT_HTTPS=true
  else
    IFS=$'\t' read -r \
      protocol \
      from_port \
      to_port \
      source_group \
      cidr_ipv4 \
      cidr_ipv6 \
      prefix_list <<<"$rules"
    [[ "$protocol" == "tcp" &&
      "$from_port" == "443" &&
      "$to_port" == "443" &&
      "$source_group" == "$ASG_SECURITY_GROUP_ID" ]] ||
      die "endpoint security group ingress drift: only TCP 443 from the ASG security group is allowed"
    is_none "$cidr_ipv4" &&
      is_none "$cidr_ipv6" &&
      is_none "$prefix_list" ||
      die "endpoint security group ingress drift: CIDR and prefix-list sources are forbidden"
    AUTHORIZE_ENDPOINT_HTTPS=false
  fi

  ENDPOINT_SECURITY_GROUP_ID="$group_id"
  CREATE_ENDPOINT_SECURITY_GROUP=false
}

inspect_vpc_endpoint() {
  local suffix="$1"
  local expected_name="$2"
  local expected_type="$3"
  local service_name="com.amazonaws.${APPROVED_AWS_REGION}.${suffix}"
  local output endpoint_id actual_service actual_type state private_dns actual_name
  local route_table_ids subnet_ids security_group_ids

  output="$(
    aws_ec2 describe-vpc-endpoints \
      --filters \
        "Name=vpc-id,Values=$VPC_ID" \
        "Name=service-name,Values=$service_name" \
      --query \
        'VpcEndpoints[].[VpcEndpointId,ServiceName,VpcEndpointType,State,PrivateDnsEnabled,Tags[?Key==`Name`]|[0].Value]' \
      --output text
  )"
  ensure_single_row "VPC endpoint $service_name" "$output"

  if [[ -z "$output" ]]; then
    DISCOVERED_ENDPOINT_MISSING=true
    return 0
  fi

  IFS=$'\t' read -r \
    endpoint_id \
    actual_service \
    actual_type \
    state \
    private_dns \
    actual_name <<<"$output"
  [[ "$actual_service" == "$service_name" ]] ||
    die "VPC endpoint $service_name drift: service name does not match"
  [[ "$actual_type" == "$expected_type" ]] ||
    die "VPC endpoint $service_name drift: type is $actual_type, expected $expected_type"
  [[ "$actual_name" == "$expected_name" ]] ||
    die "VPC endpoint $service_name drift: Name tag must be $expected_name"
  case "$state" in
    pending)
      PENDING_ENDPOINT_IDS[${#PENDING_ENDPOINT_IDS[@]}]="$endpoint_id"
      ;;
    available)
      ;;
    *)
      die "VPC endpoint $service_name drift: unsupported state $state"
      ;;
  esac

  if [[ "$expected_type" == "Gateway" ]]; then
    [[ -n "$ROUTE_TABLE_A_ID" && -n "$ROUTE_TABLE_B_ID" ]] ||
      die "VPC endpoint $service_name drift: expected route tables do not exist"
    is_false "$private_dns" ||
      die "VPC endpoint $service_name drift: gateway endpoint private DNS must be false"
    route_table_ids="$(
      aws_ec2 describe-vpc-endpoints \
        --vpc-endpoint-ids "$endpoint_id" \
        --query 'VpcEndpoints[0].RouteTableIds' \
        --output text
    )"
    id_sets_equal \
      "$route_table_ids" \
      "$ROUTE_TABLE_A_ID" \
      "$ROUTE_TABLE_B_ID" ||
      die "VPC endpoint $service_name route table drift"
  else
    [[ -n "$SUBNET_A_ID" &&
      -n "$SUBNET_B_ID" &&
      -n "$ENDPOINT_SECURITY_GROUP_ID" ]] ||
      die "VPC endpoint $service_name drift: expected subnet or security group does not exist"
    is_true "$private_dns" ||
      die "VPC endpoint $service_name private DNS drift: private DNS must be enabled"
    subnet_ids="$(
      aws_ec2 describe-vpc-endpoints \
        --vpc-endpoint-ids "$endpoint_id" \
        --query 'VpcEndpoints[0].SubnetIds' \
        --output text
    )"
    id_sets_equal "$subnet_ids" "$SUBNET_A_ID" "$SUBNET_B_ID" ||
      die "VPC endpoint $service_name subnet drift"
    security_group_ids="$(
      aws_ec2 describe-vpc-endpoints \
        --vpc-endpoint-ids "$endpoint_id" \
        --query 'VpcEndpoints[0].Groups[].GroupId' \
        --output text
    )"
    id_sets_equal "$security_group_ids" "$ENDPOINT_SECURITY_GROUP_ID" ||
      die "VPC endpoint $service_name security group drift"
  fi

  DISCOVERED_ENDPOINT_MISSING=false
}

create_subnet() {
  local name="$1"
  local cidr="$2"
  local az="$3"
  local subnet_id

  subnet_id="$(
    aws_ec2 create-subnet \
      --vpc-id "$VPC_ID" \
      --cidr-block "$cidr" \
      --availability-zone "$az" \
      --tag-specifications \
        "ResourceType=subnet,Tags=[{Key=Name,Value=$name},$PROJECT_TAGS]" \
      --query 'Subnet.SubnetId' \
      --output text
  )"
  [[ "$subnet_id" == subnet-* ]] ||
    die "create-subnet returned an invalid ID for $name"
  aws_ec2 wait subnet-available --subnet-ids "$subnet_id"
  aws_ec2 modify-subnet-attribute \
    --subnet-id "$subnet_id" \
    --no-map-public-ip-on-launch
  printf '%s' "$subnet_id"
}

create_route_table() {
  local name="$1"
  local route_table_id

  route_table_id="$(
    aws_ec2 create-route-table \
      --vpc-id "$VPC_ID" \
      --tag-specifications \
        "ResourceType=route-table,Tags=[{Key=Name,Value=$name},$PROJECT_TAGS]" \
      --query 'RouteTable.RouteTableId' \
      --output text
  )"
  [[ "$route_table_id" == rtb-* ]] ||
    die "create-route-table returned an invalid ID for $name"
  printf '%s' "$route_table_id"
}

create_interface_endpoint() {
  local suffix="$1"
  local name="$2"
  local endpoint_id

  endpoint_id="$(
    aws_ec2 create-vpc-endpoint \
      --vpc-id "$VPC_ID" \
      --vpc-endpoint-type Interface \
      --service-name "com.amazonaws.${APPROVED_AWS_REGION}.${suffix}" \
      --subnet-ids "$SUBNET_A_ID" "$SUBNET_B_ID" \
      --security-group-ids "$ENDPOINT_SECURITY_GROUP_ID" \
      --private-dns-enabled \
      --tag-specifications \
        "ResourceType=vpc-endpoint,Tags=[{Key=Name,Value=$name},$PROJECT_TAGS]" \
      --query 'VpcEndpoint.VpcEndpointId' \
      --output text
  )"
  [[ "$endpoint_id" == vpce-* ]] ||
    die "create-vpc-endpoint returned an invalid ID for $suffix"
  NEW_ENDPOINT_IDS[${#NEW_ENDPOINT_IDS[@]}]="$endpoint_id"
}

wait_for_vpc_endpoints() {
  local endpoint_id attempt state

  [[ "$VPCE_WAIT_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] ||
    die "VPC endpoint wait attempts must be positive"
  [[ "$VPCE_WAIT_POLL_SECONDS" =~ ^[0-9]+$ ]] ||
    die "VPC endpoint wait poll seconds must be non-negative"

  for endpoint_id in "$@"; do
    state=""
    for ((attempt = 1; attempt <= VPCE_WAIT_MAX_ATTEMPTS; attempt++)); do
      state="$(
        aws_ec2 describe-vpc-endpoints \
          --vpc-endpoint-ids "$endpoint_id" \
          --query 'VpcEndpoints[0].State' \
          --output text
      )"
      case "$state" in
        available)
          break
          ;;
        pending)
          if [[ "$attempt" -lt "$VPCE_WAIT_MAX_ATTEMPTS" ]]; then
            sleep "$VPCE_WAIT_POLL_SECONDS"
          fi
          ;;
        *)
          die "VPC endpoint $endpoint_id entered unsupported state $state"
          ;;
      esac
    done
    [[ "$state" == "available" ]] ||
      die "VPC endpoint $endpoint_id did not become available"
  done
}

if [[ "$#" -eq 0 ]]; then
  APPLY=false
elif [[ "$#" -eq 1 && "$1" == "--apply" ]]; then
  APPLY=true
elif [[ "$#" -eq 1 && ("$1" == "--help" || "$1" == "-h") ]]; then
  usage
  exit 0
else
  usage >&2
  die "the only supported mutation flag is --apply"
fi

for command_name in aws paste python3 sed sleep sort tr; do
  require_command "$command_name"
done

[[ "$TARGET_AWS_REGION" == "$APPROVED_AWS_REGION" ]] ||
  die "region mismatch: expected $APPROVED_AWS_REGION, got $TARGET_AWS_REGION"

caller_account="$(
  aws_cli sts get-caller-identity \
    --query Account \
    --output text
)"
[[ "$caller_account" == "$APPROVED_AWS_ACCOUNT_ID" ]] ||
  die "account mismatch: expected $APPROVED_AWS_ACCOUNT_ID, got $caller_account"

vpc_output="$(
  aws_ec2 describe-vpcs \
    --vpc-ids "$VPC_ID" \
    --query 'Vpcs[0].[VpcId,State]' \
    --output text
)"
ensure_single_row "VPC $VPC_ID" "$vpc_output"
IFS=$'\t' read -r actual_vpc_id vpc_state <<<"$vpc_output"
[[ "$actual_vpc_id" == "$VPC_ID" && "$vpc_state" == "available" ]] ||
  die "VPC drift: expected available $VPC_ID"

dns_support="$(
  aws_ec2 describe-vpc-attribute \
    --vpc-id "$VPC_ID" \
    --attribute enableDnsSupport \
    --query 'EnableDnsSupport.Value' \
    --output text
)"
is_true "$dns_support" ||
  die "VPC DNS drift: enableDnsSupport must be true"

dns_hostnames="$(
  aws_ec2 describe-vpc-attribute \
    --vpc-id "$VPC_ID" \
    --attribute enableDnsHostnames \
    --query 'EnableDnsHostnames.Value' \
    --output text
)"
is_true "$dns_hostnames" ||
  die "VPC DNS drift: enableDnsHostnames must be true"

asg_security_group="$(
  aws_ec2 describe-security-groups \
    --group-ids "$ASG_SECURITY_GROUP_ID" \
    --query 'SecurityGroups[0].[GroupId,VpcId]' \
    --output text
)"
ensure_single_row "ASG security group" "$asg_security_group"
IFS=$'\t' read -r actual_asg_security_group_id asg_security_group_vpc_id \
  <<<"$asg_security_group"
[[ "$actual_asg_security_group_id" == "$ASG_SECURITY_GROUP_ID" &&
  "$asg_security_group_vpc_id" == "$VPC_ID" ]] ||
  die "ASG security group drift: expected $ASG_SECURITY_GROUP_ID in $VPC_ID"

discover_subnet "$SUBNET_A_NAME" "$SUBNET_A_CIDR" "$SUBNET_A_AZ"
SUBNET_A_ID="$DISCOVERED_ID"
CREATE_SUBNET_A="$DISCOVERED_MISSING"

discover_subnet "$SUBNET_B_NAME" "$SUBNET_B_CIDR" "$SUBNET_B_AZ"
SUBNET_B_ID="$DISCOVERED_ID"
CREATE_SUBNET_B="$DISCOVERED_MISSING"

validate_cidr_availability

discover_route_table "$ROUTE_TABLE_A_NAME" "$SUBNET_A_ID"
validate_subnet_route_table_binding \
  "$SUBNET_A_NAME" \
  "$SUBNET_A_ID" \
  "$DISCOVERED_ID"
ROUTE_TABLE_A_ID="$DISCOVERED_ID"
CREATE_ROUTE_TABLE_A="$DISCOVERED_MISSING"
ASSOCIATE_ROUTE_TABLE_A="$DISCOVERED_NEEDS_ASSOCIATION"

discover_route_table "$ROUTE_TABLE_B_NAME" "$SUBNET_B_ID"
validate_subnet_route_table_binding \
  "$SUBNET_B_NAME" \
  "$SUBNET_B_ID" \
  "$DISCOVERED_ID"
ROUTE_TABLE_B_ID="$DISCOVERED_ID"
CREATE_ROUTE_TABLE_B="$DISCOVERED_MISSING"
ASSOCIATE_ROUTE_TABLE_B="$DISCOVERED_NEEDS_ASSOCIATION"

discover_nat_gateway

inspect_default_route "$ROUTE_TABLE_A_NAME" "$ROUTE_TABLE_A_ID"
CREATE_DEFAULT_ROUTE_A="$DISCOVERED_NEEDS_ROUTE"
inspect_default_route "$ROUTE_TABLE_B_NAME" "$ROUTE_TABLE_B_ID"
CREATE_DEFAULT_ROUTE_B="$DISCOVERED_NEEDS_ROUTE"

discover_endpoint_security_group

inspect_vpc_endpoint "s3" "$S3_ENDPOINT_NAME" "Gateway"
CREATE_S3_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"
inspect_vpc_endpoint "ecr.api" "$ECR_API_ENDPOINT_NAME" "Interface"
CREATE_ECR_API_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"
inspect_vpc_endpoint "ecr.dkr" "$ECR_DKR_ENDPOINT_NAME" "Interface"
CREATE_ECR_DKR_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"
inspect_vpc_endpoint \
  "secretsmanager" \
  "$SECRETSMANAGER_ENDPOINT_NAME" \
  "Interface"
CREATE_SECRETSMANAGER_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"
inspect_vpc_endpoint "ssm" "$SSM_ENDPOINT_NAME" "Interface"
CREATE_SSM_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"
inspect_vpc_endpoint "ssmmessages" "$SSMMESSAGES_ENDPOINT_NAME" "Interface"
CREATE_SSMMESSAGES_ENDPOINT="$DISCOVERED_ENDPOINT_MISSING"

[[ "$CREATE_SUBNET_A" == "false" ]] ||
  record_plan "create subnet $SUBNET_A_NAME ($SUBNET_A_CIDR, $SUBNET_A_AZ)"
[[ "$CREATE_SUBNET_B" == "false" ]] ||
  record_plan "create subnet $SUBNET_B_NAME ($SUBNET_B_CIDR, $SUBNET_B_AZ)"
[[ "$CREATE_ROUTE_TABLE_A" == "false" ]] ||
  record_plan "create route table $ROUTE_TABLE_A_NAME"
[[ "$CREATE_ROUTE_TABLE_B" == "false" ]] ||
  record_plan "create route table $ROUTE_TABLE_B_NAME"
[[ "$CREATE_NAT" == "false" ]] ||
  record_plan "create automatic regional NAT gateway $NAT_NAME"
[[ "$WAIT_FOR_NAT" == "false" ]] ||
  record_plan "wait for pending regional NAT gateway $NAT_NAME"
[[ "$ASSOCIATE_ROUTE_TABLE_A" == "false" ]] ||
  record_plan "associate $ROUTE_TABLE_A_NAME with $SUBNET_A_NAME"
[[ "$ASSOCIATE_ROUTE_TABLE_B" == "false" ]] ||
  record_plan "associate $ROUTE_TABLE_B_NAME with $SUBNET_B_NAME"
[[ "$CREATE_DEFAULT_ROUTE_A" == "false" ]] ||
  record_plan "route $ROUTE_TABLE_A_NAME 0.0.0.0/0 to $NAT_NAME"
[[ "$CREATE_DEFAULT_ROUTE_B" == "false" ]] ||
  record_plan "route $ROUTE_TABLE_B_NAME 0.0.0.0/0 to $NAT_NAME"
[[ "$CREATE_ENDPOINT_SECURITY_GROUP" == "false" ]] ||
  record_plan "create endpoint security group $ENDPOINT_SECURITY_GROUP_NAME"
[[ "$AUTHORIZE_ENDPOINT_HTTPS" == "false" ]] ||
  record_plan "allow endpoint TCP 443 only from $ASG_SECURITY_GROUP_ID"
[[ "$CREATE_S3_ENDPOINT" == "false" ]] ||
  record_plan "create S3 Gateway endpoint on both app route tables"
[[ "$CREATE_ECR_API_ENDPOINT" == "false" ]] ||
  record_plan "create ecr.api Interface endpoint in both app subnets"
[[ "$CREATE_ECR_DKR_ENDPOINT" == "false" ]] ||
  record_plan "create ecr.dkr Interface endpoint in both app subnets"
[[ "$CREATE_SECRETSMANAGER_ENDPOINT" == "false" ]] ||
  record_plan "create secretsmanager Interface endpoint in both app subnets"
[[ "$CREATE_SSM_ENDPOINT" == "false" ]] ||
  record_plan "create ssm Interface endpoint in both app subnets"
[[ "$CREATE_SSMMESSAGES_ENDPOINT" == "false" ]] ||
  record_plan "create ssmmessages Interface endpoint in both app subnets"
[[ "${#PENDING_ENDPOINT_IDS[@]}" -eq 0 ]] ||
  record_plan "wait for ${#PENDING_ENDPOINT_IDS[@]} pending VPC endpoint(s)"

if [[ "$APPLY" != "true" ]]; then
  if [[ "$PLAN_COUNT" -eq 0 ]]; then
    log "read-only plan: infrastructure is already converged"
  else
    log "read-only plan complete; rerun with --apply to create the listed resources"
  fi
  exit 0
fi

if [[ "$PLAN_COUNT" -eq 0 ]]; then
  log "already converged; no mutations were issued"
  exit 0
fi

if [[ "$CREATE_SUBNET_A" == "true" ]]; then
  SUBNET_A_ID="$(create_subnet "$SUBNET_A_NAME" "$SUBNET_A_CIDR" "$SUBNET_A_AZ")"
fi
if [[ "$CREATE_SUBNET_B" == "true" ]]; then
  SUBNET_B_ID="$(create_subnet "$SUBNET_B_NAME" "$SUBNET_B_CIDR" "$SUBNET_B_AZ")"
fi

if [[ "$CREATE_ROUTE_TABLE_A" == "true" ]]; then
  ROUTE_TABLE_A_ID="$(create_route_table "$ROUTE_TABLE_A_NAME")"
fi
if [[ "$CREATE_ROUTE_TABLE_B" == "true" ]]; then
  ROUTE_TABLE_B_ID="$(create_route_table "$ROUTE_TABLE_B_NAME")"
fi

if [[ "$CREATE_NAT" == "true" ]]; then
  NAT_ID="$(
    aws_ec2 create-nat-gateway \
      --vpc-id "$VPC_ID" \
      --availability-mode regional \
      --tag-specifications \
        "ResourceType=natgateway,Tags=[{Key=Name,Value=$NAT_NAME},$PROJECT_TAGS]" \
      --query 'NatGateway.NatGatewayId' \
      --output text
  )"
  [[ "$NAT_ID" == nat-* ]] ||
    die "create-nat-gateway returned an invalid ID"
fi
if [[ "$CREATE_NAT" == "true" || "$WAIT_FOR_NAT" == "true" ]]; then
  aws_ec2 wait nat-gateway-available --nat-gateway-ids "$NAT_ID"
fi

if [[ "$CREATE_ENDPOINT_SECURITY_GROUP" == "true" ]]; then
  ENDPOINT_SECURITY_GROUP_ID="$(
    aws_ec2 create-security-group \
      --group-name "$ENDPOINT_SECURITY_GROUP_NAME" \
      --description "$ENDPOINT_SECURITY_GROUP_DESCRIPTION" \
      --vpc-id "$VPC_ID" \
      --tag-specifications \
        "ResourceType=security-group,Tags=[{Key=Name,Value=$ENDPOINT_SECURITY_GROUP_NAME},$PROJECT_TAGS]" \
      --query GroupId \
      --output text
  )"
  [[ "$ENDPOINT_SECURITY_GROUP_ID" == sg-* ]] ||
    die "create-security-group returned an invalid ID"
fi

if [[ "$ASSOCIATE_ROUTE_TABLE_A" == "true" ]]; then
  aws_ec2 associate-route-table \
    --route-table-id "$ROUTE_TABLE_A_ID" \
    --subnet-id "$SUBNET_A_ID" \
    --query AssociationId \
    --output text >/dev/null
fi
if [[ "$ASSOCIATE_ROUTE_TABLE_B" == "true" ]]; then
  aws_ec2 associate-route-table \
    --route-table-id "$ROUTE_TABLE_B_ID" \
    --subnet-id "$SUBNET_B_ID" \
    --query AssociationId \
    --output text >/dev/null
fi

if [[ "$CREATE_DEFAULT_ROUTE_A" == "true" ]]; then
  aws_ec2 create-route \
    --route-table-id "$ROUTE_TABLE_A_ID" \
    --destination-cidr-block 0.0.0.0/0 \
    --nat-gateway-id "$NAT_ID" \
    --query Return \
    --output text >/dev/null
fi
if [[ "$CREATE_DEFAULT_ROUTE_B" == "true" ]]; then
  aws_ec2 create-route \
    --route-table-id "$ROUTE_TABLE_B_ID" \
    --destination-cidr-block 0.0.0.0/0 \
    --nat-gateway-id "$NAT_ID" \
    --query Return \
    --output text >/dev/null
fi

if [[ "$AUTHORIZE_ENDPOINT_HTTPS" == "true" ]]; then
  aws_ec2 authorize-security-group-ingress \
    --group-id "$ENDPOINT_SECURITY_GROUP_ID" \
    --protocol tcp \
    --port 443 \
    --source-group "$ASG_SECURITY_GROUP_ID" >/dev/null
fi

NEW_ENDPOINT_IDS=()

if [[ "$CREATE_S3_ENDPOINT" == "true" ]]; then
  s3_endpoint_id="$(
    aws_ec2 create-vpc-endpoint \
      --vpc-id "$VPC_ID" \
      --vpc-endpoint-type Gateway \
      --service-name "com.amazonaws.${APPROVED_AWS_REGION}.s3" \
      --route-table-ids "$ROUTE_TABLE_A_ID" "$ROUTE_TABLE_B_ID" \
      --tag-specifications \
        "ResourceType=vpc-endpoint,Tags=[{Key=Name,Value=$S3_ENDPOINT_NAME},$PROJECT_TAGS]" \
      --query 'VpcEndpoint.VpcEndpointId' \
      --output text
  )"
  [[ "$s3_endpoint_id" == vpce-* ]] ||
    die "create-vpc-endpoint returned an invalid ID for s3"
  NEW_ENDPOINT_IDS[${#NEW_ENDPOINT_IDS[@]}]="$s3_endpoint_id"
fi

if [[ "$CREATE_ECR_API_ENDPOINT" == "true" ]]; then
  create_interface_endpoint "ecr.api" "$ECR_API_ENDPOINT_NAME"
fi
if [[ "$CREATE_ECR_DKR_ENDPOINT" == "true" ]]; then
  create_interface_endpoint "ecr.dkr" "$ECR_DKR_ENDPOINT_NAME"
fi
if [[ "$CREATE_SECRETSMANAGER_ENDPOINT" == "true" ]]; then
  create_interface_endpoint "secretsmanager" "$SECRETSMANAGER_ENDPOINT_NAME"
fi
if [[ "$CREATE_SSM_ENDPOINT" == "true" ]]; then
  create_interface_endpoint "ssm" "$SSM_ENDPOINT_NAME"
fi
if [[ "$CREATE_SSMMESSAGES_ENDPOINT" == "true" ]]; then
  create_interface_endpoint "ssmmessages" "$SSMMESSAGES_ENDPOINT_NAME"
fi

if [[ "${#NEW_ENDPOINT_IDS[@]}" -gt 0 ]]; then
  wait_for_vpc_endpoints "${NEW_ENDPOINT_IDS[@]}"
fi
if [[ "${#PENDING_ENDPOINT_IDS[@]}" -gt 0 ]]; then
  wait_for_vpc_endpoints "${PENDING_ENDPOINT_IDS[@]}"
fi

log "apply completed successfully"
log "private app subnets: $SUBNET_A_ID, $SUBNET_B_ID"
log "private app route tables: $ROUTE_TABLE_A_ID, $ROUTE_TABLE_B_ID"
log "regional NAT gateway: $NAT_ID"
log "endpoint security group: $ENDPOINT_SECURITY_GROUP_ID"
