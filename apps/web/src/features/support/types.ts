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
  safetyAdviceLevel?: string | null;
  safetyNotices?: SafetyNoticeDto[] | null;
  createdAt?: string;
  deleteAfter: string;
};

export type SafetyNoticeDto = {
  code?: string;
  message?: string;
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
  analysisStatus?: string | null;
  reviewStatus?: string | null;
  supportDecision?: string | null;
  riskLevel?: string | null;
  autoResponseAllowed?: boolean | null;
  safetyAdviceLevel?: string | null;
  safetyNotices?: SafetyNoticeDto[] | null;
  feedbackRating?: number | null;
  feedbackComment?: string | null;
  feedbackCreatedAt?: string | null;
  diagnosticAccuracy?: string | null;
  symptom: string;
  logUploadId?: string | null;
  assignedAdminId?: string | null;
  causeCandidates: CauseCandidate[];
  upgradeCandidates: UpgradeCandidate[];
  adminNote?: string | null;
  remoteSupportLink?: string | null;
  remoteSupportStatus?: string | null;
  visitSupportRequired?: boolean | null;
  visitSupportStatus?: string | null;
  visitPreferredDate?: string | null;
  visitTimeSlot?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
};
