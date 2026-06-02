import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AdminUser, PermissionRegistryEntry, Role } from '../../core/models';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

/** A user's permission override as returned by /admin/users/{uuid}. */
interface Override { key: string; granted: boolean; updatedAt: number; updatedBy: string; }

/**
 * Admin user management. Search ALL known players (offline included,
 * via the eternal_profiles table) and for the selected one:
 *
 *  - see their cached CloudNet group + resolved web role,
 *  - change their CloudNet rank (queues a CLOUDNET_GROUP action the
 *    Bungee poller applies — works for offline players too),
 *  - set per-user permission overrides that beat the role grants.
 *
 * The permission matrix mirrors the role editor's three-state control,
 * but here DEFAULT means "fall through to the role + hardcoded default".
 */
@Component({
  selector: 'et-admin-users',
  standalone: true,
  imports: [
    CommonModule, DatePipe, FormsModule, MatCardModule, MatIconModule,
    MatButtonModule, MatProgressSpinnerModule, MatSnackBarModule, LegacyTextPipe
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Benutzer &amp; Ränge</h1>
    <p class="text-ink-300 mb-6">
      Jeden bekannten Spieler bearbeiten — auch offline. Rang ändern oder
      einzelne Rechte gezielt zuteilen / entziehen.
    </p>

    <div class="grid lg:grid-cols-[340px_1fr] gap-5">
      <!-- ===== Search + list ===== -->
      <mat-card class="p-4 h-fit">
        <div class="relative mb-3">
          <mat-icon class="absolute left-2 top-2 text-ink-400 !text-lg">search</mat-icon>
          <input [(ngModel)]="query" (ngModelChange)="onSearch($event)"
                 class="w-full bg-ink-800 border border-ink-600 rounded pl-9 pr-3 py-2 text-sm"
                 placeholder="Name oder UUID … (leer = zuletzt gesehen)" />
        </div>
        <div *ngIf="searching()" class="flex justify-center py-4"><mat-spinner diameter="24" /></div>
        <div *ngIf="!searching() && users().length === 0" class="text-ink-300 text-sm py-2">Keine Treffer.</div>
        <div class="space-y-1 max-h-[60vh] overflow-y-auto">
          <button *ngFor="let u of users()"
                  (click)="selectUser(u)"
                  class="w-full text-left px-2 py-2 rounded transition flex items-center gap-2"
                  [class.bg-ink-700]="selected()?.uuid === u.uuid"
                  [class.hover:bg-ink-700]="selected()?.uuid !== u.uuid">
            <img [src]="head(u.uuid)" class="w-7 h-7 rounded" alt="" />
            <div class="flex-1 min-w-0">
              <div class="text-sm truncate" [innerHTML]="(u.lastDisplayName || u.name) | legacy"></div>
              <div class="text-xs text-ink-400 truncate">
                {{ u.groupName || 'keine Gruppe' }}
                <span *ngIf="u.resolvedRole" class="text-eternal-300">· {{ u.resolvedRole }}</span>
              </div>
            </div>
          </button>
        </div>
      </mat-card>

      <!-- ===== Detail pane ===== -->
      <mat-card class="p-6" *ngIf="selected() as u; else noSel">
        <div class="flex items-center gap-4 mb-6">
          <img [src]="head(u.uuid)" class="w-16 h-16 rounded" alt="" />
          <div class="flex-1 min-w-0">
            <h2 class="text-2xl font-bold" [innerHTML]="(u.lastDisplayName || u.name) | legacy"></h2>
            <div class="text-xs text-ink-400 font-mono">{{ u.uuid }}</div>
            <div class="text-sm text-ink-300 mt-1">
              Zuletzt gesehen: {{ u.lastSeen ? (u.lastSeen | date:'yyyy-MM-dd HH:mm') : '—' }}
            </div>
          </div>
        </div>

        <!-- Rank changer -->
        <div class="bg-ink-800/50 border border-ink-700 rounded-lg p-4 mb-6">
          <h3 class="font-semibold mb-1 flex items-center gap-2">
            <mat-icon class="text-eternal-300 !text-lg">military_tech</mat-icon> Rang
          </h3>
          <p class="text-sm text-ink-300 mb-3">
            Aktuelle CloudNet-Gruppe: <span class="font-mono text-cyan-300">{{ u.groupName || '—' }}</span>
            <span *ngIf="u.resolvedRole" class="text-ink-400"> → Web-Rolle {{ u.resolvedRole }}</span>
          </p>
          <div class="flex flex-wrap gap-2 items-end">
            <label class="block">
              <span class="text-xs text-ink-300">Neue Gruppe</span>
              <input [(ngModel)]="newGroup" list="role-groups"
                     class="mt-1 bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm font-mono w-56"
                     placeholder="CloudNet-Gruppenname" />
              <datalist id="role-groups">
                <option *ngFor="let r of roles()" [value]="r.mcGroupName">{{ r.displayName }}</option>
              </datalist>
            </label>
            <button mat-flat-button color="primary" (click)="applyGroup(u, 'SET')" [disabled]="!newGroup.trim()">
              <mat-icon>swap_horiz</mat-icon> Rang setzen
            </button>
            <button mat-stroked-button (click)="applyGroup(u, 'ADD')" [disabled]="!newGroup.trim()">
              <mat-icon>add</mat-icon> Hinzufügen
            </button>
            <button mat-stroked-button color="warn" (click)="applyGroup(u, 'REMOVE')" [disabled]="!newGroup.trim()">
              <mat-icon>remove</mat-icon> Entfernen
            </button>
          </div>
          <p class="text-xs text-ink-400 mt-2">
            Änderung wird über CloudNet angewendet (auch wenn der Spieler offline ist) und
            greift ingame nach kurzer Verzögerung.
          </p>
        </div>

        <!-- Per-user permission overrides -->
        <h3 class="font-semibold mb-1">Persönliche Rechte-Overrides</h3>
        <p class="text-sm text-ink-300 mb-4">
          Überschreiben die Rolle. <span class="text-emerald-300">An</span> /
          <span class="text-red-300">Aus</span> setzen oder auf
          <span class="text-ink-400">Rolle</span> zurückfallen lassen.
        </p>

        <div *ngIf="loadingPerms()" class="flex justify-center py-6"><mat-spinner diameter="28" /></div>

        <div *ngIf="!loadingPerms()">
          <div *ngFor="let cat of categoryKeys()" class="mb-5">
            <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">{{ cat }}</div>
            <div class="space-y-1.5">
              <div *ngFor="let entry of registry()[cat]"
                   class="flex items-center gap-3 py-1.5 px-2 rounded hover:bg-ink-800/50">
                <div class="flex-1 min-w-0">
                  <div class="text-sm">{{ entry.label }}</div>
                  <div class="text-xs text-ink-400 font-mono truncate">{{ entry.key }}</div>
                </div>
                <div class="flex rounded-md overflow-hidden border border-ink-600 shrink-0">
                  <button (click)="setPerm(u, entry.key, true)"
                          class="px-2.5 py-1 text-xs transition"
                          [class.bg-emerald-600]="stateOf(entry.key) === 'GRANT'"
                          [class.text-white]="stateOf(entry.key) === 'GRANT'"
                          [class.text-ink-300]="stateOf(entry.key) !== 'GRANT'"
                          [class.hover:bg-ink-700]="stateOf(entry.key) !== 'GRANT'">An</button>
                  <button (click)="clearPerm(u, entry.key)"
                          class="px-2.5 py-1 text-xs transition border-x border-ink-600"
                          [class.bg-ink-500]="stateOf(entry.key) === 'DEFAULT'"
                          [class.text-white]="stateOf(entry.key) === 'DEFAULT'"
                          [class.text-ink-300]="stateOf(entry.key) !== 'DEFAULT'"
                          [class.hover:bg-ink-700]="stateOf(entry.key) !== 'DEFAULT'">Rolle</button>
                  <button (click)="setPerm(u, entry.key, false)"
                          class="px-2.5 py-1 text-xs transition"
                          [class.bg-red-600]="stateOf(entry.key) === 'DENY'"
                          [class.text-white]="stateOf(entry.key) === 'DENY'"
                          [class.text-ink-300]="stateOf(entry.key) !== 'DENY'"
                          [class.hover:bg-ink-700]="stateOf(entry.key) !== 'DENY'">Aus</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </mat-card>

      <ng-template #noSel>
        <mat-card class="p-12 text-center text-ink-300">
          <mat-icon class="!text-5xl !w-12 !h-12 mb-2 text-ink-500">person_search</mat-icon>
          <div>Suche links nach einem Spieler.</div>
        </mat-card>
      </ng-template>
    </div>
  `
})
export class AdminUsersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly search$ = new Subject<string>();

  query = '';
  newGroup = '';
  readonly searching = signal(false);
  readonly users = signal<AdminUser[]>([]);
  readonly selected = signal<AdminUser | null>(null);
  readonly roles = signal<Role[]>([]);
  readonly registry = signal<Record<string, PermissionRegistryEntry[]>>({});
  readonly overrides = signal<Override[]>([]);
  readonly loadingPerms = signal(false);

  readonly categoryKeys = computed(() => Object.keys(this.registry()));

  ngOnInit() {
    // Registry + roles for the matrix + rank datalist.
    this.api.permissionRegistry().subscribe(reg => this.registry.set(reg.categories ?? {}));
    this.api.listRoles().subscribe(res => this.roles.set(res.roles ?? []));
    // Debounced search pipe.
    this.search$.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(q => { this.searching.set(true); return this.api.adminListUsers(q); })
    ).subscribe({
      next: res => { this.users.set(res.users ?? []); this.searching.set(false); },
      error: () => { this.searching.set(false); }
    });
    // Initial load: recent players.
    this.search$.next('');
  }

  onSearch(q: string) {
    this.search$.next(q);
  }

  selectUser(u: AdminUser) {
    this.selected.set(u);
    this.newGroup = '';
    this.loadOverrides(u.uuid);
  }

  private loadOverrides(uuid: string) {
    this.loadingPerms.set(true);
    this.api.adminGetUser(uuid).subscribe({
      next: res => { this.overrides.set(res.overrides ?? []); this.loadingPerms.set(false); },
      error: () => { this.overrides.set([]); this.loadingPerms.set(false); }
    });
  }

  stateOf(key: string): 'GRANT' | 'DENY' | 'DEFAULT' {
    const o = this.overrides().find(x => x.key === key);
    if (!o) return 'DEFAULT';
    return o.granted ? 'GRANT' : 'DENY';
  }

  setPerm(u: AdminUser, key: string, granted: boolean) {
    this.api.setUserPermission(u.uuid, key, granted).subscribe({
      next: () => this.patchOverride(key, granted),
      error: e => this.err(e.error?.error ?? 'Update fehlgeschlagen.')
    });
  }

  clearPerm(u: AdminUser, key: string) {
    this.api.clearUserPermission(u.uuid, key).subscribe({
      next: () => this.patchOverride(key, null),
      error: e => this.err(e.error?.error ?? 'Update fehlgeschlagen.')
    });
  }

  private patchOverride(key: string, granted: boolean | null) {
    const without = this.overrides().filter(o => o.key !== key);
    if (granted !== null) without.push({ key, granted, updatedAt: Date.now(), updatedBy: 'you' });
    this.overrides.set(without);
  }

  applyGroup(u: AdminUser, op: 'SET' | 'ADD' | 'REMOVE') {
    const group = this.newGroup.trim();
    if (!group) return;
    this.api.changeUserGroup(u.uuid, group, op).subscribe({
      next: () => {
        const verb = op === 'SET' ? 'gesetzt' : op === 'ADD' ? 'hinzugefügt' : 'entfernt';
        this.snack.open(`Rang-Änderung (${group}) ${verb} — wird über CloudNet angewendet.`, 'OK', { duration: 3500 });
        // Optimistically reflect SET in the list/detail; ADD/REMOVE we
        // leave until the next search refresh since multi-group state
        // isn't tracked client-side.
        if (op === 'SET') {
          const updated = { ...u, groupName: group };
          this.selected.set(updated);
          this.users.set(this.users().map(x => x.uuid === u.uuid ? updated : x));
        }
        this.newGroup = '';
      },
      error: e => this.err(e.error?.error ?? 'Rang-Änderung fehlgeschlagen.')
    });
  }

  head(uuid: string): string {
    // Same source the rest of the dashboard uses (mc-heads.net), so the
    // browser cache is shared and avatars look identical app-wide.
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/64`;
  }

  private err(msg: string) {
    this.snack.open(msg, 'OK', { duration: 4000 });
  }
}
