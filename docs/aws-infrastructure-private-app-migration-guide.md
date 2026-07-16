# AWS Green Web ASG Private App Subnet 전환 가이드

이 문서는 BuildGraph Green Web ASG를 Public Subnet과 Public IPv4에서 분리해
Private App Subnet으로 전환하는 절차와 롤백 기준을 정리한다.

> 확인 기준일: 2026-07-16  
> AWS 계정: `443915990705`  
> 리전: `ap-northeast-2`  
> VPC: `vpc-06c90b864a62f93a4`

## 1. 범위

### 1.1 전환 전

```text
CloudFront
→ Internet-facing ALB(Public 2a·2b)
→ Target Group
→ Web ASG 1/1/1(Public 2a·2b + Public IPv4)
→ Nginx :80
→ Spring API :8080 / XGB :8091
```

Spring API `8080`과 XGB `8091`은 EC2 host나 인터넷에 직접 공개되지 않는다.
외부에서 EC2로 들어오는 경로는 ALB SG를 source로 허용한 Nginx `80`이다.
이번 전환 대상은 컨테이너 포트가 아니라 Web ASG EC2의 Subnet과 Public IPv4다.

### 1.2 전환 후

```text
CloudFront
→ Internet-facing ALB(Public 유지)
→ Target Group
→ Web ASG 1/1/1(Private App 2a·2b, Public IPv4 없음)
→ Nginx :80
→ Spring API :8080 / XGB :8091

Private outbound
├─ Regional NAT Gateway automatic mode
├─ S3 Gateway Endpoint
└─ ECR API/DKR·Secrets Manager·SSM·SSM Messages Interface Endpoint
```

### 1.3 이번 단계에서 하지 않는 것

- Web ASG `Max 3` 활성화
- Scheduler ASG 생성 또는 활성화
- CloudFront origin custom header와 ALB 기본 `403`
- CloudFront→ALB HTTPS 전환
- WAF Count→Block 전환
- 수동 Green, 기존 Public Launch Template, AMI, EIP 삭제
- Agent 로그와 XGB 모델 공유 S3 구현

공유 로그·모델 버킷은 2026-07-16 조회 시 존재하지 않았다. Web ASG를 `1/1/1`로
유지하는 이번 Private 전환의 하드 게이트는 아니지만 `Max 3` 전에는 반드시
별도로 구현하고 검증한다.

## 2. 전환 전 실시간 기준선

아래 표는 Private 네트워크 생성과 ASG 전환을 시작하기 전의 역사적 기준선이다.
현재 운영 상태는 5.8의 실행 결과를 기준으로 판단한다.

| 항목 | 확인값 |
| --- | --- |
| VPC CIDR | `10.0.0.0/16` |
| VPC DNS support | 활성 |
| VPC DNS hostnames | 활성 |
| Public 2a | `subnet-0b48bd72162060261`, `10.0.0.0/20` |
| Public 2b | `subnet-0db73cf18a85ea8f1`, `10.0.16.0/20` |
| Private Data 2a | `subnet-09bba1fd17639ce6a`, `10.0.32.0/24` |
| Private Data 2b | `subnet-0816bc2771fd5e1ca`, `10.0.33.0/24` |
| NAT Gateway | 없음 |
| 앱용 VPC Endpoint | 없음 |
| Web ASG | `buildgraph-demo-api-green-asg`, `1/1/1` |
| ASG Subnet | Public 2a·2b |
| Launch Template | `lt-0024991a1e82e5e6c`, version `1` |
| LT Public IPv4 | 활성 |
| 실행 EC2 | `i-0d8bfaec8aab8cc65` |
| 실행 EC2 Public IPv4 | `3.35.19.43` |
| Target | HTTP `80`, `healthy` |
| CloudFront API/WS | ALB origin 사용, `Deployed` |
| 수동 Green rollback origin | `buildgraph-demo-api-green-origin` |
| 수동 Green health | `status=UP`, `database=UP` |
| SSM Agent | `3.3.4793.0`, `Online` |

`10.0.34.0/24`와 `10.0.35.0/24`는 기존 Subnet과 겹치지 않는 것을
실시간 조회로 확인했다. 실제 생성 script도 매 실행 시 중복을 다시 확인한다.

## 3. 신규 리소스 계약

### 3.1 Private App Subnet

| AZ | Name | CIDR |
| --- | --- | --- |
| `ap-northeast-2a` | `buildgraph-demo-subnet-private-app1-ap-northeast-2a` | `10.0.34.0/24` |
| `ap-northeast-2b` | `buildgraph-demo-subnet-private-app2-ap-northeast-2b` | `10.0.35.0/24` |

공통 조건:

- `MapPublicIpOnLaunch=false`
- 기존 Private Data Subnet과 분리
- 기본 NACL 유지
- AZ별 Private App Route Table 사용

### 3.2 Regional NAT

이 작업은 AWS Regional NAT Gateway automatic mode를 사용한다.
Regional NAT는 별도 Public Subnet에 배치하지 않고 VPC에 생성하며, 두 Private App
Route Table의 `0.0.0.0/0`가 같은 Regional NAT ID를 가리킨다.

AWS 공식 문서:

- [Regional NAT gateways for automatic multi-AZ expansion](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateways-regional.html)

Regional NAT는 활성 AZ별 비용이 발생한다. Private App 두 AZ에 Interface Endpoint
ENI와 검증 EC2를 생성하므로 두 AZ 사용 비용이 발생할 수 있다.

### 3.3 VPC Endpoint

| 유형 | Service |
| --- | --- |
| Gateway | `com.amazonaws.ap-northeast-2.s3` |
| Interface | `com.amazonaws.ap-northeast-2.ecr.api` |
| Interface | `com.amazonaws.ap-northeast-2.ecr.dkr` |
| Interface | `com.amazonaws.ap-northeast-2.secretsmanager` |
| Interface | `com.amazonaws.ap-northeast-2.ssm` |
| Interface | `com.amazonaws.ap-northeast-2.ssmmessages` |

ECR image layer는 S3를 사용하므로 ECR Interface Endpoint 두 개만 생성해서는
Private image pull이 완성되지 않는다.

- [Amazon ECR VPC endpoints](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html)

SSM Agent는 `3.3.40.0` 이상을 요구한다. 이 버전부터 가능한 경우
`ssmmessages`를 우선 사용하므로 이번 단계에서는 `ec2messages` Endpoint를
생성하지 않는다.

- [Systems Manager VPC endpoints](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-create-vpc.html)

Interface Endpoint 조건:

- Private App 2a·2b에 Endpoint ENI 생성
- Private DNS 활성
- Endpoint SG inbound TCP `443`
- inbound source는 `sg-0a0a2fe0e54027420`만 허용
- `0.0.0.0/0` inbound 금지
- 초기 Endpoint policy는 기본 Full Access

CloudWatch와 STS, GitHub, Docker Hub, OpenAI, Google, Naver 등은 이번 단계에서
Regional NAT를 사용한다.

## 4. 자동화 파일

| 파일 | 역할 |
| --- | --- |
| `tools/provision_green_private_app_network.sh` | Private Subnet·Route Table·Regional NAT·Endpoint 생성 |
| `tools/test_provision_green_private_app_network.py` | 네트워크 script의 dry-run·drift·apply 계약 검증 |
| `tools/migrate_green_web_asg_private.sh` | 검증 EC2·LT version·ASG 전환·자동 롤백 |
| `tools/test_migrate_green_web_asg_private.py` | 전환 순서·실패·signal rollback 계약 검증 |

두 script는 기본 실행이 읽기 전용이다. `--apply`가 없으면 AWS create/update/delete
명령을 실행하지 않는다.

## 5. 실행 순서

### 5.1 로컬 테스트

```bash
python -m unittest \
  tools.test_provision_green_private_app_network \
  tools.test_migrate_green_web_asg_private \
  tools.test_bootstrap_green_asg \
  tools.test_prepare_green_asg_builder

bash -n tools/provision_green_private_app_network.sh
bash -n tools/migrate_green_web_asg_private.sh
git diff --check
```

### 5.2 네트워크 read-only preflight

```bash
AWS_PROFILE=team03-admin-443915990705 \
  bash tools/provision_green_private_app_network.sh
```

통과 조건:

- 계정과 리전 일치
- VPC와 DNS 설정 일치
- CIDR 중복 없음
- 같은 Name의 다른 설정 리소스 없음
- 운영 ASG와 CloudFront 변경 0건

### 5.3 Private 네트워크 생성

```bash
AWS_PROFILE=team03-admin-443915990705 \
  bash tools/provision_green_private_app_network.sh --apply
```

생성 후 다음 ID를 기록한다.

| 자원 | ID |
| --- | --- |
| Private App 2a | `subnet-06b224c812227caba` |
| Private App 2b | `subnet-0ec9ec7bb60433a68` |
| Route Table 2a | `rtb-0d43e9b9da9bc978f` |
| Route Table 2b | `rtb-0533f4b3fe7f605b9` |
| Regional NAT | `nat-1bbbae0197680e5ce` |
| Endpoint SG | `sg-0c4d1cfc36df0bfbd` |
| S3 Gateway Endpoint | `vpce-07bbe787337a0bbf5` |
| ECR API Endpoint | `vpce-08979e3ccf8f54e42` |
| ECR DKR Endpoint | `vpce-01613434ac4f72cf9` |
| Secrets Manager Endpoint | `vpce-006a850f94e89f71e` |
| SSM Endpoint | `vpce-08b0b7c9425b90913` |
| SSM Messages Endpoint | `vpce-08fdde16650fa42e3` |

### 5.4 두 AZ 검증 EC2

```bash
AWS_PROFILE=team03-admin-443915990705 \
  bash tools/migrate_green_web_asg_private.sh \
  --validate-private-azs \
  --apply
```

검증 EC2는 운영 Target Group에 등록하지 않는다. invocation tag로 소유권을
확인한 인스턴스만 종료한다. 이 모드는 ASG를 변경하지 않으므로 CloudFront
manual Green 격리 확인 환경변수를 요구하지 않는다.

통과 조건:

- 2a·2b 각각 한 대 기동
- Public IPv4 없음
- SSM Online
- bootstrap success marker
- Nginx·API·XGB 실행
- host `80` listen
- `/api/health` `200`, DB `UP`
- RDS·Redis·RabbitMQ TCP 연결
- ECR/S3/Secrets Manager bootstrap 완료
- CloudWatch Agent active
- GitHub·Docker Hub·외부 HTTPS 연결
- 검증 인스턴스 두 대 종료

### 5.5 CloudFront 수동 Green 격리

운영 ASG를 바꾸기 전에 CloudFront `/api/*`, `/ws/*` behavior의 origin만
`buildgraph-demo-api-green-origin`으로 전환한다.

1. `/api/*`를 먼저 전환한다.
2. Distribution `Deployed`를 기다린다.
3. API smoke를 통과시킨다.
4. `/ws/*`를 전환한다.
5. WebSocket 연결·재연결을 확인한다.

배포 workflow는 전환 완료까지 실행하지 않는다.

### 5.6 Web ASG Private 전환

```bash
AWS_PROFILE=team03-admin-443915990705 \
BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED=true \
  bash tools/migrate_green_web_asg_private.sh --apply
```

`BUILDGRAPH_CLOUDFRONT_MANUAL_GREEN_CONFIRMED=true`는 5.5 절차대로
CloudFront `/api/*`, `/ws/*`가 이미 수동 Green origin으로 격리됐다는 수동
확인값이다. script는 CloudFront 설정을 조회하거나 변경하지 않는다.

script는 다음을 수행한다.

1. 기존 Pending/InProgress Instance Refresh가 없는지 확인
2. 기존 Public Subnet과 LT 숫자 version 기록
3. 이미 동일한 Private Subnet·private LT·healthy Target이면 안전한 no-op 종료
4. Public IPv4가 비활성인 LT 새 version 생성
5. ASG Subnet을 Private App 2a·2b로 변경
6. 새 LT 숫자 version 고정
7. `MinHealthyPercentage=100`, `MaxHealthyPercentage=200` refresh 시작
8. 새 EC2 `InService`, Public IPv4 없음, Target Healthy 확인
9. 실패·응답 유실·INT·TERM 시 ASG 상태를 다시 조회하고 기존 Subnet과 LT
   version을 복원한 뒤 reverse refresh

Web ASG 용량은 전 과정에서 `Min 1 / Desired 1 / Max 1`을 유지한다.

### 5.7 CloudFront ALB 복귀

1. `/api/*`를 `buildgraph-demo-api-green-alb-origin`으로 복귀한다.
2. Distribution `Deployed` 후 API smoke를 수행한다.
3. `/ws/*`를 ALB origin으로 복귀한다.
4. WebSocket `101`, frame, 65초 유지, 재연결을 검증한다.

### 5.8 2026-07-16 실행 결과

| 항목 | 결과 |
| --- | --- |
| 두 AZ 검증 EC2 | `i-0eb6cb14d7dfdcdaf`(2a), `i-06890d6180a870050`(2b) 검증 통과 후 종료 |
| 운영 Launch Template | `lt-0024991a1e82e5e6c`, version `4` |
| 운영 AMI | `ami-0b047a683dc98c08a` |
| 운영 ASG Subnet | `subnet-06b224c812227caba`, `subnet-0ec9ec7bb60433a68` |
| 운영 EC2 | `i-03eb5c75820e6f224`, Private 2b |
| 운영 EC2 주소 | Private `10.0.35.62`, Public IPv4 없음 |
| Instance Refresh | `Successful`, 100% |
| 기존 Public ASG EC2 | `i-0d8bfaec8aab8cc65`, 종료됨 |
| ASG 용량 | `Min 1 / Desired 1 / Max 1` |
| Target Group | Private EC2 한 대 `healthy` |
| 배포 동결 | GitHub variable `GREEN_CD_ENABLED=false` |
| bootstrap Git SHA | `e7d481bdf7b455723f888df8d1522adae97b0c85` |
| 이미지 검증 | Nginx·API·XGB 실행 이미지가 release manifest와 일치 |
| Scheduler | API 컨테이너 `BUILDGRAPH_SCHEDULING_ENABLED=false` |
| API | `/api/health` 200, `status=UP`, `database=UP` |
| API 404 계약 | 존재하지 않는 API가 JSON 404 반환 |
| WebSocket | CloudFront→ALB→Private EC2 경로에서 HTTP `101` 확인 |
| CloudWatch Agent | `active` |
| 전환 시간대 5XX | ALB 5XX `0`, Target 5XX `0` |
| CloudFront 최종 상태 | `/api/*`, `/ws/*` 모두 ALB origin, `Deployed` |

CloudFront 설정은 전체 config를 파일로 저장하지 않고 메모리 파이프로 전달했으며,
각 behavior의 `TargetOriginId`만 순서대로 변경했다.

다음 검증은 인증 ticket과 실제 로그인 세션이 필요하므로 수동 확인 항목으로 남긴다.

- [ ] WebSocket `AUTH` frame 처리
- [ ] 실제 push frame 수신
- [ ] 연결 65초 이상 유지
- [ ] 연결 종료 후 정상 재연결

현재 AMI는 API 컨테이너에
`AWS_STS_REGIONAL_ENDPOINTS=regional`을 전달하기 전 버전이다. Private 전환에 필요한
bootstrap·ECR·Secrets Manager·SSM·외부 HTTPS와 애플리케이션 health는 모두
통과했으며 STS는 Regional NAT를 통해 접근할 수 있다. 현재 브랜치의
Compose·bootstrap 변경을 main에 반영하고 새 immutable AMI를 만들 때 이 runtime
드리프트를 해소한다.

검증 과정에서 만들어진 미사용 LT version `2`, `3`은 운영 ASG에서 사용하지 않는다.
운영 version `4`와 rollback version `1`을 보존한 상태에서 별도 정리한다.

## 6. 롤백

Private 전환 실패 시 CloudFront는 수동 Green origin에 둔 상태를 유지한다.

자동 롤백 순서:

1. 진행 중 forward Instance Refresh 취소
2. ASG Subnet을 기존 Public 2a·2b로 복원
3. 이전 LT 숫자 version 복원
4. reverse Instance Refresh 시작
5. Public ASG Target Healthy 확인
6. API·WebSocket smoke 확인

Instance Refresh AutoRollback만으로는 ASG Subnet 목록이 복원되지 않을 수 있으므로
script의 명시적 Subnet·LT 복원을 기준으로 한다.

기존 Public LT version, AMI, 수동 Green, EIP는 전환 성공 후 최소 24시간 동안
삭제하지 않는다.

## 7. 완료 조건

- [x] Private App Subnet 2개 Available
- [x] `MapPublicIpOnLaunch=false`
- [x] Regional NAT Available
- [x] Private App Route Table 두 개의 `0.0.0.0/0`가 Regional NAT를 가리킴
- [x] S3 Gateway Endpoint Available
- [x] Interface Endpoint 5개 Available, Private DNS 활성
- [x] Endpoint SG inbound가 ASG SG source TCP `443`만 허용
- [x] 두 AZ 검증 EC2가 Public IPv4 없이 bootstrap 통과 후 종료됨
- [x] Web ASG가 Private App Subnet 2개만 사용
- [x] ASG LT가 숫자 version이며 Public IPv4 비활성
- [x] 실행 EC2 Public IPv4 없음
- [x] ASG `1/1/1`
- [x] Target Healthy 1개
- [x] CloudFront `/api/*`, `/ws/*`가 ALB origin으로 복귀
- [x] API health·404 smoke 통과
- [x] WebSocket HTTP `101` 통과
- [ ] WebSocket 인증 frame·실제 push·65초 유지·재연결 통과
- [x] ALB·Target 5XX 증가 없음
- [x] 기존 Public LT·AMI·수동 Green rollback 자원 보존
