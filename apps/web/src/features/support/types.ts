export type TicketRow = {
  id: string;
  user: string;
  symptom: string;
  status: string;
  cause: string;
  confidence: string;
};

export type AgentLogUploadDto = {
  id: string;
  status: string;
  fileName: string;
  fileSize?: number;
  rangeMinutes: number;
  summary?: string;
  createdAt?: string;
  deleteAfter: string;
};

export type CauseCandidate = {
  code?: string;
  label?: string;
  confidence?: string;
  evidenceIds?: string[];
};

export type UpgradeCandidate = {
  category?: string;
  reason?: string;
  partIds?: string[];
  estimatedPrice?: number;
};

export type AsTicketDto = {
  id: string;
  status: string;
  symptom: string;
  logUploadId?: string | null;
  assignedAdminId?: string | null;
  causeCandidates: CauseCandidate[];
  upgradeCandidates: UpgradeCandidate[];
  adminNote?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
};
