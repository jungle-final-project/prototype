import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../../lib/api';
import { getPcAgentDiagnosis } from './supportApi';
import type { PcAgentDiagnosisDto, PcAgentDiagnosisEventDto } from './supportApi';

export const PC_AGENT_DIAGNOSIS_POLL_INTERVAL_MS = 2_000;

const TERMINAL_WITHOUT_RESULT_STATES = new Set([
  'FAILED',
  'CANCELLED',
  'TIMED_OUT'
]);

export type PcAgentDiagnosisPollingState = {
  diagnosisId: string;
  snapshot: PcAgentDiagnosisDto | null;
  events: PcAgentDiagnosisEventDto[];
  error: string;
  polling: boolean;
};

const EMPTY_POLLING_STATE: PcAgentDiagnosisPollingState = {
  diagnosisId: '',
  snapshot: null,
  events: [],
  error: '',
  polling: false
};

export function usePcAgentDiagnosisPolling(diagnosisId: string): PcAgentDiagnosisPollingState {
  const [state, setState] = useState<PcAgentDiagnosisPollingState>(EMPTY_POLLING_STATE);
  const inFlightRequest = useRef<Promise<unknown> | null>(null);
  const activeController = useRef<AbortController | null>(null);

  useEffect(() => {
    let active = true;
    let timer: number | undefined;
    let pollCount = 0;
    let completedGrace = 0;
    // 무진행 방어: 이벤트가 끊긴 채(에이전트 종료 등) 무한 2초 폴링하지 않게 총 상한을 둔다(~5분).
    const MAX_POLLS = 150;
    // 서버가 완료(completed)를 알렸는데 결과 프레임이 아직 안 온 창을 위한 유예(~10초). 넘으면 결과 미수신 종료.
    const COMPLETED_GRACE_POLLS = 5;

    setState({
      diagnosisId,
      snapshot: null,
      events: [],
      error: '',
      polling: Boolean(diagnosisId)
    });

    if (!diagnosisId) {
      return () => {
        active = false;
      };
    }

    const scheduleNext = () => {
      timer = window.setTimeout(runPoll, PC_AGENT_DIAGNOSIS_POLL_INTERVAL_MS);
    };

    const runPoll = async (): Promise<void> => {
      if (!active) return;

      // StrictMode 재마운트나 diagnosis ID 교체 시 이전 fetch가 정리될 때까지 기다려
      // 같은 컴포넌트 인스턴스에서 조회 요청이 겹치지 않게 한다.
      if (inFlightRequest.current) {
        try {
          await inFlightRequest.current;
        } catch {
          // 이전 effect가 오류 상태를 처리한다. 새 effect는 정리 완료 뒤 자신의 ID를 조회한다.
        }
        if (active) {
          await runPoll();
        }
        return;
      }

      const controller = new AbortController();
      activeController.current = controller;
      const request = getPcAgentDiagnosis(diagnosisId, controller.signal);
      inFlightRequest.current = request;
      let repeat = true;

      try {
        const response = await request;
        if (!active) return;
        pollCount += 1;
        const events = uniqueEventsInServerOrder(response.events);
        let terminal = isTerminalDiagnosis(response);
        let terminationNote = '';
        if (!terminal && response.completed && !response.result) {
          // 서버는 완료를 알렸는데 결과가 유예 시간 내 도착하지 않으면 '결과 미수신'으로 종료한다.
          completedGrace += 1;
          if (completedGrace > COMPLETED_GRACE_POLLS) {
            terminal = true;
            terminationNote = '진단은 완료됐지만 결과를 받지 못했습니다. 잠시 후 다시 시도해 주세요.';
          }
        } else {
          completedGrace = 0;
        }
        if (!terminal && pollCount >= MAX_POLLS) {
          // 이벤트가 끊긴 채 상한을 넘으면(에이전트 종료 등) 무한 폴링 대신 멈춘다.
          terminal = true;
          terminationNote = 'PC Agent 응답이 없어 진단 조회를 멈췄습니다. 연결을 확인하고 다시 시도해 주세요.';
        }
        repeat = !terminal;
        setState({
          diagnosisId,
          snapshot: response,
          events,
          error: terminationNote,
          polling: repeat
        });
      } catch (cause) {
        if (!active || isAbortError(cause)) return;
        repeat = isRetryablePollingError(cause);
        setState((previous) => previous.diagnosisId === diagnosisId
          ? {
              ...previous,
              error: pollingErrorMessage(cause),
              polling: repeat
            }
          : previous);
      } finally {
        if (inFlightRequest.current === request) {
          inFlightRequest.current = null;
        }
        if (activeController.current === controller) {
          activeController.current = null;
        }
      }

      if (active && repeat) {
        scheduleNext();
      }
    };

    void runPoll();

    return () => {
      active = false;
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
      activeController.current?.abort();
    };
  }, [diagnosisId]);

  if (state.diagnosisId !== diagnosisId) {
    return {
      ...EMPTY_POLLING_STATE,
      diagnosisId,
      polling: Boolean(diagnosisId)
    };
  }
  return state;
}

export function uniqueEventsInServerOrder(events: PcAgentDiagnosisEventDto[]) {
  const seen = new Set<string>();
  return events.filter((event) => {
    if (seen.has(event.eventId)) return false;
    seen.add(event.eventId);
    return true;
  });
}

export function isTerminalDiagnosis(diagnosis: PcAgentDiagnosisDto) {
  // 성공 이벤트와 최종 결과는 별도 메시지로 저장되므로, 두 저장 사이의 응답에서는
  // 결과가 도착할 때까지 조회를 이어 간다. 실패 계열은 결과 없이도 즉시 종료한다.
  return Boolean(diagnosis.result)
    || TERMINAL_WITHOUT_RESULT_STATES.has(diagnosisStatus(diagnosis));
}

export function diagnosisStatus(diagnosis: PcAgentDiagnosisDto) {
  return diagnosis.events[diagnosis.events.length - 1]?.status || diagnosis.status;
}

function isRetryablePollingError(cause: unknown) {
  return !(cause instanceof ApiError && [401, 403, 404].includes(cause.status));
}

function pollingErrorMessage(cause: unknown) {
  if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
    return '진단 진행 상태를 조회할 권한이 없습니다. 로그인 상태를 확인해 주세요.';
  }
  if (cause instanceof ApiError && cause.status === 404) {
    return '진단 요청을 찾을 수 없습니다.';
  }
  return '진단 진행 상태를 일시적으로 불러오지 못했습니다. 다음 조회에서 다시 시도합니다.';
}

function isAbortError(cause: unknown) {
  return cause instanceof DOMException && cause.name === 'AbortError';
}
