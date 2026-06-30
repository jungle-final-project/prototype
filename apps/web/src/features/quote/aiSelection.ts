export const AI_SELECTED_BUILD_STORAGE_KEY = 'buildgraph.ai.selectedBuild';
export const AI_SELECTED_BUILD_CHANGED_EVENT = 'buildgraph.ai.selectedBuildChanged';
export const AI_ASSISTANT_SESSION_STORAGE_KEY = 'buildgraph.ai.assistantSession';
export const AI_ASSISTANT_SESSION_CHANGED_EVENT = 'buildgraph.ai.assistantSessionChanged';

export type AiBuildTier = 'budget' | 'balanced' | 'performance';
export type PartCategory = 'CPU' | 'MOTHERBOARD' | 'RAM' | 'GPU' | 'STORAGE' | 'PSU' | 'CASE' | 'COOLER';
export type AiChatAnswerType = 'BUDGET' | 'PART' | 'GENERAL';

export type AiToolResult = {
  tool: string;
  status: 'PASS' | 'WARN' | 'FAIL';
  confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  summary: string;
  details?: Record<string, unknown>;
};

export type AiBuildItem = {
  partId: string;
  category: PartCategory;
  name: string;
  manufacturer: string;
  quantity: number;
  price: number;
  note: string;
};

export type AiRecommendedBuild = {
  id: string;
  tier: AiBuildTier;
  label: string;
  title: string;
  summary: string;
  totalPrice: number;
  badges: string[];
  budgetWon: number;
  budgetLabel: string;
  tierLabel: string;
  appliedPartCategories: PartCategory[];
  items: AiBuildItem[];
  toolResults?: AiToolResult[];
  warnings?: string[];
  confidence?: 'LOW' | 'MEDIUM' | 'HIGH';
};

export type AiSelectedBuild = Omit<AiRecommendedBuild, 'label' | 'badges'> & {
  selectedAt: string;
};

export type AiPartRecommendation = {
  category: PartCategory;
  label: string;
  intro: string;
  options: AiBuildItem[];
};

export type AiAppliedPartPreference = {
  category: PartCategory;
  label: string;
  appliedAt: string;
  options: AiBuildItem[];
};

export type AiChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  createdAt: string;
  kind: 'intro' | 'budget' | 'part' | 'general';
  budgetWon?: number;
  builds?: AiRecommendedBuild[];
  partRecommendation?: AiPartRecommendation | null;
  warnings?: string[];
};

export type AiAssistantSession = {
  messages: AiChatMessage[];
  latestBuilds: AiRecommendedBuild[];
  appliedPartPreferences: AiAppliedPartPreference[];
  updatedAt: string;
};

export type AiBuildChatRequest = {
  message: string;
  currentBuilds?: AiRecommendedBuild[];
  appliedPartPreferences?: AiAppliedPartPreference[];
};

export type AiBuildChatResponse = {
  answerType: AiChatAnswerType;
  message: string;
  builds: AiRecommendedBuild[];
  partRecommendation?: AiPartRecommendation | null;
  warnings?: string[];
};

export const PART_CATEGORY_LABELS: Record<PartCategory, string> = {
  CPU: 'CPU',
  MOTHERBOARD: '메인보드',
  RAM: 'RAM',
  GPU: 'GPU',
  STORAGE: 'SSD',
  PSU: '파워',
  CASE: '케이스',
  COOLER: '쿨러'
};

const initialAssistantMessage: AiChatMessage = {
  id: 'ai-intro',
  role: 'assistant',
  text: '예산은 “200만원 PC 추천”처럼, 부품은 “GPU 추천해줘”처럼 물어보세요. 추천은 서버의 실제 부품 DB와 룰 기반 검증 결과로 계산됩니다.',
  createdAt: '2026-06-30T00:00:00.000Z',
  kind: 'intro'
};

export function emptyAssistantSession(): AiAssistantSession {
  return {
    messages: [initialAssistantMessage],
    latestBuilds: [],
    appliedPartPreferences: [],
    updatedAt: initialAssistantMessage.createdAt
  };
}

export function createAiMessageId(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function toSelectedAiBuild(build: AiRecommendedBuild): AiSelectedBuild {
  const { label: _label, badges: _badges, ...selectedBuild } = build;
  return {
    ...selectedBuild,
    selectedAt: new Date().toISOString()
  };
}

export function saveSelectedAiBuild(build: AiRecommendedBuild) {
  if (typeof window === 'undefined') return;
  const selectedBuild = toSelectedAiBuild(build);
  window.sessionStorage.setItem(AI_SELECTED_BUILD_STORAGE_KEY, JSON.stringify(selectedBuild));
  window.dispatchEvent(new Event(AI_SELECTED_BUILD_CHANGED_EVENT));
}

export function readSelectedAiBuild(): AiSelectedBuild | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(AI_SELECTED_BUILD_STORAGE_KEY);
    return raw ? JSON.parse(raw) as AiSelectedBuild : null;
  } catch {
    return null;
  }
}

export function clearSelectedAiBuild() {
  if (typeof window === 'undefined') return;
  window.sessionStorage.removeItem(AI_SELECTED_BUILD_STORAGE_KEY);
  window.dispatchEvent(new Event(AI_SELECTED_BUILD_CHANGED_EVENT));
}

export function readAssistantSession(): AiAssistantSession {
  if (typeof window === 'undefined') return emptyAssistantSession();
  try {
    const raw = window.sessionStorage.getItem(AI_ASSISTANT_SESSION_STORAGE_KEY);
    if (!raw) return emptyAssistantSession();
    const parsed = JSON.parse(raw) as AiAssistantSession;
    if (!Array.isArray(parsed.messages) || !Array.isArray(parsed.latestBuilds)) {
      return emptyAssistantSession();
    }
    return {
      messages: parsed.messages.length > 0 ? parsed.messages : [initialAssistantMessage],
      latestBuilds: parsed.latestBuilds ?? [],
      appliedPartPreferences: parsed.appliedPartPreferences ?? [],
      updatedAt: parsed.updatedAt ?? initialAssistantMessage.createdAt
    };
  } catch {
    return emptyAssistantSession();
  }
}

export function saveAssistantSession(session: AiAssistantSession) {
  if (typeof window === 'undefined') return;
  window.sessionStorage.setItem(AI_ASSISTANT_SESSION_STORAGE_KEY, JSON.stringify(session));
  window.dispatchEvent(new Event(AI_ASSISTANT_SESSION_CHANGED_EVENT));
}
