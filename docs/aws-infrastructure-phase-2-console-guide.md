# AWS 인프라 분리 Phase 2 저비용 네트워크·보안 콘솔 따라하기

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 2를 AWS Management Console에서 사용자가 직접 수행하기 위한 절차다.

이번 단계에서는 관리형 서비스를 아직 생성하지 않는다. Private Subnet, 전용 Route Table, Blue·Green 공유 API EC2 보안 그룹 확인, 데이터 서비스 보안 그룹, RDS·ElastiCache Subnet Group까지만 준비한다.

## 0. 확정된 저비용 구성

```text
VPC: buildgraph-demo-vpc
CIDR: 10.0.0.0/16

기존 Public Subnet
├─ ap-northeast-2a: 10.0.0.0/20
│  └─ 현재 API EC2 없음
└─ ap-northeast-2b: 10.0.16.0/20
   ├─ Blue: 기존 통합 EC2
   └─ Green: 새 API EC2, Phase 6에서 생성

새 Private Data Subnet
├─ ap-northeast-2a: 10.0.32.0/24
└─ ap-northeast-2b: 10.0.33.0/24

관리형 서비스의 실제 배치 목표
├─ RDS PostgreSQL: Single-AZ, 2b 우선
├─ ElastiCache Redis: single node, 2b 우선
└─ Amazon MQ RabbitMQ: single instance, 2b
```

VPC 전체 Subnet 수는 `Public 2개 + Private 2개 = 총 4개`다.

비용 절감을 위해 다음 리소스는 생성하지 않는다.

1. 세 번째 `ap-northeast-2c` Private Subnet
2. NAT Gateway
3. RDS Multi-AZ standby
4. ElastiCache replica와 Multi-AZ 자동 장애조치
5. Amazon MQ 3-node Cluster

이 구성은 `ap-northeast-2b` 장애나 관리형 서비스 점검 때 자동 failover가 없어서 API와 데이터 서비스가 함께 중단될 수 있다. 이 위험을 수용하고 비용을 줄이는 구성이다.

## 1. 이번 단계에서 하지 않는 작업

1. 기존 Public Subnet을 수정하거나 삭제하지 않는다.
2. 기존 Public Route Table과 Internet Gateway를 수정하거나 삭제하지 않는다.
3. Blue EC2를 중지하거나 재부팅하지 않는다.
4. 기존 CloudFront Origin을 변경하지 않는다.
5. Blue EC2에 Elastic IP를 연결하지 않는다. Green용 새 Elastic IP는 Phase 6에서 Green EC2에 연결한다.
6. RDS, ElastiCache, Amazon MQ 실제 인스턴스를 아직 만들지 않는다.
7. `compose.prod.yaml` 또는 운영 컨테이너를 변경하지 않는다.
8. Docker Volume을 삭제하지 않는다.
9. NAT Gateway와 VPC Endpoint를 만들지 않는다.
10. Blue의 SSH 22번 규칙을 아직 삭제하지 않는다. 현재 GitHub Actions 배포가 SSH를 사용하므로 Phase 9의 CloudFront 전환과 롤백 대기 뒤 제거한다.
11. Green EC2 자체는 아직 생성하지 않는다. Green은 Phase 6에서 기존 `buildgraph-demo-ec2-sg`를 Blue와 공유한다.

## 2. 작업 전 기준값 확인

| 항목 | 값 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| VPC 이름 | `buildgraph-demo-vpc` |
| VPC ID | `vpc-06c90b864a62f93a4` |
| VPC CIDR | `10.0.0.0/16` |
| Blue 통합 EC2 | `buildgraph-demo-ec2` |
| Blue EC2 ID | `i-082c21a20e14f3295` |
| Blue EC2 AZ | `ap-northeast-2b` |
| Blue·Green 공유 EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` |
| Green API EC2 | Phase 6에서 새로 생성 |
| Green EC2에 연결할 SG | 기존 `buildgraph-demo-ec2-sg` 재사용 |

위 값 중 AWS 콘솔의 값과 다른 항목이 있으면 생성을 시작하지 않고 중단한다.

### 2.1 Blue와 Green의 포트 원칙

Blue와 Green은 서로 다른 EC2이므로 같은 포트를 사용해도 충돌하지 않는다.

| 연결 | Blue | Green |
| --- | ---: | ---: |
| CloudFront → Nginx | 80 | 80 |
| Nginx → Spring Boot 컨테이너 | 8080 | 8080 |
| PostgreSQL | 컨테이너 5432 | RDS 5432 |
| Redis | 컨테이너 6379 | ElastiCache 6379 |
| RabbitMQ | 컨테이너 5672 | Amazon MQ TLS 5671 |

Green API 컨테이너의 `8080`은 Docker 내부 네트워크에서만 사용하고 EC2 보안 그룹이나 호스트 포트로 공개하지 않는다. `18080` 같은 별도 Green 포트는 만들지 않는다.

## 3. 서울 Region과 VPC 확인

1. [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 오른쪽 위 Region을 누른다.
3. `아시아 태평양(서울) ap-northeast-2`를 선택한다.
4. 상단 검색창에서 `VPC`를 검색해 VPC 콘솔을 연다.
5. 왼쪽 메뉴에서 `Your VPCs` 또는 `VPC`를 누른다.
6. `buildgraph-demo-vpc`를 선택한다.
7. VPC ID가 `vpc-06c90b864a62f93a4`인지 확인한다.
8. IPv4 CIDR이 `10.0.0.0/16`인지 확인한다.
9. 다른 VPC를 선택한 상태라면 다음 단계로 넘어가지 않는다.

성공 기준:

```text
Region: ap-northeast-2
VPC: vpc-06c90b864a62f93a4
CIDR: 10.0.0.0/16
```

## 4. 기존 CIDR과 중복되지 않는지 확인

1. VPC 콘솔 왼쪽 메뉴에서 `Subnets`를 누른다.
2. 필터에서 `VPC ID = vpc-06c90b864a62f93a4`를 선택한다.
3. 현재 Subnet이 2개인지 확인한다.
4. 기존 CIDR이 `10.0.0.0/20`, `10.0.16.0/20`인지 확인한다.
5. `10.0.32.0/24` 또는 `10.0.33.0/24`를 사용하는 Subnet이 이미 없는지 확인한다.
6. 중복 CIDR이 있으면 Subnet을 만들지 말고 중단한다.

성공 기준:

```text
10.0.32.0/24: 사용 가능
10.0.33.0/24: 사용 가능
```

## 5. VPC DNS 설정 확인

Amazon MQ와 관리형 서비스 endpoint의 DNS 이름을 EC2에서 해석할 수 있어야 한다.

1. VPC 콘솔 왼쪽 메뉴에서 `Your VPCs`를 누른다.
2. `buildgraph-demo-vpc`를 선택한다.
3. `Actions`를 누른다.
4. 콘솔에 따라 `Edit VPC settings`, `Edit DNS resolution` 또는 `Edit DNS hostnames`를 누른다.
5. `Enable DNS resolution`이 활성화되어 있는지 확인한다.
6. `Enable DNS hostnames`가 활성화되어 있는지 확인한다.
7. 둘 중 비활성화된 항목이 있으면 활성화하고 저장한다.
8. 둘 다 이미 활성화되어 있으면 변경하지 않는다.

성공 기준:

```text
DNS resolution: enabled
DNS hostnames: enabled
```

## 6. 2a Private Data Subnet 생성

1. VPC 콘솔 왼쪽 메뉴에서 `Subnets`를 누른다.
2. `Create subnet`을 누른다.
3. VPC ID에서 `buildgraph-demo-vpc (vpc-06c90b864a62f93a4)`를 선택한다.
4. Subnet name에 다음 값을 입력한다.

```text
buildgraph-demo-subnet-private-data1-ap-northeast-2a
```

5. Availability Zone에서 `ap-northeast-2a`를 직접 선택한다. `No preference`를 선택하지 않는다.
6. IPv4 VPC CIDR block은 `10.0.0.0/16`을 선택한다.
7. IPv4 subnet CIDR block에 `10.0.32.0/24`를 입력한다.
8. IPv6는 추가하지 않는다.
9. 아직 생성 버튼을 누르지 않고 입력값을 다시 확인한다.

확인값:

```text
Name: buildgraph-demo-subnet-private-data1-ap-northeast-2a
AZ: ap-northeast-2a
CIDR: 10.0.32.0/24
```

## 7. 2b Private Data Subnet을 같은 화면에 추가

1. 같은 `Create subnet` 화면에서 `Add new subnet`을 누른다.
2. Subnet name에 다음 값을 입력한다.

```text
buildgraph-demo-subnet-private-data2-ap-northeast-2b
```

3. Availability Zone에서 `ap-northeast-2b`를 직접 선택한다.
4. IPv4 VPC CIDR block은 `10.0.0.0/16`을 선택한다.
5. IPv4 subnet CIDR block에 `10.0.33.0/24`를 입력한다.
6. IPv6는 추가하지 않는다.
7. 두 Subnet의 VPC, AZ, CIDR을 다시 확인한다.
8. `Create subnet`을 누른다.
9. 생성 성공 메시지가 표시되는지 확인한다.
10. 두 Subnet ID를 기록한다.

```text
Private 2a Subnet ID:subnet-09bba1fd17639ce6a
Private 2b Subnet ID:subnet-0816bc2771fd5e1ca
```

중단 조건:

1. CIDR overlap 오류가 발생한다.
2. VPC가 `vpc-06c90b864a62f93a4`가 아니다.
3. 두 Subnet이 같은 AZ에 생성됐다.

## 8. Public IPv4 자동 할당 비활성 확인

새 Private Subnet에는 Public IPv4를 자동 할당하지 않는다.

1. `Subnets` 목록에서 새 2a Private Subnet을 선택한다.
2. `Actions` → `Edit subnet settings`를 누른다.
3. `Enable auto-assign public IPv4 address`가 체크 해제되어 있는지 확인한다.
4. 체크되어 있으면 해제하고 저장한다.
5. 새 2b Private Subnet에도 같은 확인을 반복한다.
6. IPv6 auto-assign도 활성화하지 않는다.

성공 기준:

```text
Private 2a auto-assign public IPv4: disabled
Private 2b auto-assign public IPv4: disabled
```

## 9. Private Data Route Table 생성

기존 Main Route Table을 그대로 사용하지 않고 Private Data 전용 Route Table을 만든다. 이렇게 해야 기존 Public Route가 실수로 상속되는 것을 막을 수 있다.

1. VPC 콘솔 왼쪽 메뉴에서 `Route tables`를 누른다.
2. `Create route table`을 누른다.
3. Name에 다음 값을 입력한다.

```text
buildgraph-demo-rtb-private-data
```

4. VPC에서 `buildgraph-demo-vpc (vpc-06c90b864a62f93a4)`를 선택한다.
5. `Create route table`을 누른다.
6. 생성된 Route Table을 선택한다.
7. Route Table ID를 기록한다.

```text
Private Route Table ID:rtb-084440a81e5721f2f
```

## 10. Private Route가 local 하나뿐인지 확인

1. 생성한 `buildgraph-demo-rtb-private-data`를 선택한다.
2. 아래 `Routes` 탭을 누른다.
3. 다음 route가 자동으로 있는지 확인한다.

```text
Destination: 10.0.0.0/16
Target: local
```

4. `0.0.0.0/0` route를 추가하지 않는다.
5. Internet Gateway 대상 route를 추가하지 않는다.
6. NAT Gateway 대상 route를 추가하지 않는다.
7. 다른 route가 이미 있다면 Subnet을 연결하기 전에 중단한다.

성공 기준:

```text
10.0.0.0/16 → local
0.0.0.0/0 → 없음
```

같은 VPC의 모든 Subnet은 `local` route로 서로 통신할 수 있다. 따라서 Phase 6에서 Public Subnet에 생성할 Green API EC2는 NAT 없이 Private Subnet의 RDS, Redis, RabbitMQ private endpoint에 접근할 수 있다.

## 11. 두 Private Subnet을 Route Table에 연결

1. `buildgraph-demo-rtb-private-data`가 선택된 상태인지 확인한다.
2. `Subnet associations` 탭을 누른다.
3. `Edit subnet associations`를 누른다.
4. 다음 두 Subnet만 선택한다.

```text
buildgraph-demo-subnet-private-data1-ap-northeast-2a
buildgraph-demo-subnet-private-data2-ap-northeast-2b
```

5. 기존 Public Subnet은 선택하지 않는다.
6. `Save associations`를 누른다.
7. Explicit subnet associations에 새 Private Subnet 2개만 표시되는지 확인한다.

## 12. Subnet 최종 확인

1. VPC 콘솔의 `Subnets`로 이동한다.
2. `VPC ID = vpc-06c90b864a62f93a4`로 필터링한다.
3. 총 4개 Subnet이 표시되는지 확인한다.
4. 새 2a Private Subnet의 Route Table이 `buildgraph-demo-rtb-private-data`인지 확인한다.
5. 새 2b Private Subnet도 같은 Route Table인지 확인한다.
6. 두 Private Subnet의 Public IPv4 자동 할당이 비활성인지 확인한다.

성공 기준:

```text
Public Subnet: 2개
Private Subnet: 2개
전체: 4개
Private route: local only
```

## 13. Blue·Green 공유 API EC2 보안 그룹 확인

빠른 병렬 전환을 위해 Green 전용 보안 그룹은 새로 만들지 않는다. Phase 6에서 새 Green EC2에 Blue가 사용하는 기존 보안 그룹을 그대로 연결한다.

1. VPC 또는 EC2 콘솔에서 `Security groups`를 누른다.
2. VPC ID를 `vpc-06c90b864a62f93a4`로 필터링한다.
3. 다음 보안 그룹을 선택한다.

```text
Name: buildgraph-demo-ec2-sg
Security group ID: sg-099aac782b77a854e
```

4. 같은 이름이 다른 VPC에도 있으므로 반드시 SG ID와 VPC ID를 함께 확인한다.
5. 이 SG를 삭제하거나 새 Green 전용 SG로 복제하지 않는다.
6. 기존 HTTP 80과 SSH 22 규칙은 Blue 서비스와 GitHub Actions SSH 배포를 유지하기 위해 이번 Phase에서 변경하지 않는다.
7. Custom TCP 8080, PostgreSQL 5432, Redis 6379, RabbitMQ 5671을 공유 EC2 SG의 Inbound에 추가하지 않는다.
8. Outbound의 기본 `All traffic → 0.0.0.0/0`은 유지한다.

성공 기준:

```text
공유 SG: buildgraph-demo-ec2-sg
공유 SG ID: sg-099aac782b77a854e
VPC: vpc-06c90b864a62f93a4
Green 전용 SG 신규 생성: 없음
```

공유 SG를 사용하면 병렬 운영 동안 Blue와 Green이 같은 네트워크 권한을 가진다. Green API 컨테이너의 `8080`은 계속 Docker 내부에서만 사용한다. SSH 22와 HTTP 80의 추가 제한은 Blue 전환과 기존 SSH 배포 종료 후 수행한다.

## 14. RDS 전용 보안 그룹 생성

1. VPC 또는 EC2 콘솔에서 `Security groups`를 누른다.
2. `Create security group`을 누른다.
3. Security group name에 다음 값을 입력한다.

```text
buildgraph-demo-rds-sg
```

4. Description에 다음 값을 입력한다.

```text
Allow PostgreSQL only from BuildGraph API EC2 SG
```

5. VPC에서 `buildgraph-demo-vpc (vpc-06c90b864a62f93a4)`를 선택한다.
6. Inbound rules에서 `Add rule`을 누른다.
7. Type은 `PostgreSQL`을 선택한다.
8. Protocol은 TCP, Port는 `5432`인지 확인한다.
9. Source는 `Custom`을 선택한다.
10. Source 검색란에 13번에서 확인한 공유 SG ID `sg-099aac782b77a854e`를 입력해 `buildgraph-demo-ec2-sg`를 선택한다.
11. Source를 `0.0.0.0/0`, `10.0.0.0/16`, 현재 IP로 입력하지 않는다.
12. Outbound는 기본 `All traffic → 0.0.0.0/0`을 유지한다.
13. `Create security group`을 누른다.
14. 생성된 RDS SG ID를 기록한다.

```text
RDS SG ID:sg-0587fdbc766f9088f
```

## 15. Redis 전용 보안 그룹 생성

1. `Security groups` → `Create security group`을 누른다.
2. Security group name에 다음 값을 입력한다.

```text
buildgraph-demo-redis-sg
```

3. Description에 다음 값을 입력한다.

```text
Allow Redis only from BuildGraph API EC2 SG
```

4. VPC는 `buildgraph-demo-vpc`를 선택한다.
5. Inbound rule의 Type은 `Custom TCP`를 선택한다.
6. Port range에 `6379`를 입력한다.
7. Source는 `Custom`을 선택한다.
8. Source로 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e`를 선택한다.
9. Outbound는 기본값을 유지한다.
10. `Create security group`을 누른다.
11. 생성된 Redis SG ID를 기록한다.

```text
Redis SG ID:sg-0dc3c8766358e57f4
```

## 16. RabbitMQ 전용 보안 그룹 생성

1. `Security groups` → `Create security group`을 누른다.
2. Security group name에 다음 값을 입력한다.

```text
buildgraph-demo-rabbitmq-sg
```

3. Description에 다음 값을 입력한다.

```text
Allow Amazon MQ AMQPS only from BuildGraph API EC2 SG
```

4. VPC는 `buildgraph-demo-vpc`를 선택한다.
5. Inbound rule의 Type은 `Custom TCP`를 선택한다.
6. Port range에 `5671`을 입력한다.
7. Source는 `Custom`을 선택한다.
8. Source로 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e`를 선택한다.
9. RabbitMQ 관리 UI용 `443`과 `15671`은 추가하지 않는다.
10. 평문 AMQP `5672`도 추가하지 않는다.
11. Outbound는 기본값을 유지한다.
12. `Create security group`을 누른다.
13. 생성된 RabbitMQ SG ID를 기록한다.

```text
RabbitMQ SG ID:sg-0876855a9ac1da572
```

## 17. 공유 SG Source 최종 검증

데이터 서비스 SG의 Source로 기존 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e`를 사용한다. 별도 Green SG로 교체하지 않는다.

1. RDS SG의 Inbound rules를 연다.
2. TCP 5432 Source가 `sg-099aac782b77a854e`인지 확인한다.
3. Redis SG의 TCP 6379 Source도 같은 SG인지 확인한다.
4. RabbitMQ SG의 TCP 5671 Source도 같은 SG인지 확인한다.
5. Source가 IP CIDR이 아니라 Security Group ID로 표시되는지 확인한다.
6. Blue는 기존 Compose 데이터 서비스를 계속 사용하며 `.env.prod`를 변경하지 않는다.
7. Phase 6에서 Green EC2에 같은 공유 SG를 연결하면 별도 데이터 SG 수정 없이 관리형 서비스에 접근할 수 있다.

각 보안 그룹의 Inbound rules를 열고 다음과 정확히 일치하는지 확인한다.

| 보안 그룹 | Protocol | Port | Source |
| --- | --- | ---: | --- |
| `buildgraph-demo-rds-sg` | TCP | 5432 | `sg-099aac782b77a854e` |
| `buildgraph-demo-redis-sg` | TCP | 6379 | `sg-099aac782b77a854e` |
| `buildgraph-demo-rabbitmq-sg` | TCP | 5671 | `sg-099aac782b77a854e` |

다음 규칙이 하나라도 있으면 삭제하거나 Codex에게 확인한 후 진행한다.

```text
5432 from 0.0.0.0/0
6379 from 0.0.0.0/0
5671 or 5672 from 0.0.0.0/0
8080 on buildgraph-demo-ec2-sg
```

보안 그룹을 Source로 참조하면 해당 SG가 연결된 EC2의 private IP에서 들어오는 트래픽만 허용한다.

공유 결정에 따라 `sg-099aac782b77a854e`가 RDS·Redis·RabbitMQ SG의 Source인 상태가 정상이다. 병렬 운영 중에는 Blue도 네트워크상 관리형 서비스에 접근할 수 있지만 Blue 애플리케이션 설정은 기존 Compose endpoint를 유지한다.

## 18. RDS DB Subnet Group 생성

RDS Single-AZ 인스턴스를 만들더라도 DB Subnet Group에는 서로 다른 AZ의 Subnet이 최소 2개 필요하다.

1. 상단 검색창에서 `RDS`를 검색해 RDS 콘솔을 연다.
2. Region이 서울인지 확인한다.
3. 왼쪽 메뉴에서 `Subnet groups`를 누른다.
4. `Create DB subnet group`을 누른다.
5. Name에 다음 값을 입력한다.

```text
buildgraph-demo-rds-subnet-group
```

6. Description에 다음 값을 입력한다.

```text
Private data subnets for BuildGraph RDS
```

7. VPC에서 `buildgraph-demo-vpc`를 선택한다.
8. Availability Zones에서 `ap-northeast-2a`, `ap-northeast-2b`를 선택한다.
9. Subnets에서 다음 CIDR의 Private Subnet만 선택한다.

```text
10.0.32.0/24
10.0.33.0/24
```

10. 기존 Public Subnet `10.0.0.0/20`, `10.0.16.0/20`은 선택하지 않는다.
11. `Create`를 누른다.
12. 상태가 `Complete`인지 확인한다.

성공 기준:

```text
DB Subnet Group: buildgraph-demo-rds-subnet-group
AZ count: 2
Subnet count: 2
Public subnet 포함: 없음
```

## 19. ElastiCache Subnet Group 생성

1. 상단 검색창에서 `ElastiCache`를 검색해 콘솔을 연다.
2. Region이 서울인지 확인한다.
3. 왼쪽 메뉴에서 `Subnet groups`를 누른다. 콘솔 버전에 따라 `Network & security` 아래에 있을 수 있다.
4. `Create subnet group`을 누른다.
5. Name에 다음 값을 입력한다.

```text
buildgraph-demo-redis-subnet-group
```

6. Description에 다음 값을 입력한다.

```text
Private data subnets for BuildGraph Redis
```

7. VPC에서 `buildgraph-demo-vpc`를 선택한다.
8. 다음 Private Subnet 2개만 선택한다.

```text
10.0.32.0/24 (ap-northeast-2a)
10.0.33.0/24 (ap-northeast-2b)
```

9. `Create`를 누른다.
10. 생성된 Subnet Group에 두 Private Subnet만 포함됐는지 확인한다.

Amazon MQ에는 별도 Subnet Group을 만들지 않는다. Phase 5에서 Single-instance broker 생성 시 `10.0.33.0/24 (ap-northeast-2b)` Subnet 하나를 직접 선택한다.

## 20. Blue Session Manager 준비

Blue의 SSH 22번을 바로 닫으면 현재 GitHub Actions의 SSH 배포가 중단된다. 이번 Phase에서는 Blue의 Session Manager 접속 경로만 먼저 준비하고, SSH 규칙 제거는 Phase 9의 CloudFront 전환과 롤백 대기 뒤 수행한다. Green은 Phase 6에서 별도 IAM Role을 만들지만 기존 SG를 공유하므로 22 규칙을 상속하며, 실제 Green 접속과 배포에는 SSM을 사용한다.

### 20.1 Blue EC2 IAM Role 생성

1. 상단 검색창에서 `IAM`을 검색한다.
2. 왼쪽 메뉴에서 `Roles`를 누른다.
3. `Create role`을 누른다.
4. Trusted entity type은 `AWS service`를 선택한다.
5. Use case는 `EC2`를 선택한다.
6. `Next`를 누른다.
7. 권한 검색창에 `AmazonSSMManagedInstanceCore`를 입력한다.
8. `AmazonSSMManagedInstanceCore`만 선택한다.
9. 이번 단계에서는 `SecretsManagerReadWrite` 같은 광범위한 정책을 추가하지 않는다.
10. `Next`를 누른다.
11. Role name에 다음 값을 입력한다.

```text
buildgraph-demo-ec2-role
```

12. Trust policy가 `ec2.amazonaws.com`을 신뢰하는지 확인한다.
13. `Create role`을 누른다.

### 20.2 실행 중인 Blue EC2에 Role 연결

1. EC2 콘솔을 연다.
2. `Instances`에서 `buildgraph-demo-ec2`를 선택한다.
3. `Actions` → `Security` → `Modify IAM role`을 누른다.
4. IAM role에서 `buildgraph-demo-ec2-role`을 선택한다.
5. `Update IAM role`을 누른다.
6. 인스턴스를 중지하거나 재부팅하지 않는다.

기존 IAM Role이 이미 연결되어 있다면 바로 교체하지 말고 중단한다. EC2에는 한 번에 IAM Role 하나만 연결할 수 있으므로 기존 권한을 새 Role에 병합해야 한다.

### 20.3 Blue Ubuntu에서 SSM Agent 상태 확인

현재 접속 가능한 EC2 터미널에서 다음 명령을 실행한다.

```bash
sudo snap list amazon-ssm-agent
sudo snap services amazon-ssm-agent
```

설치되어 있지만 중지 상태라면 다음 명령을 실행한다.

```bash
sudo snap start amazon-ssm-agent
```

`snap list`에서 설치되지 않았다고 나올 때만 다음 명령을 실행한다.

```bash
sudo snap install amazon-ssm-agent --classic
sudo snap start amazon-ssm-agent
```

### 20.4 Blue Session Manager 접속 확인

1. AWS Systems Manager 콘솔을 연다.
2. 왼쪽 메뉴에서 `Session Manager`를 누른다.
3. `Start session`을 누른다.
4. Target instances에 `buildgraph-demo-ec2`가 표시되는지 확인한다.
5. 인스턴스를 선택하고 `Start session`을 누른다.
6. 터미널이 열리면 다음 명령을 실행한다.

```bash
whoami
hostname
```

7. 명령이 실행되면 세션을 종료한다.
8. 접속에 성공해도 Blue의 기존 SSH 22번 규칙은 이번 단계에서 삭제하지 않는다.

## 21. Blue의 현재 SSH 규칙 확인만 수행

1. EC2 콘솔에서 `buildgraph-demo-ec2`를 선택한다.
2. Security 탭에서 `sg-099aac782b77a854e`를 누른다.
3. Inbound rules에서 SSH 22번 Source를 확인한다.
4. `0.0.0.0/0`이면 다음과 같이 기록한다.

```text
SSH 22: 0.0.0.0/0 — Phase 9 CloudFront 전환과 롤백 대기 후 제거 예정
```

5. 지금 삭제하면 GitHub Actions 배포가 실패할 수 있으므로 변경하지 않는다.
6. 실제 개인 키가 노출된 경우에는 이 문서와 별개로 즉시 키 교체를 수행한다.

## 22. Phase 2 결과 기록

다음 형식으로 실제 생성 결과를 기록한다. 비밀번호, endpoint 자격정보, Secret 값은 기록하지 않는다.

```text
Private 2a Subnet ID: subnet-09bba1fd17639ce6a
Private 2b Subnet ID: subnet-0816bc2771fd5e1ca
Private Route Table ID: rtb-084440a81e5721f2f

Blue·Green 공유 API EC2 SG ID: sg-099aac782b77a854e
RDS SG ID: sg-0587fdbc766f9088f
Redis SG ID: sg-0dc3c8766358e57f4
RabbitMQ SG ID: sg-0876855a9ac1da572

RDS DB Subnet Group: buildgraph-demo-rds-subnet-group
ElastiCache Subnet Group: buildgraph-demo-redis-subnet-group

VPC DNS resolution: enabled | disabled
VPC DNS hostnames: enabled | disabled
SSM IAM Role: buildgraph-demo-ec2-role
Session Manager 접속: 성공 | 실패
SSH 22 현재 Source:
```

## 23. Phase 2 완료 조건

- [ ] `10.0.32.0/24` Private Subnet이 `ap-northeast-2a`에 존재
- [ ] `10.0.33.0/24` Private Subnet이 `ap-northeast-2b`에 존재
- [ ] VPC 전체 Subnet 수가 4개
- [ ] 두 Private Subnet의 Public IPv4 자동 할당 비활성
- [ ] 두 Private Subnet이 Private Data Route Table에 연결
- [ ] Private Route Table에는 `10.0.0.0/16 → local`만 존재
- [ ] NAT Gateway를 생성하지 않음
- [ ] 공유 API EC2 SG가 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e`
- [ ] Green 전용 SG를 별도로 생성하지 않음
- [ ] 공유 API EC2 SG에 외부 공개 8080이 없음
- [ ] RDS SG 5432 Source가 공유 SG `sg-099aac782b77a854e`
- [ ] Redis SG 6379 Source가 공유 SG `sg-099aac782b77a854e`
- [ ] RabbitMQ SG 5671 Source가 공유 SG `sg-099aac782b77a854e`
- [ ] 공유 SG의 기존 SSH 22 규칙을 Blue SSH 배포 종료 전까지 유지
- [ ] RDS DB Subnet Group에 2a·2b Private Subnet 포함
- [ ] ElastiCache Subnet Group에 2a·2b Private Subnet 포함
- [ ] VPC DNS resolution과 DNS hostnames 활성
- [ ] Session Manager 접속 성공
- [ ] Blue EC2는 IAM Role·SSM Agent 외에 인스턴스 상태와 애플리케이션 변경 없음
- [ ] CloudFront, Compose, 실행 중인 컨테이너에 변경 없음

위 조건을 확인한 뒤 Phase 3에서 RDS PostgreSQL Single-AZ 인스턴스를 생성한다.

## 공식 참고 문서

- [AWS RDS DB Subnet Group 요구사항](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_CreateDBSubnetGroup.html)
- [AWS VPC Subnet 생성](https://docs.aws.amazon.com/vpc/latest/userguide/create-subnets.html)
- [AWS VPC Route Table 생성과 Subnet 연결](https://docs.aws.amazon.com/vpc/latest/userguide/create-vpc-route-table.html)
- [AWS VPC Security Group 참조 규칙](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)
- [AWS-managed CloudFront origin-facing prefix list](https://docs.aws.amazon.com/vpc/latest/userguide/working-with-aws-managed-prefix-lists.html)
- [Amazon ElastiCache Subnet Group 생성](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SubnetGroups.Creating.html)
- [Private Amazon MQ Broker 구성](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/configuring-private-broker.html)
- [Amazon MQ RabbitMQ Listener Port](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/working-with-rabbitmq.html)
- [AWS VPC DNS 속성](https://docs.aws.amazon.com/vpc/latest/userguide/AmazonDNS-concepts.html)
- [Systems Manager EC2 권한 구성](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html)
- [Session Manager: 인바운드 포트 없는 EC2 접속](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
- [Ubuntu SSM Agent 설치](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu-64-snap.html)
