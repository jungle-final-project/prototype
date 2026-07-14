# AWS 인프라 Phase 0~7 실제 설정 감사 문서

이 문서는 BuildGraph AWS 인프라를 AWS CLI로 **읽기 전용 조회**한 결과를 하나의 문서로 정리한 것이다.

- 확인 일자: 2026-07-14
- AWS 계정: `443915990705`
- 기본 리전: `ap-northeast-2` (서울)
- 확인 범위: Phase 0~7에서 생성하거나 연결한 BuildGraph 리소스
- 확인 방법: AWS CLI의 `get`, `list`, `describe`, `head` 계열 명령
- AWS 설정 변경: 없음
- Secret value 조회: 없음

> 비밀번호, Redis AUTH token, RabbitMQ 비밀번호, OAuth Secret, OpenAI API Key 등 비밀값은 이 문서에 기록하지 않는다.

## 1. 전체 구성

```text
사용자
  -> Green CloudFront
       ├─ Default (*) -> Private S3 Web
       ├─ /api/*      -> Green EC2 Nginx -> Spring Boot API
       └─ /ws/*       -> Green EC2 Nginx -> Spring WebSocket

Green API
  ├─ RDS PostgreSQL
  ├─ ElastiCache Redis
  ├─ Amazon MQ RabbitMQ
  └─ XGB Reranker Container

Blue EC2와 기존 CloudFront는 별도로 계속 실행 중
```

## 2. Phase 0~7 요약

| Phase | 목적 | 실제 적용 상태 |
| --- | --- | --- |
| 0 | 기존 Blue 기준선 확인 | Blue EC2·Compose·CloudFront 유지 |
| 1 | 인프라 분리 테스트·계약 | Green Compose를 `nginx`, `api`, `xgb-reranker`로 제한 |
| 2 | 네트워크·보안 기반 | Public 2개, Private 2개, Data SG 3개 구성 |
| 3 | PostgreSQL 분리 | RDS PostgreSQL Single-AZ 운영 중 |
| 4 | Redis 분리 | ElastiCache Redis OSS 단일 노드 운영 중 |
| 5 | RabbitMQ 분리 | Amazon MQ RabbitMQ Single-instance 운영 중 |
| 6 | Green API 병렬 배포 | Green EC2·EIP·IAM·Secret·Compose 배포 완료 |
| 7 | Web·CloudFront 분리 | Private S3와 독립 Green CloudFront 배포 완료 |

## 3. 계정·VPC

| 항목 | 실제 값 |
| --- | --- |
| AWS 계정 | `443915990705` |
| 리전 | `ap-northeast-2` |
| VPC 이름 | `buildgraph-demo-vpc` |
| VPC ID | `vpc-06c90b864a62f93a4` |
| CIDR | `10.0.0.0/16` |
| 상태 | `available` |
| Instance tenancy | `default` |
| DNS Support | 활성 |
| DNS Hostnames | 활성 |
| Internet Gateway Block Mode | `off` |
| DHCP Options | `dopt-0c88d4b900969f348` |
| Internet Gateway | `buildgraph-demo-igw` / `igw-0eeddf412eaad84c9` |
| NAT Gateway | 없음 |

## 4. Subnet

| 구분 | 이름 | ID | AZ | CIDR | Public IPv4 자동 할당 |
| --- | --- | --- | --- | --- | --- |
| Public | `buildgraph-demo-subnet-public1-ap-northeast-2a` | `subnet-0b48bd72162060261` | `ap-northeast-2a` | `10.0.0.0/20` | 비활성 |
| Public | `buildgraph-demo-subnet-public2-ap-northeast-2b` | `subnet-0db73cf18a85ea8f1` | `ap-northeast-2b` | `10.0.16.0/20` | 비활성 |
| Private | `buildgraph-demo-subnet-private-data1-ap-northeast-2a` | `subnet-09bba1fd17639ce6a` | `ap-northeast-2a` | `10.0.32.0/24` | 비활성 |
| Private | `buildgraph-demo-subnet-private-data2-ap-northeast-2b` | `subnet-0816bc2771fd5e1ca` | `ap-northeast-2b` | `10.0.33.0/24` | 비활성 |

## 5. Route Table

| Route Table | ID | 연결 Subnet | Route |
| --- | --- | --- | --- |
| Main | `rtb-06ebc9091b81d6788` | Main association | `10.0.0.0/16 -> local` |
| `buildgraph-demo-rtb-public` | `rtb-05bef98dcdf339683` | Public 2a, Public 2b | `10.0.0.0/16 -> local`, `0.0.0.0/0 -> igw-0eeddf412eaad84c9` |
| `buildgraph-demo-rtb-private-data` | `rtb-084440a81e5721f2f` | Private 2a, Private 2b | `10.0.0.0/16 -> local` |

Private Data Subnet에는 인터넷 기본 경로와 NAT Gateway 경로가 없다.

## 6. Network ACL·VPC Endpoint

| 항목 | 실제 설정 |
| --- | --- |
| Network ACL | 기본 NACL `acl-0a75907f80d8a6f04` |
| 연결 대상 | 네 Subnet 전체 |
| Inbound | Rule 100 전체 허용, 마지막 Rule 전체 거부 |
| Outbound | Rule 100 전체 허용, 마지막 Rule 전체 거부 |
| Amazon MQ VPC Endpoint | `vpce-0510f62ee4020f51d` |
| Endpoint 유형 | Interface |
| Endpoint Subnet | Private 2b |
| Endpoint SG | RabbitMQ SG |
| Requester Managed | `true` |
| Tag | `AMQManaged=true` |

Amazon MQ Endpoint는 사용자가 직접 관리하는 Endpoint가 아니라 Amazon MQ가 자동 관리하는 리소스다.

## 7. Security Group

### 7.1 EC2 공유 SG

| 항목 | 값 |
| --- | --- |
| 이름 | `buildgraph-demo-ec2-sg` |
| ID | `sg-099aac782b77a854e` |
| Inbound 80 | TCP 80, Source `0.0.0.0/0` |
| Inbound 22 | TCP 22, Source `0.0.0.0/0` |
| Outbound | 모든 프로토콜, Destination `0.0.0.0/0` |
| 사용 대상 | Blue EC2, Green EC2 |

### 7.2 Data SG

| 이름 | ID | Inbound | Outbound |
| --- | --- | --- | --- |
| `buildgraph-demo-rds-sg` | `sg-0587fdbc766f9088f` | TCP 5432, Source EC2 SG | 전체 허용 |
| `buildgraph-demo-redis-sg` | `sg-0dc3c8766358e57f4` | TCP 6379, Source EC2 SG | TCP 6379, Destination EC2 SG |
| `buildgraph-demo-rabbitmq-sg` | `sg-0876855a9ac1da572` | TCP 5671, Source EC2 SG | TCP 5671, Destination EC2 SG |

RDS·Redis·RabbitMQ 포트는 인터넷 CIDR에 공개되지 않았다.

## 8. Blue·Green EC2

| 항목 | Blue | Green |
| --- | --- | --- |
| 이름 | `buildgraph-demo-ec2` | `buildgraph-demo-api-green-ec2` |
| Instance ID | `i-082c21a20e14f3295` | `i-033105106a7970ac1` |
| 상태 | Running | Running |
| Status check | System OK, Instance OK | System OK, Instance OK |
| 타입 | `t3.medium` | `t3.medium` |
| AZ | `ap-northeast-2b` | `ap-northeast-2b` |
| Subnet | Public 2b | Public 2b |
| Private IP | `10.0.24.118` | `10.0.23.7` |
| Public IP | `15.164.235.183` | `43.203.33.190` |
| Public DNS | `ec2-15-164-235-183.ap-northeast-2.compute.amazonaws.com` | `ec2-43-203-33-190.ap-northeast-2.compute.amazonaws.com` |
| AMI | `ami-0e4ab31f1847c850c` | 동일 |
| OS | Canonical Ubuntu 24.04 x86_64 | 동일 |
| Key pair | `buildgraph-demo-key`, RSA | `buildgraph-demo-api-green-key`, RSA |
| IAM Profile | `buildgraph-demo-ec2-role` | `buildgraph-demo-api-green-role` |
| SG | 공유 EC2 SG 하나 | 공유 EC2 SG 하나 |
| IMDS | V2 token required | V2 token required |
| IMDS Hop limit | 2 | 2 |
| Detailed Monitoring | 활성 | 활성 |
| Termination protection | 비활성 | 활성 |
| Stop protection | 확인 대상 아님 | 비활성 |
| OS shutdown 동작 | Stop | Stop |

### 8.1 EBS

| 항목 | Blue | Green |
| --- | --- | --- |
| Volume | `vol-01f341009f6f43cc9` | `vol-0a30e22be13d33d04` |
| 유형 | gp3 | gp3 |
| 크기 | 30 GiB | 30 GiB |
| IOPS | 3000 | 3000 |
| Throughput | 125 MiB/s | 125 MiB/s |
| 암호화 | 비활성 | 활성 |
| KMS | 없음 | `alias/aws/ebs` |
| Delete on termination | 활성 | 활성 |

### 8.2 Elastic IP

| 항목 | 값 |
| --- | --- |
| Green EIP | `43.203.33.190` |
| Allocation ID | `eipalloc-08fdd544c8bd08106` |
| Association ID | `eipassoc-0ffb631ecf48a3555` |
| 연결 대상 | Green EC2 / `10.0.23.7` |

계정에는 `ServiceManaged=rnat`인 AWS 관리 주소 `54.116.200.128`도 존재하지만 사용자 EC2에 연결된 EIP는 아니다.

## 9. RDS PostgreSQL

| 항목 | 실제 값 |
| --- | --- |
| ID | `buildgraph-demo-postgres-green` |
| ARN | `arn:aws:rds:ap-northeast-2:443915990705:db:buildgraph-demo-postgres-green` |
| 상태 | `available` |
| 엔진 | PostgreSQL `16.14` |
| 클래스 | `db.t4g.small` |
| DB 이름 | `buildgraph` |
| Master username | `buildgraph` |
| Port | 5432 |
| AZ | `ap-northeast-2b` |
| Multi-AZ | 비활성 |
| Public access | 비활성 |
| Network | IPv4 |
| SG | RDS SG 하나 |
| Storage | gp3 30 GiB |
| Autoscaling 최대 | 1000 GiB |
| IOPS | 3000 |
| Throughput | 125 MiB/s |
| 암호화 | 활성 |
| KMS | `alias/aws/rds` |
| Backup retention | 1일 |
| Backup window | `17:53-18:23` UTC |
| Maintenance window | 목요일 `15:16-15:46` UTC |
| Deletion protection | 비활성 |
| Auto minor upgrade | 활성 |
| IAM DB Authentication | 비활성 |
| Performance Insights | 활성 |
| Enhanced Monitoring | 비활성, interval 0 |
| Database Insights | Standard |
| PostgreSQL log export | 활성 |
| Parameter group | `default.postgres16` |
| Option group | `default:postgres-16` |
| CA | `rds-ca-rsa2048-g1` |
| Copy tags to snapshot | 활성 |

### 9.1 RDS Subnet Group

| 항목 | 값 |
| --- | --- |
| 이름 | `buildgraph-demo-rds-subnet-group` |
| 상태 | Complete |
| Subnet | Private 2a, Private 2b |

## 10. ElastiCache Redis

| 항목 | 실제 값 |
| --- | --- |
| Replication Group | `buildgraph-demo-redis-green` |
| 상태 | `available` |
| 엔진 | Redis OSS `7.1.0` |
| Node type | `cache.t4g.small` |
| Node 수 | 1 |
| 배치 AZ | `ap-northeast-2b` |
| Cluster mode | 비활성 |
| Automatic failover | 비활성 |
| Multi-AZ | 비활성 |
| Port | 6379 |
| Transit encryption | 활성, Required |
| At-rest encryption | 활성 |
| AUTH token | 활성 |
| User Group | 없음 |
| SG | Redis SG 하나 |
| Parameter group | `default.redis7` |
| Auto minor upgrade | 활성 |
| Snapshot retention | 0일 |
| Snapshot window | `00:30-01:30` UTC |
| Maintenance window | 수요일 `03:30-04:30` UTC |
| Log delivery | 없음 |

### 10.1 Redis Subnet Group

| 항목 | 값 |
| --- | --- |
| 이름 | `buildgraph-demo-redis-subnet-group` |
| Network type | IPv4 |
| Subnet | Private 2a, Private 2b |

## 11. Amazon MQ RabbitMQ

| 항목 | 실제 값 |
| --- | --- |
| Broker | `buildgraph-demo-rabbitmq-green` |
| Broker ID | `b-5f60ad7a-7f84-4153-865c-65b640110b8b` |
| 상태 | `RUNNING` |
| 엔진 | RabbitMQ `3.13.7` |
| Deployment mode | `SINGLE_INSTANCE` |
| Instance type | `mq.m7g.medium` |
| Storage type | EBS |
| Public access | 비활성 |
| Subnet | Private 2b |
| SG | RabbitMQ SG 하나 |
| Endpoint protocol | AMQPS |
| Port | 5671 |
| Authentication | Simple username/password |
| Encryption | AWS owned key |
| Auto minor upgrade | 활성 |
| General logs | 활성 |
| Maintenance | 수요일 22:00 UTC |
| Configuration revision | 1 |

RabbitMQ 비밀번호는 조회하거나 기록하지 않았다.

## 12. IAM Role·Instance Profile

### 12.1 Green Role

| 항목 | 실제 값 |
| --- | --- |
| Role | `buildgraph-demo-api-green-role` |
| Trust principal | `ec2.amazonaws.com` |
| Max session | 3600초 |
| Permissions boundary | 없음 |
| Instance Profile | `buildgraph-demo-api-green-role` |

연결된 Managed Policy:

| Policy | 상태 |
| --- | --- |
| `AmazonSSMManagedInstanceCore` | 연결됨 |
| `CloudWatchAgentServerPolicy` | 연결됨 |
| `AmazonEC2ContainerRegistryReadOnly` | 연결됨 |

Inline Policy:

| 항목 | 값 |
| --- | --- |
| 이름 | `BuildGraphGreenApiSecretRead` |
| Action | `secretsmanager:DescribeSecret`, `secretsmanager:GetSecretValue` |
| Resource | `arn:aws:secretsmanager:ap-northeast-2:443915990705:secret:buildgraph/demo-green/api-env-UfCAaW` |

### 12.2 Blue Role

| 항목 | 실제 값 |
| --- | --- |
| Role | `buildgraph-demo-ec2-role` |
| Trust principal | `ec2.amazonaws.com` |
| Managed Policy | `AmazonSSMManagedInstanceCore` |
| Permissions boundary | 없음 |

## 13. SSM

| Instance | Ping | Agent | OS |
| --- | --- | --- | --- |
| Blue | Online | `3.3.4793.0` | Ubuntu 24.04 |
| Green | Online | `3.3.4793.0` | Ubuntu 24.04 |

## 14. Secrets Manager

| 항목 | 실제 값 |
| --- | --- |
| 이름 | `buildgraph/demo-green/api-env` |
| ARN suffix | `api-env-UfCAaW` |
| 설명 | BuildGraph Green API production dotenv |
| KMS | 기본 `aws/secretsmanager` |
| Rotation | 비활성 |
| 리전 복제 | 없음 |
| Tag | 없음 |
| 현재 Stage | `AWSCURRENT` |
| 이전 Stage | `AWSPREVIOUS` |
| 확인된 Version 수 | 4 |

Secret value는 조회하지 않았다.

## 15. CloudWatch·AWS Config

### 15.1 Log Group

| Log Group | Class | Retention |
| --- | --- | --- |
| `/buildgraph/demo/api-green/docker` | Standard | 14일 |
| `/aws/rds/instance/buildgraph-demo-postgres-green/postgresql` | Standard | 만료 없음 |
| Amazon MQ `general` | Standard | 만료 없음 |
| Amazon MQ `connection` | Standard | 만료 없음 |

Green Docker Log Stream:

| 항목 | 값 |
| --- | --- |
| Stream | `i-033105106a7970ac1` |
| 최근 이벤트 | 존재 |

### 15.2 Alarm·Config

| 항목 | 실제 상태 |
| --- | --- |
| BuildGraph CloudWatch Alarm | 0개 |
| Queue Alarm | 없음 |
| AWS Config Recorder | 없음 |

AWS Config가 비활성 상태이므로 활성화 이전 설정 변경 이력은 AWS Config에서 확인할 수 없다.

## 16. Private S3 Web Bucket

| 항목 | 실제 값 |
| --- | --- |
| Bucket | `buildgraph-demo-web-green-443915990705` |
| 리전 | `ap-northeast-2` |
| Block Public ACL | 활성 |
| Ignore Public ACL | 활성 |
| Block Public Policy | 활성 |
| Restrict Public Buckets | 활성 |
| Policy Public 상태 | `false` |
| Object Ownership | `BucketOwnerEnforced` |
| 기본 암호화 | SSE-S3 / AES256 |
| Bucket Key | 비활성 |
| SSE-C | 차단 |
| Versioning | 활성 |
| Static Website | 없음 |
| CORS | 없음 |
| Lifecycle | 없음 |
| Replication | 없음 |
| Access Logging | 없음 |
| Notification | 없음 |

### 16.1 Bucket Policy

| 항목 | 실제 정책 |
| --- | --- |
| Principal | `cloudfront.amazonaws.com` |
| Action | `s3:GetObject` |
| Resource | `arn:aws:s3:::buildgraph-demo-web-green-443915990705/*` |
| SourceArn | Green Distribution `E1MVNMU0O781IM` |

### 16.2 주요 Object Metadata

| Object | Content-Type | Cache-Control | 암호화 |
| --- | --- | --- | --- |
| `index.html` | `text/html` | `no-cache,max-age=0,must-revalidate` | AES256 |
| `assets/index-Dqb4Jqjw.js` | `application/javascript` | `public,max-age=31536000,immutable` | AES256 |
| `favicon.svg` | `image/svg+xml` | `public,max-age=3600` | AES256 |
| `downloads/pc-agent/latest.json` | `application/json` | `no-cache,max-age=0,must-revalidate` | AES256 |

## 17. CloudFront Distribution

현재 Distribution은 Blue와 Green 두 개이며 Staging Distribution은 없다.

| 구분 | ID | Domain | 상태 | Staging |
| --- | --- | --- | --- | --- |
| Blue | `EI6MMNZLTTN3H` | `d1a7gxvxxd385i.cloudfront.net` | Deployed | false |
| Green | `E1MVNMU0O781IM` | `d2qhd7deuwmlln.cloudfront.net` | Deployed | false |

Continuous Deployment Policy 수는 0개다.

### 17.1 Green 공통 설정

| 항목 | 실제 값 |
| --- | --- |
| Comment | BuildGraph Green Web and API validation distribution |
| Enabled | true |
| IPv6 | 활성 |
| HTTP Version | HTTP/2 |
| Price Class | All |
| Alias domain | 없음 |
| 인증서 | CloudFront 기본 인증서 |
| Minimum protocol 표시 | TLSv1 |
| Geo restriction | 없음 |
| Access logging | 비활성 |
| Custom error response | 없음 |
| Origin Shield | 비활성 |
| Continuous Deployment | 없음 |
| WAF | 연결됨 |

### 17.2 Green Origin

| Origin | 대상 | 주요 설정 |
| --- | --- | --- |
| Web | Private S3 REST endpoint | OAC `ETLAXAQDCBJF1`, Origin path 없음 |
| API | Green EC2 Public DNS | HTTP only, Port 80, Read timeout 120초 |

### 17.3 Green Behavior

| Path | Origin | Allowed methods | Cache Policy | Origin Request Policy | Function |
| --- | --- | --- | --- | --- | --- |
| Default `*` | S3 | GET, HEAD | Managed CachingOptimized | 없음 | SPA rewrite, viewer-request |
| `/api/*` | Green API | GET, HEAD, OPTIONS, PUT, POST, PATCH, DELETE | Managed CachingDisabled | Managed AllViewer | 없음 |
| `/ws/*` | Green API | GET, HEAD, OPTIONS | Managed CachingDisabled | Managed AllViewer | 없음 |

모든 Behavior의 Viewer Protocol Policy는 HTTP에서 HTTPS로 Redirect한다.

### 17.4 Cache Policy 실제 값

| Policy | Min TTL | Default TTL | Max TTL |
| --- | ---: | ---: | ---: |
| Managed CachingOptimized | 1초 | 86400초 | 31536000초 |
| Managed CachingDisabled | 0초 | 0초 | 0초 |

Managed CachingOptimized의 Min TTL이 1초이므로 `index.html`의 `no-cache` 응답도 CloudFront에서 최소 1초 캐시될 수 있다.

Managed AllViewer는 모든 Header, Cookie, Query String을 Origin으로 전달한다.

### 17.5 Blue CloudFront

| 항목 | 실제 값 |
| --- | --- |
| Origin | Blue EC2 Public DNS |
| Origin protocol | HTTP only, Port 80 |
| Default Cache | CachingDisabled + AllViewer |
| `/assets/*` | CachingOptimized + AllViewer |
| Access logging | 비활성 |
| WAF | 연결됨 |
| IPv6 | 활성 |

## 18. CloudFront Function·OAC·WAF

### 18.1 SPA Function

| 항목 | 실제 값 |
| --- | --- |
| 이름 | `buildgraph-demo-web-spa-rewrite` |
| Stage | LIVE |
| 상태 | DEPLOYED |
| Runtime | `cloudfront-js-2.0` |
| Association | Green Default Behavior, viewer-request |

### 18.2 OAC

| ID | 이름 | Signing | 연결 상태 |
| --- | --- | --- | --- |
| `ETLAXAQDCBJF1` | CloudFront 자동 생성 OAC | SigV4 always | Green S3 Origin에 사용 중 |
| `E32Q7M4JULG23V` | `buildgraph-demo-web-green-oac` | SigV4 always | 연결되지 않은 중복 OAC |

### 18.3 Green WAF

| 항목 | 실제 값 |
| --- | --- |
| 이름 | `CreatedByCloudFront-cf51f62f` |
| Default Action | Allow |
| Capacity | 925 |
| Sampled Requests | 활성 |
| CloudWatch Metrics | 활성 |

| Rule | Priority | 동작 |
| --- | ---: | --- |
| `AWSManagedRulesAmazonIpReputationList` | 0 | Count |
| `AWSManagedRulesCommonRuleSet` | 1 | Count |
| `AWSManagedRulesKnownBadInputsRuleSet` | 2 | Count |

현재 WAF는 Monitor/Count 모드이며 위협 요청을 실제 차단하지 않는다.

## 19. Phase 6 Green Runtime 검증값

| 항목 | 확인값 |
| --- | --- |
| Compose project | `buildgraph-green` |
| 서비스 | `nginx`, `api`, `xgb-reranker` |
| Host port | Nginx 80만 공개 |
| API 8080 | Container internal only |
| XGB 8091 | Container internal only |
| 5432·6379·5671 | Host 비공개 |
| `.env.prod` | `600 ubuntu:ubuntu` |
| 배포 Git SHA | `45a4b1e78cdf44cca1c13cfb55636a15ecdf438b` |
| Compose config SHA | `d9296779e0966af32c8783c600197c4c3cf6f75b07ab668ac3eaf0f49a3a1b78` |
| Nginx config | `nginx -t` PASS |
| `/healthz` | PASS |
| `/api/health` | DB 포함 PASS |
| RDS·Redis·MQ Smoke Test | PASS |
| RAG Vector | PASS |
| RabbitMQ Agent publish·consume | PASS |
| Redis Build Chat cache hit | 확인 |

## 20. 계획과 실제 설정의 차이

| 항목 | 계획·이전 문서 | 실제 AWS |
| --- | --- | --- |
| Green Key pair | 없음으로 시작한 계획 | RSA Key pair 연결됨 |
| EC2 Detailed Monitoring | 저비용 단계 비활성 계획 | Blue·Green 모두 활성 |
| Blue EBS 암호화 | 미확정 | 비활성 |
| Green EBS 암호화 | 활성 | 활성 |
| RDS 초기 Storage | 20 GiB 문서 | 30 GiB |
| RDS 최대 Storage | 100 GiB 문서 | 1000 GiB |
| RDS deletion protection | 활성 문서 | 비활성 |
| Redis 백업 | 별도 명시 없음 | 보존 0일 |
| Green Docker 로그 | 과거 화면에서 Never expire | 현재 14일 |
| Queue Alarm | 임계값 PENDING | Alarm 자체가 없음 |
| CloudFront Web Min TTL | 0 목표 | Managed policy로 1초 |
| S3 Versioning | 필수 조건 아님 | 활성 |
| S3 Lifecycle | 미정 | 없음 |
| WAF | 보안 보호 활성 | 세 Rule 모두 Count 모드 |
| Continuous Deployment | 초기 검토 | 현재 Policy와 Staging 모두 없음 |
| OAC | 하나 필요 | 두 개 존재, 하나만 사용 중 |

## 21. 운영·보안 확인 항목

이 절은 변경 지시가 아니라 현재 상태에서 확인된 주의사항을 기록한다.

| 우선순위 | 항목 | 현재 영향 |
| --- | --- | --- |
| 높음 | SSH 22가 `0.0.0.0/0`에 공개 | 키 유출 시 접근 위험 증가 |
| 높음 | RDS deletion protection 비활성 | 실수로 DB 삭제 가능 |
| 높음 | CloudWatch Alarm 0개 | 장애·Queue 적체 자동 알림 없음 |
| 중간 | WAF Count 모드 | 위협 탐지는 하지만 차단하지 않음 |
| 중간 | CloudFront -> EC2 HTTP | Edge와 Origin 사이 암호화 없음 |
| 중간 | Blue EBS 미암호화 | Blue Disk 저장 데이터 암호화 없음 |
| 중간 | AWS Config 미사용 | 과거 구성 변경 이력 추적 불가 |
| 비용 | RDS 최대 1000 GiB | 비정상 증가 시 비용 상승 가능 |
| 비용 | Versioning 활성·Lifecycle 없음 | 과거 Web object 버전 누적 |
| 비용 | RDS·MQ 로그 만료 없음 | 로그 저장량 지속 증가 |
| 비용 | Detailed Monitoring 활성 | EC2 모니터링 추가 비용 가능 |
| 정리 | 미사용 OAC 존재 | 기능 영향은 없지만 리소스 혼동 가능 |

## 22. 조회 무결성

이번 감사에서 수행하지 않은 작업:

- AWS 리소스 생성
- AWS 리소스 수정
- AWS 리소스 삭제
- IAM Policy 저장 또는 연결 변경
- Security Group 변경
- EC2·RDS·Redis·RabbitMQ 재시작
- S3 Object 업로드·삭제
- CloudFront Invalidation·배포 수정
- Secret value 조회
- 비밀번호·Token·API Key 출력

이 문서는 2026-07-14 조회 시점의 실제 AWS 상태를 나타낸다.
