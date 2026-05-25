import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

type Row = {
  userUuid: string;
  userName: string;
  role: 'ADMIN' | 'MOD' | 'PLAYER';
  createdAt: number;
  expiresAt: number;
  lastDisplayName?: string;
};

/**
 * Admin overview of everyone with a currently-valid dashboard session.
 * Split into "Staff" (ADMIN + MOD) and "Players" (PLAYER), each with the
 * rank-coloured DisplayName from the in-game profile cache, login time
 * and session expiry.
 */
@Component({
  selector: 'et-active-users',
  standalone: true,
  imports: [CommonModule, DatePipe, MatCardModule, MatIconModule, MatProgressSpinnerModule, LegacyTextPipe],
  template: `
    <h1 class="text-3xl font-bold mb-2">Aktive Dashboard-User</h1>
    <p class="text-ink-300 mb-6">Wer ist gerade im Dashboard eingeloggt.</p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <ng-container *ngIf="!loading()">
      <!-- Staff section: ADMIN + MOD merged, since they all have staff perms -->
      <mat-card class="p-6 mb-6">
        <h2 class="text-xl font-semibold mb-3 flex items-center gap-2">
          <mat-icon class="text-eternal-300">shield</mat-icon>
          Staff
          <span class="text-ink-300 text-sm font-normal">({{ staff().length }})</span>
        </h2>
        <div *ngIf="staff().length === 0" class="text-ink-300 text-sm">Niemand vom Team eingeloggt.</div>
        <div class="space-y-2">
          <div *ngFor="let r of staff()"
               class="flex items-center gap-3 py-2 border-b border-ink-700/30 last:border-b-0">
            <img [src]="head(r.userUuid)" class="w-8 h-8 rounded" alt="head" />
            <span class="font-medium" [innerHTML]="(r.lastDisplayName || r.userName) | legacy"></span>
            <span [class]="roleChipClass(r.role)">{{ r.role }}</span>
            <span class="flex-1"></span>
            <span class="text-ink-300 text-xs font-mono">seit {{ r.createdAt | date:fmt }}</span>
          </div>
        </div>
      </mat-card>

      <mat-card class="p-6">
        <h2 class="text-xl font-semibold mb-3 flex items-center gap-2">
          <mat-icon class="text-cyan-300">person</mat-icon>
          Spieler
          <span class="text-ink-300 text-sm font-normal">({{ players().length }})</span>
        </h2>
        <div *ngIf="players().length === 0" class="text-ink-300 text-sm">Keine Spieler-Logins.</div>
        <div class="space-y-2">
          <div *ngFor="let r of players()"
               class="flex items-center gap-3 py-2 border-b border-ink-700/30 last:border-b-0">
            <img [src]="head(r.userUuid)" class="w-8 h-8 rounded" alt="head" />
            <span class="font-medium" [innerHTML]="(r.lastDisplayName || r.userName) | legacy"></span>
            <span class="flex-1"></span>
            <span class="text-ink-300 text-xs font-mono">seit {{ r.createdAt | date:fmt }}</span>
          </div>
        </div>
      </mat-card>
    </ng-container>
  `
})
export class ActiveUsersComponent {
  private readonly api = inject(ApiService);
  readonly rows = signal<Row[]>([]);
  readonly loading = signal(true);

  /** Same EU short pattern as the rest of the dashboard. */
  readonly fmt = 'yyyy-MM-dd HH:mm';

  readonly staff   = computed(() => this.rows().filter(r => r.role !== 'PLAYER'));
  readonly players = computed(() => this.rows().filter(r => r.role === 'PLAYER'));

  constructor() {
    this.api.adminActiveSessions().subscribe({
      next: list => { this.rows.set(list); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  head(uuid: string) {
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/32`;
  }

  roleChipClass(role: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (role === 'ADMIN') return `${base} bg-red-900/40 text-red-300`;
    if (role === 'MOD')   return `${base} bg-eternal-900/40 text-eternal-300`;
    return `${base} bg-ink-700/40 text-ink-300`;
  }
}
