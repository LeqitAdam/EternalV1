/** Mirrors PunishmentEntry from eternal-core. */
export interface Punishment {
  id: number;
  type: 'BAN' | 'MUTE';
  targetUuid: string;
  targetName: string;
  issuerUuid: string | null;
  issuerName: string;
  reasonId: string;
  reasonLabel: string;
  publicMessage: string;
  issuedAt: number;
  expiresAt: number | null;
  active: boolean;
  pardonIssuerUuid: string | null;
  pardonIssuerName: string | null;
  pardonReason: string | null;
  pardonedAt: number | null;
  /** Set by /modify; null when the row was never touched. */
  modifiedAt: number | null;
  modifiedByUuid: string | null;
  modifiedByName: string | null;
}

export interface Report {
  id: number;
  reporterUuid: string;
  reporterName: string;
  targetUuid: string;
  targetName: string;
  reasonId: string;
  reasonLabel: string;
  comment: string | null;
  serverName: string;
  createdAt: number;
  status: 'OPEN' | 'CLAIMED' | 'CLOSED';
  handlerUuid: string | null;
  handlerName: string | null;
  claimedAt: number | null;
  closedAt: number | null;
  resolution: string | null;
}

export interface PlayerProfile {
  uuid: string;
  name: string;
  firstSeen: number;
  lastSeen: number;
  lastAddress: string;
  lastTier: number;
  lastGroupName: string;
}

export interface PlayerLookup {
  profile: PlayerProfile;
  activeBan: Punishment | null;
  activeMute: Punishment | null;
  history: Punishment[];
  reports: Report[];
}

export interface StaffStat {
  staffUuid: string;
  staffName: string;
  banCount: number;
  muteCount: number;
  reportsHandled: number;
}

export interface Me {
  name: string;
  role: 'ADMIN' | 'MOD' | 'PLAYER';
  uuid: string | null;
}

export interface ReportPage {
  total: number;
  items: Report[];
}

export type ReportStatusFilter = 'open' | 'claimed' | 'closed' | 'active' | 'all';

export interface UnbanAppeal {
  id: number;
  banId: number;
  applicantUuid: string;
  applicantName: string;
  text: string;
  status: 'PENDING' | 'APPROVED' | 'DENIED';
  createdAt: number;
  reviewerUuid: string | null;
  reviewerName: string | null;
  reviewedAt: number | null;
  decisionReason: string | null;
}

export interface LinkInit {
  code: string;
  linkToken: string;
  expiresAt: number;
}

export interface LinkStatus {
  status: 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CONSUMED';
  sessionToken?: string;
  user?: { uuid: string; name: string; role: 'ADMIN' | 'MOD' };
  expiresAt?: number;
}
