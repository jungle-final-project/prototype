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
  supportChatRoomId?: string | null;
  supportChatUserUnreadCount?: number;
  supportChatAdminUnreadCount?: number;
  supportChatLastMessageAt?: string | null;
  assignedAdminId?: string | null;
  causeCandidates: CauseCandidate[];
  upgradeCandidates: UpgradeCandidate[];
  adminNote?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
};

export type SupportChatContact = {
  id: string;
  asTicketId: string;
  status: string;
  ticketStatus?: string;
  title: string;
  symptom?: string;
  lastMessagePreview?: string | null;
  lastMessageAt?: string | null;
  userUnreadCount?: number;
  adminUnreadCount?: number;
  assignedAdminId?: string | null;
  canSendMessage?: boolean;
  visitReservation?: VisitSupportReservation | null;
  user?: {
    id?: string;
    email?: string;
    name?: string;
  };
};

export type VisitSupportReservation = {
  id: string;
  status: 'REQUESTED' | 'RESCHEDULE_REQUESTED' | 'SCHEDULED' | 'VISIT_IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | string;
  scheduledAt?: string | null;
  addressSnapshot?: string | null;
  technicianNote?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type SupportChatMessage = {
  id: string;
  role: 'USER' | 'ADMIN' | 'SYSTEM';
  content: string;
  senderId?: string | null;
  senderName?: string | null;
  createdAt?: string;
};

export type SupportChatSessionDto = {
  contact: SupportChatContact | null;
  messages: SupportChatMessage[];
  supportNewPath?: string;
  pollingIntervalMs?: number;
};

export type SupportChatSessionListDto = {
  items: SupportChatContact[];
  pollingIntervalMs?: number;
};
