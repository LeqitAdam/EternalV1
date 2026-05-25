import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_CONFIG } from './api.config';
import {
  LinkInit, LinkStatus, Me, PlayerLookup, Punishment, Report, ReportPage,
  ReportStatusFilter, StaffStat, UnbanAppeal
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

  bans(): Observable<Punishment[]> { return this.http.get<Punishment[]>(this.url('/bans')); }
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
   *  table can grey out the pardon button for non-admins. */
  reasons(): Observable<Array<{ id: number; label: string; type: string; durationSeconds: number; adminOnly: boolean; requiredGroupId: number }>> {
    return this.http.get<Array<{ id: number; label: string; type: string; durationSeconds: number; adminOnly: boolean; requiredGroupId: number }>>(this.url('/reasons'));
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
}
