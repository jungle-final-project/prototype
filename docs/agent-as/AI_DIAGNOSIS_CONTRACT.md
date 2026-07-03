# PC Agent AS AI 진단 계약 초안

## 목적

이 문서는 `jungle-final-project/PCagent`의 AS 로그 시나리오를 참고해 3번 AI 담당자가 준비할 수 있는 진단 입력/출력/학습 데이터 계약을 정리한다. 현재 `prototype/main`에는 PC Agent 최종 AS 런타임이 아직 병합되지 않았으므로, 이 문서는 production DB/API를 선점하지 않는다.

## 경계

- `PCagent` 코드는 prototype에 병합하거나 복사하지 않는다.
- AS 로그 저장, AS 티켓 확정 필드, 원격지원/방문지원 상태 전이는 4번 PC Agent/AS owner가 확정한 계약을 따른다.
- AI는 관리자 승인 전 ticket 확정값을 수정하지 않는다.
- AI 추론/학습 입력은 raw gzip 또는 전체 JSONL이 아니다.
- fixture는 AS 런타임 병합 전에도 AI 계약을 검증하기 위한 준비물이다.

## 입력 계약

AI는 ticket 단위 `AiDiagnosisRequest` snapshot만 받는다.

```json
{
  "requestId": "ai-request-id",
  "ticketId": "ticket-public-id",
  "userSymptom": {
    "symptomType": "REMOTE_DRIVER_OS",
    "description": "게임 중 화면 드라이버 경고와 발열이 있습니다."
  },
  "logSummary": {},
  "supportRouting": {},
  "rawSamples": [],
  "locale": "ko-KR",
  "outputContractVersion": "1"
}
```

허용 입력:

- `LogSummary`
- `supportRouting`
- 제한된 `rawSamples`, 최대 20개
- 관리자 승인 결과
- 사용자 피드백

금지 입력:

- raw gzip 전체
- 전체 JSONL
- 전체 프로세스 목록
- full path
- token/password
- 브라우저 기록
- clipboard/key input/screenshot

## 출력 계약

`AiDiagnosisResult`는 ticket 자동 반영값이 아니라 관리자 승인 화면에 올릴 제안이다.

```json
{
  "contractVersion": "1",
  "supportDecision": "REMOTE_POSSIBLE",
  "riskLevel": "MEDIUM",
  "confidence": "MEDIUM",
  "reasonCodes": ["DRIVER_CRASH_LOG"],
  "causeCandidates": [
    {
      "label": "DRIVER_CRASH_LOG",
      "confidence": "MEDIUM",
      "reason": "디스플레이 드라이버 경고가 로그에 반복됩니다."
    }
  ],
  "nextActions": [
    {
      "label": "관리자 검토",
      "priority": "MEDIUM",
      "instruction": "관리자 승인 후 사용자 안내에 반영합니다."
    }
  ],
  "remoteActions": ["REINSTALL_GRAPHICS_DRIVER"],
  "visitReasons": [],
  "blockingFactors": [],
  "requiredAdditionalLogs": [],
  "evidenceRefs": [],
  "toolRefs": [],
  "unsafeActionsExcluded": ["원본 전체 로그 반환", "전체 프로세스 목록 학습"],
  "adminReviewRequired": true,
  "userFirstNotice": {
    "title": "그래픽 드라이버 오류 가능성이 있습니다.",
    "summary": "업로드된 로그에서 디스플레이 드라이버 경고가 확인되었습니다.",
    "safeActions": ["진행 중인 작업을 저장하세요."],
    "additionalQuestions": []
  },
  "proposedTicketPatch": {}
}
```

## 학습 데이터 정책

학습 row 하나는 raw log line이 아니라 AS ticket 단위다.

포함 가능:

- `userSymptom`
- `LogSummary`
- `supportRouting`
- `AiDiagnosisResult`
- 관리자 최종 처리 결과
- `diagnosticAccuracy`
- 사용자 feedback

제외:

- raw gzip
- 전체 JSONL
- 전체 process list
- full path
- token/password

eligible 기준:

- 관리자 승인 또는 종결된 AS ticket만 학습 후보가 된다.
- 단순 AS 접수만으로 자동 label을 만들지 않는다.
- `admin-label.json`의 `rawFullLogIncluded=false` 정책을 유지한다.

## 추후 연결 기준

PC Agent/AS 런타임이 prototype에 병합된 뒤 연결할 때는 다음 순서로 진행한다.

1. AS owner가 확정한 ticket/log summary/support routing schema를 확인한다.
2. `AiDiagnosisRequest` adapter를 만든다.
3. AI 결과는 별도 `ai_diagnoses` 또는 owner가 확정한 저장소에 `PENDING_REVIEW`로 저장한다.
4. 관리자 승인 전에는 ticket 확정 필드를 수정하지 않는다.
5. 승인/거절/재생성 API와 학습 dataset/job/model UI를 owner 계약 위에 얹는다.

## Runtime 연결 보류

AS 담당자 구현이 별도 진행 중이므로 이 문서는 런타임 API를 새로 정의하지 않는다. 현재 산출물의 역할은 fixture와 입출력 계약을 고정해 후속 AS 구현 검증에 재사용하는 것이다.

- 별도 독립 AI 랩 route/API는 제공하지 않는다.
- AS ticket 상태, cause candidates, remote/visit decision 수정은 AS owner 구현과 승인 정책을 따른다.
- 후속 연결 시에는 이 문서의 `AiDiagnosisRequest`/`AiDiagnosisResult`와 50개 fixture를 AS owner API 검증에 연결한다.
