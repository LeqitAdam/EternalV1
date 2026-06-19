import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';
import { PermissionRequest, RequestablePermission } from '../../core/models';

/**
 * Self-service access-request page (GIAM-style). A team member browses the
 * permission catalogue and "orders" individual keys with an optional
 * justification; an admin approves/denies elsewhere. Already-held keys and
 * pending ones are marked so the user doesn't re-request them.
 */
@Component({
  selector: 'et-access-requests',
  standalone: true,
  imports: [
    CommonModule, DatePipe, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatProgressSpinnerModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Rechte bestellen</h1>
    <p class="text-ink-300 mb-6">
      Frag dir die Rechte an, die du brauchst — ein Admin gibt sie frei.
    </p>

    <div *ngIf="error()" class="p-3 mb-4 bg-red-900/30 border border-red-700/40 rounded text-red-300 text-sm">
      {{ error() }}
    </div>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner diameter="32" /></div>

    <div *ngIf="!loading()" class="grid lg:grid-cols-[1fr_360px] gap-5">
      <!-- ===== Catalogue ===== -->
      <div class="space-y-5">
        <mat-card *ngFor="let cat of categories()" class="p-5">
          <h2 class="text-lg font-semibold mb-3 capitalize">{{ cat.name }}</h2>
          <div class="space-y-2">
            <div *ngFor="let e of cat.entries"
                 class="rounded-lg border border-ink-700/40 px-3 py-2">
              <div class="flex items-center gap-3">
                <div class="flex-1 min-w-0">
                  <div class="font-medium text-ink-100">{{ e.label }}
                    <span class="font-mono text-xs text-ink-400">{{ e.key }}</span>
                  </div>
                  <div class="text-sm text-ink-300">{{ e.description }}</div>
                  <div *ngIf="e.requires?.length" class="text-[11px] mt-1"
                       [class.text-amber-300]="missingRequires(e).length === 0"
                       [class.text-red-300]="missingRequires(e).length > 0">
                    ⚠ Wirkt nur mit: {{ labelsFor(e.requires!) }}
                    <span *ngIf="missingRequires(e).length > 0"> — fehlt dir noch: {{ labelsFor(missingRequires(e)) }}</span>
                  </div>
                  <div *ngIf="e.relatedTo?.length" class="text-[11px] text-ink-400 mt-0.5">
                    Empfohlen dazu: {{ labelsFor(e.relatedTo!) }}
                  </div>
                </div>
                <span *ngIf="e.held" class="text-xs px-2 py-0.5 rounded bg-emerald-900/40 text-emerald-300">Hast du</span>
                <span *ngIf="!e.held && e.pending" class="text-xs px-2 py-0.5 rounded bg-amber-900/40 text-amber-300">Angefragt</span>
                <button *ngIf="!e.held && !e.pending && requestingKey() !== e.key"
                        mat-stroked-button (click)="startRequest(e.key)">
                  <mat-icon>add</mat-icon> Bestellen
                </button>
              </div>
              <!-- inline justification -->
              <div *ngIf="requestingKey() === e.key" class="mt-2 flex items-start gap-2">
                <mat-form-field appearance="outline" class="flex-1 !mb-0">
                  <mat-label>Begründung (optional)</mat-label>
                  <textarea matInput rows="2" [(ngModel)]="justification"
                            placeholder="Wofür brauchst du das Recht?"></textarea>
                </mat-form-field>
                <button mat-flat-button color="primary" (click)="submit(e.key)">Absenden</button>
                <button mat-button (click)="cancelRequest()">Abbrechen</button>
              </div>
            </div>
          </div>
        </mat-card>
      </div>

      <!-- ===== My requests ===== -->
      <mat-card class="p-5 h-fit">
        <h2 class="text-lg font-semibold mb-3">Meine Anfragen</h2>
        <div *ngIf="requests().length === 0" class="text-ink-300 text-sm">Noch keine Anfragen.</div>
        <div class="space-y-2">
          <div *ngFor="let r of requests()" class="rounded-lg border border-ink-700/40 px-3 py-2">
            <div class="flex items-center gap-2">
              <span class="font-mono text-sm text-ink-100 flex-1 truncate">{{ r.permissionKey }}</span>
              <span [class]="statusClass(r.status)">{{ statusLabel(r.status) }}</span>
            </div>
            <div class="text-xs text-ink-400 mt-1">{{ r.createdAt | date:fmt }}</div>
            <div *ngIf="r.justification" class="text-xs text-ink-300 mt-1 italic">„{{ r.justification }}"</div>
            <div *ngIf="r.decisionNote" class="text-xs text-ink-300 mt-1">Notiz: {{ r.decisionNote }}</div>
            <div *ngIf="r.status === 'APPROVED' && r.expiresAt" class="text-xs text-amber-300 mt-1">
              läuft ab: {{ r.expiresAt | date:fmt }}
            </div>
          </div>
        </div>
      </mat-card>
    </div>
  `
})
export class AccessRequestsComponent {
  private readonly api = inject(ApiService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly catalogue = signal<RequestablePermission[]>([]);
  readonly requests = signal<PermissionRequest[]>([]);
  readonly requestingKey = signal<string | null>(null);
  justification = '';
  readonly fmt = 'dd.MM.yy HH:mm';

  readonly categories = computed(() => {
    const map = new Map<string, RequestablePermission[]>();
    for (const e of this.catalogue()) {
      (map.get(e.category) ?? map.set(e.category, []).get(e.category)!).push(e);
    }
    return Array.from(map.entries()).map(([name, entries]) => ({ name, entries }));
  });

  /** Flat key → label map across the whole catalogue, for rendering dependency
   *  hints with human labels instead of raw keys. */
  readonly labelMap = computed(() => {
    const map: Record<string, string> = {};
    for (const e of this.catalogue()) map[e.key] = e.label;
    return map;
  });
  /** Keys the requester already holds — used to flag missing dependencies. */
  readonly heldKeys = computed(() => new Set(this.catalogue().filter(e => e.held).map(e => e.key)));

  /** Join the human labels for a list of permission keys (fallback: raw key). */
  labelsFor(keys: string[]): string {
    const map = this.labelMap();
    return keys.map(k => map[k] ?? k).join(', ');
  }

  /** Of a permission's `requires`, the ones the requester does NOT yet hold —
   *  so the order page can nudge them to order those too. */
  missingRequires(e: RequestablePermission): string[] {
    if (!e.requires?.length) return [];
    const held = this.heldKeys();
    return e.requires.filter(k => !held.has(k));
  }

  constructor() { this.load(); }

  load() {
    this.loading.set(true);
    this.api.myPermissionRequests().subscribe({
      next: r => { this.catalogue.set(r.catalogue ?? []); this.requests.set(r.requests ?? []); this.loading.set(false); },
      error: () => { this.error.set('Laden fehlgeschlagen.'); this.loading.set(false); }
    });
  }

  startRequest(key: string) { this.requestingKey.set(key); this.justification = ''; }
  cancelRequest() { this.requestingKey.set(null); this.justification = ''; }

  submit(key: string) {
    this.error.set(null);
    this.api.createPermissionRequest(key, this.justification.trim() || undefined).subscribe({
      next: () => { this.requestingKey.set(null); this.justification = ''; this.load(); },
      error: e => this.error.set(e.error?.error ?? 'Anfrage fehlgeschlagen.')
    });
  }

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
