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
  } | null;
  score?: number;
};

export type PartPage = {
  items: PartRow[];
  page: number;
  size: number;
  total: number;
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
  sort?: 'category' | 'price_asc' | 'price_desc' | 'name';
};

export type ToolRow = {
  tool: string;
  status: string;
  confidence: string;
  summary: string;
};
