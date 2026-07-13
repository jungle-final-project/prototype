# AWS 인프라 분리 Phase 4 ElastiCache Redis 콘솔 따라하기

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 4를 AWS Management Console에서 사용자가 직접 수행하기 위한 절차다.

이번 단계에서는 Green API가 사용할 빈 Amazon ElastiCache for Redis OSS를 생성한다. 기존 Blue EC2, `compose.prod.yaml`, Redis 컨테이너, CloudFront, RDS는 변경하거나 중지하지 않는다.

Phase 3까지의 전체 생성 현황은 [aws-infrastructure-phase-3-console-guide.md](aws-infrastructure-phase-3-console-guide.md)의 `27. Phase 3까지 생성·확인된 구성표`를 기준으로 한다.

## 0. 이번 Phase의 확정값

| 항목 | 최종값 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| Cache name | `buildgraph-demo-redis-green` |
| Engine | Redis OSS `7.1` |
| Deployment | Node-based cache |
| Cluster mode | Disabled |
| Node type | `cache.t4g.small` |
| Shards | 1 |
| Primary nodes | 1 |
| Replicas | 0 |
| Multi-AZ | Disabled |
| Automatic failover | Disabled |
| Preferred AZ | `ap-northeast-2b` |
| Network type | IPv4 |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Subnet group | `buildgraph-demo-redis-subnet-group` |
| Security group | `buildgraph-demo-redis-sg` / `sg-0dc3c8766358e57f4`만 연결 |
| Security group Source | 공유 API EC2 SG `sg-099aac782b77a854e` |
| Port | TCP `6379` |
| Encryption in transit | Enabled / Required |
| Encryption at rest | Enabled |
| Authentication | Redis OSS AUTH default user |
| Automatic backup | Disabled |
| Parameter group | Redis OSS 7.1용 콘솔 기본값 |

`cache.t4g.small`은 사용자가 최종 검토 화면에서 확정한 단일 burstable 노드다. Replica 없이 한 대만 사용해 비용을 제한하면서 `micro`보다 캐시·OAuth 임시 데이터·WebSocket ticket·Build Chat cache를 위한 메모리 여유를 확보한다. 메모리 또는 CPU 부족이 확인되면 Green 검증 후 더 큰 node type으로 확장한다.

## 1. 이번 단계에서 하지 않는 작업

1. Blue EC2를 중지하거나 재부팅하지 않는다.
2. Blue Redis 컨테이너를 중지하거나 Volume을 삭제하지 않는다.
3. Blue의 `.env.prod`에서 `SPRING_DATA_REDIS_*`를 변경하지 않는다.
4. 기존 Redis key를 export하거나 ElastiCache로 이전하지 않는다.
5. Redis SG에 `0.0.0.0/0`, 현재 사용자 IP, VPC 전체 CIDR을 추가하지 않는다.
6. 로컬 PC에서 ElastiCache로 직접 접속하려고 6379를 공개하지 않는다.
7. Serverless cache를 만들지 않는다.
8. Valkey 또는 Memcached를 선택하지 않는다.
9. Replica, Multi-AZ, automatic failover를 활성화하지 않는다.
10. 실제 AUTH token을 문서, Git, 채팅, 스크린샷에 남기지 않는다.
11. Green EC2나 API를 아직 배포하지 않는다.
12. Spring Boot Redis 설정은 이번 Phase에서 수정하지 않는다.

## 2. Phase 3 완료 여부 확인

ElastiCache 생성 전에 RDS 콘솔에서 다음을 확인한다.

1. `buildgraph-demo-postgres-green` 상태가 `Available`인지 확인한다.
2. 아직 `Backing-up`, `Creating`, `Configuring`이면 기다린다.
3. RDS가 `Available`이 아니면 ElastiCache 생성 버튼을 누르지 않는다.
4. Blue EC2와 기존 Compose가 계속 실행 중인지 확인한다.

Phase 3의 다음 항목도 생성 결과 화면에서 최종 확인한다.

```text
RDS storage: gp3 20 GiB
RDS autoscaling maximum: 100 GiB
Backup retention: 1 day
Deletion protection: Enabled
```

## 3. Phase 4 사전 리소스 확인

ElastiCache를 만들기 전에 다음 리소스가 있어야 한다.

| 리소스 | 확인값 |
| --- | --- |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Private 2a | `subnet-09bba1fd17639ce6a` / `10.0.32.0/24` |
| Private 2b | `subnet-0816bc2771fd5e1ca` / `10.0.33.0/24` |
| ElastiCache subnet group | `buildgraph-demo-redis-subnet-group` |
| Redis SG | `buildgraph-demo-redis-sg` / `sg-0dc3c8766358e57f4` |
| 공유 API EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` |

다음 순서로 확인한다.

1. ElastiCache 콘솔의 `Subnet groups`를 연다.
2. `buildgraph-demo-redis-subnet-group`을 선택한다.
3. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
4. Private 2a와 Private 2b만 포함됐는지 확인한다.
5. Public Subnet `10.0.0.0/20`, `10.0.16.0/20`이 포함되지 않았는지 확인한다.
6. EC2 콘솔의 `Security groups`로 이동한다.
7. `buildgraph-demo-redis-sg`를 연다.
8. Inbound rule이 Custom TCP `6379` 한 개인지 확인한다.
9. Source가 `sg-099aac782b77a854e`인지 확인한다.
10. Source가 `0.0.0.0/0`, `10.0.0.0/16`, 현재 사용자 IP가 아닌지 확인한다.

아래 중 하나라도 해당하면 ElastiCache를 생성하지 않고 Phase 2 설정부터 교정한다.

```text
ElastiCache subnet group이 없음
Subnet group에 Public Subnet이 포함됨
Redis SG가 다른 VPC에 있음
Redis SG 6379 Source가 sg-099aac782b77a854e가 아님
Redis SG 6379 Source가 0.0.0.0/0임
```

## 4. ElastiCache 콘솔 열기

1. [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 오른쪽 위 Region을 누른다.
3. `아시아 태평양(서울) ap-northeast-2`를 선택한다.
4. 상단 검색창에서 `ElastiCache`를 검색한다.
5. `Amazon ElastiCache`를 누른다.
6. 콘솔 오른쪽 위에도 서울 Region이 표시되는지 확인한다.
7. 왼쪽 메뉴에서 `Redis OSS caches` 또는 `Caches`를 누른다.
8. `buildgraph-demo-redis-green`이 이미 존재하지 않는지 검색한다.

동일한 이름이 이미 있으면 중복 생성하지 않고 기존 리소스의 상태와 설정을 먼저 확인한다.

## 5. Redis OSS cache 생성 시작

1. `Create Redis OSS cache` 또는 `Create cache`를 누른다.
2. Engine 선택란이 있으면 `Redis OSS`를 선택한다.
3. `Valkey`를 선택하지 않는다.
4. `Memcached`를 선택하지 않는다.
5. Deployment option에서 `Node-based cache`를 선택한다.
6. `Serverless`를 선택하지 않는다.
7. Creation method가 표시되면 `Design your own cache`를 선택한다.
8. `Easy create` 또는 `Restore from backup`을 선택하지 않는다.

콘솔 문구가 조금 다르더라도 최종 결과가 `Redis OSS + Node-based`인지 확인한다.

## 6. Cluster mode와 Engine 설정

1. Cluster mode에서 `Disabled`를 선택한다.
2. Cluster mode enabled 또는 compatible을 선택하지 않는다.
3. Engine version에서 Redis OSS `7.1`을 선택한다.
4. `7.0`, `6.x`, Extended Support 대상 버전을 선택하지 않는다.
5. Redis OSS Extended Support 동의 항목이 나타나면 활성화하지 않는다.
6. Port가 `6379`인지 확인한다.
7. Parameter group은 Redis OSS 7.1과 cluster mode disabled에 맞춰 콘솔이 제안하는 기본값을 유지한다.
8. Custom parameter group을 새로 만들지 않는다.

성공 기준:

```text
Engine: Redis OSS
Version: 7.1
Cluster mode: Disabled
Port: 6379
```

## 7. Cache 이름 입력

1. Name 또는 Replication group ID에 다음 값을 입력한다.

```text
buildgraph-demo-redis-green
```

2. Description이 표시되면 다음 값을 입력한다.

```text
BuildGraph Green API managed Redis cache
```

3. 이름 끝에 날짜나 임의 숫자를 붙이지 않는다.
4. 기존 Blue Redis와 데이터를 공유하거나 복원하지 않는다.

## 8. Node type과 복제 구성

1. Node type 또는 Cache node type에서 `cache.t4g.small`을 선택한다.
2. `cache.t4g.micro`, `cache.m*`, `cache.r*`를 임의로 선택하지 않는다.
3. Number of replicas 또는 Replicas per shard를 `0`으로 설정한다.
4. Number of shards가 표시되면 `1`을 유지한다.
5. Multi-AZ를 `Disabled`로 설정한다.
6. Automatic failover를 `Disabled`로 설정한다.
7. Data tiering을 활성화하지 않는다.

성공 기준:

```text
Node type: cache.t4g.small
Primary: 1
Replica: 0
Shard: 1
Multi-AZ: Disabled
Automatic failover: Disabled
```

`cache.t4g.small`이 서울 Region의 목록에 보이지 않으면 다른 클래스를 임의로 선택하지 않고 중단한다.

## 9. Network type과 Subnet Group 설정

1. Connectivity 또는 Network 섹션을 연다.
2. Network type에서 `IPv4`를 선택한다.
3. VPC에서 다음 값을 선택한다.

```text
buildgraph-demo-vpc (vpc-06c90b864a62f93a4)
```

4. Subnet group에서 다음 값을 선택한다.

```text
buildgraph-demo-redis-subnet-group
```

5. `default` Subnet Group을 선택하지 않는다.
6. 새 Subnet Group을 즉석에서 만들지 않는다.
7. Public Subnet을 직접 선택하지 않는다.

## 10. Availability Zone 배치

1. Availability Zone placement가 표시되면 `Specify availability zones`를 선택한다.
2. Primary node의 AZ로 `ap-northeast-2b`를 선택한다.
3. Replica AZ 입력란이 있더라도 replica 수가 0인지 다시 확인한다.
4. AZ 선택란이 표시되지 않으면 `No preference` 또는 콘솔 기본값을 유지한다.
5. AZ가 자동 선택되더라도 Subnet Group을 바꾸지 않는다.

`ap-northeast-2b`가 우선값이지만 자동으로 `2a`에 생성되어도 같은 VPC local route를 통해 Green EC2가 접근할 수 있으므로 구조 오류는 아니다.

## 11. Redis Security Group 연결

1. Security groups에서 `Choose existing`을 선택한다.
2. 다음 보안 그룹 하나만 선택한다.

```text
buildgraph-demo-redis-sg (sg-0dc3c8766358e57f4)
```

3. `default` SG가 자동 선택되어 있으면 선택을 해제한다.
4. `buildgraph-demo-ec2-sg`를 ElastiCache에 직접 연결하지 않는다.
5. `buildgraph-demo-ec2-sg`는 Redis SG의 Inbound Source로만 사용한다.
6. `buildgraph-demo-rds-sg`, `buildgraph-demo-rabbitmq-sg`를 선택하지 않는다.

구조는 다음과 같다.

```text
Blue EC2와 향후 Green EC2
└─ buildgraph-demo-ec2-sg / sg-099aac782b77a854e
   └─ TCP 6379 요청
      └─ buildgraph-demo-redis-sg / sg-0dc3c8766358e57f4
         └─ ElastiCache Redis OSS
```

## 12. 전송 중·저장 암호화 설정

1. Encryption at rest를 활성화한다.
2. KMS key가 표시되면 AWS managed key 또는 콘솔 기본값을 유지한다.
3. Encryption in transit를 활성화한다.
4. In-transit encryption mode가 표시되면 `Required`를 선택한다.
5. `Preferred`를 선택하지 않는다.
6. `No encryption`을 선택하지 않는다.

Redis AUTH token은 전송 중 암호화가 활성화된 cache에서만 사용할 수 있다. Green Spring Boot는 Phase 6에서 TLS 연결을 사용한다.

## 13. Redis AUTH token 생성

1. Access control에서 `Redis OSS AUTH default user access` 또는 `AUTH default user`를 선택한다.
2. `No access control`을 선택하지 않는다.
3. 이번 빠른 배포에서는 User group/RBAC와 IAM authentication을 선택하지 않는다.
4. 개인 비밀번호 관리자에서 새 임의 token을 생성한다.
5. token 길이는 32자 이상으로 만든다.
6. 영문 대문자, 영문 소문자, 숫자, `-`를 조합한다.
7. 공백과 `/`, `'`, `"`, `@`를 사용하지 않는다.
8. AWS가 허용하는 특수문자는 `!`, `&`, `#`, `$`, `^`, `<`, `>`, `-`로 제한된다.
9. 비밀번호 관리자에 다음 이름으로 저장한다.

```text
buildgraph-demo-redis-green-auth-token
```

10. AUTH token 입력란과 확인 입력란에 같은 값을 넣는다.
11. 실제 token을 문서, 채팅, GitHub Secret, 스크린샷에 입력하지 않는다.
12. Phase 6에서 이 token을 Secrets Manager로 옮기기 전까지 비밀번호 관리자를 원본으로 사용한다.

AUTH default user 방식은 별도 username 없이 password만 사용한다. Phase 6 환경변수에서 `SPRING_DATA_REDIS_USERNAME`은 설정하지 않고 `SPRING_DATA_REDIS_PASSWORD`에 AUTH token을 전달한다.

## 14. Backup과 Maintenance 설정

Redis 데이터는 캐시, 짧은 수명의 ticket, 임시 상태이며 기존 데이터를 이전하지 않는다.

1. Automatic backups를 비활성화한다.
2. Snapshot retention period가 표시되면 `0` 또는 Disabled인지 확인한다.
3. S3 snapshot restore를 선택하지 않는다.
4. Maintenance window는 `No preference` 또는 기본값을 유지한다.
5. Auto minor version upgrade가 표시되면 활성화한다.
6. SNS notification topic은 만들지 않는다.
7. Log delivery가 표시되면 이번 빠른 배포에서는 비활성화한다.
8. 기본 CloudWatch cache metrics는 생성 후 Monitoring 탭에서 확인한다.

## 15. Tags 입력

Tags는 선택 사항이지만 리소스 식별을 위해 다음 세 개를 추가한다.

| Key | Value |
| --- | --- |
| `Name` | `buildgraph-demo-redis-green` |
| `Environment` | `demo-green` |
| `Role` | `cache` |

태그 때문에 생성이 지연되거나 오류가 발생하면 태그 없이 생성하고 나중에 추가할 수 있다.

## 16. 생성 전 최종 검토

`Create`를 누르기 전에 다음 표와 화면 값을 한 줄씩 비교한다.

| 항목 | 최종값 |
| --- | --- |
| Engine | Redis OSS 7.1 |
| Deployment | Node-based |
| Cache name | `buildgraph-demo-redis-green` |
| Cluster mode | Disabled |
| Node type | `cache.t4g.small` |
| Primary / Replica | 1 / 0 |
| Multi-AZ | Disabled |
| Automatic failover | Disabled |
| Preferred AZ | `ap-northeast-2b` 또는 No preference |
| VPC | `vpc-06c90b864a62f93a4` |
| Subnet group | `buildgraph-demo-redis-subnet-group` |
| Security group | `buildgraph-demo-redis-sg`만 선택 |
| Port | 6379 |
| In-transit encryption | Enabled / Required |
| At-rest encryption | Enabled |
| Access control | Redis OSS AUTH default user |
| Automatic backups | Disabled |

다음 중 하나라도 발견되면 생성하지 않는다.

```text
Serverless가 선택됨
Valkey 또는 Memcached가 선택됨
Cluster mode가 Enabled임
Replica가 1개 이상임
Multi-AZ 또는 automatic failover가 Enabled임
default Subnet Group이 선택됨
default SG가 추가됨
Redis SG 외의 SG가 ElastiCache에 연결됨
In-transit encryption이 Disabled 또는 Preferred임
Access control이 No access control임
```

## 17. ElastiCache 생성

1. 예상 월 비용 요약이 표시되면 노드가 `cache.t4g.small` 한 대인지 확인한다.
2. `Create` 또는 `Create cache`를 누른다.
3. Caches 목록으로 이동한다.
4. `buildgraph-demo-redis-green` 상태가 `Creating`인지 확인한다.
5. 생성 중에 Modify 또는 Delete를 누르지 않는다.
6. 상태가 `Available`이 될 때까지 기다린다.

## 18. 생성 결과 검증

상태가 `Available`이 되면 `buildgraph-demo-redis-green`을 선택하고 다음을 확인한다.

1. Engine이 Redis OSS `7.1`인지 확인한다.
2. Cluster mode가 Disabled인지 확인한다.
3. Node type이 `cache.t4g.small`인지 확인한다.
4. Node 수가 1개인지 확인한다.
5. Replica가 0개인지 확인한다.
6. Multi-AZ와 automatic failover가 Disabled인지 확인한다.
7. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
8. Subnet group이 `buildgraph-demo-redis-subnet-group`인지 확인한다.
9. Security group이 `buildgraph-demo-redis-sg` 하나인지 확인한다.
10. Port가 `6379`인지 확인한다.
11. Encryption in transit가 Enabled이고 mode가 Required인지 확인한다.
12. Encryption at rest가 Enabled인지 확인한다.
13. AUTH token authentication이 활성인지 확인한다.
14. Automatic backup이 Disabled인지 확인한다.
15. 실제 Availability Zone을 기록한다.
16. Primary endpoint 또는 Endpoint의 hostname을 기록한다.

Cluster mode disabled 단일 노드에서는 Primary endpoint를 사용한다. Reader endpoint나 Configuration endpoint를 Green 환경변수에 넣지 않는다.

Endpoint는 다음과 비슷하다.

```text
buildgraph-demo-redis-green.xxxxxx.ng.0001.apn2.cache.amazonaws.com
```

Endpoint는 비밀번호가 아니지만 AUTH token과 함께 한 줄에 기록하지 않는다.

## 19. 이번 Phase에서 연결 테스트를 하지 않는 이유

Green EC2는 아직 생성되지 않았다. Redis SG Source는 Blue와 Green이 공유할 `sg-099aac782b77a854e`이므로 Blue EC2도 네트워크상 접근할 수 있지만, Blue의 `.env.prod`와 Compose Redis endpoint는 변경하지 않는다.

연결을 확인하려고 다음 작업을 하지 않는다.

```text
6379를 0.0.0.0/0에 공개
현재 사용자 IP를 Redis SG Source에 추가
Blue의 Redis 환경변수를 ElastiCache endpoint로 변경
AUTH token을 터미널 history에 직접 입력
TLS를 끄고 평문 연결 시도
```

Phase 6에서 Green EC2에 공유 SG를 연결하고 API용 환경변수를 구성한 뒤 `ManagedInfrastructureSmokeTest`로 TLS·AUTH·쓰기·읽기·TTL을 검증한다.

## 20. Phase 6 환경변수 예약

이번 Phase에서는 실제 파일에 입력하지 않고 다음 매핑만 기록한다.

```text
SPRING_DATA_REDIS_HOST=<PRIMARY_ENDPOINT_HOSTNAME>
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_USERNAME 환경변수는 만들지 않음
SPRING_DATA_REDIS_PASSWORD=<AUTH_TOKEN_FROM_SECRET>
SPRING_DATA_REDIS_SSL_ENABLED=true
```

Host에는 `rediss://` 접두사와 `:6379`를 붙이지 않는다. Port와 TLS 여부는 별도 환경변수로 전달한다.

## 21. Phase 4 결과 기록

AUTH token을 제외하고 다음 형식으로 결과를 기록한다.

```text
Cache name: buildgraph-demo-redis-green
Status: Available | Creating | Failed
Engine: Redis OSS
Engine version:
Cluster mode: Disabled
Node type: cache.t4g.small
Node count: 1
Replica count: 0
Multi-AZ: Disabled
Automatic failover: Disabled
Availability Zone:
VPC: vpc-06c90b864a62f93a4
Subnet group: buildgraph-demo-redis-subnet-group
Redis SG ID: sg-0dc3c8766358e57f4
Redis SG Source: sg-099aac782b77a854e
Port: 6379
In-transit encryption: Enabled / Required
At-rest encryption: Enabled
Authentication: AUTH default user
Primary endpoint:
Automatic backup: Disabled
AUTH token stored outside Git/chat: Yes | No
```

다음 값은 기록하거나 전달하지 않는다.

1. Redis AUTH token
2. AUTH token이 포함된 URI
3. `.env.prod` 원문
4. AWS access key
5. SSH private key

## 22. Phase 4 완료 조건

- [ ] Redis OSS 7.1 node-based cache가 `Available`
- [ ] Cache name이 `buildgraph-demo-redis-green`
- [ ] Cluster mode가 Disabled
- [ ] Node type이 `cache.t4g.small`
- [ ] Primary 1개, replica 0개
- [ ] Multi-AZ와 automatic failover 비활성
- [ ] VPC가 `vpc-06c90b864a62f93a4`
- [ ] Subnet group이 `buildgraph-demo-redis-subnet-group`
- [ ] Redis에 `buildgraph-demo-redis-sg`만 연결
- [ ] Redis SG 6379 Source가 공유 API EC2 SG `sg-099aac782b77a854e`
- [ ] `0.0.0.0/0`, 현재 사용자 IP, 다른 SG가 Redis SG Source에 없음
- [ ] Port가 `6379`
- [ ] In-transit encryption이 Required
- [ ] At-rest encryption 활성
- [ ] AUTH default user 인증 활성
- [ ] Automatic backup 비활성
- [ ] Primary endpoint 기록 완료
- [ ] AUTH token을 비밀번호 관리자에 저장
- [ ] AUTH token을 Git·문서·채팅에 노출하지 않음
- [ ] Blue EC2·Compose·Redis 컨테이너에 변경 없음

위 조건을 확인하면 Phase 4를 완료하고 [Phase 5 Amazon MQ RabbitMQ 콘솔 가이드](aws-infrastructure-phase-5-console-guide.md)로 넘어간다.

## 23. 문제 발생 시 중단 기준

### `buildgraph-demo-redis-subnet-group`이 목록에 없음

1. Region이 서울인지 확인한다.
2. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
3. ElastiCache Subnet Group에 Private 2a·2b가 포함됐는지 확인한다.
4. `default` Subnet Group으로 대신 진행하지 않는다.

### `buildgraph-demo-redis-sg`가 목록에 없음

1. VPC가 올바른지 확인한다.
2. Redis SG가 같은 VPC에 생성됐는지 확인한다.
3. SG ID가 `sg-0dc3c8766358e57f4`인지 확인한다.
4. 새 SG를 즉석에서 만들지 않고 Phase 2로 돌아간다.

### AUTH token 검증 오류

1. token 길이가 16~128자인지 확인한다.
2. 공백이 없는지 확인한다.
3. 특수문자가 `!`, `&`, `#`, `$`, `^`, `<`, `>`, `-` 중 하나인지 확인한다.
4. token을 채팅에 보내지 않는다.
5. 비밀번호 관리자에서 새 token을 만들어 다시 입력한다.

### 생성 후 AZ가 `ap-northeast-2a`

같은 VPC local route를 통해 Green EC2가 접근할 수 있으므로 즉시 삭제하지 않는다. Phase 6 연결 테스트를 먼저 수행한다.

### 상태가 `Create failed` 또는 장시간 `Creating`

1. 같은 이름의 cache를 다시 만들지 않는다.
2. ElastiCache Events에서 가장 최근 오류를 확인한다.
3. 오류 문구만 전달하고 AUTH token은 전달하지 않는다.

### Phase 6에서 timeout 발생

다음 순서로 확인한다.

1. Green EC2에 `sg-099aac782b77a854e`가 연결됐는지 확인한다.
2. Redis SG Inbound 6379 Source가 같은 SG인지 확인한다.
3. Endpoint hostname과 port를 분리해 입력했는지 확인한다.
4. `SPRING_DATA_REDIS_SSL_ENABLED=true`인지 확인한다.
5. 6379를 인터넷에 공개하지 않는다.

## 공식 참고 문서

- [ElastiCache node-based Redis OSS 생성](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SubnetGroups.designing-cluster-pre.redis.html)
- [ElastiCache Subnet Group](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/SubnetGroups.html)
- [Redis OSS 지원 버전](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/engine-versions.html)
- [지원 Node type](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/CacheNodes.SupportedTypes.html)
- [Redis OSS AUTH token](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/auth.html)
- [ElastiCache 전송 중 암호화](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/in-transit-encryption.html)
- [Redis OSS cluster mode disabled와 enabled](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/Replication.Redis-RedisCluster.html)
