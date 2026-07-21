export type PartRow = {
  id: string;
  category: string;
  name: string;
  manufacturer?: string;
  price: number;
  status: string;
  attributes?: Record<string, unknown>;
  benchmarkSummary?: {
    summary?: string;
    score?: number | string;
  } | null;
  latestPriceSource?: string | null;
  latestPriceCollectedAt?: string | null;
  externalOffer?: {
    title?: string | null;
    imageUrl?: string | null;
    supplierName?: string | null;
    offerUrl?: string | null;
    lowPrice?: number | null;
    source?: string | null;
    refreshedAt?: string | null;
  } | null;
  score?: number;
  compatibility?: PartCompatibility | null;
  recommendation?: PartRecommendation | null;
};

export type PartRecommendation = {
  recommended: true;
  rank: number;
  score: number;
  reasons: string[];
};

export type PartCompatibility = {
  status: 'PASS' | 'WARN' | 'FAIL';
  statusLabel: string;
  summary: string;
  checkedTools: string[];
};

export type PartPage = {
  items: PartRow[];
  page: number;
  size: number;
  total: number;
};

export type HomeRecommendedPart = {
  recommendationId: string;
  rankPosition: number;
  part: PartRow;
  scoreSource?: string | null;
  modelVersion?: string | null;
  reasonTags?: string[];
};

export type HomeRecommendedPartsResponse = {
  items: HomeRecommendedPart[];
  generatedAt: string;
  fallbackUsed: boolean;
};
export type PublicHomeResponse = {
  categoryParts: Partial<Record<string, PartRow[]>>;
  recommendedParts: HomeRecommendedPartsResponse;
};

export type RecommendationEventRequest = {
  eventType: 'IMPRESSION' | 'CLICK' | 'DETAIL_VIEW' | 'SAVE' | 'CHANGE_ADOPTED' | 'ADD_BUILD_TO_DRAFT' | 'ADD_PART_TO_DRAFT' | 'ORDER_INTENT' | 'REJECT' | 'CHANGE_REVERTED';
  sourceSurface: string;
  recommendationId?: string;
  partId?: string;
  buildId?: string;
  category?: string;
  rankPosition?: number;
  idempotencyKey?: string;
  eventPayload?: Record<string, unknown>;
};

export type RecommendationEventBulkRequest = {
  events: RecommendationEventRequest[];
};

export type RecommendationEventBulkAcceptedResponse = {
  accepted: boolean;
  queued: number;
};

export type PartSearchParams = {
  category?: string;
  q?: string;
  manufacturer?: string;
  status?: string;
  minPrice?: number;
  maxPrice?: number;
  page?: number;
  size?: number;
  sort?: 'category' | 'price_asc' | 'price_desc' | 'name' | 'compatibility';
  compatibilitySource?: 'QUOTE_DRAFT_CURRENT';
  compatibilityMode?: 'ADD' | 'REPLACE';
  replaceTargetPartId?: string;
};

export type CompatiblePartCandidateRequest = {
  source: 'AI_BUILD' | 'QUOTE_DRAFT_CURRENT';
  category: string;
  items?: Array<{
    partId: string;
    category: string;
    quantity: number;
  }>;
  limit?: number;
};

export type CompatiblePartCandidate = {
  part: PartRow;
  status: 'PASS' | 'WARN' | 'FAIL';
  statusLabel: string;
  summary: string;
  checkedTools: string[];
};

export type CompatiblePartCandidateResponse = {
  category: string;
  items: CompatiblePartCandidate[];
  rejectedCount: number;
  warnings: string[];
};

export type PartPriceHistoryPoint = {
  price: number;
  source: string;
  collectedAt: string;
};

export type PartPriceHistorySummary = {
  sampleCount: number;
  currentPrice: number;
  minPrice: number;
  maxPrice: number;
  firstPrice: number;
  lastPrice: number;
  changeAmount: number;
  changeRatePercent: number;
};

export type PartPriceHistory = {
  partId: string;
  partName: string;
  currentPrice: number;
  days: number;
  source?: string | null;
  items: PartPriceHistoryPoint[];
  summary: PartPriceHistorySummary;
};

export type PartPriceHistoryParams = {
  days?: number;
  source?: string;
  limit?: number;
};

export type QuoteDraftItem = {
  id: string;
  partId: string;
  category: string;
  name: string;
  manufacturer?: string | null;
  quantity: number;
  unitPriceAtAdd: number;
  currentPrice: number;
  lineTotal: number;
  attributes?: Record<string, unknown>;
  externalOffer?: PartRow['externalOffer'];
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type QuoteDraft = {
  id?: string | null;
  status: 'EMPTY' | 'ACTIVE' | 'ORDERED' | 'ARCHIVED';
  name: string;
  items: QuoteDraftItem[];
  totalPrice: number;
  itemCount: number;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type QuoteDraftHistoryEntry = {
  id: string;
  actionType: 'ITEM_ADD' | 'ITEM_REPLACE' | 'ITEM_REMOVE' | 'QUANTITY_CHANGE' | 'AI_BUILD_APPLY' | 'RESTORE';
  actionLabel: string;
  relatedCategories: string[];
  totalPrice: number;
  itemCount: number;
  evaluationStatus: 'PENDING' | 'RUNNING' | 'VALID' | 'INVALID' | 'UNAVAILABLE';
  score?: number | null;
  maxScore?: number | null;
  issueCodes: string[];
  evaluatedAt?: string | null;
  createdAt: string;
  expiresAt: string;
};

export type QuoteDraftHistoryList = {
  items: QuoteDraftHistoryEntry[];
  retentionDays: number;
  maxItems: number;
  maxProblemItems: number;
};

export type QuoteDraftHistorySnapshotItem = {
  partId: string;
  category: string;
  name: string;
  manufacturer?: string | null;
  quantity: number;
  unitPriceAtAdd: number;
  lineTotal: number;
};

export type QuoteDraftHistoryIssue = {
  code?: string | null;
  severity?: string | null;
  title?: string | null;
  description?: string | null;
  relatedCategories?: string[] | null;
};

export type QuoteDraftHistoryComparisonSide = {
  items: QuoteDraftHistorySnapshotItem[];
  totalPrice: number;
  itemCount: number;
  compositeScore?: { score: number; maxScore: number; grade?: string; label?: string; summary?: string } | null;
  fps?: { gameTitle?: string; resolution?: string; graphicsPreset?: string; avgFps?: number; sourceName?: string } | null;
  issues: QuoteDraftHistoryIssue[];
  evaluationAvailable: boolean;
};

export type QuoteDraftHistoryDifference = {
  category: string;
  categoryLabel: string;
  changeType: 'ADDED' | 'REMOVED' | 'REPLACED' | 'QUANTITY_CHANGED';
  beforeItems: QuoteDraftHistorySnapshotItem[];
  afterItems: QuoteDraftHistorySnapshotItem[];
};

export type QuoteDraftHistoryComparison = {
  history: QuoteDraftHistoryEntry;
  past: QuoteDraftHistoryComparisonSide;
  current: QuoteDraftHistoryComparisonSide;
  differences: QuoteDraftHistoryDifference[];
  issueChanges: { added: QuoteDraftHistoryIssue[]; resolved: QuoteDraftHistoryIssue[] };
  game: string;
  resolution: 'fhd' | 'qhd' | '4k';
  restorable: boolean;
  unavailableItems: Array<{ partId: string; category: string; name: string }>;
  requiresCompatibilityConfirmation: boolean;
  evaluationBasis: 'CURRENT_CATALOG_DATA';
  historicalPriceBasis: 'SNAPSHOT_UNIT_PRICE';
};

export type ToolRow = {
  tool: string;
  status: string;
  confidence: string;
  summary: string;
};
