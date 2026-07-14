# AWS 인프라 분리 Phase 6 Green API EC2·배포 따라하기

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 6을 수행하기 위한 실행 가이드다.

Phase 6에서는 기존 통합 서버를 `Blue`로 계속 운영한 채 새 API 전용 EC2를 `Green`으로 병렬 생성한다. Green에는 `nginx`, `api`, `xgb-reranker`만 실행하고 RDS, ElastiCache Redis, Amazon MQ RabbitMQ에 연결한다. 이번 Phase에서는 CloudFront Origin을 바꾸지 않는다.

AWS Management Console 조작은 사용자가 직접 수행한다. Codex는 콘솔에 로그인하거나 리소스를 생성하지 않고, 저장소 구현·검증과 사용자가 제공한 화면 확인만 지원한다.

## 0. Phase 6의 두 작업 구분

Phase 6은 다음 두 작업을 모두 포함한다.

| 구분 | 담당 | 작업 |
| --- | --- | --- |
| 저장소 구현 | Codex 지원 + 사용자 확인 | Spring 관리형 서비스 설정, `compose.api.prod.yaml`, API 전용 Nginx 설정, 테스트 |
| AWS와 Green 서버 작업 | 사용자 | Secret, IAM Role, EC2, Elastic IP, SSM, Docker, Green 배포와 스모크 테스트 |

저장소 구현 검증이 통과하기 전에는 Green EC2에서 `docker compose up`을 실행하지 않는다. EC2·IAM·SSM 준비까지는 먼저 진행할 수 있지만, 아래 **12번 구현 게이트**를 통과하지 않은 코드를 배포하면 안 된다.

## 1. 이번 Phase의 확정값

| 항목 | 최종값 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| Green EC2 이름 | `buildgraph-demo-api-green-ec2` |
| AMI | Canonical Ubuntu Server `24.04 LTS`, `x86_64` |
| Instance type | `t3.medium` |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Subnet | 기존 Public 2b / `10.0.16.0/20` / `ap-northeast-2b` |
| EC2 Security group | 기존 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` 재사용 |
| IAM Role | 신규 `buildgraph-demo-api-green-role` |
| 접속 방식 | SSM Session Manager만 사용 |
| Key pair | 없이 진행 |
| Public address | 신규 Elastic IP 한 개를 Green에 연결 |
| Root EBS | `gp3`, `30 GiB`, 암호화, 종료 시 삭제 |
| Secret 이름 | `buildgraph/demo-green/api-env` |
| Compose 파일 | `compose.api.prod.yaml` |
| Compose project | `buildgraph-green` |
| 실행 서비스 | `nginx`, `api`, `xgb-reranker`만 |
| 외부 공개 포트 | Nginx TCP `80`만 |
| API 포트 | Docker 내부 `8080`, EC2 호스트에 공개하지 않음 |
| 첫 Green 배포 이미지 | 검증된 Git SHA를 Green에서 source build |
| 최종 이미지 배포 | Phase 8에서 ECR의 Git SHA 태그 이미지로 전환 |
| CloudFront | 이번 Phase에서는 기존 Blue Origin 유지 |

Public 2b의 Subnet ID는 아직 이 문서에 확정 기록되지 않았다. EC2 생성 화면에서 다음 세 값을 함께 확인하고 실제 Subnet ID를 **30번 기록표**에 적는다.

```text
Availability Zone: ap-northeast-2b
IPv4 CIDR: 10.0.16.0/20
Route: 0.0.0.0/0 → buildgraph-demo-igw
```

## 2. 이번 단계에서 하지 않는 작업

1. 기존 `buildgraph-demo-ec2` Blue를 중지·재부팅·종료하지 않는다.
2. Blue의 `compose.prod.yaml`, `.env.prod`, Volume을 변경하지 않는다.
3. Blue에서 `docker compose down` 또는 `docker compose down -v`를 실행하지 않는다.
4. 기존 CloudFront Distribution이나 Origin을 변경하지 않는다.
5. 기존 Blue Public IP `15.164.235.183`을 Green으로 옮기지 않는다.
6. 공유 SG의 기존 SSH `22` 규칙을 이번 Phase에서 삭제하지 않는다.
7. Green용 SSH key pair나 GitHub Actions SSH 배포를 새로 만들지 않는다.
8. API 컨테이너 `8080`을 EC2 host 또는 SG에 공개하지 않는다.
9. RDS `5432`, Redis `6379`, RabbitMQ `5671`을 인터넷에 공개하지 않는다.
10. RDS·Redis·RabbitMQ endpoint와 비밀번호를 Blue `.env.prod`에 넣지 않는다.
11. 기존 PostgreSQL, Redis, RabbitMQ, XGB 모델, PC Agent 로그 데이터를 이전하지 않는다.
12. Secret 값을 문서, Git, 채팅, 터미널 캡처에 남기지 않는다.
13. `docker compose config` 전체 출력은 복사하지 않는다. 렌더링 결과에 비밀값이 포함될 수 있다.
14. `latest` branch 상태를 그대로 배포하지 않는다. 검증한 Git SHA를 고정한다.
15. Phase 7 전에는 사용자의 실제 트래픽을 Green으로 보내지 않는다.

## 3. 시작 전 완료 조건

다음 조건을 모두 확인한다.

1. RDS `buildgraph-demo-postgres-green` 상태가 `Available`이다.
2. RDS가 `ap-northeast-2b`, Private access, `db.t4g.small`이다.
3. RDS SG가 `sg-0587fdbc766f9088f`이다.
4. RDS SG TCP `5432` Source가 `sg-099aac782b77a854e`이다.
5. ElastiCache `buildgraph-demo-redis-green` 상태가 `Available`이다.
6. Redis가 `cache.t4g.small`, node 1개, replica 0개이다.
7. Redis TLS가 Required이고 AUTH token이 활성화되어 있다.
8. Redis SG가 `sg-0dc3c8766358e57f4`이다.
9. Redis SG TCP `6379` Source가 `sg-099aac782b77a854e`이다.
10. Amazon MQ `buildgraph-demo-rabbitmq-green` 상태가 `Running`이다.
11. RabbitMQ가 Private single-instance, `mq.m7g.medium`, `ap-northeast-2b`이다.
12. RabbitMQ SG가 `sg-0876855a9ac1da572`이다.
13. RabbitMQ SG TCP `5671` Source가 `sg-099aac782b77a854e`이다.
14. Blue EC2와 현재 Compose가 정상 실행 중이다.
15. 기존 CloudFront가 계속 Blue를 Origin으로 사용하고 있다.
16. RDS 비밀번호, Redis AUTH token, RabbitMQ 비밀번호가 개인 비밀번호 관리자에 저장되어 있다.

하나라도 충족되지 않으면 Green EC2 배포 전에 해당 Phase를 먼저 교정한다.

## 4. 관리형 endpoint 기록

비밀번호가 아닌 endpoint만 기록한다. 스크린샷에 password가 포함되지 않도록 한다.

### 4.1 RDS

1. RDS 콘솔에서 `buildgraph-demo-postgres-green`을 연다.
2. `Connectivity & security`를 연다.
3. Endpoint를 복사한다.
4. Port가 `5432`인지 확인한다.
5. Database name은 `buildgraph`, username은 `buildgraph`를 사용한다.

현재 확인된 endpoint는 다음과 같다.

```text
buildgraph-demo-postgres-green.cdcw2ykmk609.ap-northeast-2.rds.amazonaws.com
```

### 4.2 Redis

1. ElastiCache 콘솔에서 `buildgraph-demo-redis-green`을 연다.
2. `Primary endpoint`의 hostname만 복사한다.
3. endpoint 뒤에 표시되는 port가 `6379`인지 확인한다.
4. `rediss://` 같은 scheme이나 `:6379`는 hostname 변수에 넣지 않는다.

### 4.3 RabbitMQ

1. Amazon MQ 콘솔에서 `buildgraph-demo-rabbitmq-green`을 연다.
2. `Connections`에서 AMQPS endpoint를 찾는다.
3. 다음 형식인지 확인한다.

```text
amqps://<broker-hostname>:5671
```

4. Spring의 `SPRING_RABBITMQ_ADDRESSES`에는 scheme을 제거한 다음 형식만 사용한다.

```text
<broker-hostname>:5671
```

5. RabbitMQ Web console URL을 application address로 사용하지 않는다.

## 5. 저장소 구현 게이트 준비

현재 Phase 1 테스트는 목표 구성을 정의하지만, 실제 `compose.api.prod.yaml`과 `infra/nginx/api.conf`가 아직 없으므로 검증기는 실패하는 것이 정상이다.

사용자의 로컬 프로젝트에서 다음 명령을 실행한다.

```bash
cd /Users/juhoseok/Desktop/prototype
python3 -m unittest tools.test_validate_infrastructure_separation
python3 tools/validate_infrastructure_separation.py
```

예상 결과:

```text
단위 테스트: PASS
저장소 검증: FAIL
```

이 실패는 Phase 6 구현 전 RED 기준선이다. 구현 전인데 검증이 PASS라고 임의 기록하지 않는다.

## 6. Secrets Manager Secret 생성

Green은 EC2 안에 비밀값을 직접 작성하지 않고 Secrets Manager의 한 Secret에서 `.env.prod` 원문을 내려받는다.

### 6.1 Secret 생성 시작

1. AWS Management Console에서 Region을 `아시아 태평양(서울) ap-northeast-2`로 선택한다.
2. `Secrets Manager`를 검색해 연다.
3. 왼쪽 메뉴에서 `Secrets`를 누른다.
4. `Store a new secret`을 누른다.
5. Secret type에서 `Other type of secret`을 선택한다.
6. `Plaintext` 탭을 선택한다.
7. Encryption key는 기본 `aws/secretsmanager`를 유지한다.

### 6.2 Secret 값 작성

아래는 **형식 예시**다. `<...>`를 그대로 저장하지 말고 실제 값으로 바꾼다. 비밀값은 채팅으로 보내지 않는다.

```dotenv
COMPOSE_PROJECT_NAME=buildgraph-green
SPRING_DATASOURCE_URL=jdbc:postgresql://buildgraph-demo-postgres-green.cdcw2ykmk609.ap-northeast-2.rds.amazonaws.com:5432/buildgraph
SPRING_DATASOURCE_USERNAME=buildgraph
SPRING_DATASOURCE_PASSWORD='<RDS 실제 비밀번호>'

SPRING_DATA_REDIS_HOST=<Redis Primary endpoint의 hostname>
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_USERNAME=
SPRING_DATA_REDIS_PASSWORD='<Redis 실제 AUTH token>'
SPRING_DATA_REDIS_SSL_ENABLED=true

SPRING_RABBITMQ_ADDRESSES=<RabbitMQ hostname>:5671
SPRING_RABBITMQ_USERNAME=buildgraph
SPRING_RABBITMQ_PASSWORD='<RabbitMQ 실제 비밀번호>'
SPRING_RABBITMQ_VIRTUAL_HOST=/
SPRING_RABBITMQ_SSL_ENABLED=true

RECOMMENDATION_DB_HOST=buildgraph-demo-postgres-green.cdcw2ykmk609.ap-northeast-2.rds.amazonaws.com
RECOMMENDATION_DB_PORT=5432
RECOMMENDATION_DB_NAME=buildgraph
RECOMMENDATION_DB_USER=buildgraph
RECOMMENDATION_DB_PASSWORD='<RDS 실제 비밀번호>'

BUILDGRAPH_AUTH_JWT_SECRET='<기존 Blue와 같은 JWT secret>'
BUILDGRAPH_CORS_ALLOWED_ORIGINS=<현재 CloudFront의 https origin>
GOOGLE_OAUTH_CLIENT_ID='<기존 값 또는 빈값>'
GOOGLE_OAUTH_CLIENT_SECRET='<기존 값 또는 빈값>'
GOOGLE_OAUTH_REDIRECT_URI=<현재 CloudFront origin>/api/auth/google/callback
GOOGLE_OAUTH_WEB_CALLBACK_URL=<현재 CloudFront origin>/auth/callback

OPENAI_API_KEY='<기존 값 또는 빈값>'
NAVER_SEARCH_CLIENT_ID='<기존 값 또는 빈값>'
NAVER_SEARCH_CLIENT_SECRET='<기존 값 또는 빈값>'
AGENT_DEMO_ACTIVATION_TOKEN=
PART_MANUFACTURER_RELEASE_DEMO_FEED_ENABLED=false
RAG_EMBEDDING_BACKFILL_ON_STARTUP=false
RECOMMENDATION_RERANKER_ENABLED=false
RECOMMENDATION_TRAINING_WORKER_ENABLED=false
```

작성 규칙:

1. `<`, `>`를 포함한 예시 문구를 실제 Secret에 남기지 않는다.
2. password와 token은 한 줄 값으로 넣는다.
3. password와 token은 작은따옴표로 감싸 `$`, `#`가 Compose 보간이나 주석으로 해석되지 않게 한다.
4. 실제 password 안에 작은따옴표 `'`가 있다면 직접 escape하지 말고 비밀번호 관리자에서 작은따옴표가 없는 값으로 교체한다.
5. RDS password는 datasource와 XGB DB password에 같은 실제 값을 넣는다.
6. Redis hostname에는 `:6379`를 중복으로 붙이지 않는다.
7. RabbitMQ address에는 `amqps://`를 넣지 않는다.
8. `BUILDGRAPH_AUTH_JWT_SECRET`은 기존 로그인 토큰 연속성을 위해 Blue와 같은 값을 유지한다.
9. `BUILDGRAPH_CORS_ALLOWED_ORIGINS`은 현재 CloudFront HTTPS origin과 정확히 일치시킨다.
10. 저장소 예시의 현재 CloudFront 값은 `https://d1a7gxvxxd385i.cloudfront.net`이다. 실제 Distribution domain이 다르면 실제 값을 사용하고 임의로 추정하지 않는다.
11. `AGENT_DEMO_ACTIVATION_TOKEN`은 빈값으로 둔다.
12. 기존 XGB 모델을 보존하지 않으므로 첫 배포는 `RECOMMENDATION_RERANKER_ENABLED=false`로 둔다.
13. Google, OpenAI, Naver 값을 사용 중이면 기존 값을 보존한다. 사용하지 않으면 빈값으로 둔다.
14. 운영에 필요한 다른 비민감 설정은 저장소의 `.env.prod.example` 기본값을 사용하고, 기존 Blue에서 명시적으로 변경한 값만 추가한다.

Mailpit은 Green Compose에서 제거된다. 실제 SMTP 서비스가 확정되지 않았다면 `SPRING_MAIL_*`를 임의로 만들지 않는다. 이 경우 이메일 발송 기능은 Phase 6 완료 기준에서 제외하고 별도 SMTP 결정 후 검증한다.

### 6.3 Secret 이름과 생성

1. `Next`를 누른다.
2. Secret name에 다음을 입력한다.

```text
buildgraph/demo-green/api-env
```

3. Description에 다음을 입력한다.

```text
BuildGraph Green API production dotenv
```

4. Automatic rotation은 이번 Phase에서 비활성으로 둔다.
5. Review에서 Secret name을 다시 확인한다.
6. 실제 값이 보이는 Review 화면은 캡처하지 않는다.
7. `Store`를 누른다.
8. 생성된 Secret을 열어 ARN을 복사한다.
9. ARN만 30번 기록표에 기록하고 Secret value는 기록하지 않는다.

## 7. Green 전용 IAM Role 생성

### 7.1 Role 생성

1. IAM 콘솔을 연다.
2. 왼쪽 메뉴에서 `Roles`를 누른다.
3. `Create role`을 누른다.
4. Trusted entity type은 `AWS service`를 선택한다.
5. Use case는 `EC2`를 선택한다.
6. `Next`를 누른다.
7. 다음 AWS managed policy 세 개를 검색해 체크한다.

```text
AmazonSSMManagedInstanceCore
CloudWatchAgentServerPolicy
AmazonEC2ContainerRegistryReadOnly
```

8. `Next`를 누른다.
9. Role name에 다음을 입력한다.

```text
buildgraph-demo-api-green-role
```

10. Description에 다음을 입력한다.

```text
BuildGraph Green API EC2 role for SSM, logs, ECR pull, and one Secret
```

11. Trusted entity가 `ec2.amazonaws.com`인지 확인한다.
12. `Create role`을 누른다.

### 7.2 지정 Secret 읽기 Inline policy 추가

1. 생성한 `buildgraph-demo-api-green-role`을 연다.
2. `Add permissions`를 누른다.
3. `Create inline policy`를 누른다.
4. `JSON` 탭을 선택한다.
5. 아래 JSON의 `<실제 Secret ARN>`을 6번에서 복사한 ARN으로 바꾼다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:DescribeSecret",
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "<실제 Secret ARN>"
    }
  ]
}
```

6. `<실제 Secret ARN>` 문구가 남아 있지 않은지 확인한다.
7. Resource를 `*`로 바꾸지 않는다.
8. `secretsmanager:*` 권한을 부여하지 않는다.
9. `Next`를 누른다.
10. Policy name에 다음을 입력한다.

```text
BuildGraphGreenApiSecretRead
```

11. `Create policy`를 누른다.
12. Role의 Permissions 목록에 managed policy 3개와 inline policy 1개가 있는지 확인한다.

## 8. Public 2b Subnet 확인

1. VPC 콘솔에서 `Subnets`를 연다.
2. VPC ID `vpc-06c90b864a62f93a4`로 필터한다.
3. Availability Zone이 `ap-northeast-2b`인 기존 Public Subnet을 선택한다.
4. IPv4 CIDR이 `10.0.16.0/20`인지 확인한다.
5. Route table 탭을 연다.
6. 다음 route가 있는지 확인한다.

```text
10.0.0.0/16 → local
0.0.0.0/0 → buildgraph-demo-igw
```

7. Subnet ID를 복사해 30번 기록표에 적는다.
8. Private 2b `subnet-0816bc2771fd5e1ca`를 EC2용 Subnet으로 선택하지 않는다.

## 9. Green EC2 생성

### 9.1 Launch instance 시작

1. EC2 콘솔을 연다.
2. Region이 서울인지 확인한다.
3. 왼쪽 메뉴에서 `Instances`를 누른다.
4. `Launch instances`를 누른다.
5. Name에 다음을 입력한다.

```text
buildgraph-demo-api-green-ec2
```

### 9.2 AMI 선택

1. Quick Start에서 `Ubuntu`를 선택한다.
2. `Ubuntu Server 24.04 LTS`를 선택한다.
3. Architecture가 `64-bit (x86)` 또는 `x86_64`인지 확인한다.
4. Publisher/Owner가 Canonical의 공식 이미지인지 확인한다.
5. ARM64 이미지를 선택하지 않는다. `t3.medium`은 x86_64 구성으로 진행한다.

### 9.3 Instance type과 Key pair

1. Instance type에서 `t3.medium`을 선택한다.
2. Key pair에서 `Proceed without a key pair`를 선택한다.
3. 새 key pair를 만들지 않는다.
4. 경고가 표시되면 SSM 접속용 IAM Role을 연결할 예정임을 확인하고 진행한다.

### 9.4 Network settings

1. `Edit`를 누른다.
2. VPC에서 다음 값을 선택한다.

```text
buildgraph-demo-vpc (vpc-06c90b864a62f93a4)
```

3. Subnet에서 8번에서 확인한 Public 2b Subnet을 선택한다.
4. AZ가 `ap-northeast-2b`인지 확인한다.
5. CIDR이 `10.0.16.0/20`인지 확인한다.
6. Auto-assign public IP는 `Disable`을 선택한다.
7. Elastic IP는 EC2 생성 직후 별도로 연결한다.
8. Firewall에서 `Select existing security group`을 선택한다.
9. 다음 SG 하나를 선택한다.

```text
buildgraph-demo-ec2-sg (sg-099aac782b77a854e)
```

10. default SG나 `launch-wizard-*` SG가 함께 선택되어 있으면 제거한다.
11. 새 SG를 만들지 않는다.
12. SSH 22 체크박스를 새로 추가하지 않는다.
13. HTTP 80은 공유 SG의 기존 규칙을 사용한다.

### 9.5 Storage

1. Root volume type은 `gp3`를 선택한다.
2. Size는 `30 GiB`로 설정한다.
3. Encryption을 활성화한다.
4. 기본 AWS managed EBS key를 사용한다.
5. `Delete on termination`을 활성화한다.
6. 추가 EBS Volume을 만들지 않는다.

### 9.6 Advanced details

1. `Advanced details`를 펼친다.
2. IAM instance profile에서 다음 Role을 선택한다.

```text
buildgraph-demo-api-green-role
```

3. Shutdown behavior는 `Stop`을 유지한다.
4. Termination protection을 `Enable`로 둔다.
5. Detailed CloudWatch monitoring은 이번 저비용 단계에서 비활성으로 둔다.
6. Metadata version은 `V2 only (token required)`를 선택한다.
7. User data는 비워 둔다.
8. 자동 설치 스크립트에 password나 token을 넣지 않는다.

### 9.7 Tags와 생성

Name 외에 tag 입력란이 있으면 다음 값을 추가한다.

| Key | Value |
| --- | --- |
| `Environment` | `green` |
| `Role` | `api` |
| `Project` | `buildgraph-demo` |

1. Summary에서 `t3.medium`, `30 GiB gp3`, 올바른 VPC·Subnet·SG·IAM Role을 확인한다.
2. Instance 수가 `1`인지 확인한다.
3. `Launch instance`를 누른다.
4. 생성된 Instance ID를 복사해 30번 기록표에 적는다.
5. Instance state가 `Running`이 될 때까지 기다린다.
6. Status checks가 `2/2 checks passed`가 될 때까지 기다린다.

## 10. Green 전용 Elastic IP 생성·연결

Elastic IP 연결 전에는 Green의 인터넷·AWS public endpoint 통신이 되지 않을 수 있다.

1. EC2 콘솔 왼쪽 메뉴에서 `Elastic IP addresses`를 누른다.
2. `Allocate Elastic IP address`를 누른다.
3. Network border group이 `ap-northeast-2`인지 확인한다.
4. Public IPv4 address pool은 `Amazon's pool of IPv4 addresses`를 선택한다.
5. Tag를 다음과 같이 입력한다.

| Key | Value |
| --- | --- |
| `Name` | `buildgraph-demo-api-green-eip` |
| `Environment` | `green` |

6. `Allocate`를 누른다.
7. 생성된 Elastic IP를 선택한다.
8. `Actions` → `Associate Elastic IP address`를 누른다.
9. Resource type은 `Instance`를 선택한다.
10. Instance에서 `buildgraph-demo-api-green-ec2`를 선택한다.
11. Private IP는 Green 인스턴스의 기본 private IP를 선택한다.
12. Reassociation 경고가 있으면 다른 리소스에 연결된 EIP를 선택한 것이므로 중단한다.
13. `Associate`를 누른다.
14. Green EC2 상세 화면에서 Public IPv4가 새 EIP인지 확인한다.
15. 새 IP가 Blue IP `15.164.235.183`과 다른지 확인한다.
16. Allocation ID, Green EIP, Public IPv4 DNS를 30번 기록표에 적는다.

## 11. SSM Session Manager 접속 확인

### 11.1 Target 표시 확인

1. Systems Manager 콘솔을 연다.
2. 왼쪽 메뉴에서 `Session Manager`를 누른다.
3. `Start session`을 누른다.
4. `buildgraph-demo-api-green-ec2`가 Target instances에 표시되는지 확인한다.
5. 생성 직후에는 등록까지 몇 분 걸릴 수 있으므로 새로고침한다.
6. Green을 선택하고 `Start session`을 누른다.

### 11.2 기본 명령 확인

터미널이 열리면 다음을 실행한다.

```bash
whoami
hostname
sudo snap services amazon-ssm-agent
```

확인 기준:

1. `whoami`는 `ssm-user` 또는 Session Manager가 구성한 사용자일 수 있다.
2. hostname이 Green 인스턴스인지 확인한다.
3. SSM Agent의 Startup이 `enabled`, Current가 `active`인지 확인한다.
4. 접속에 성공해도 공유 SG의 SSH 22 규칙은 이번 Phase에서 삭제하지 않는다.

### 11.3 Target에 보이지 않을 때

다음을 순서대로 확인한다.

1. Green IAM Role이 `buildgraph-demo-api-green-role`인지 확인한다.
2. Role에 `AmazonSSMManagedInstanceCore`가 연결됐는지 확인한다.
3. Instance state가 Running이고 status check가 2/2인지 확인한다.
4. Elastic IP가 Green에 연결됐는지 확인한다.
5. Green Subnet route에 Internet Gateway를 향한 `0.0.0.0/0`가 있는지 확인한다.
6. 공유 SG outbound가 모든 트래픽 허용인지 확인한다.
7. EC2 콘솔의 `Connect` → `SSM Session Manager`에서 최신 error를 확인한다.
8. IAM Role을 생성 후 나중에 연결했다면 5분 정도 기다린 뒤 새로고침한다.
9. SSH 22를 더 열어서 해결하려고 하지 않는다.

## 12. 저장소 구현 게이트

이 지점에서 Green EC2는 준비됐지만 아직 API를 올리지 않는다. Codex에게 다음 범위의 저장소 구현을 요청한다.

```text
Phase 6 저장소 구현 진행:
- Phase 1 테스트를 기준으로 Spring Redis/RabbitMQ TLS·인증 환경변수 연결
- compose.api.prod.yaml 추가
- infra/nginx/api.conf 추가
- nginx/api/xgb-reranker만 남기기
- API 8080 host 공개 금지
- 기존 compose.prod.yaml과 Blue 배포 유지
- 테스트와 compose config 검증
```

구현 완료 후 로컬에서 다음을 실행한다.

```bash
cd /Users/juhoseok/Desktop/prototype
python3 -m unittest tools.test_validate_infrastructure_separation
python3 tools/validate_infrastructure_separation.py
cd apps/api
./gradlew test --no-daemon
cd ../..
docker compose -f compose.api.prod.yaml --env-file .env.prod.example config --quiet
docker compose -f compose.api.prod.yaml --env-file .env.prod.example config --services
```

통과 기준:

1. Infrastructure validator 단위 테스트가 PASS다.
2. `validate_infrastructure_separation.py`가 PASS다.
3. API test가 PASS다.
4. Compose config가 오류 없이 렌더링된다.
5. service 목록이 다음 세 개뿐이다.

```text
nginx
api
xgb-reranker
```

6. `web`, `postgres`, `redis`, `rabbitmq`, `mailpit`이 없다.
7. `api`에 host port `8080:8080`이 없다.
8. Nginx만 host port `80:80`을 사용한다.

하나라도 실패하면 아래 Green 배포로 넘어가지 않는다.

## 13. 배포 Git SHA 확정

1. Phase 6 저장소 변경을 Git에 반영한다.
2. 테스트가 통과한 commit을 remote repository에 push한다.
3. 배포할 40자리 Git SHA를 확인한다.

```bash
cd /Users/juhoseok/Desktop/prototype
git rev-parse HEAD
```

4. SHA만 30번 기록표에 적는다.
5. working tree의 미커밋 변경을 Green에 복사하지 않는다.
6. Phase 6 첫 bootstrap은 이 SHA를 Green에서 source build한다.
7. Phase 8에서 같은 방식의 host build를 ECR `:<git-sha>` 이미지 pull 방식으로 교체한다.

## 14. Green에 Docker Engine 설치

SSM Session Manager로 Green에 접속한 상태에서 실행한다.

### 14.1 Ubuntu 사용자 shell로 전환

```bash
sudo -iu ubuntu
```

다음으로 확인한다.

```bash
whoami
uname -m
lsb_release -ds
```

예상값:

```text
ubuntu
x86_64
Ubuntu 24.04 LTS
```

### 14.2 기본 패키지 설치

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git jq unzip openjdk-21-jdk-headless
```

### 14.3 Docker 공식 repository 등록

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker ubuntu
```

### 14.4 Docker 확인

현재 SSM 세션을 종료하고 새 Session Manager 세션을 연 뒤 다시 Ubuntu 사용자로 전환한다.

```bash
sudo -iu ubuntu
docker version
docker compose version
docker run --rm hello-world
```

`permission denied`가 나오면 `sudo usermod -aG docker ubuntu` 실행 후 세션을 완전히 종료하고 새로 접속했는지 확인한다. Docker socket 권한을 `666`으로 바꾸지 않는다.

## 15. AWS CLI v2 설치와 IAM 확인

Ubuntu 사용자 shell에서 실행한다.

```bash
cd /tmp
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip -q awscliv2.zip
sudo ./aws/install
aws --version
```

이미 AWS CLI v2가 설치돼 있다면 재설치하지 않고 `aws --version`만 확인한다.

IAM Role credential이 정상인지 확인한다.

```bash
aws sts get-caller-identity --region ap-northeast-2
```

확인 기준:

1. Account가 현재 AWS 계정 `443915990705`이다.
2. ARN에 `buildgraph-demo-api-green-role`이 포함된다.
3. Access key를 직접 설정하거나 `aws configure`를 실행하지 않는다.
4. `~/.aws/credentials`를 만들지 않는다.

## 16. CloudWatch Agent 설치

### 16.1 Run Command로 package 설치

1. Systems Manager 콘솔에서 `Run Command`를 연다.
2. `Run command`를 누른다.
3. Command document에서 `AWS-ConfigureAWSPackage`를 선택한다.
4. Action은 `Install`을 선택한다.
5. Installation type은 `Uninstall and reinstall`을 선택한다. AWS 공식 절차상 `AmazonCloudWatchAgent` package에는 `In-place update`를 사용할 수 없다.
6. Name에 `AmazonCloudWatchAgent`를 입력한다.
7. Version은 `latest`를 사용한다.
8. Targets에서 `buildgraph-demo-api-green-ec2` 한 개만 선택한다.
9. Output options의 S3 저장은 이번 단계에서 비활성으로 둔다.
10. `Run`을 누른다.
11. Status가 `Success`인지 확인한다.

### 16.2 설치 확인

Green SSM 터미널에서 실행한다.

```bash
dpkg -l amazon-cloudwatch-agent
sudo systemctl status amazon-cloudwatch-agent --no-pager
```

설치 직후 설정 파일이 없어서 service가 stopped여도 package 설치 자체는 정상이다. 실제 Docker 로그 수집 설정은 25번에서 Compose 실행 후 적용한다.

## 17. 배포 디렉터리와 저장소 준비

Green SSM의 Ubuntu 사용자 shell에서 실행한다.

```bash
sudo mkdir -p /opt/buildgraph
sudo chown ubuntu:ubuntu /opt/buildgraph
cd /opt/buildgraph
git clone https://github.com/jungle-final-project/prototype.git
cd prototype
```

저장소가 이미 있으면 다시 clone하지 않는다. 다음 명령으로 remote를 확인한다.

```bash
git remote -v
```

13번에서 기록한 실제 SHA를 큰따옴표 안에 넣는다.

```bash
export GREEN_DEPLOY_GIT_SHA="실제_40자리_GIT_SHA"
git fetch origin
git checkout --detach "$GREEN_DEPLOY_GIT_SHA"
git rev-parse HEAD
```

확인 기준:

1. `git rev-parse HEAD`가 기록한 SHA와 완전히 같다.
2. `compose.api.prod.yaml`이 있다.
3. `infra/nginx/api.conf`가 있다.

```bash
test -f compose.api.prod.yaml
test -f infra/nginx/api.conf
```

두 명령 중 하나라도 실패하면 배포를 중단한다. branch를 최신으로 pull해서 임의 해결하지 않는다.

## 18. Secret을 `.env.prod`로 내려받기

Green SSM의 `/opt/buildgraph/prototype`에서 실행한다.

```bash
cd /opt/buildgraph/prototype
umask 077
aws secretsmanager get-secret-value \
  --region ap-northeast-2 \
  --secret-id buildgraph/demo-green/api-env \
  --query SecretString \
  --output text > .env.prod
chmod 600 .env.prod
test -s .env.prod
stat -c '%a %U:%G %n' .env.prod
```

정상 결과:

```text
600 ubuntu:ubuntu .env.prod
```

다음을 지킨다.

1. `cat .env.prod`를 실행하지 않는다.
2. `less`, `head`, `tail`로 값 전체를 출력하지 않는다.
3. 파일을 채팅이나 GitHub Issue에 붙이지 않는다.
4. `.env.prod`가 Git ignored인지 확인한다.

```bash
git check-ignore .env.prod
```

5. 키 이름만 확인하려면 다음 명령을 사용한다. 값은 출력되지 않는다.

```bash
sed -n 's/=.*$//p' .env.prod | sort
```

6. 예시 placeholder가 남았는지만 값 출력 없이 검사한다.

```bash
if grep -Eq '<[^>]+>|실제_|replace-with' .env.prod; then
  echo "ERROR: placeholder remains"
else
  echo "OK: no known placeholder"
fi
```

`ERROR`가 나오면 Compose를 실행하지 않고 Secrets Manager의 값을 교정한 뒤 `.env.prod`를 다시 내려받는다.

## 19. Green Compose 정적 검증

렌더링 원문을 출력하지 않고 다음 명령만 실행한다.

```bash
cd /opt/buildgraph/prototype
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod config --quiet
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod config --services
```

service 목록은 정확히 다음 세 개여야 한다.

```text
nginx
api
xgb-reranker
```

다음 명령으로 렌더링된 Compose의 SHA-256만 기록한다.

```bash
set -o pipefail
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod config | sha256sum
```

1. SHA-256만 30번 기록표에 적는다.
2. Compose 원문은 복사하지 않는다.
3. `e3b0c44298fc...`는 빈 입력의 SHA-256이므로 정상 결과로 기록하지 않는다.
4. config command가 실패하면 다음 명령으로 Docker Compose 존재를 다시 확인한다.

```bash
docker compose version
```

## 20. Green image build와 Compose 실행

Phase 6 bootstrap은 Green EC2에서 검증된 Git SHA를 source build한다. Phase 8 CI/CD가 준비되기 전 임시 방식이며, 사용자 트래픽 전환 전에는 ECR Git SHA 이미지 배포로 교체한다.

```bash
cd /opt/buildgraph/prototype
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod build
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod up -d
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod ps
```

확인 기준:

1. `nginx`, `api`, `xgb-reranker` 세 서비스가 Up이다.
2. `postgres`, `redis`, `rabbitmq`, `mailpit`, `web` 컨테이너가 없다.
3. 같은 이름의 Blue container를 중지하지 않았다.
4. Compose project가 `buildgraph-green`이다.
5. API startup log에서 Flyway 실패, RDS 인증 실패, Redis TLS 실패, RabbitMQ TLS 실패가 없다.

로그는 비밀값이 출력되지 않는지 주의하며 필요한 범위만 확인한다.

```bash
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod logs --tail 150 api
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod logs --tail 100 nginx
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod logs --tail 100 xgb-reranker
```

애플리케이션 로그에 password, token, 전체 datasource URL query가 보이면 로그를 공유하지 말고 먼저 마스킹 문제를 교정한다.

## 21. Nginx와 포트 검증

### 21.1 컨테이너 내부 Nginx 설정

```bash
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod exec nginx nginx -t
```

`syntax is ok`, `test is successful`이 나와야 한다.

### 21.2 EC2 host listening port

```bash
sudo ss -lntp
```

확인 기준:

1. host TCP `80`은 Docker/Nginx가 listening한다.
2. host TCP `8080`은 listening하지 않는다.
3. host TCP `5432`, `6379`, `5671`은 listening하지 않는다.

### 21.3 Docker port 목록

```bash
docker ps --format 'table {{.Names}}\t{{.Ports}}'
```

Nginx만 `0.0.0.0:80->80/tcp` 형태로 표시되어야 한다. API `8080/tcp`는 Docker 내부 표시만 가능하며 host mapping `0.0.0.0:8080->8080`이 있으면 실패다.

## 22. RDS 초기화와 API health 확인

첫 API startup에서 Flyway가 빈 RDS schema를 초기화한다.

Green 안에서 실행한다.

```bash
curl -fsS http://127.0.0.1/healthz
curl -fsS http://127.0.0.1/api/health
```

확인 기준:

1. Nginx `/healthz`가 `200`이다.
2. `/api/health`가 `200`이다.
3. 프로젝트 `/api/health`가 DB 연결까지 확인하고 성공한다.
4. API log에 Flyway migration 성공이 보인다.
5. `RAG_EMBEDDING_BACKFILL_ON_STARTUP=false`여서 시작 시 대량 embedding backfill을 하지 않는다.

Green Elastic IP를 로컬 PC에서 직접 확인한다. `<GREEN_EIP>`를 실제 값으로 바꾼다.

```bash
curl -i http://<GREEN_EIP>/healthz
curl -i http://<GREEN_EIP>/api/health
```

이번 Phase의 직접 EC2 검증은 HTTP 80을 사용한다. HTTPS는 Phase 7 CloudFront를 통해 제공한다.

외부에서 다음 요청은 실패해야 한다.

```bash
curl --connect-timeout 5 http://<GREEN_EIP>:8080/api/health
```

## 23. 관리형 인프라 실제 연결 테스트

Green에서만 실제 AWS 연결 테스트를 실행한다. `.env.prod`를 shell environment로 로드하므로 실행 중 화면 공유를 하지 않는다.

```bash
cd /opt/buildgraph/prototype
set -a
. ./.env.prod
set +a
export MANAGED_INFRA_TEST_ENABLED=true
cd apps/api
./gradlew test --tests com.buildgraph.prototype.infra.ManagedInfrastructureSmokeTest --no-daemon
unset MANAGED_INFRA_TEST_ENABLED
```

통과 기준:

| 대상 | 확인 내용 |
| --- | --- |
| RDS | JDBC 연결, `vector` extension, 성공한 Flyway migration |
| Redis | TLS·AUTH 연결, 쓰기·읽기·TTL, 테스트 key 삭제 |
| RabbitMQ | AMQPS publish·consume |
| RabbitMQ 재연결 | 첫 연결 종료 후 새 연결 성공 |

네 테스트가 모두 PASS여야 한다. 테스트가 만든 Redis key와 RabbitMQ temporary queue는 자동 삭제된다.

테스트 후 현재 shell에 비밀 environment가 남지 않게 SSM 세션을 종료하고 새로 연다. `.env.prod` 파일은 `600` 권한으로 유지한다.

## 24. API 기능 스모크 테스트

이번 Phase에서는 CloudFront를 Green으로 바꾸지 않으므로 Green EIP를 직접 사용해 API만 확인한다.

최소 확인 순서:

1. `GET /api/health`가 성공한다.
2. 로그인 또는 테스트 사용자 인증이 성공한다.
3. 토큰 갱신과 로그아웃이 성공한다.
4. 부품 목록 조회가 성공한다.
5. 견적 저장·조회가 성공한다.
6. Build Chat 요청에서 Redis cache가 동작한다.
7. RabbitMQ를 사용하는 Agent job이 publish·consume된다.
8. WebSocket ticket 발급과 `/ws/*` upgrade가 성공한다.
9. RAG vector 검색이 성공한다.
10. XGB model이 없으므로 실제 rerank 반영은 비활성 상태인지 확인한다.
11. PC Agent 기존 로그는 이전하지 않고 새 로그만 Green에 생성되는지 확인한다.

Google OAuth callback은 Google Console의 승인 URI와 CloudFront domain에 묶여 있으므로 EIP 직접 호출로 최종 판정하지 않는다. Phase 7 CloudFront Staging에서 검증한다.

## 25. CloudWatch Docker 로그 수집 설정

Compose가 실행된 뒤 CloudWatch Agent가 Docker JSON log를 수집하도록 설정한다.

Green SSM에서 다음 명령을 실행한다.

```bash
sudo install -d -m 0755 /opt/aws/amazon-cloudwatch-agent/etc
sudo tee /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json > /dev/null <<'EOF'
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
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config \
  -m ec2 \
  -s \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json
sudo systemctl status amazon-cloudwatch-agent --no-pager
```

AWS 콘솔에서 확인한다.

1. CloudWatch 콘솔을 연다.
2. `Logs` → `Log groups`를 누른다.
3. `/buildgraph/demo/api-green/docker`가 생성됐는지 확인한다.
4. Green Instance ID 이름의 stream이 있는지 확인한다.
5. 최근 Nginx/API/XGB log가 들어오는지 확인한다.
6. Log group의 Retention setting을 `14 days`로 변경한다.
7. Log에 password나 token이 없는지 확인한다.

## 26. RabbitMQ queue와 alarm 결정 게이트

1. Amazon MQ 콘솔에서 `buildgraph-demo-rabbitmq-green`을 연다.
2. API 실행 후 `ConnectionCount`가 증가하는지 확인한다.
3. application queue가 자동 선언됐는지 확인한다.
4. 테스트 message가 publish·consume되어 적체되지 않는지 확인한다.
5. 정상 사용 중의 queue depth를 기록한다.

Queue depth alarm 임계값과 평가 시간은 현재 계약 문서에 확정되어 있지 않다. 이 값은 임의로 정하지 않는다. 다음 두 값을 사용자와 확정한 후 alarm을 생성한다.

```text
MessageCount 임계값: 미확정
평가 시간: 미확정
```

Alarm 생성은 Phase 6 연결 성공을 막는 조건은 아니지만, 미확정 상태를 완료로 표시하지 말고 30번 기록표에 `PENDING`으로 남긴다.

## 27. Blue 무변경 확인

Blue에 기존 방식으로 접속해 조회 명령만 실행한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod ps
```

확인 기준:

1. Blue의 기존 Web·API·PostgreSQL·Redis·RabbitMQ·XGB가 계속 실행 중이다.
2. Blue `.env.prod`는 기존 container endpoint를 계속 사용한다.
3. Blue Public IP는 `15.164.235.183` 그대로다.
4. 기존 CloudFront Origin은 Blue 그대로다.
5. 기존 사용자 요청이 계속 성공한다.

Blue에서 `docker compose config` 원문, `.env.prod`, container environment를 출력하지 않는다.

## 28. Phase 6 완료 조건

2026-07-14 대화 기록 기준으로 **30개 확인, 3개 보류**다. 따라서 Phase 6은 아직 형식상 완료 처리하지 않는다.

- [x] Phase 3 RDS가 Available
- [x] Phase 4 Redis가 Available
- [x] Phase 5 RabbitMQ가 Running
- [x] Secret `buildgraph/demo-green/api-env` 생성
- [ ] Secret value에 placeholder가 없음
- [x] Green IAM Role `buildgraph-demo-api-green-role` 생성
- [x] Role에 `AmazonSSMManagedInstanceCore` 연결
- [x] Role에 `CloudWatchAgentServerPolicy` 연결
- [ ] Role에 `AmazonEC2ContainerRegistryReadOnly` 연결
- [x] Secret ARN 한 개만 읽는 inline policy 연결
- [x] Green EC2 `buildgraph-demo-api-green-ec2` 생성
- [x] Green이 Public 2b `10.0.16.0/20`, `ap-northeast-2b`에 배치
- [x] Green에 공유 SG `sg-099aac782b77a854e`만 연결
- [ ] Green에 key pair 없음
- [x] Green에 신규 Elastic IP 연결
- [x] SSM Session Manager 접속 성공
- [x] Docker Engine과 Compose plugin 설치
- [x] CloudWatch Agent 설치·실행
- [x] Phase 6 저장소 구현 테스트 PASS
- [x] 배포 Git SHA 고정
- [x] `.env.prod` 권한 `600 ubuntu:ubuntu`
- [x] Compose config SHA가 빈 입력 SHA가 아님
- [x] Green Compose service가 `nginx`, `api`, `xgb-reranker`만 존재
- [x] API `8080` host 공개 없음
- [x] host `5432`, `6379`, `5671` 공개 없음
- [x] Nginx `nginx -t` PASS
- [x] Green `/healthz`와 `/api/health` PASS
- [x] RDS·Redis·RabbitMQ ManagedInfrastructureSmokeTest PASS
- [x] RabbitMQ 재연결 test PASS
- [x] CloudWatch log group과 stream 확인
- [x] Queue alarm의 미확정 임계값은 `PENDING`으로 기록
- [x] Blue EC2·Compose·Public IP 무변경
- [x] 기존 CloudFront Origin 무변경

### 28.1 2026-07-14 대화 기록 기준 판정

체크된 항목은 실제 콘솔 화면이나 실행 로그로 확인된 항목이다. 체크되지 않은 항목은 실패가 아니라 **확인 증거가 아직 없는 보류 항목**이다.

| 판정 | 근거 |
| --- | --- |
| RDS·Redis·RabbitMQ 정상 | `ManagedInfrastructureSmokeTest`가 `BUILD SUCCESSFUL`로 끝났고 RAG vector backfill·검색, Redis cache hit, RabbitMQ Agent publish·consume가 성공했다. |
| Green runtime 정상 | `nginx`, `api`, `xgb-reranker` 세 서비스만 실행되며 외부에는 `80`만 공개됐다. `8080`, `8091`, `5432`, `6379`, `5671`은 host에 공개되지 않았다. |
| Green health 정상 | `http://43.203.33.190/healthz`와 `/api/health`가 `200`을 반환했다. 외부 `:8080` 요청은 timeout으로 실패했다. |
| SSM·CloudWatch 정상 | Green이 SSM Online으로 표시됐고 `/buildgraph/demo/api-green/docker`의 `i-033105106a7970ac1` stream에서 API·Nginx 로그가 확인됐다. |
| 기능 스모크 정상 | RAG vector 검색, Agent `SUCCEEDED`, RabbitMQ worker 처리, OpenAI 호출, Redis Build Chat cache hit가 확인됐다. |
| 배포 SHA 고정 | Green이 `45a4b1e78cdf44cca1c13cfb55636a15ecdf438b`를 detached HEAD로 checkout했다. |
| Green 재확인 정상 | 내부·외부 health가 모두 `UP`, 정의·실행 service가 세 개로 일치하고 host의 `8080`, `8091`, `5432`, `6379`, `5671`이 모두 비공개다. |
| 파일·Compose 확인 | `.env.prod`가 `600 ubuntu:ubuntu`이고 Compose config SHA가 `d9296779e0966af32c8783c600197c4c3cf6f75b07ab668ac3eaf0f49a3a1b78`로 빈 입력 SHA가 아니다. |
| Secret inline policy 정상 | `DescribeSecret`·`GetSecretValue`만 허용하며 Resource가 `buildgraph/demo-green/api-env`의 실제 ARN 한 개로 제한됐다. |
| Primary CloudFront 무변경 | `/assets/*`와 `Default (*)`가 모두 기존 Blue DNS `ec2-15-164-235-183.ap-northeast-2.compute.amazonaws.com` Origin을 사용한다. |
| Nginx 설정 정상 | `nginx -t`가 `syntax is ok`, `test is successful`, exit code `0`으로 끝났다. |
| Blue 상태 정상 | 기존 Blue Compose가 계속 실행 중이고 Public IP가 `15.164.235.183`으로 유지됨을 사용자가 최종 확인했다. |
| Queue alarm 보류 | 임계값과 평가 시간이 확정되지 않아 의도적으로 `PENDING`이다. |
| IAM managed policy 일부 보류 | inline policy는 확인됐지만 `AmazonEC2ContainerRegistryReadOnly`의 최종 Attached 화면은 아직 확인되지 않았다. |
| key pair 보류 | 최초 계획은 key pair 없음이지만 이후 SSH 22와 key pair 사용 의사가 언급됐다. EC2의 실제 Key pair name을 다시 확인해야 한다. |

### 28.2 Phase 7 진입 전 남은 확인

1. IAM Role의 Permissions 탭에서 다음 두 항목을 확인한다.
   - `AmazonEC2ContainerRegistryReadOnly`가 Attached 상태다.
   - inline policy의 `secretsmanager:GetSecretValue` Resource가 `buildgraph/demo-green/api-env`의 ARN 한 개뿐이다.
2. EC2 상세의 `Key pair name`을 확인한다. 값이 있으면 이 체크리스트의 요구사항과 다르므로 `없음`으로 거짓 체크하지 말고 실제 운영 결정을 기록한다.
3. Green에서 Secret 값을 출력하지 않고 다음을 실행한다.

```bash
cd /opt/buildgraph/prototype
stat -c '%a %U:%G %n' .env.prod
set -o pipefail
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod config | sha256sum
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod exec nginx nginx -t
```

통과 기준:

- 첫 줄이 `600 ubuntu:ubuntu .env.prod`다.
- SHA-256이 64자리이고 빈 입력 SHA `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`가 아니다.
- Nginx 결과에 `syntax is ok`와 `test is successful`이 모두 있다.

4. placeholder는 값 자체를 출력하지 않고 key 이름만 검사한다.

```bash
awk -F= '
  $2 ~ /replace-with|placeholder|<[^>]+>|실제 비밀번호|실제 AUTH token/ { print $1 }
' .env.prod
```

아무 key도 출력되지 않아야 한다. 빈 값이 허용된 OAuth·Agent demo 설정은 placeholder가 아니다.

5. Blue에서 조회 명령만 실행하고 기존 서비스가 계속 실행 중인지 확인한다.

```bash
docker compose -f compose.prod.yaml --env-file .env.prod ps
```

6. Blue Public IP가 `15.164.235.183`인지 확인한다.
7. CloudFront Primary Distribution의 현재 Origin과 behavior를 스크린샷으로 기록한다. 아직 Green EIP·Green Public DNS·새 S3가 Primary에 연결돼 있으면 안 된다.

위 조건을 만족하면 Phase 6을 완료하고 Phase 7의 Private S3와 CloudFront Staging 검증으로 넘어간다.

## 29. 실패 시 롤백

CloudFront를 아직 변경하지 않았으므로 Phase 6 실패는 사용자 트래픽에 영향을 주지 않는다.

### Green application 문제

1. CloudFront와 Blue는 그대로 둔다.
2. Green에서 다음 명령으로 상태와 log만 확인한다.

```bash
cd /opt/buildgraph/prototype
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod ps
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod logs --tail 200
```

3. 필요하면 Green Compose만 중지한다.

```bash
docker compose -p buildgraph-green -f compose.api.prod.yaml --env-file .env.prod stop
```

4. `down -v`를 실행하지 않는다.
5. Blue에는 아무 명령도 실행하지 않는다.

### EC2 또는 IAM 문제

1. Green EC2를 Stop해도 Blue 서비스에는 영향이 없다.
2. 문제 해결 중 Elastic IP를 Blue에 연결하지 않는다.
3. Green Role을 Blue에 연결하지 않는다.
4. Secret value를 터미널 argument에 넣지 않는다.
5. 원인을 고친 뒤 Green만 다시 시작한다.

### 관리형 서비스 연결 문제

1. RDS·Redis·RabbitMQ를 Public으로 바꾸지 않는다.
2. 데이터 서비스 SG Source가 공유 SG인지 확인한다.
3. endpoint·port·TLS·username을 확인한다.
4. password를 log에 출력하지 않는다.
5. Blue `.env.prod`로 관리형 endpoint를 시험하지 않는다.

## 30. Phase 6 결과 기록표

비밀번호와 token은 절대 기록하지 않는다.

| 항목 | 기록값 |
| --- | --- |
| 실행 날짜 | `YYYY-MM-DD HH:mm KST` |
| 담당자 | `<이름>` |
| Public 2b Subnet ID | `<subnet-...>` |
| Secret ARN | `<arn:aws:secretsmanager:...>` |
| IAM Role ARN | `<arn:aws:iam:...:role/buildgraph-demo-api-green-role>` |
| Green Instance ID | `<i-...>` |
| Green Private IP | `<10.0.x.x>` |
| Green Elastic IP | `<public IPv4>` |
| Elastic IP Allocation ID | `<eipalloc-...>` |
| Green Public DNS | `<ec2-...compute.amazonaws.com>` |
| 배포 Git SHA | `<40자리 SHA>` |
| Compose config SHA-256 | `d9296779e0966af32c8783c600197c4c3cf6f75b07ab668ac3eaf0f49a3a1b78` |
| RDS endpoint | `buildgraph-demo-postgres-green.cdcw2ykmk609.ap-northeast-2.rds.amazonaws.com` |
| Redis primary endpoint | `<hostname only>` |
| RabbitMQ address | `<hostname>:5671` |
| SSM Session | `PASS / FAIL` |
| Repository validator | `PASS / FAIL` |
| API tests | `PASS / FAIL` |
| Managed infrastructure tests | `PASS / FAIL` |
| Nginx test | `PASS` |
| Green health | `PASS` |
| CloudWatch Docker logs | `PASS / FAIL` |
| RabbitMQ MessageCount alarm | `PENDING — threshold/time 미확정` |
| Blue 상태 | `UNCHANGED` |
| CloudFront Origin | `BLUE 유지` |

## 31. 자주 발생하는 문제

### Secret `AccessDeniedException`

1. EC2 Role이 `buildgraph-demo-api-green-role`인지 확인한다.
2. Inline policy Resource가 실제 Secret ARN과 정확히 같은지 확인한다.
3. Secret Region이 서울인지 확인한다.
4. Secret 이름 오타를 확인한다.
5. Resource를 `*`로 넓혀서 임시 해결하지 않는다.

### Docker build 중 disk 부족

1. `df -h`로 root EBS 사용량을 확인한다.
2. `docker system df`로 build cache를 확인한다.
3. 실행 중인 image와 Volume을 임의 삭제하지 않는다.
4. 30 GiB로 부족함이 확인되면 EBS 확장을 사용자와 결정한다.

### RDS timeout

1. Green과 RDS가 같은 VPC인지 확인한다.
2. RDS SG 5432 Source가 `sg-099aac782b77a854e`인지 확인한다.
3. RDS endpoint와 database name `buildgraph`를 확인한다.
4. RDS Public access를 켜지 않는다.

### Redis timeout 또는 SSL 오류

1. Redis SG 6379 Source를 확인한다.
2. `SPRING_DATA_REDIS_HOST`가 hostname only인지 확인한다.
3. port가 `6379`인지 확인한다.
4. `SPRING_DATA_REDIS_SSL_ENABLED=true`인지 확인한다.
5. AUTH token을 URL에 붙이지 않는다.

### RabbitMQ timeout 또는 인증 오류

1. RabbitMQ SG 5671 Source를 확인한다.
2. address가 `hostname:5671` 형식인지 확인한다.
3. `amqps://`를 `SPRING_RABBITMQ_ADDRESSES`에 넣지 않았는지 확인한다.
4. username이 `buildgraph`인지 확인한다.
5. virtual host가 `/`인지 확인한다.
6. `SPRING_RABBITMQ_SSL_ENABLED=true`인지 확인한다.

### API container가 반복 재시작

1. `docker compose ... ps`로 exit 상태를 확인한다.
2. API log 마지막 150줄만 확인한다.
3. Flyway migration, datasource auth, Redis TLS, RabbitMQ TLS 순서로 원인을 확인한다.
4. `restart: unless-stopped` 때문에 원인이 가려지면 Green Compose만 `stop`하고 log를 확인한다.
5. Blue는 중지하지 않는다.

### `/api/health`는 실패하고 `/healthz`는 성공

Nginx는 실행 중이지만 Spring Boot 또는 DB 연결이 실패한 상태다.

1. API container 상태를 확인한다.
2. API log에서 datasource와 Flyway 오류를 확인한다.
3. RDS endpoint·password·SG를 확인한다.
4. `/healthz` 성공만으로 Phase 6 완료 처리하지 않는다.

## 공식 참고 문서

- [EC2 Launch Instance Wizard](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-launch-instance-wizard.html)
- [EC2에 IAM Role 연결](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/attach-iam-role.html)
- [Elastic IP 할당과 연결](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/working-with-eips.html)
- [Ubuntu의 SSM Agent 설치·상태 확인](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu-64-snap.html)
- [Secrets Manager Secret 생성](https://docs.aws.amazon.com/secretsmanager/latest/userguide/create_secret.html)
- [Secret ARN 최소 권한 IAM 예시](https://docs.aws.amazon.com/secretsmanager/latest/userguide/auth-and-access_iam-policies.html)
- [CloudWatch Agent를 Systems Manager로 설치](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/installing-cloudwatch-agent-ssm.html)
- [ECR AWS managed policies](https://docs.aws.amazon.com/AmazonECR/latest/userguide/security-iam-awsmanpol.html)
- [AWS CLI v2 Linux 설치](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- [Docker Engine Ubuntu 설치](https://docs.docker.com/engine/install/ubuntu/)
