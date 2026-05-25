import { Component, Input, OnChanges, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { PlayerLookup, Punishment, Report, UnbanAppeal } from '../../core/models';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

/** EU date format that matches the in-game pattern (yyyy-MM-dd HH:mm). */
const EU_DATE_FORMAT = 'yyyy-MM-dd HH:mm';

/** Unified row shape for the merged history view. */
type HistoryRow = {
  kind: 'BAN' | 'MUTE' | 'REPORT';
  id: number;
  reasonLabel: string;
  staffName: string;
  staffUuid: string | null;
  /** Resolved from PlayerLookup.displayNames on the fly. Null when the
   *  staff hasn't been seen ingame with our MONITOR listener yet. */
  staffDisplay: string | null;
  issuedAt: number;
  expiresAt: number | null;
  active: boolean;
  pardonReason: string | null;
  pardonByName: string | null;
  /** Same lookup, for the staff that pardoned the punishment. */
  pardonByDisplay: string | null;
  pardonedAt: number | null;
  modifiedAt: number | null;
  modifiedByName: string | null;
  modifiedByDisplay: string | null;
  reportStatus?: 'OPEN' | 'CLAIMED' | 'CLOSED';
};

@Component({
  selector: 'et-player-detail',
  standalone: true,
  imports: [CommonModule, DatePipe, MatCardModule, MatIconModule, MatProgressSpinnerModule, MatButtonModule, RouterLink, LegacyTextPipe],
  template: `
    <button mat-stroked-button routerLink="/players" class="mb-4">
      <mat-icon>arrow_back</mat-icon> Zur Suche
    </button>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>
    <div *ngIf="error()" class="text-red-400">{{ error() }}</div>

    <ng-container *ngIf="data() as d">
      <mat-card class="p-6 mb-4">
        <div class="flex gap-6 items-start">
          <img [src]="head(d.profile.uuid)" class="w-24 h-24 rounded" alt="head" />
          <div class="flex-1">
            <!-- Show the rank-coloured DisplayName from CloudNet-Chat when
                 we have one; fall back to the plain name otherwise. -->
            <h1 class="text-3xl font-bold" [innerHTML]="(d.profile.lastDisplayName || d.profile.name) | legacy"></h1>
            <div class="text-ink-300 text-sm font-mono">{{ d.profile.uuid }}</div>
            <div class="mt-3 grid grid-cols-2 gap-3 text-sm">
              <div><span class="text-ink-300">Rang:</span> <span class="text-eternal-300 ml-2">{{ d.profile.lastGroupName || '—' }}</span></div>
              <div><span class="text-ink-300">Tier:</span> <span class="ml-2">{{ d.profile.lastTier }}</span></div>
              <div><span class="text-ink-300">Erstmals:</span> <span class="ml-2 font-mono">{{ d.profile.firstSeen | date:fmt }}</span></div>
              <div><span class="text-ink-300">Zuletzt:</span> <span class="ml-2 font-mono">{{ d.profile.lastSeen | date:fmt }}</span></div>
            </div>
          </div>
        </div>

        <div *ngIf="d.activeBan || d.activeMute" class="mt-4 space-y-2">
          <div *ngIf="d.activeBan" class="p-3 bg-red-900/30 border border-red-700/40 rounded">
            <strong class="text-red-300">Aktiver Bann #{{ d.activeBan.id }}</strong>:
            {{ d.activeBan.reasonLabel }}
            <span *ngIf="d.activeBan.expiresAt; else perm">
              <span class="font-mono"> bis {{ d.activeBan.expiresAt | date:fmt }}</span>
            </span>
            <ng-template #perm><span class="font-medium"> · permanent</span></ng-template>
          </div>
          <div *ngIf="d.activeMute" class="p-3 bg-orange-900/30 border border-orange-700/40 rounded">
            <strong class="text-orange-300">Aktiver Mute #{{ d.activeMute.id }}</strong>:
            {{ d.activeMute.reasonLabel }}
          </div>
        </div>
      </mat-card>

      <!-- Appeals section: visible to staff inspecting a player. Shows
           every appeal filed by that player + the decision message. -->
      <mat-card class="p-6 mb-4" *ngIf="d.appeals?.length">
        <h2 class="text-xl font-semibold mb-4">Entbannungsanträge</h2>
        <div class="space-y-3">
          <div *ngFor="let a of d.appeals"
               class="rounded-lg border border-ink-700/40 bg-ink-800/30 p-4">
            <div class="flex items-center gap-3 mb-2">
              <span class="font-mono text-ink-300">#{{ a.id }}</span>
              <span [class]="appealBadge(a.status)">{{ a.status }}</span>
              <span class="text-ink-300 text-xs">→ Bann #{{ a.banId }}</span>
              <span class="flex-1"></span>
              <span class="font-mono text-xs text-ink-400">{{ a.createdAt | date:fmt }}</span>
            </div>
            <div class="text-sm italic text-ink-300/80 mb-2">„{{ a.text }}"</div>
            <div *ngIf="a.status !== 'PENDING'" class="text-xs text-ink-300">
              {{ a.status === 'APPROVED' ? 'Genehmigt' : a.status === 'SHORTENED' ? 'Verkürzt' : 'Abgelehnt' }}
              von {{ a.reviewerName }}
              am {{ a.reviewedAt | date:fmt }}
              <span *ngIf="a.status === 'SHORTENED' && a.shortenedToSeconds != null">
                · neue Rest-Dauer {{ humaniseSeconds(a.shortenedToSeconds) }}
              </span>
            </div>
            <div *ngIf="a.decisionMessage"
                 class="mt-2 p-2 bg-cyan-900/30 border border-cyan-700/40 rounded text-cyan-200 text-sm">
              <strong>Nachricht:</strong> {{ a.decisionMessage }}
            </div>
          </div>
        </div>
      </mat-card>

      <mat-card class="p-6">
        <h2 class="text-xl font-semibold mb-4">Historie</h2>
        <div *ngIf="rows(d).length === 0" class="text-ink-300 text-sm">Noch nichts.</div>

        <div class="space-y-3">
          <div *ngFor="let row of rows(d)"
               class="rounded-lg border border-ink-700/40 bg-ink-800/30 p-4">
            <!-- Card header: kind chip + #id + active state on left, issued-at on right -->
            <div class="flex flex-wrap items-center gap-3 mb-3">
              <span [class]="badgeClass(row.kind)">{{ row.kind }}</span>
              <span class="font-mono text-ink-300">#{{ row.id }}</span>
              <span *ngIf="row.active" class="text-xs px-2 py-0.5 rounded bg-emerald-900/40 text-emerald-300">aktiv</span>
              <span *ngIf="!row.active && row.kind !== 'REPORT'"
                    class="text-xs px-2 py-0.5 rounded bg-ink-700/40 text-ink-300">
                {{ row.pardonedAt ? 'aufgehoben' : 'abgelaufen' }}
              </span>
              <span *ngIf="row.kind === 'REPORT' && !row.active"
                    class="text-xs px-2 py-0.5 rounded bg-ink-700/40 text-ink-300">geschlossen</span>
              <span class="flex-1"></span>
              <span class="font-mono text-xs text-ink-400">{{ row.issuedAt | date:fmt }}</span>
            </div>

            <!-- Body: 2-column grid of labelled fields. Field names use the
                 Eternal-pink accent (matches in-game &d labels); reason
                 labels are cyan (&b) globally per the spec. -->
            <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-sm">
              <div><span class="text-eternal-300">Grund:</span> <span class="ml-2 text-cyan-300">{{ row.reasonLabel }}</span></div>
              <div>
                <span class="text-eternal-300">{{ row.kind === 'REPORT' ? 'Reporter' : 'Staff' }}:</span>
                <span class="ml-2" [innerHTML]="(row.staffDisplay || row.staffName) | legacy"></span>
              </div>

              <div *ngIf="row.kind !== 'REPORT'">
                <span class="text-eternal-300">Dauer:</span>
                <span class="ml-2">{{ formatDuration(row) }}</span>
              </div>
              <div *ngIf="row.kind !== 'REPORT'">
                <span class="text-eternal-300">Läuft ab:</span>
                <span class="ml-2 font-mono">{{ row.expiresAt ? (row.expiresAt | date:fmt) : 'permanent' }}</span>
              </div>

              <!-- Pardon details if applicable -->
              <div *ngIf="row.pardonedAt" class="md:col-span-2">
                <span class="text-eternal-300">Aufgehoben:</span>
                <span class="ml-2 font-mono">{{ row.pardonedAt | date:fmt }}</span>
                <span *ngIf="row.pardonByName" class="ml-2 text-ink-300">durch</span>
                <span *ngIf="row.pardonByName" class="ml-1"
                      [innerHTML]="(row.pardonByDisplay || row.pardonByName) | legacy"></span>
                <span *ngIf="row.pardonReason" class="ml-2 text-ink-300 italic">„{{ row.pardonReason }}"</span>
              </div>

              <!-- Modified info (only shown if /modify was used on this row) -->
              <div *ngIf="row.modifiedAt" class="md:col-span-2 pt-2 border-t border-ink-700/30 text-xs">
                <span class="text-amber-400">Nachträglich geändert</span>
                <span class="ml-2 font-mono text-ink-300">{{ row.modifiedAt | date:fmt }}</span>
                <span *ngIf="row.modifiedByName" class="ml-2 text-ink-300">durch</span>
                <span *ngIf="row.modifiedByName" class="ml-1"
                      [innerHTML]="(row.modifiedByDisplay || row.modifiedByName) | legacy"></span>
              </div>
            </div>
          </div>
        </div>
      </mat-card>
    </ng-container>
  `
})
export class PlayerDetailComponent implements OnChanges {
  @Input() name!: string;

  private readonly api = inject(ApiService);
  readonly data = signal<PlayerLookup | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  /** EU date pattern, shared across the template to avoid drift. */
  readonly fmt = EU_DATE_FORMAT;

  ngOnChanges() {
    if (!this.name) return;
    this.loading.set(true);
    this.api.playerLookup(this.name).subscribe({
      next: d => { this.data.set(d); this.loading.set(false); },
      error: e => { this.error.set(e.error?.error ?? e.message); this.loading.set(false); }
    });
  }

  head(uuid: string) {
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/96`;
  }

  /** Merges bans/mutes and reports into a single, time-sorted list. */
  rows(d: PlayerLookup): HistoryRow[] {
    // Backed by the server-provided displayNames map. Returns the cached
    // &-coded string or null when we have no profile for that UUID.
    const display = (uuid: string | null | undefined): string | null =>
      uuid ? (d.displayNames?.[uuid] ?? null) : null;

    const fromPunishment = (p: Punishment): HistoryRow => ({
      kind: p.type,
      id: p.id,
      reasonLabel: p.reasonLabel,
      staffName: p.issuerName,
      staffUuid: p.issuerUuid,
      staffDisplay: display(p.issuerUuid),
      issuedAt: p.issuedAt,
      expiresAt: p.expiresAt,
      active: p.active,
      pardonReason: p.pardonReason,
      pardonByName: p.pardonIssuerName,
      pardonByDisplay: display(p.pardonIssuerUuid),
      pardonedAt: p.pardonedAt,
      modifiedAt: p.modifiedAt,
      modifiedByName: p.modifiedByName,
      modifiedByDisplay: display(p.modifiedByUuid)
    });
    const fromReport = (r: Report): HistoryRow => ({
      kind: 'REPORT',
      id: r.id,
      reasonLabel: r.reasonLabel,
      staffName: r.reporterName,
      staffUuid: r.reporterUuid,
      staffDisplay: display(r.reporterUuid),
      issuedAt: r.createdAt,
      expiresAt: null,
      active: r.status !== 'CLOSED',
      pardonReason: null,
      pardonByName: null,
      pardonByDisplay: null,
      pardonedAt: null,
      modifiedAt: null,
      modifiedByName: null,
      modifiedByDisplay: null,
      reportStatus: r.status
    });
    // Oldest first → newest at the bottom of the list, mirroring the
    // in-game /history scrollback (and how a chat-style transcript reads).
    return [
      ...d.history.map(fromPunishment),
      ...d.reports.map(fromReport)
    ].sort((a, b) => a.issuedAt - b.issuedAt);
  }

  /** Difference between issued and expires, formatted as "Xd Yh Zm" or "permanent". */
  formatDuration(row: HistoryRow): string {
    if (row.expiresAt == null) return 'permanent';
    const sec = Math.max(0, Math.round((row.expiresAt - row.issuedAt) / 1000));
    return this.humanise(sec);
  }

  private humanise(seconds: number): string {
    if (seconds <= 0) return '0s';
    const d = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const parts: string[] = [];
    if (d) parts.push(`${d}d`);
    if (h) parts.push(`${h}h`);
    if (m) parts.push(`${m}m`);
    return parts.length ? parts.join(' ') : `${seconds}s`;
  }

  badgeClass(kind: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (kind === 'BAN') return `${base} bg-red-900/40 text-red-300`;
    if (kind === 'MUTE') return `${base} bg-orange-900/40 text-orange-300`;
    return `${base} bg-eternal-900/40 text-eternal-300`;
  }

  appealBadge(s: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (s === 'PENDING')   return `${base} bg-orange-900/40 text-orange-300`;
    if (s === 'APPROVED')  return `${base} bg-green-900/40 text-green-300`;
    if (s === 'SHORTENED') return `${base} bg-cyan-900/40 text-cyan-300`;
    return `${base} bg-red-900/40 text-red-300`;
  }

  /** Public for the appeals card so it doesn't need its own helper. */
  humaniseSeconds(s: number): string {
    if (s <= 0) return 'sofort';
    return this.humanise(s);
  }
}
