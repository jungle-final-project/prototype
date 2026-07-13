# AWS 인프라 분리 Phase 5 Amazon MQ RabbitMQ 콘솔 따라하기

이 문서는 [aws-infrastructure-separation-plan.md](aws-infrastructure-separation-plan.md)의 Phase 5를 AWS Management Console에서 사용자가 직접 수행하기 위한 절차다.

이번 단계에서는 Green API가 사용할 빈 Amazon MQ for RabbitMQ broker를 생성한다. 기존 Blue EC2, `compose.prod.yaml`, RabbitMQ 컨테이너, Worker, CloudFront, RDS, ElastiCache는 변경하거나 중지하지 않는다.

## 0. 이번 Phase의 확정값

| 항목 | 최종값 |
| --- | --- |
| Region | `ap-northeast-2` 서울 |
| Broker engine | RabbitMQ |
| Engine version | `3.13` |
| Broker name | `buildgraph-demo-rabbitmq-green` |
| Deployment mode | Single-instance broker |
| Broker instance type | `mq.m7g.medium` |
| Storage | Single-instance 기본 EBS `200 GB` |
| Availability Zone | `ap-northeast-2b` |
| Public accessibility | `No` |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Subnet | Private 2b / `subnet-0816bc2771fd5e1ca` / `10.0.33.0/24` |
| Security group | `buildgraph-demo-rabbitmq-sg` / `sg-0876855a9ac1da572`만 연결 |
| Security group Source | 공유 API EC2 SG `sg-099aac782b77a854e` |
| Application protocol | AMQPS |
| Listener port | TCP `5671` |
| Authentication | RabbitMQ simple username/password |
| Initial username | `buildgraph` |
| Virtual host | 기본 `/` |
| Encryption in transit | TLS, Amazon MQ 기본 AMQPS |
| Encryption at rest | Enabled, AWS owned key |
| CloudWatch logs | General logs Enabled |
| Automatic minor version upgrade | Enabled |

`mq.t3.micro`는 deprecated 상태이며 새 broker 생성에 사용할 수 없다. 2026-07-13 기준 신규 생성 가능한 가장 작은 RabbitMQ instance type은 `mq.m7g.medium`이다.

AWS는 최신 RabbitMQ 4.2를 권장하지만 4.2는 기본 queue type이 quorum으로 바뀐다. 현재 BuildGraph는 RabbitMQ 3 기반 Compose를 사용하고 queue type을 명시하지 않으므로, 빠른 Green 전환에서는 호환성 위험이 더 낮고 아직 지원되는 `3.13`을 선택한다. 4.2 업그레이드는 Green 안정화 후 queue type과 재전달 정책을 명시하고 별도로 테스트한다.

## 1. 이번 단계에서 하지 않는 작업

1. Blue EC2를 중지하거나 재부팅하지 않는다.
2. Blue RabbitMQ 컨테이너나 Worker를 중지하지 않는다.
3. Blue의 `.env.prod`에서 `SPRING_RABBITMQ_*`를 변경하지 않는다.
4. 기존 RabbitMQ message, queue, exchange를 이전하지 않는다.
5. Queue drain이나 message 발행 중단을 수행하지 않는다.
6. RabbitMQ SG에 `0.0.0.0/0`, 현재 사용자 IP, VPC 전체 CIDR을 추가하지 않는다.
7. Public accessibility를 활성화하지 않는다.
8. AMQP 평문 포트 `5672`를 열지 않는다.
9. Web console 접속을 위해 `443`을 추가로 열지 않는다.
10. ActiveMQ를 선택하지 않는다.
11. RabbitMQ cluster deployment를 선택하지 않는다.
12. OAuth 2.0 또는 mTLS 인증을 구성하지 않는다.
13. 실제 broker password를 문서, Git, 채팅, 스크린샷에 남기지 않는다.
14. Green EC2나 API를 아직 배포하지 않는다.
15. Spring Boot RabbitMQ 설정은 이번 Phase에서 수정하지 않는다.

## 2. Phase 3·4 완료 여부 확인

Amazon MQ 생성 전에 관리형 데이터 서비스 상태를 확인한다.

1. RDS 콘솔에서 `buildgraph-demo-postgres-green` 상태가 `Available`인지 확인한다.
2. ElastiCache 콘솔에서 `buildgraph-demo-redis-green` 상태가 `Available`인지 확인한다.
3. Redis node type이 사용자가 최종 확정한 `cache.t4g.small`인지 확인한다.
4. Redis가 아직 `Creating` 또는 `Modifying`이면 완료될 때까지 기다린다.
5. Blue EC2와 기존 Compose가 계속 실행 중인지 확인한다.

RDS 또는 Redis 생성에 실패한 상태라면 Amazon MQ 생성 전에 해당 Phase를 먼저 교정한다.

## 3. Phase 5 사전 리소스 확인

Amazon MQ를 만들기 전에 다음 리소스가 있어야 한다.

| 리소스 | 확인값 |
| --- | --- |
| VPC | `buildgraph-demo-vpc` / `vpc-06c90b864a62f93a4` |
| Private 2b Subnet | `subnet-0816bc2771fd5e1ca` / `10.0.33.0/24` / `ap-northeast-2b` |
| Private route table | `rtb-084440a81e5721f2f`, VPC local route만 사용 |
| RabbitMQ SG | `buildgraph-demo-rabbitmq-sg` / `sg-0876855a9ac1da572` |
| 공유 API EC2 SG | `buildgraph-demo-ec2-sg` / `sg-099aac782b77a854e` |

다음 순서로 확인한다.

1. VPC 콘솔에서 `Subnets`를 연다.
2. `subnet-0816bc2771fd5e1ca`를 선택한다.
3. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
4. Availability Zone이 `ap-northeast-2b`인지 확인한다.
5. IPv4 CIDR이 `10.0.33.0/24`인지 확인한다.
6. 연결된 route table이 `rtb-084440a81e5721f2f`인지 확인한다.
7. route에 `10.0.0.0/16 → local`이 있는지 확인한다.
8. NAT Gateway 또는 Internet Gateway를 향하는 `0.0.0.0/0` route가 없는지 확인한다.
9. EC2 콘솔의 `Security groups`를 연다.
10. `buildgraph-demo-rabbitmq-sg`를 선택한다.
11. Inbound rule이 Custom TCP `5671` 한 개인지 확인한다.
12. Source가 `sg-099aac782b77a854e`인지 확인한다.
13. Source가 `0.0.0.0/0`, `10.0.0.0/16`, 현재 사용자 IP가 아닌지 확인한다.

아래 중 하나라도 해당하면 broker를 생성하지 않고 Phase 2 설정부터 교정한다.

```text
Private 2b Subnet의 VPC 또는 AZ가 다름
Private route table에 0.0.0.0/0 route가 있음
RabbitMQ SG가 다른 VPC에 있음
RabbitMQ SG의 5671 Source가 sg-099aac782b77a854e가 아님
RabbitMQ SG에 5672 또는 0.0.0.0/0 Inbound rule이 있음
```

## 4. Amazon MQ 콘솔 열기

1. [AWS Management Console](https://console.aws.amazon.com/)에 로그인한다.
2. 오른쪽 위 Region을 누른다.
3. `아시아 태평양(서울) ap-northeast-2`를 선택한다.
4. 상단 검색창에서 `Amazon MQ`를 검색한다.
5. `Amazon MQ`를 누른다.
6. 콘솔 오른쪽 위에도 서울 Region이 표시되는지 확인한다.
7. 왼쪽 메뉴에서 `Brokers`를 누른다.
8. `buildgraph-demo-rabbitmq-green`이 이미 존재하지 않는지 검색한다.

동일한 이름의 broker가 이미 있으면 중복 생성하지 않고 기존 broker의 상태와 설정을 먼저 확인한다.

## 5. Broker 생성 시작과 Engine 선택

1. `Create broker`를 누른다.
2. Broker engine에서 `RabbitMQ`를 선택한다.
3. `ActiveMQ`를 선택하지 않는다.
4. `Next`를 누른다.

콘솔 버전에 따라 engine과 deployment mode가 한 화면에 표시될 수 있다. 최종 선택값만 아래 기준과 같으면 된다.

## 6. Deployment mode 선택

1. Deployment mode에서 `Single-instance broker`를 선택한다.
2. `Cluster deployment`를 선택하지 않는다.
3. 노드 수가 한 개인지 설명을 확인한다.
4. `Next`를 누른다.

Single instance는 한 AZ에 broker 한 대만 실행하므로 유지보수 또는 장애 중 연결이 끊길 수 있다. 이 위험은 현재 저비용 결정으로 수용하며 Phase 6에서 client 자동 재연결을 검증한다.

## 7. Broker 이름과 Engine version 입력

1. Broker name에 다음 값을 입력한다.

```text
buildgraph-demo-rabbitmq-green
```

2. 이름 끝에 날짜나 임의 숫자를 붙이지 않는다.
3. Broker engine version에서 `3.13`을 선택한다.
4. `4.2`를 선택하지 않는다.
5. `3.12` 이하 버전을 선택하지 않는다.
6. patch version은 Amazon MQ가 지원하는 최신 3.13 patch를 관리하므로 major.minor `3.13` 선택을 확인한다.

성공 기준:

```text
Engine: RabbitMQ
Engine version: 3.13
Broker name: buildgraph-demo-rabbitmq-green
```

## 8. Broker instance type 선택

1. Broker instance type에서 다음 값을 선택한다.

```text
mq.m7g.medium
```

2. `mq.m7g.large` 이상을 임의로 선택하지 않는다.
3. `mq.m5.large`를 선택하지 않는다.
4. `mq.t3.micro`를 찾거나 선택하지 않는다.
5. Review 화면에서 single-instance용 EBS storage가 `200 GB`로 표시되면 정상값으로 둔다.
6. storage 크기를 직접 선택하는 항목이 없으면 그대로 진행한다.

`mq.m7g.medium`이 서울 Region의 목록에 보이지 않으면 다른 instance type을 임의로 선택하지 않고 생성 전 중단한다.

## 9. RabbitMQ 초기 계정 생성

빠른 첫 배포에서는 Amazon MQ가 생성하는 첫 administrator 사용자를 API 연결에도 임시 사용한다. Green 안정화 후에는 application 전용 최소 권한 사용자를 별도로 만드는 하드닝 작업을 수행한다.

1. RabbitMQ access 또는 Broker access 섹션을 연다.
2. Username에 다음 값을 입력한다.

```text
buildgraph
```

3. `guest`를 입력하지 않는다.
4. 개인 비밀번호 관리자에서 새 임의 password를 생성한다.
5. password 길이는 32자 이상으로 만든다.
6. 최소 네 종류 이상의 서로 다른 문자가 들어가도록 영문 대문자, 영문 소문자, 숫자, 허용 특수문자를 조합한다.
7. 쉼표 `,`, 콜론 `:`, 등호 `=`는 사용하지 않는다.
8. 공백, 따옴표, 역슬래시도 사용하지 않는다.
9. 비밀번호 관리자에 다음 이름으로 저장한다.

```text
buildgraph-demo-rabbitmq-green-admin
```

10. Password와 Confirm password 입력란에 같은 값을 입력한다.
11. 실제 password를 문서, 채팅, GitHub Secret, 스크린샷에 입력하지 않는다.
12. Phase 6에서 Secrets Manager로 옮기기 전까지 비밀번호 관리자를 원본으로 사용한다.

Amazon MQ의 password 최소 조건은 12자, 서로 다른 문자 4개 이상, `,`, `:`, `=` 제외다. 이 문서에서는 운영 실수를 줄이기 위해 더 긴 32자 이상을 사용한다.

## 10. Additional settings 열기

1. `Additional settings` 또는 `Advanced settings`를 펼친다.
2. Easy create에서 네트워크를 자동 선택한 상태라면 그대로 생성하지 않는다.
3. Configuration, Logs, Network, Encryption, Maintenance 항목을 각각 확인한다.

Private Subnet과 기존 RabbitMQ SG를 직접 선택할 수 없는 생성 방식이라면 이전 화면으로 돌아가 상세 설정 방식을 선택한다.

## 11. Broker configuration 설정

1. Configuration에서 Amazon MQ가 제공하는 RabbitMQ 3.13 기본 configuration을 선택한다.
2. 새 custom configuration을 만들지 않는다.
3. 기존 다른 broker의 configuration을 재사용하지 않는다.
4. `Apply immediately` 같은 변경 옵션이 표시되지 않으면 그대로 진행한다.

이번 Phase에서는 Amazon MQ 기본 policy와 기본 virtual host `/`를 사용한다. queue, exchange, binding은 Phase 6에서 Spring Boot가 선언한다.

## 12. CloudWatch general logs 활성화

1. CloudWatch logs 섹션을 연다.
2. `General logs`를 활성화한다.
3. RabbitMQ에는 지원되지 않는 audit logs를 찾거나 별도로 구성하지 않는다.
4. CloudWatch Logs용 custom IAM role을 새로 만들지 않는다.
5. Amazon MQ service-linked role을 자동으로 사용하는 기본 동작을 유지한다.

Broker가 Running이 된 뒤 general log group 생성 여부를 다시 확인한다.

## 13. Public accessibility 비활성화

1. Network 또는 Connections 섹션을 연다.
2. Public accessibility에서 `No` 또는 비활성화를 선택한다.
3. `Yes`를 선택하지 않는다.
4. Public broker 방식으로 생성하지 않는다.

성공 기준:

```text
Public accessibility: No
Access: Private VPC only
```

## 14. VPC와 Private Subnet 선택

1. VPC에서 다음 값을 선택한다.

```text
buildgraph-demo-vpc (vpc-06c90b864a62f93a4)
```

2. 기본 VPC나 다른 VPC를 선택하지 않는다.
3. Subnet에서 다음 항목 하나만 선택한다.

```text
subnet-0816bc2771fd5e1ca
ap-northeast-2b
10.0.33.0/24
```

4. Public Subnet `10.0.0.0/20`, `10.0.16.0/20`을 선택하지 않는다.
5. Private 2a `subnet-09bba1fd17639ce6a`를 선택하지 않는다.
6. Single-instance이므로 Subnet을 두 개 또는 세 개 선택하지 않는다.
7. 선택 결과의 Availability Zone이 `ap-northeast-2b`인지 확인한다.

Amazon MQ broker 생성 후에는 선택한 Subnet을 다른 Subnet으로 바꿀 수 없다. ID와 AZ를 반드시 다시 확인한다.

## 15. RabbitMQ Security Group 연결

1. Security groups에서 기존 보안 그룹 사용을 선택한다.
2. 다음 보안 그룹 하나만 선택한다.

```text
buildgraph-demo-rabbitmq-sg (sg-0876855a9ac1da572)
```

3. `default` SG가 자동 선택되어 있으면 선택을 해제한다.
4. `buildgraph-demo-ec2-sg`를 broker에 직접 연결하지 않는다.
5. `buildgraph-demo-ec2-sg`는 RabbitMQ SG의 Inbound Source로만 사용한다.
6. RDS SG와 Redis SG를 선택하지 않는다.

구조는 다음과 같다.

```text
Blue EC2와 향후 Green EC2
└─ buildgraph-demo-ec2-sg / sg-099aac782b77a854e
   └─ AMQPS TCP 5671 요청
      └─ buildgraph-demo-rabbitmq-sg / sg-0876855a9ac1da572
         └─ Amazon MQ RabbitMQ
```

Amazon MQ broker 생성 후에는 연결한 Security Group 자체를 다른 SG로 교체할 수 없다. SG 규칙은 나중에 수정할 수 있지만, 지금은 정확한 SG 하나를 선택해야 한다.

## 16. Encryption 설정

1. Encryption 또는 Data encryption 섹션을 연다.
2. Encryption at rest가 활성화되어 있는지 확인한다.
3. Encryption key에서 `AWS owned key` 또는 콘솔 기본 AWS 소유 키를 선택한다.
4. Customer managed KMS key를 새로 만들지 않는다.
5. Application endpoint가 secure AMQP인 `amqps`와 port `5671`을 사용한다는 점을 확인한다.
6. 평문 AMQP `5672`용 설정을 만들지 않는다.

Amazon MQ for RabbitMQ의 application 연결은 TLS를 사용하는 AMQPS endpoint로 제공된다. Phase 6 Spring 설정에서도 SSL을 반드시 활성화한다.

## 17. Maintenance 설정

1. Automatic minor version upgrade를 활성화한다.
2. Maintenance window는 `No preference` 또는 콘솔 기본값을 유지한다.
3. 요일과 시간을 직접 선택해야 할 때는 실제 서비스 점검 시간을 사용자와 확정하기 전 임의값을 정하지 않는다.
4. 이번 설정의 automatic upgrade는 3.13 patch 관리 목적이다.
5. RabbitMQ 4.2 major upgrade는 자동으로 선택하지 않는다.

Single-instance broker는 reboot와 maintenance 중 일시 중단될 수 있다. Phase 6에서 Spring client 재연결을 검증하는 이유다.

## 18. Tags 입력

Tags는 선택 사항이지만 리소스 식별을 위해 다음 세 개를 추가한다.

| Key | Value |
| --- | --- |
| `Name` | `buildgraph-demo-rabbitmq-green` |
| `Environment` | `demo-green` |
| `Role` | `message-broker` |

태그 때문에 생성이 지연되거나 오류가 발생하면 태그 없이 생성하고 나중에 추가할 수 있다.

## 19. 생성 전 최종 검토

`Create broker`를 누르기 전에 다음 표와 화면 값을 한 줄씩 비교한다.

| 항목 | 최종값 |
| --- | --- |
| Engine | RabbitMQ |
| Engine version | `3.13` |
| Broker name | `buildgraph-demo-rabbitmq-green` |
| Deployment mode | Single-instance |
| Instance type | `mq.m7g.medium` |
| Username | `buildgraph` |
| Public accessibility | `No` |
| VPC | `vpc-06c90b864a62f93a4` |
| Subnet | `subnet-0816bc2771fd5e1ca` 하나 |
| Availability Zone | `ap-northeast-2b` |
| Security group | `sg-0876855a9ac1da572` 하나 |
| Application endpoint | AMQPS `5671` |
| Encryption at rest | Enabled / AWS owned key |
| General logs | Enabled |
| Automatic minor upgrade | Enabled |

다음 중 하나라도 발견되면 생성하지 않는다.

```text
ActiveMQ가 선택됨
RabbitMQ 4.2 또는 3.12 이하가 선택됨
Cluster deployment가 선택됨
Public accessibility가 Yes임
Public Subnet이 선택됨
Subnet이 2a이거나 두 개 이상 선택됨
default SG가 추가됨
RabbitMQ SG 외의 SG가 broker에 연결됨
Username이 guest임
General logs가 Disabled임
```

## 20. Amazon MQ broker 생성

1. 실제 password가 비밀번호 관리자에 저장됐는지 마지막으로 확인한다.
2. `Create broker`를 누른다.
3. Brokers 목록으로 이동한다.
4. `buildgraph-demo-rabbitmq-green` 상태가 `Creation in progress`인지 확인한다.
5. 생성 중에 Edit, Reboot, Delete를 누르지 않는다.
6. 상태가 `Running`이 될 때까지 기다린다.

Amazon MQ 공식 안내상 broker 생성에는 약 15분이 걸릴 수 있다. `Creation in progress`는 오류가 아니다.

## 21. 생성 결과 검증

상태가 `Running`이 되면 `buildgraph-demo-rabbitmq-green`을 선택하고 다음을 확인한다.

1. Engine이 RabbitMQ인지 확인한다.
2. Engine version이 `3.13`인지 확인한다.
3. Deployment mode가 Single-instance인지 확인한다.
4. Instance type이 `mq.m7g.medium`인지 확인한다.
5. broker node가 한 개인지 확인한다.
6. Region과 AZ가 `ap-northeast-2`, `ap-northeast-2b`인지 확인한다.
7. Public accessibility가 `No`인지 확인한다.
8. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
9. Subnet이 `subnet-0816bc2771fd5e1ca`인지 확인한다.
10. Security group이 `sg-0876855a9ac1da572` 하나인지 확인한다.
11. General logs가 Enabled인지 확인한다.
12. Encryption at rest가 활성인지 확인한다.
13. Automatic minor version upgrade가 활성인지 확인한다.
14. Connect 또는 Connections 섹션에서 secure AMQP endpoint를 확인한다.

endpoint는 다음과 비슷하다.

```text
amqps://b-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.mq.ap-northeast-2.amazonaws.com:5671
```

실제 콘솔 endpoint의 도메인이 `.on.aws` 형식이면 그 값을 그대로 사용한다. 예시 도메인으로 바꾸지 않는다.

RabbitMQ Web console URL은 application endpoint가 아니다. Green Spring Boot에는 secure AMQP endpoint를 사용한다.

## 22. AMQPS endpoint 기록 방법

password를 제외하고 다음 두 값을 기록한다.

```text
Full AMQPS endpoint: amqps://<BROKER_HOST>:5671
Spring address: <BROKER_HOST>:5671
```

Spring address를 만들 때 다음만 제거한다.

```text
제거: amqps://
유지: hostname
유지: :5671
```

endpoint 자체는 비밀값이 아니지만 username과 password를 포함한 URI로 기록하지 않는다.

## 23. CloudWatch general logs 확인

1. Amazon MQ broker 상세 화면에서 Logs 또는 Monitoring을 연다.
2. General logs가 Enabled인지 확인한다.
3. CloudWatch Logs 링크가 있으면 누른다.
4. 다음과 비슷한 log group이 생성됐는지 확인한다.

```text
/aws/amazonmq/broker/b-<BROKER_ID>/general
```

5. broker가 막 Running이 된 경우 log stream이 표시될 때까지 몇 분 기다린다.
6. log group이 바로 보이지 않아도 broker 설정에서 General logs가 Enabled라면 broker를 재생성하지 않는다.
7. RabbitMQ용 audit log group은 만들지 않는다.

## 24. CloudWatch metrics 확인

1. Amazon MQ broker 상세 화면에서 `Actions`를 누른다.
2. `View CloudWatch metrics`가 있으면 누른다.
3. CloudWatch의 `AmazonMQ` namespace가 열리는지 확인한다.
4. Broker Metrics에서 `ConnectionCount`, `QueueCount`, `MessageCount`, `SystemCpuUtilization`, `RabbitMQMemUsed`, `RabbitMQMemLimit`, `RabbitMQDiskFree`가 검색되는지 확인한다.
5. 아직 Green API를 연결하지 않았으므로 `ConnectionCount`, `QueueCount`, `MessageCount`가 0이어도 정상이다.

이번 Phase에서는 Queue depth alarm을 만들지 않는다. 아직 application queue가 없고 정상 적체량도 확정되지 않아 임계값을 임의로 정할 수 없기 때문이다. Phase 6에서 실제 queue 생성과 publish/consume을 확인한 뒤 broker-level 또는 queue-level `MessageCount` 경보의 임계값과 평가 시간을 확정한다.

## 25. 이번 Phase에서 연결 테스트를 하지 않는 이유

Green EC2는 아직 생성되지 않았다. RabbitMQ SG Source는 Blue와 Green이 공유할 `sg-099aac782b77a854e`이므로 Blue EC2도 네트워크상 접근할 수 있지만, Blue의 `.env.prod`와 Compose RabbitMQ endpoint는 변경하지 않는다.

연결을 확인하려고 다음 작업을 하지 않는다.

```text
5671을 0.0.0.0/0에 공개
현재 사용자 IP를 RabbitMQ SG Source에 추가
Web console용 443을 임시 공개
Blue의 RabbitMQ 환경변수를 Amazon MQ endpoint로 변경
password를 shell command 또는 terminal history에 직접 입력
TLS를 끄고 5672로 평문 연결 시도
```

Phase 6에서 Green EC2에 공유 SG를 연결하고 `ManagedInfrastructureSmokeTest`로 TLS publish/consume과 재연결을 검증한다.

## 26. Phase 6 환경변수 예약

이번 Phase에서는 실제 파일에 입력하지 않고 다음 매핑만 기록한다.

```text
SPRING_RABBITMQ_ADDRESSES=<BROKER_HOST>:5671
SPRING_RABBITMQ_USERNAME=buildgraph
SPRING_RABBITMQ_PASSWORD=<PASSWORD_FROM_SECRET>
SPRING_RABBITMQ_SSL_ENABLED=true
SPRING_RABBITMQ_VIRTUAL_HOST=/
```

`SPRING_RABBITMQ_ADDRESSES`에는 `amqps://`를 붙이지 않는다. port `5671`은 address에 포함하고 TLS 여부는 별도 환경변수로 전달한다.

Phase 6에서 RDS password, Redis AUTH token, RabbitMQ password와 기존 `.env.prod`의 보존 대상 비밀값을 Secrets Manager로 옮긴다. 이번 Phase에서는 Secret ARN이 아직 없어도 정상이다.

## 27. Phase 5 결과 기록

password를 제외하고 다음 형식으로 결과를 기록한다.

```text
Broker name: buildgraph-demo-rabbitmq-green
Status: Running | Creation in progress | Creation failed
Broker ID:
Broker ARN:
Engine: RabbitMQ
Engine version: 3.13
Deployment mode: Single-instance
Instance type: mq.m7g.medium
Availability Zone: ap-northeast-2b
VPC: vpc-06c90b864a62f93a4
Subnet: subnet-0816bc2771fd5e1ca
RabbitMQ SG ID: sg-0876855a9ac1da572
RabbitMQ SG Source: sg-099aac782b77a854e
Public accessibility: No
Port: 5671
Full AMQPS endpoint:
Spring address:
Initial username: buildgraph
Virtual host: /
General logs: Enabled
Automatic minor upgrade: Enabled
Password stored outside Git/chat: Yes | No
```

다음 값은 기록하거나 전달하지 않는다.

1. RabbitMQ password
2. username과 password가 포함된 AMQPS URI
3. `.env.prod` 원문
4. AWS access key
5. SSH private key

## 28. Phase 5 완료 조건

- [ ] `buildgraph-demo-rabbitmq-green` 상태가 `Running`
- [ ] Engine이 RabbitMQ `3.13`
- [ ] Deployment mode가 Single-instance
- [ ] Instance type이 `mq.m7g.medium`
- [ ] Availability Zone이 `ap-northeast-2b`
- [ ] VPC가 `vpc-06c90b864a62f93a4`
- [ ] Subnet이 Private 2b `subnet-0816bc2771fd5e1ca` 하나
- [ ] Public accessibility가 `No`
- [ ] broker에 `buildgraph-demo-rabbitmq-sg`만 연결
- [ ] RabbitMQ SG 5671 Source가 공유 API EC2 SG `sg-099aac782b77a854e`
- [ ] `0.0.0.0/0`, 현재 사용자 IP, 5672 rule이 RabbitMQ SG에 없음
- [ ] AMQPS endpoint port가 `5671`
- [ ] Encryption at rest 활성
- [ ] General logs 활성
- [ ] Automatic minor version upgrade 활성
- [ ] Full AMQPS endpoint와 Spring address 기록 완료
- [ ] password를 비밀번호 관리자에 저장
- [ ] password를 Git·문서·채팅에 노출하지 않음
- [ ] Queue depth alarm은 Phase 6의 실제 queue·적체 기준 확정 뒤 생성하도록 보류
- [ ] Blue EC2·Compose·RabbitMQ 컨테이너·Worker에 변경 없음

위 조건을 확인하면 Phase 5를 완료하고 [aws-infrastructure-phase-6-console-guide.md](aws-infrastructure-phase-6-console-guide.md)에 따라 Green API EC2와 API 전용 Compose 구축으로 넘어간다.

## 29. 문제 발생 시 중단 기준

### `mq.t3.micro`가 보이지 않음

정상이다. 신규 broker에는 더 이상 사용할 수 없다. `mq.m7g.medium`을 선택한다.

### `mq.m7g.medium`이 보이지 않음

1. Region이 서울인지 확인한다.
2. Engine이 RabbitMQ인지 확인한다.
3. Deployment mode가 Single-instance인지 확인한다.
4. Engine version이 `3.13`인지 확인한다.
5. 다른 instance type으로 임의 진행하지 않는다.

### Private 2b Subnet이 목록에 없음

1. VPC가 `vpc-06c90b864a62f93a4`인지 확인한다.
2. Subnet ID가 `subnet-0816bc2771fd5e1ca`인지 확인한다.
3. Subnet과 broker를 같은 AWS 계정에서 만들고 있는지 확인한다.
4. default VPC 또는 Public Subnet으로 대신 진행하지 않는다.

### `buildgraph-demo-rabbitmq-sg`가 목록에 없음

1. VPC가 올바른지 확인한다.
2. SG ID가 `sg-0876855a9ac1da572`인지 확인한다.
3. RabbitMQ SG가 같은 VPC에 생성됐는지 확인한다.
4. 새 SG를 즉석에서 만들지 않고 Phase 2로 돌아간다.

### Password 검증 오류

1. password가 최소 12자 이상인지 확인한다.
2. 서로 다른 문자가 최소 4개 이상인지 확인한다.
3. `,`, `:`, `=`가 포함되지 않았는지 확인한다.
4. 공백이나 복사 과정의 앞뒤 줄바꿈이 없는지 확인한다.
5. password를 채팅에 보내지 않는다.
6. 비밀번호 관리자에서 새 32자 이상 password를 생성해 다시 입력한다.

### Creation failed

1. broker 상세 화면의 failure reason을 확인한다.
2. Region, VPC, Subnet, SG가 같은 계정과 VPC인지 확인한다.
3. 계정의 Amazon MQ service-linked role 생성 권한을 확인한다.
4. 실패한 broker와 같은 이름으로 즉시 반복 생성하지 않는다.
5. failure reason을 password 없이 기록한 뒤 원인을 교정한다.

### General log group이 보이지 않음

1. broker 상태가 `Running`인지 확인한다.
2. broker 설정에서 General logs가 Enabled인지 확인한다.
3. 몇 분 기다린 뒤 CloudWatch Logs를 새로고침한다.
4. Amazon MQ가 service-linked role을 생성했는지 IAM에서 확인한다.
5. RabbitMQ에는 audit logging이 지원되지 않으므로 audit log를 찾지 않는다.

### Phase 6에서 AMQPS timeout 발생

1. Green EC2에 공유 SG `sg-099aac782b77a854e`가 연결됐는지 확인한다.
2. RabbitMQ SG 5671 Source가 해당 공유 SG인지 확인한다.
3. Green EC2와 broker가 같은 VPC인지 확인한다.
4. endpoint에서 `amqps://`를 제거하고 `hostname:5671` 형식으로 전달했는지 확인한다.
5. `SPRING_RABBITMQ_SSL_ENABLED=true`인지 확인한다.
6. 5671을 인터넷에 공개하지 않는다.

### Phase 6에서 인증 실패 발생

1. username이 `buildgraph`인지 확인한다.
2. password manager의 Green broker 전용 password를 사용했는지 확인한다.
3. 기존 Blue RabbitMQ password를 사용하지 않았는지 확인한다.
4. virtual host가 `/`인지 확인한다.
5. password를 로그에 출력하지 않는다.

## 공식 참고 문서

- [RabbitMQ broker 생성](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/getting-started-rabbitmq.html)
- [RabbitMQ broker instance types](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/rmq-broker-instance-types.html)
- [RabbitMQ engine version 관리](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/rabbitmq-version-management.html)
- [RabbitMQ 4 호환성 변경](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/rabbitmq-4.html)
- [Private Amazon MQ broker 구성](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/configuring-private-broker.html)
- [Amazon MQ 보안 권장 사항과 AMQPS 5671](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/using-amazon-mq-securely.html)
- [RabbitMQ broker 사용자](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/rabbitmq-simple-auth-broker-users.html)
- [RabbitMQ CloudWatch metrics와 general logs](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/rabbitmq-logging-monitoring.html)
- [RabbitMQ broker 설정과 maintenance](https://docs.aws.amazon.com/amazon-mq/latest/developer-guide/amazon-mq-rabbitmq-editing-broker-preferences.html)
