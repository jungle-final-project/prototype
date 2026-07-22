import type { ReactNode } from 'react';

/**
 * AI 챗 답변에서 중요 문구(가격·FPS·스펙 수치·해상도·점수·판정 어휘·부품명)를 <strong>으로 강조한다.
 *
 * 계약: 원문 문자열은 한 글자도 더하거나 빼지 않고 감싸기만 한다.
 * E2E 오라클이 "서버 message가 렌더된 textContent에 그대로 포함"을 검사하고,
 * 서버 응답·캐시(v80)는 순수 평문이라 마커 파싱이 아니라 표기 형식 매칭으로 찾는다.
 * (안정 표기의 출처: BuildChatService "%,d원"/"{n}FPS"/"N) 부품명 — 가격원"/"교체: A → B.",
 *  AiDraftApplicationFeedbackCoordinator "종합 점수는 N점"/"FHD 209 · QHD 140 · 4K 80FPS"/
 *  "120Hz 이상으로 쾌적"/"120Hz에 못 미치니", ToolCheckService 부정 판정 문미 —
 *  이 형식이 바뀌면 여기도 함께 본다.)
 */
function buildTokenPattern(): RegExp {
  try {
    return new RegExp(
      [
        '\\d{1,3}(?:,\\d{3})+\\s?만\\s?원', // 1,300만원 (LLM 표기) — 콤마 원화보다 먼저 시도해 통째로 잡는다
        '\\d{1,3}(?:,\\d{3})+원', // 1,234,000원 — 콤마 묶음이 있는 금액
        '\\d+(?:\\.\\d+)?\\s?만\\s?원', // 150만원 (formatBudgetLabel)
        '\\d+원', // 콤마 없는 소액·LLM 표기
        '(?<![A-Za-z0-9])(?:FHD|QHD|UHD|4K)\\s\\d{1,3}(?:,\\d{3})*(?:\\s?FPS)?', // 해상도별 예상 성능 "FHD 209" / "4K 80FPS" — 해상도 단독 규칙보다 먼저
        '\\d+(?:\\.\\d+)?\\s?FPS', // 87FPS / 87.5FPS (서버는 공백 없이 대문자)
        '(?<![A-Za-z0-9])\\d+(?:,\\d{3})*\\s?W(?![A-Za-z0-9])', // 850W/1000W — 숫자 중간에서 매칭이 시작되지 않게 좌측 경계에 숫자 포함
        '(?<![A-Za-z0-9])\\d+(?:\\.\\d+)?\\s?mm(?![A-Za-z0-9])', // 360mm (장착 검사 치수)
        '(?<![A-Za-z0-9])\\d+(?:\\.\\d+)?\\s?Hz(?![A-Za-z0-9])', // 120Hz — 고주사율 판정 기준
        '(?<![A-Za-z0-9.])\\d{1,3}(?:,\\d{3})*점', // 종합 점수 806점 / 0점
        '(?<![A-Za-z0-9])(?:FHD|QHD|UHD|4K)(?![A-Za-z0-9])', // 해상도 토큰
        '(?<![A-Za-z])TOP\\s?\\d+', // 추천 TOP3 (Desktop 3 같은 단어 내부 오매칭 방지)
        '쾌적[가-힣]*', // 판정(긍정): 쾌적하고/쾌적합니다
        '못\\s?미[치칩쳐침][가-힣]*', // 판정(한계): 못 미치니/못 미칩니다 — 사용자가 꼭 봐야 할 주의 문구
        '부족합니다|빠듯합니다|초과합니다|장착할 수 없습니다', // 검사 요약의 부정 판정 문미
        '배틀그라운드|배그|로스트아크|발로란트|오버워치(?:\\s?2)?|사이버펑크(?:\\s?2077)?', // 게임명 (서버 표시명 + 성능 패널 라벨)
      ].join('|'),
      'gi'
    );
  } catch {
    // lookbehind 미지원 엔진에서 모듈 로드가 죽지 않게 — 좌측 경계가 필요한
    // W/mm/Hz/해상도/TOP 규칙만 빼고 나머지 강조는 그대로 동작한다.
    // (현재 소비처 AiBuildAssistant에 이미 lookbehind 리터럴이 있어 사실상 방어용 여분이다.)
    return new RegExp(
      [
        '\\d{1,3}(?:,\\d{3})+\\s?만\\s?원',
        '\\d{1,3}(?:,\\d{3})+원',
        '\\d+(?:\\.\\d+)?\\s?만\\s?원',
        '\\d+원',
        '\\d+(?:\\.\\d+)?\\s?FPS',
        '\\d{1,3}(?:,\\d{3})*점',
        '쾌적[가-힣]*',
        '못\\s?미[치칩쳐침][가-힣]*',
        '부족합니다|빠듯합니다|초과합니다|장착할 수 없습니다',
        '배틀그라운드|배그|로스트아크|발로란트|오버워치(?:\\s?2)?|사이버펑크(?:\\s?2077)?',
      ].join('|'),
      'gi'
    );
  }
}

const TOKEN_PATTERN = buildTokenPattern();

// "1) 부품명 — 1,234,000원 ..." — 번호 목록의 부품명 구간 (구분자 ' — ' 앞까지)
const NUMBERED_ITEM_PATTERN = /^(\d{1,2}[.)]\s)(.+?)(\s—\s)/;
// "교체: A → B. " / "추가: 부품명. " — 변경 요약의 부품명 구간 (첫 '. ' 앞까지)
const CHANGE_SUMMARY_PATTERN = /^((?:교체|추가|제거):\s)(.+?)(?=\.\s|\.$|$)/;

function strongNode(key: string, text: string): ReactNode {
  return (
    <strong key={key} className="font-semibold">
      {text}
    </strong>
  );
}

function emphasizeTokens(text: string, keyBase: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  let cursor = 0;
  TOKEN_PATTERN.lastIndex = 0;
  let match = TOKEN_PATTERN.exec(text);
  while (match !== null) {
    if (match.index > cursor) nodes.push(text.slice(cursor, match.index));
    nodes.push(strongNode(`${keyBase}:${match.index}`, match[0]));
    cursor = match.index + match[0].length;
    match = TOKEN_PATTERN.exec(text);
  }
  if (cursor < text.length) nodes.push(text.slice(cursor));
  return nodes.length ? nodes : [text];
}

export function emphasizeChatText(text: string): ReactNode {
  const numbered = NUMBERED_ITEM_PATTERN.exec(text);
  if (numbered) {
    const head = numbered[0];
    return [
      numbered[1],
      strongNode('item-name', numbered[2]),
      numbered[3],
      ...emphasizeTokens(text.slice(head.length), 'rest'),
    ];
  }
  const change = CHANGE_SUMMARY_PATTERN.exec(text);
  if (change) {
    const head = change[0];
    return [
      change[1],
      strongNode('change-name', change[2]),
      ...emphasizeTokens(text.slice(head.length), 'rest'),
    ];
  }
  return emphasizeTokens(text, 't');
}
