# AWS 인프라 분리 Phase 3 Green RDS PostgreSQL 콘솔 따라하기

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 3을 AWS Management Console에서 사용자가 직접 수행하기 위한 절차다.

이번 단계에서는 Green API가 나중에 사용할 빈 RDS PostgreSQL을 생성한다. 기존 Blue EC2, `compose.prod.yaml`, PostgreSQL 컨테이너, CloudFront는 변경하거나 중지하지 않는다.

## 0. 이번 Phase의 빠른 배포 결정

| 항목 | 설정 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| Engine | Amazon RDS for PostgreSQL 16 최신 minor |
| DB instance identifier | `buildgraph-demo-postgres-green` |
| Initial database name | `buildgraph` |
| Master username | `buildgraph` |
| DB instance class | `db.t4g.small` |
| Availability | Single DB instance, Single-AZ |
| Preferred AZ | `ap-northeast-2b` |
| Storage | General Purpose SSD `gp3`, 20 GiB |
| Storage autoscaling | 활성, 최대 100 GiB |
| VPC | `buildgraph-demo-vpc` |
| DB subnet group | `buildgraph-demo-rds-subnet-group` |
| Public access | `No` |
| VPC security group | `buildgraph-demo-rds-sg`만 연결 |
| Port | TCP `5432` |
| Backup retention | 1일 |
| Deletion protection | 활성 |
| Credential management | 빠른 배포 동안 Self managed, 이후 Secrets Manager 이전 |

`db.t4g.small`은 데모 비용을 낮추면서 전체 Flyway migration, seed, pgvector 초기화를 수행하기 위한 시작 크기다. 콘솔에서 이 클래스가 보이지 않으면 다른 클래스를 임의로 선택하지 않고 중단한다.

현재 API는 Flyway와 애플리케이션 쿼리에 동일한 datasource 사용자를 사용한다. 빠른 배포에서는 기존 기본값과 같은 `buildgraph` 사용자를 사용하고, migration/runtime 사용자 분리는 Green 배포가 끝난 뒤 하드닝 작업으로 미룬다.

## 1. 이번 단계에서 하지 않는 작업

1. Blue EC2를 중지하거나 재부팅하지 않는다.
2. Blue의 PostgreSQL 컨테이너를 중지하지 않는다.
3. Blue의 `.env.prod`에서 `POSTGRES_*` 값을 변경하지 않는다.
4. 기존 PostgreSQL 데이터를 dump하거나 RDS로 이전하지 않는다.
5. RDS를 Public access로 만들지 않는다.
6. RDS SG에 `0.0.0.0/0`, 현재 사용자 IP, 공유 SG 이외의 Source를 추가하지 않는다.
7. 로컬 PC에서 RDS로 직접 접속하려고 5432를 공개하지 않는다.
8. `vector`, `pgcrypto`, Flyway migration을 수동 실행하지 않는다.
9. Green EC2나 API를 아직 배포하지 않는다.
10. 실제 DB 비밀번호를 문서, Git, 채팅, 스크린샷에 남기지 않는다.

## 2. Phase 2 완료 여부 확인

RDS 생성 전에 다음 리소스가 모두 있어야 한다.

| 리소스 | 확인값 |
| --- | --- |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Private 2a | `10.0.32.0/24`, `ap-northeast-2a` |
| Private 2b | `10.0.33.0/24`, `ap-northeast-2b` |
| DB subnet group | `buildgraph-demo-rds-subnet-group` |
| RDS SG | `buildgraph-demo-rds-sg` / `sg-0587fdbc766f9088f` |
| Blue·Green 공유 API EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` |

다음 순서로 확인한다.

1. VPC 콘솔에서 Region이 `ap-northeast-2`인지 확인한다.
2. `buildgraph-demo-rds-subnet-group`에 Private Subnet 2개가 포함됐는지 확인한다.
3. Public Subnet `10.0.0.0/20`, `10.0.16.0/20`이 DB subnet group에 포함되지 않았는지 확인한다.
4. EC2 또는 VPC 콘솔에서 `buildgraph-demo-rds-sg`를 연다.
5. Inbound rule이 TCP `5432` 한 개인지 확인한다.
6. Source가 공유 API EC2 SG `sg-099aac782b77a854e`인지 확인한다.
7. 별도 Green SG로 교체하지 않는다.
8. `0.0.0.0/0`, `10.0.0.0/16`, 현재 사용자 IP가 Source에 없는지 확인한다.

아래 중 하나라도 해당하면 RDS를 생성하지 않고 Phase 2를 먼저 교정한다.

```text
공유 API EC2 SG ID가 sg-099aac782b77a854e와 다름
RDS SG Source가 공유 API EC2 SG가 아님
RDS SG Source가 0.0.0.0/0임
DB subnet group에 Public Subnet이 포함됨
DB subnet group 상태가 Complete가 아님
```

## 3. RDS 콘솔과 Region 확인

1. [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 오른쪽 위 Region을 누른다.
3. `아시아 태평양(서울) ap-northeast-2`를 선택한다.
4. 상단 검색창에서 `RDS`를 검색한다.
5. `Amazon RDS`를 누른다.
6. RDS 콘솔 오른쪽 위에도 서울 Region이 표시되는지 확인한다.
7. 왼쪽 메뉴에서 `Databases`를 누른다.
8. `buildgraph-demo-postgres-green`이 이미 존재하지 않는지 확인한다.

동일한 identifier가 이미 있으면 새로 만들지 않고 기존 리소스의 상태와 설정을 먼저 확인한다.

## 4. Standard create 선택

1. `Create database`를 누른다.
2. Database creation method에서 `Standard create`를 선택한다.
3. `Easy create`는 선택하지 않는다.

Standard create를 사용해야 기존 VPC, Private DB subnet group, RDS SG, Single-AZ를 직접 지정할 수 있다.

## 5. PostgreSQL Engine 선택

1. Engine options에서 `PostgreSQL`을 선택한다.
2. `Aurora (PostgreSQL Compatible)`를 선택하지 않는다.
3. Engine version에서 PostgreSQL `16` 계열의 가장 최신 minor를 선택한다.
4. 콘솔에 `16.14`가 있으면 `16.14`를 선택한다.
5. `17`, `18` 또는 이전 major를 선택하지 않는다.
6. RDS Extended Support 선택 항목이 표시되면 PostgreSQL 16의 일반 지원 설정을 유지한다.

성공 기준:

```text
Engine: PostgreSQL
Major version: 16
```

프로젝트의 첫 Flyway migration은 다음 확장을 생성한다.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

PostgreSQL 16용 RDS는 `vector` 확장을 지원한다. 실제 확장 생성은 Green API를 처음 실행하는 Phase 6에서 Flyway가 수행한다.

## 6. Template 선택

1. Templates에서 `Dev/Test`를 선택한다.
2. `Production`을 선택하지 않는다.
3. `Free tier`가 표시되더라도 이 문서에서는 선택하지 않는다.

`Production`은 Multi-AZ와 더 큰 스토리지 구성이 자동 선택될 수 있으므로 현재 저비용 Single-AZ 결정과 맞지 않는다.

## 7. Availability and durability 선택

1. Availability & durability 섹션을 연다.
2. `Single DB instance` 또는 `Single-AZ DB instance deployment`를 선택한다.
3. `Multi-AZ DB cluster`를 선택하지 않는다.
4. `Multi-AZ DB instance`를 선택하지 않는다.

성공 기준:

```text
Deployment option: Single DB instance
Standby instance: 없음
```

## 8. DB identifier와 계정 입력

1. DB instance identifier에 다음 값을 입력한다.

```text
buildgraph-demo-postgres-green
```

2. Master username에 다음 값을 입력한다.

```text
buildgraph
```

3. Credentials management에서 `Self managed`를 선택한다.
4. `Manage master credentials in AWS Secrets Manager`는 빠른 배포 단계에서는 선택하지 않는다.
5. Master password에 새 RDS 전용 비밀번호를 입력한다.
6. Confirm master password에 같은 값을 다시 입력한다.
7. 기존 Blue의 `POSTGRES_PASSWORD`를 재사용하지 않는다.
8. 비밀번호는 개인 비밀번호 관리자에 `buildgraph-demo-postgres-green` 이름으로 저장한다.
9. 비밀번호를 이 문서, 채팅, GitHub Secret, 스크린샷에 아직 입력하지 않는다.

비밀번호는 콘솔 검증을 통과하는 충분히 긴 임의 값으로 생성한다. 나중에 Green EC2의 환경 변수 또는 Secrets Manager로만 전달한다.

## 9. DB instance class 선택

1. DB instance class에서 `Burstable classes`를 선택한다.
2. 클래스 검색 또는 목록에서 다음 값을 선택한다.

```text
db.t4g.small
```

3. `db.t4g.micro`를 선택하지 않는다. 전체 migration과 pgvector 초기화 시 메모리 여유가 너무 작을 수 있다.
4. `db.m*`, `db.r*` 계열로 임의 변경하지 않는다.
5. `db.t4g.small`이 보이지 않으면 생성 버튼을 누르지 않고 중단한다.

## 10. Storage 설정

1. Storage type에서 `General Purpose SSD (gp3)`를 선택한다.
2. Allocated storage에 `20`을 입력한다.

```text
Allocated storage: 20 GiB
```

3. Provisioned IOPS가 표시되면 gp3 기본값 `3000`을 유지한다.
4. Storage throughput이 표시되면 기본값 `125 MiB/s`를 유지한다.
5. `Provisioned IOPS (io1/io2)`를 선택하지 않는다.
6. Storage autoscaling을 활성화한다.
7. Maximum storage threshold에 `100`을 입력한다.

```text
Maximum storage threshold: 100 GiB
```

8. Storage encryption을 활성화한다.
9. KMS key는 AWS managed key인 `aws/rds`를 선택한다.

## 11. EC2 자동 연결을 사용하지 않도록 설정

Connectivity 섹션을 연다.

1. Compute resource 항목에서 `Don't connect to an EC2 compute resource`를 선택한다.
2. `Connect to an EC2 compute resource`를 선택하지 않는다.

자동 EC2 연결을 선택하면 RDS가 새 Subnet이나 `rds-ec2-*`, `ec2-rds-*` 보안 그룹을 자동 생성할 수 있다. 기존 공유 API EC2 SG와 RDS SG를 사용하기 위해 자동 연결을 끈다.

## 12. VPC와 DB subnet group 선택

1. Network type에서 `IPv4`를 선택한다.
2. Virtual private cloud에서 다음 VPC를 선택한다.

```text
buildgraph-demo-vpc (vpc-06c90b864a62f93a4)
```

3. 기본 VPC나 다른 VPC를 선택하지 않는다.
4. DB subnet group에서 다음 값을 선택한다.

```text
buildgraph-demo-rds-subnet-group
```

5. `Automatic setup` 또는 `default` DB subnet group을 선택하지 않는다.
6. Public access에서 `No`를 선택한다.
7. `Yes`를 선택하지 않는다.

성공 기준:

```text
VPC: vpc-06c90b864a62f93a4
DB subnet group: buildgraph-demo-rds-subnet-group
Public access: No
```

## 13. RDS Security Group 선택

1. VPC security group에서 `Choose existing`을 선택한다.
2. 기존 보안 그룹 목록에서 다음 하나만 선택한다.

```text
buildgraph-demo-rds-sg
```

3. `default` SG가 자동 선택되어 있으면 선택을 해제한다.
4. `buildgraph-demo-ec2-sg`를 RDS에 직접 연결하지 않는다. 이 공유 SG는 RDS SG의 Inbound Source로만 사용한다.
5. 별도 Green 전용 SG를 새로 만들거나 RDS에 연결하지 않는다.
6. 최종 선택 목록에 `buildgraph-demo-rds-sg`만 있는지 확인한다.

구조는 다음과 같아야 한다.

```text
Blue EC2와 향후 Green EC2
└─ buildgraph-demo-ec2-sg / sg-099aac782b77a854e
   └─ TCP 5432 요청
      └─ buildgraph-demo-rds-sg
         └─ RDS
```

## 14. AZ와 Port 확인

1. Additional connectivity configuration을 연다.
2. Availability Zone 선택란이 표시되면 `ap-northeast-2b`를 선택한다.
3. AZ 선택란이 표시되지 않으면 임의로 다른 설정을 바꾸지 않는다. 생성 후 실제 AZ를 확인한다.
4. Database port가 `5432`인지 확인한다.
5. 포트를 `15432` 같은 다른 번호로 변경하지 않는다.
6. RDS Proxy는 생성하지 않는다.
7. Certificate authority는 콘솔의 최신 기본값을 유지한다.

`ap-northeast-2b`는 비용과 지연을 줄이기 위한 우선값이다. Subnet Group에 2a와 2b가 모두 있으므로 콘솔이 AZ를 자동 선택해도 네트워크 구조 자체는 유효하다.

## 15. Database authentication 설정

1. Database authentication에서 `Password authentication`을 선택한다.
2. IAM DB authentication은 이번 빠른 배포에서 활성화하지 않는다.
3. Kerberos authentication은 활성화하지 않는다.

## 16. Monitoring 설정

1. Database Insights 옵션이 표시되면 `Standard` 또는 기본값을 사용한다.
2. `Advanced`는 선택하지 않는다.
3. Enhanced Monitoring은 활성화하지 않는다.
4. DevOps Guru는 활성화하지 않는다.
5. CloudWatch log export에서 `PostgreSQL log`를 선택할 수 있으면 활성화한다.
6. 다른 로그는 기본값을 유지한다.

모니터링 고도화는 Green 배포 후 진행한다. RDS 생성 자체를 지연시키는 별도 모니터링 리소스는 이번 Phase에서 만들지 않는다.

## 17. Initial database와 Parameter group 설정

Additional configuration을 연다.

1. Initial database name에 다음 값을 입력한다.

```text
buildgraph
```

2. 이 값을 비워 두지 않는다. API JDBC URL이 `/buildgraph` 데이터베이스에 연결한다.
3. DB parameter group은 PostgreSQL 16의 `default.postgres16`을 사용한다.
4. Option group이 표시되면 기본값을 유지한다.
5. 별도 custom parameter group을 만들지 않는다.

## 18. Backup과 Maintenance 설정

1. Enable automated backups를 활성화한다.
2. Backup retention period를 `1 day`로 설정한다.
3. Backup window는 `No preference` 또는 기본값을 유지한다.
4. Copy tags to snapshots를 활성화한다.
5. Enable encryption은 Storage 단계에서 활성화된 상태인지 다시 확인한다.
6. Auto minor version upgrade를 활성화한다.
7. Maintenance window는 `No preference` 또는 기본값을 유지한다.
8. `Enable deletion protection`을 활성화한다.

Deletion protection은 빈 DB를 잘못 삭제하는 것을 막는다. 나중에 DB를 정말 삭제할 때는 먼저 Modify에서 이 옵션을 꺼야 한다.

## 19. 최종 입력값 검토

`Create database`를 누르기 전에 아래 표와 화면 값을 한 줄씩 비교한다.

| 항목 | 최종값 |
| --- | --- |
| Engine | PostgreSQL 16 최신 minor |
| Template | Dev/Test |
| Availability | Single DB instance |
| Identifier | `buildgraph-demo-postgres-green` |
| Initial database | `buildgraph` |
| Master username | `buildgraph` |
| Class | `db.t4g.small` |
| Storage | gp3 20 GiB, 최대 100 GiB |
| VPC | `vpc-06c90b864a62f93a4` |
| DB subnet group | `buildgraph-demo-rds-subnet-group` |
| Public access | No |
| Security group | `buildgraph-demo-rds-sg`만 선택 |
| Preferred AZ | `ap-northeast-2b` |
| Port | 5432 |
| Backup | 1일 |
| Deletion protection | Enabled |

다음 중 하나라도 발견되면 `Create database`를 누르지 않는다.

```text
Multi-AZ가 선택됨
Public access가 Yes임
default VPC가 선택됨
default DB subnet group이 선택됨
default SG가 추가됨
Blue EC2 SG가 추가됨
Initial database name이 비어 있음
DB port가 5432가 아님
```

## 20. RDS 생성

1. 예상 월 비용 요약을 확인한다.
2. 선택값이 19번 표와 일치하면 `Create database`를 누른다.
3. 비밀번호 저장 안내가 나타나면 비밀번호 관리자에 저장됐는지 확인하고 창을 닫는다.
4. Databases 목록으로 이동한다.
5. `buildgraph-demo-postgres-green` 상태가 `Creating`인지 확인한다.
6. 생성 중에 Modify, Reboot, Delete를 누르지 않는다.
7. 상태가 `Available`이 될 때까지 기다린다.

RDS는 생성이 완료되기 전부터 설정에 따라 비용이 발생할 수 있다. 사용하지 않을 리소스를 중복 생성하지 않는다.

## 21. 생성 결과 검증

상태가 `Available`이 되면 `buildgraph-demo-postgres-green`을 선택하고 다음을 확인한다.

1. Connectivity & security 탭을 연다.
2. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
3. DB subnet group이 `buildgraph-demo-rds-subnet-group`인지 확인한다.
4. Publicly accessible이 `No`인지 확인한다.
5. VPC security groups에 `buildgraph-demo-rds-sg`만 있는지 확인한다.
6. Port가 `5432`인지 확인한다.
7. Availability Zone을 확인한다.
8. Configuration 탭에서 Engine이 PostgreSQL 16인지 확인한다.
9. DB instance class가 `db.t4g.small`인지 확인한다.
10. Storage가 gp3 20 GiB인지 확인한다.
11. Deletion protection이 활성인지 확인한다.
12. Endpoint를 기록한다.

Endpoint는 다음과 비슷한 DNS 이름이다.

```text
buildgraph-demo-postgres-green.xxxxxxxxxxxx.ap-northeast-2.rds.amazonaws.com
```

Endpoint 자체는 비밀번호가 아니지만 문서에는 결과 기록란에만 적는다. JDBC URL은 Phase 6에서 다음 형식으로 만든다.

```text
jdbc:postgresql://<RDS_ENDPOINT>:5432/buildgraph
```

## 22. 이번 Phase에서 연결 테스트를 하지 않는 이유

현재 RDS SG의 Source는 Blue와 Green이 공유할 `sg-099aac782b77a854e`다. 따라서 Blue EC2도 네트워크상 RDS에 접근할 수 있지만, 이번 Phase에서는 Blue의 `.env.prod`와 Compose endpoint를 변경하지 않고 기존 PostgreSQL 컨테이너를 계속 사용한다. 로컬 PC에서는 RDS에 직접 접근할 수 없는 것이 정상이다.

연결을 확인하려고 다음 작업을 하지 않는다.

```text
Public access를 Yes로 변경
5432를 0.0.0.0/0에 공개
공유 SG 이외의 Source를 추가
현재 사용자 IP를 RDS SG Source에 추가
```

Phase 6에서 Green EC2에 `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e`를 연결한 다음 Flyway와 실제 연결 테스트를 실행한다.

## 23. Phase 6에서 실행할 검증 예약

Green API를 처음 실행하면 `V1__extensions.sql`부터 전체 Flyway migration이 실행된다. 그때 다음을 확인한다.

```sql
SELECT extname, extversion
FROM pg_extension
WHERE extname IN ('vector', 'pgcrypto');
```

성공 기준:

```text
vector 존재
pgcrypto 존재
flyway_schema_history에 성공한 migration 존재
/api/health 성공
```

이 검증은 Phase 3에서 실행하지 않는다.

## 24. Phase 3 결과 기록

비밀번호를 제외하고 다음 형식으로 결과를 기록한다.

```text
RDS identifier: buildgraph-demo-postgres-green
Status: Available | Creating | Failed
Engine version:
DB instance class: db.t4g.small
Availability Zone:
VPC: vpc-06c90b864a62f93a4
DB subnet group: buildgraph-demo-rds-subnet-group
RDS SG ID: sg-0587fdbc766f9088f
RDS SG Source: sg-099aac782b77a854e
Publicly accessible: No
Port: 5432
Initial database: buildgraph
Endpoint:
Backup retention: 1 day
Deletion protection: Enabled
Master password stored outside Git/chat: Yes | No
```

다음 값은 기록하거나 전달하지 않는다.

1. Master password
2. JDBC URL에 비밀번호를 포함한 문자열
3. `.env.prod` 원문
4. AWS access key
5. SSH private key

## 25. Phase 3 완료 조건

- [ ] PostgreSQL 16 RDS가 `Available`
- [ ] Identifier가 `buildgraph-demo-postgres-green`
- [ ] Single DB instance이며 Multi-AZ가 아님
- [ ] DB instance class가 `db.t4g.small`
- [ ] VPC가 `vpc-06c90b864a62f93a4`
- [ ] DB subnet group이 `buildgraph-demo-rds-subnet-group`
- [ ] Publicly accessible이 `No`
- [ ] RDS에 `buildgraph-demo-rds-sg`만 연결
- [ ] RDS SG 5432 Source가 공유 API EC2 SG `sg-099aac782b77a854e`
- [ ] `0.0.0.0/0`, 현재 사용자 IP, 다른 SG가 RDS SG Source에 없음
- [ ] Initial database가 `buildgraph`
- [ ] Port가 `5432`
- [ ] Storage가 gp3 20 GiB이며 autoscaling 최대 100 GiB
- [ ] Backup retention이 1일
- [ ] Deletion protection 활성
- [ ] Endpoint 기록 완료
- [ ] Master password를 Git·문서·채팅에 노출하지 않음
- [ ] Blue EC2·Compose·PostgreSQL 컨테이너에 변경 없음

위 조건을 확인하면 Phase 3을 완료하고 [Phase 4 ElastiCache Redis 콘솔 가이드](aws-infrastructure-phase-4-console-guide.md)로 넘어간다.

## 26. 문제 발생 시 중단 기준

### RDS 상태가 Failed

1. 다시 Create database를 눌러 중복 인스턴스를 만들지 않는다.
2. RDS의 Events 탭에서 가장 최근 오류를 확인한다.
3. 오류 문구만 전달하고 비밀번호는 전달하지 않는다.

### `buildgraph-demo-rds-subnet-group`이 목록에 없음

1. 선택한 VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
2. Region이 서울인지 확인한다.
3. DB subnet group 상태가 `Complete`인지 확인한다.
4. `default` subnet group으로 대신 진행하지 않는다.

### `buildgraph-demo-rds-sg`가 목록에 없음

1. VPC가 올바른지 확인한다.
2. RDS SG가 같은 VPC에 생성됐는지 확인한다.
3. 새 SG를 즉석에서 만들지 않고 Phase 2로 돌아간다.

### 생성 후 AZ가 `ap-northeast-2a`

Single-AZ가 2a에 생성됐더라도 같은 VPC local route를 통해 Green EC2가 접근할 수 있다. 구조 오류는 아니므로 즉시 삭제하지 않는다. 비용·지연을 이유로 2b 재생성이 필요한지는 Green 배포 전에 별도로 결정한다.

### 생성 후 Publicly accessible이 `Yes`

1. Green API를 연결하지 않는다.
2. RDS에서 `Modify`를 누른다.
3. Public access를 `No`로 변경한다.
4. Apply immediately를 선택한다.
5. 수정 완료 후 `No`인지 다시 확인한다.

## 27. Phase 3까지 생성·확인된 구성표

이 표는 `2026-07-13`에 AWS 콘솔 화면으로 확인한 값과 문서에 기록된 값을 합친 현재 상태다. `확인 필요` 항목은 입력 계획이 아니라 생성된 리소스의 Configuration 화면에서 다시 확인해야 하는 값이다.

| 구분 | 리소스·항목 | 현재 값 | 상태 |
| --- | --- | --- | --- |
| Region | AWS Region | `ap-northeast-2` 서울 | 확인 완료 |
| Network | VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` / `10.0.0.0/16` | 확인 완료 |
| Network | 기존 Public Subnet | `2a: 10.0.0.0/20`, `2b: 10.0.16.0/20` | 확인 완료 |
| Network | Private Data Subnet 2a | `subnet-09bba1fd17639ce6a` / `10.0.32.0/24` | 확인 완료 |
| Network | Private Data Subnet 2b | `subnet-0816bc2771fd5e1ca` / `10.0.33.0/24` | 확인 완료 |
| Network | Private Route Table | `rtb-084440a81e5721f2f` / `10.0.0.0/16 → local`만 사용 | 확인 완료 |
| Network | NAT Gateway | 생성하지 않음 | 확인 완료 |
| Security | Blue·Green 공유 API EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` | 확인 완료 |
| Security | RDS SG | `buildgraph-demo-rds-sg` / `sg-0587fdbc766f9088f` / TCP 5432 Source 공유 SG | 확인 완료 |
| Security | Redis SG | `buildgraph-demo-redis-sg` / `sg-0dc3c8766358e57f4` / TCP 6379 Source 공유 SG | 확인 완료 |
| Security | RabbitMQ SG | `buildgraph-demo-rabbitmq-sg` / `sg-0876855a9ac1da572` / TCP 5671 Source 공유 SG | 확인 완료 |
| Subnet Group | RDS | `buildgraph-demo-rds-subnet-group` / Private 2a·2b | 확인 완료 |
| Subnet Group | ElastiCache | `buildgraph-demo-redis-subnet-group` / Private 2a·2b | Phase 4 시작 전 콘솔 확인 |
| RDS | Identifier | `buildgraph-demo-postgres-green` | 생성 완료 |
| RDS | Engine | PostgreSQL 16, 생성 선택값 `16.14-R2` | 정확한 버전 최종 확인 필요 |
| RDS | Deployment | Single DB instance / Single-AZ | 확인 완료 |
| RDS | Class | `db.t4g.small` | 확인 완료 |
| RDS | AZ | `ap-northeast-2b` | 확인 완료 |
| RDS | Network | Private access / RDS SG만 연결 | 확인 완료 |
| RDS | Database·Port | `buildgraph` / TCP `5432` | 확인 완료 |
| RDS | Storage | gp3 `20 GiB`, autoscaling 최대 `100 GiB` | Configuration에서 최종 확인 필요 |
| RDS | Backup·Protection | retention `1 day`, deletion protection 활성 | Maintenance & backups에서 최종 확인 필요 |
| RDS | Endpoint | `buildgraph-demo-postgres-green.cdcw2ykmk609.ap-northeast-2.rds.amazonaws.com` | 기록 완료 |
| RDS | Status | 마지막 확인값 `Backing-up` | `Available` 최종 확인 필요 |
| Blue | 기존 EC2·Compose | 중지·재부팅·datasource 변경 없음 | 유지 |

Phase 3 완료 체크는 RDS가 `Available`로 바뀌고 Storage, Backup retention, Deletion protection을 생성 결과 화면에서 확인한 뒤 닫는다. 이 확인 전에도 Phase 4 가이드를 읽고 설정값을 준비할 수 있지만, ElastiCache 생성 버튼은 RDS 상태가 `Available`인 것을 확인한 뒤 누른다.

## 공식 참고 문서

- [Amazon RDS DB instance 생성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateDBInstance.html)
- [RDS PostgreSQL 시작 및 Private DB 구성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_GettingStarted.CreatingConnecting.PostgreSQL.html)
- [RDS DB 설정 항목](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateDBInstance.Settings.html)
- [RDS DB storage와 gp3](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Storage.html)
- [RDS PostgreSQL 16 지원 extension 목록](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-extensions.html)
- [RDS의 VPC와 Public access 구성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_VPC.WorkingWithRDSInstanceinaVPC.html)
