import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from './api.config';
import {
  AdminUser, ChatLogPage, ChatSessionDay, LinkInit, LinkStatus, Me, PermissionRegistry,
  PlayerLookup, Punishment, Report, ReportChat, ReportPage, ReportStatusFilter, Role,
  StaffStat, UnbanAppeal
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_CONFIG).baseUrl;

  url(path: string) { return `${this.base}${path}`; }

  /* --- auth / link ---------------------------------------------------- */

  linkInit(): Observable<LinkInit> {
    return this.http.post<LinkInit>(this.url('/auth/link/init'), {});
  }
  linkStatus(token: string): Observable<LinkStatus> {
    return this.http.get<LinkStatus>(this.url(`/auth/link/status/${token}`));
  }
  logout(): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(this.url('/auth/logout'), {});
  }
  me(): Observable<Me> {
    return this.http.get<Me>(this.url('/me'));
  }

  /* --- data ----------------------------------------------------------- */

  bans(): Observable<{ bans: Punishment[]; displayNames: Record<string, string> }> {
    return this.http.get<{ bans: Punishment[]; displayNames: Record<string, string> }>(this.url('/bans'));
  }
  mutes(): Observable<Punishment[]> { return this.http.get<Punishment[]>(this.url('/mutes')); }
  reports(status: ReportStatusFilter = 'active', limit = 100, offset = 0): Observable<ReportPage> {
    const qs = `?status=${status}&limit=${limit}&offset=${offset}`;
    return this.http.get<ReportPage>(this.url('/reports' + qs));
  }
  stats(): Observable<StaffStat[]> { return this.http.get<StaffStat[]>(this.url('/stats')); }

  /** Admin-only: lists everyone whose web token is still valid. Returns
   *  ADMIN/MOD/PLAYER rows with the rank-coloured DisplayName cached at
   *  last in-game join. */
  adminActiveSessions(): Observable<Array<{
    userUuid: string;
    userName: string;
    role: 'ADMIN' | 'MOD' | 'PLAYER';
    createdAt: number;
    expiresAt: number;
    lastDisplayName?: string;
  }>> {
    return this.http.get<Array<{
      userUuid: string;
      userName: string;
      role: 'ADMIN' | 'MOD' | 'PLAYER';
      createdAt: number;
      expiresAt: number;
      lastDisplayName?: string;
    }>>(this.url('/admin/active-sessions'));
  }

  playerLookup(name: string): Observable<PlayerLookup> {
    return this.http.get<PlayerLookup>(this.url(`/players/${encodeURIComponent(name)}`));
  }

  pardon(id: number, reason: string): Observable<{ ok: boolean }> {
    return this.http.request<{ ok: boolean }>('DELETE', this.url(`/bans/${id}`), { body: { reason } });
  }

  /** Fetches the configured reason list incl. adminOnly flag so the bans
   *  table can grey out the pardon button for non-admins, plus the
   *  appeal-shortening templates the dialog needs. */
  reasons(): Observable<{
    reasons: Array<{ id: number; label: string; type: string; durationSeconds: number; adminOnly: boolean; requiredGroupId: number }>;
    appealShortenTemplates: Array<{ id: string; label: string; durationSeconds: number; message: string }>;
  }> {
    return this.http.get<{
      reasons: Array<{ id: number; label: string; type: string; durationSeconds: number; adminOnly: boolean; requiredGroupId: number }>;
      appealShortenTemplates: Array<{ id: string; label: string; durationSeconds: number; message: string }>;
    }>(this.url('/reasons'));
  }

  /** Verkürzt einen offenen Antrag um {@code remainingSeconds} und schickt
   *  dem Spieler die übergebene Nachricht. */
  shortenAppeal(id: number, body: { duration: string }): Observable<{ ok: boolean; newExpiresAt: number }> {
    return this.http.post<{ ok: boolean; newExpiresAt: number }>(this.url(`/appeals/${id}/shorten`), body);
  }

  /** Autocomplete: Spieler-Suche nach Name- oder UUID-Präfix. */
  searchPlayers(q: string): Observable<Array<{
    uuid: string; name: string; lastDisplayName: string; lastGroupName: string; lastSeen: number;
  }>> {
    return this.http.get<Array<{
      uuid: string; name: string; lastDisplayName: string; lastGroupName: string; lastSeen: number;
    }>>(this.url('/players/search'), { params: { q } });
  }

  claimReport(id: number): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(this.url(`/reports/${id}/claim`), {});
  }
  closeReport(id: number, resolution: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>(this.url(`/reports/${id}/close`), { resolution });
  }
  teleportToReport(id: number): Observable<{ ok: boolean; actionId?: number }> {
    return this.http.post<{ ok: boolean; actionId?: number }>(this.url(`/reports/${id}/teleport`), {});
  }
  banFromReport(id: number, body: { reasonLabel: string; durationSeconds: number; message: string; reasonId?: string }) {
    return this.http.post<{ ok: boolean; banId: number; reportId: number }>(
            this.url(`/reports/${id}/ban`), body);
  }
  muteFromReport(id: number, body: { reasonLabel: string; durationSeconds: number; message: string; reasonId?: string }) {
    return this.http.post<{ ok: boolean; muteId: number; reportId: number }>(
            this.url(`/reports/${id}/mute`), body);
  }

  /* --- appeals ------------------------------------------------------- */

  myActiveBan(): Observable<Punishment | null> {
    return this.http.get<Punishment | null>(this.url('/me/active-ban'));
  }
  myPunishments(): Observable<Punishment[]> {
    return this.http.get<Punishment[]>(this.url('/me/punishments'));
  }
  myAppeals(): Observable<UnbanAppeal[]> {
    return this.http.get<UnbanAppeal[]>(this.url('/me/appeals'));
  }
  fileAppeal(text: string) {
    return this.http.post<{ ok: boolean; appealId: number }>(this.url('/me/appeals'), { text });
  }
  filePublicAppeal(body: { playerName: string; banId: number; text: string }) {
    return this.http.post<{ ok: boolean; appealId: number }>(this.url('/public/appeals'), body);
  }
  appeals(status: 'PENDING' | 'APPROVED' | 'DENIED' = 'PENDING'): Observable<UnbanAppeal[]> {
    return this.http.get<UnbanAppeal[]>(this.url(`/appeals?status=${status}`));
  }
  approveAppeal(id: number, reason: string) {
    return this.http.post<{ ok: boolean }>(this.url(`/appeals/${id}/approve`), { reason });
  }
  denyAppeal(id: number, reason: string) {
    return this.http.post<{ ok: boolean }>(this.url(`/appeals/${id}/deny`), { reason });
  }

  /* --- admin: permission engine -------------------------------------- */

  permissionRegistry(): Observable<PermissionRegistry> {
    return this.http.get<PermissionRegistry>(this.url('/admin/permissions/registry'));
  }
  listRoles(): Observable<{ roles: Role[] }> {
    return this.http.get<{ roles: Role[] }>(this.url('/admin/roles'));
  }
  upsertRole(name: string, body: { displayName: string; mcGroupName: string; sortOrder: number; color: string }) {
    return this.http.put<{ ok: boolean; created: boolean }>(this.url(`/admin/roles/${encodeURIComponent(name)}`), body);
  }
  deleteRole(name: string) {
    return this.http.delete<{ ok: boolean }>(this.url(`/admin/roles/${encodeURIComponent(name)}`));
  }
  setRolePermission(name: string, key: string, granted: boolean) {
    return this.http.put<{ ok: boolean }>(
      this.url(`/admin/roles/${encodeURIComponent(name)}/permissions/${encodeURIComponent(key)}`), { granted });
  }
  clearRolePermission(name: string, key: string) {
    return this.http.delete<{ ok: boolean; cleared: boolean }>(
      this.url(`/admin/roles/${encodeURIComponent(name)}/permissions/${encodeURIComponent(key)}`));
  }

  /* --- admin: user management ---------------------------------------- */

  adminListUsers(q?: string): Observable<{ users: AdminUser[] }> {
    const qs = q && q.trim().length >= 2 ? `?q=${encodeURIComponent(q.trim())}` : '';
    return this.http.get<{ users: AdminUser[] }>(this.url('/admin/users' + qs));
  }
  adminGetUser(uuid: string): Observable<{ user: AdminUser; overrides: Array<{ key: string; granted: boolean; updatedAt: number; updatedBy: string }>; groups: string[] }> {
    return this.http.get<{ user: AdminUser; overrides: Array<{ key: string; granted: boolean; updatedAt: number; updatedBy: string }>; groups: string[] }>(
      this.url(`/admin/users/${uuid}`));
  }
  /** Synced CloudNet group catalogue (name + sortId + &-colour),
   *  lowest sortId (= highest rank) first. */
  adminCloudGroups(): Observable<{ groups: Array<{ name: string; sortId: number; color: string }> }> {
    return this.http.get<{ groups: Array<{ name: string; sortId: number; color: string }> }>(this.url('/admin/cloud-groups'));
  }
  setUserPermission(uuid: string, key: string, granted: boolean) {
    return this.http.put<{ ok: boolean }>(
      this.url(`/admin/users/${uuid}/permissions/${encodeURIComponent(key)}`), { granted });
  }
  clearUserPermission(uuid: string, key: string) {
    return this.http.delete<{ ok: boolean; cleared: boolean }>(
      this.url(`/admin/users/${uuid}/permissions/${encodeURIComponent(key)}`));
  }
  changeUserGroup(uuid: string, group: string, op: 'SET' | 'ADD' | 'REMOVE' = 'SET') {
    return this.http.put<{ ok: boolean; queued: boolean; op: string; group: string }>(
      this.url(`/admin/users/${uuid}/group`), { group, op });
  }

  /* --- chat-logs + social-spy ---------------------------------------- */

  /** Paged chat-log search. server/q blank => no filter; kinds is a CSV of
   *  CHAT,COMMAND,MSG; from/to are epoch millis. Gated server-side by
   *  eternal.web.chatlogs. */
  chatLogs(o: { server?: string; q?: string; kinds?: string; from?: number; to?: number; limit?: number; offset?: number } = {}): Observable<ChatLogPage> {
    return this.http.get<ChatLogPage>(this.url('/chat-logs'), { params: this.chatParams(o) });
  }
  /** Distinct server names present in the chat log — fills the server filter. */
  chatLogServers(): Observable<string[]> {
    return this.http.get<string[]>(this.url('/chat-logs/servers'));
  }
  /** Separate, more strictly gated store for sensitive commands
   *  (login/register/…). Gated server-side by eternal.web.chatlogs.sensitive
   *  — a 403 here just means the caller may not view it. */
  sensitiveChatLogs(o: { server?: string; q?: string; from?: number; to?: number; limit?: number; offset?: number } = {}): Observable<ChatLogPage> {
    return this.http.get<ChatLogPage>(this.url('/chat-logs/sensitive'), { params: this.chatParams(o) });
  }
  /** Player's recent chat/msg activity, clustered into sessions and grouped
   *  by day (newest day first). */
  playerChatSessions(name: string, days = 7): Observable<ChatSessionDay[]> {
    return this.http.get<ChatSessionDay[]>(
      this.url(`/players/${encodeURIComponent(name)}/chat-sessions`),
      { params: new HttpParams().set('days', days) });
  }
  /** Chat context around a report's reported message. */
  reportChat(id: number): Observable<ReportChat> {
    return this.http.get<ReportChat>(this.url(`/reports/${id}/chat`));
  }

  /** Builds HttpParams from the optional chat-log filter object, dropping
   *  blank/undefined entries so the backend's "no filter" semantics kick in. */
  private chatParams(o: { server?: string; q?: string; kinds?: string; from?: number; to?: number; limit?: number; offset?: number }): HttpParams {
    let p = new HttpParams();
    if (o.server && o.server.trim()) p = p.set('server', o.server.trim());
    if (o.q && o.q.trim()) p = p.set('q', o.q.trim());
    if (o.kinds && o.kinds.trim()) p = p.set('kinds', o.kinds.trim());
    if (o.from && o.from > 0) p = p.set('from', o.from);
    if (o.to && o.to > 0) p = p.set('to', o.to);
    if (o.limit != null) p = p.set('limit', o.limit);
    if (o.offset != null) p = p.set('offset', o.offset);
    return p;
  }
}
