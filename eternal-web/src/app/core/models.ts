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
  /** Frozen JSON snapshot of the report's chat context (ChatLogEntry[]),
   *  set once the +after window has been collected. Null while still
   *  pending. The web rarely reads this directly — use api.reportChat(). */
  chatHistory?: string;
}

/* --- Chat-Logs + Social-Spy --------------------------------------- */

/** Kind of a logged line. Sensitive commands are NOT a kind — they live
 *  in a separate store but are stored with kind COMMAND there. */
export type ChatLogKind = 'CHAT' | 'COMMAND' | 'MSG';

/** Mirrors de.eternal.core.model.ChatLogEntry. createdAt is epoch millis.
 *  targetUuid/targetName are only set for MSG (private messages). */
export interface ChatLogEntry {
  id: number;
  kind: ChatLogKind;
  server: string;
  senderUuid: string;
  senderName: string;
  targetUuid?: string | null;
  targetName?: string | null;
  content: string;
  createdAt: number;
}

/** Paged chat-log search result — same {total, items} shape as ReportPage. */
export interface ChatLogPage {
  total: number;
  items: ChatLogEntry[];
}

/** A clustered run of a player's messages (gap > 5 min starts a new one). */
export interface ChatSession {
  startedAt: number;
  endedAt: number;
  messageCount: number;
  messages: ChatLogEntry[];
}

/** A player's sessions grouped by UTC day "yyyy-MM-dd". */
export interface ChatSessionDay {
  day: string;
  sessions: ChatSession[];
}

/** Chat context attached to a report. anchorAt = the reported message's
 *  createdAt (epoch ms). finalized=false => the +after window is still
 *  being collected and the result may grow. */
export interface ReportChat {
  items: ChatLogEntry[];
  anchorAt: number;
  finalized: boolean;
}

export interface PlayerProfile {
  uuid: string;
  name: string;
  firstSeen: number;
  lastSeen: number;
  lastAddress: string;
  lastTier: number;
  lastGroupName: string;
  /** Cached rank-coloured display name from CloudNet-Chat / nametag plugins.
   *  Empty string if we never captured one. Use the `legacy` pipe to render
   *  the &-codes as styled HTML. */
  lastDisplayName: string;
}

export interface PlayerLookup {
  profile: PlayerProfile;
  /** uuid → cached lastDisplayName for every staff/reporter/modifier UUID
   *  referenced in the history + reports. Empty entries are omitted. */
  displayNames: Record<string, string>;
  /** All unban-appeals the player has ever filed, newest first. */
  appeals: UnbanAppeal[];
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
  uuid: string | null;
  /** Effective permission keys (eternal.web.*, eternal.ban, …). Resolved
   *  server-side from the user's CloudNet role grants incl. wildcards, so an
   *  Owner with `*` gets every key. The UI gates purely on this. */
  permissions: string[];
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
  status: 'PENDING' | 'APPROVED' | 'DENIED' | 'SHORTENED';
  createdAt: number;
  reviewerUuid: string | null;
  reviewerName: string | null;
  reviewedAt: number | null;
  decisionReason: string | null;
  /** Player-facing explanation, shown on /me/appeals and in the ban-kick. */
  decisionMessage: string | null;
  /** Only set when status==SHORTENED — new remaining duration in seconds. */
  shortenedToSeconds: number | null;
}

export interface LinkInit {
  code: string;
  linkToken: string;
  expiresAt: number;
}

export interface LinkStatus {
  status: 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CONSUMED';
  sessionToken?: string;
  user?: { uuid: string; name: string };
  expiresAt?: number;
}

/* --- Permission engine (admin) ------------------------------------- */

/** One entry from /admin/permissions/registry — a known permission key
 *  with its UI metadata and hardcoded default policy. */
export interface PermissionRegistryEntry {
  key: string;
  label: string;
  description: string;
  defaultGrant: 'NEVER' | 'ADMIN_ONLY' | 'STAFF_ANY' | 'EVERYONE';
}

/** Registry grouped by category, as returned by the API. */
export interface PermissionRegistry {
  categories: Record<string, PermissionRegistryEntry[]>;
}

/** A single grant row (role- or user-scoped). */
export interface PermissionGrant {
  key: string;
  granted: boolean;
  updatedAt: number;
  updatedBy: string;
}

/** A web role with its embedded permission grants. Mirrors the
 *  /admin/roles response rows. */
export interface Role {
  name: string;
  displayName: string;
  mcGroupName: string;
  sortOrder: number;
  color: string;
  permissions: PermissionGrant[];
}

/** One row in the admin user-management list. */
export interface AdminUser {
  uuid: string;
  name: string;
  lastDisplayName: string;
  groupName: string;
  tier: number;
  lastSeen: number;
  resolvedRole: string;
}

/* --- self-service access requests ---------------------------------- */

/** A self-service access request. Times are epoch millis. */
export interface PermissionRequest {
  id: number;
  requesterUuid: string;
  requesterName: string;
  permissionKey: string;
  justification: string | null;
  status: 'PENDING' | 'APPROVED' | 'DENIED' | 'EXPIRED';
  createdAt: number;
  decidedByUuid: string | null;
  decidedByName: string | null;
  decidedAt: number | null;
  decisionNote: string | null;
  expiresAt: number | null;
}

/** A catalogue entry on the order page: a registry permission plus whether the
 *  user already holds it (`held`) or already has a pending request (`pending`). */
export interface RequestablePermission {
  key: string;
  label: string;
  description: string;
  category: string;
  held: boolean;
  pending: boolean;
}
