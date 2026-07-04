export const AI_BUILD_ASSISTANT_OPEN_EVENT = 'buildgraph.aiAssistant.open';
export const AI_BUILD_ASSISTANT_TOGGLE_EVENT = 'buildgraph.aiAssistant.toggle';

export type AiAssistantOpenDetail = {
  prefill?: string;
  autoSubmit?: boolean;
};

export function openAiAssistant(detail?: AiAssistantOpenDetail) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(new CustomEvent<AiAssistantOpenDetail>(AI_BUILD_ASSISTANT_OPEN_EVENT, { detail }));
}
