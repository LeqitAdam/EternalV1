import { Component, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';
import { PermissionRequest } from '../../core/models';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

const DURATIONS = [
  { label: 'Permanent', seconds: 0 },
  { label: '1 Tag', seconds: 86400 },
  { label: '7 Tage', seconds: 604800 },
  { label: '30 Tage', seconds: 2592000 },
];

/**
 * Admin decision queue for the self-service access requests. Approve writes a
 * personal user-override grant for the requester (optionally expiring); deny
 * just records the decision. Server enforces eternal.web.admin.
 */
@Component({
  selector: 'et-permission-requests',
  standalone: true,
  imports: [
    CommonModule, DatePipe, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatButtonToggleModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressSpinnerModule, LegacyTextPipe
  ],
  template: `
    <div class="flex items-center gap-3 mb-2">
      <h1 class="text-3xl font-bold">Rechte-Anfragen</h1>
      <span class="flex-1"></span>
      <mat-button-toggle-group [value]="filter()" (change)="setFilter($event.value)">
        <mat-button-toggle value="pending">Offen</mat-button-toggle>
        <mat-button-toggle value="all">Alle</mat-button-toggle>
      </mat-button-toggle-group>
    </div>
    <p class="text-ink-300 mb-6">Freigeben schreibt ein persönliches Recht für den Anfrager — optional befristet.</p>

    <div *ngIf="error()" class="p-3 mb-4 bg-red-900/30 border border-red-700/40 rounded text-red-300 text-sm">
      {{ error() }}
    </div>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner diameter="32" /></div>

    <div *ngIf="!loading() && requests().length === 0" class="text-center py-12 text-ink-300">
      <mat-icon class="!text-5xl !w-12 !h-12 mb-2 text-ink-500">inbox</mat-icon>
      <div>Keine Anfragen.</div>
    </div>

    <div class="space-y-3">
      <mat-card *ngFor="let r of requests()" class="p-4">
        <div class="flex items-center gap-3 mb-2">
          <img [src]="head(r.requesterUuid)" class="w-8 h-8 rounded" alt="" />
          <div class="flex-1 min-w-0">
            <span class="font-medium" [innerHTML]="(displayNames()[r.requesterUuid] || r.requesterName) | legacy"></span>
            <span class="text-ink-400 text-sm"> möchte </span>
            <span class="font-mono text-eternal-300">{{ r.permissionKey }}</span>
          </div>
          <span [class]="statusClass(r.status)">{{ statusLabel(r.status) }}</span>
        </div>
        <div *ngIf="r.justification" class="text-sm text-ink-300 italic mb-2">„{{ r.justification }}"</div>
        <div class="text-xs text-ink-400 mb-3">{{ r.createdAt | date:fmt }}</div>

        <div *ngIf="r.status === 'PENDING'" class="flex flex-wrap items-center gap-2">
          <mat-form-field appearance="outline" class="!mb-0 w-36">
            <mat-label>Dauer</mat-label>
            <mat-select [(ngModel)]="duration[r.id]">
              <mat-option *ngFor="let d of durations" [value]="d.seconds">{{ d.label }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="!mb-0 flex-1 min-w-[180px]">
            <mat-label>Notiz (optional)</mat-label>
            <input matInput [(ngModel)]="note[r.id]" />
          </mat-form-field>
          <button mat-flat-button color="primary" (click)="approve(r)">
            <mat-icon>check</mat-icon> Freigeben
          </button>
          <button mat-stroked-button color="warn" (click)="deny(r)">
            <mat-icon>close</mat-icon> Ablehnen
          </button>
        </div>

        <div *ngIf="r.status !== 'PENDING'" class="text-xs text-ink-400">
          {{ r.decidedByName ? (statusLabel(r.status) + ' von ' + r.decidedByName) : statusLabel(r.status) }}
          <span *ngIf="r.decidedAt"> · {{ r.decidedAt | date:fmt }}</span>
          <span *ngIf="r.expiresAt"> · läuft ab {{ r.expiresAt | date:fmt }}</span>
          <span *ngIf="r.decisionNote"> · {{ r.decisionNote }}</span>
        </div>
      </mat-card>
    </div>
  `
})
export class PermissionRequestsComponent {
  private readonly api = inject(ApiService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly filter = signal<'pending' | 'all'>('pending');
  readonly requests = signal<PermissionRequest[]>([]);
  readonly displayNames = signal<Record<string, string>>({});

  readonly durations = DURATIONS;
  duration: Record<number, number> = {};
  note: Record<number, string> = {};
  readonly fmt = 'dd.MM.yy HH:mm';

  constructor() { this.load(); }

  setFilter(f: 'pending' | 'all') { this.filter.set(f); this.load(); }

  load() {
    this.loading.set(true);
    this.api.adminPermissionRequests(this.filter()).subscribe({
      next: r => {
        this.requests.set(r.requests ?? []);
        this.displayNames.set(r.displayNames ?? {});
        this.loading.set(false);
      },
      error: () => { this.error.set('Laden fehlgeschlagen.'); this.loading.set(false); }
    });
  }

  approve(r: PermissionRequest) {
    this.error.set(null);
    const seconds = this.duration[r.id] || 0;
    this.api.approvePermissionRequest(r.id, {
      durationSeconds: seconds > 0 ? seconds : undefined,
      note: this.note[r.id]?.trim() || undefined
    }).subscribe({ next: () => this.load(), error: e => this.error.set(e.error?.error ?? 'Freigabe fehlgeschlagen.') });
  }

  deny(r: PermissionRequest) {
    this.error.set(null);
    this.api.denyPermissionRequest(r.id, this.note[r.id]?.trim() || undefined).subscribe({
      next: () => this.load(), error: e => this.error.set(e.error?.error ?? 'Ablehnen fehlgeschlagen.')
    });
  }

  head(uuid: string) { return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/32`; }

  statusLabel(s: string) {
    return s === 'PENDING' ? 'Offen' : s === 'APPROVED' ? 'Freigegeben'
         : s === 'DENIED' ? 'Abgelehnt' : 'Abgelaufen';
  }
  statusClass(s: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (s === 'APPROVED') return `${base} bg-emerald-900/40 text-emerald-300`;
    if (s === 'DENIED') return `${base} bg-red-900/40 text-red-300`;
    if (s === 'EXPIRED') return `${base} bg-ink-700/50 text-ink-300`;
    return `${base} bg-amber-900/40 text-amber-300`;
  }
}
