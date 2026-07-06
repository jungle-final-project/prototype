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
  user?: {
    id?: string;
    email?: string;
    name?: string;
  };
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
