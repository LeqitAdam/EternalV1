import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AdminUser, PermissionRegistryEntry, Role } from '../../core/models';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

interface Override { key: string; granted: boolean; updatedAt: number; updatedBy: string; }

/**
 * Admin user management — house-style Material layout. Search every known
 * player (offline included), then per player:
 *  - see the CloudNet groups they ALREADY have (chips, removable),
 *  - add a group they don't have yet (dropdown shows only the missing
 *    ones, so nothing doubles up),
 *  - set per-user permission overrides on a three-state toggle group.
 *
 * Group + permission changes propagate to Minecraft live (the backend
 * queues a refresh the proxy fans out to the player's backend).
 */
@Component({
  selector: 'et-admin-users',
  standalone: true,
  imports: [
    CommonModule, DatePipe, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonToggleModule,
    MatProgressSpinnerModule, MatSnackBarModule, LegacyTextPipe
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Benutzer &amp; Ränge</h1>
    <p class="text-ink-300 mb-6">
      Jeden bekannten Spieler bearbeiten — auch offline. Änderungen werden
      live auf Minecraft übernommen.
    </p>

    <div class="grid lg:grid-cols-[340px_1fr] gap-5">
      <!-- ===== Search + list ===== -->
      <mat-card class="p-4 h-fit">
        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Spieler suchen</mat-label>
          <mat-icon matPrefix class="!mr-1 text-ink-400">search</mat-icon>
          <input matInput [(ngModel)]="query" (ngModelChange)="onSearch($event)"
                 placeholder="Name oder UUID" />
        </mat-form-field>

        <div *ngIf="searching()" class="flex justify-center py-4"><mat-spinner diameter="24" /></div>
        <div *ngIf="!searching() && users().length === 0" class="text-ink-300 text-sm py-2">Keine Treffer.</div>
        <div class="space-y-1 max-h-[60vh] overflow-y-auto -mx-1 px-1">
          <button *ngFor="let u of users()"
                  (click)="selectUser(u)"
                  class="w-full text-left px-2 py-2 rounded-lg transition flex items-center gap-2"
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
              <span *ngIf="u.resolvedRole" class="ml-2">· Web-Rolle <span class="text-eternal-300">{{ u.resolvedRole }}</span></span>
            </div>
          </div>
        </div>

        <!-- Rank manager -->
        <div class="rounded-lg border border-ink-700 bg-ink-800/40 p-5 mb-6">
          <h3 class="font-semibold mb-3 flex items-center gap-2">
            <mat-icon class="text-eternal-300 !text-xl">military_tech</mat-icon> CloudNet-Ränge
          </h3>

          <div *ngIf="loadingDetail()" class="flex justify-center py-3"><mat-spinner diameter="24" /></div>

          <ng-container *ngIf="!loadingDetail()">
            <!-- Groups the user already has -->
            <div class="mb-1 text-sm text-ink-300">Hat aktuell:</div>
            <div class="flex flex-wrap gap-2 mb-4">
              <span *ngFor="let g of userGroups()"
                    class="inline-flex items-center gap-1.5 pl-3 pr-1.5 py-1 rounded-full bg-ink-700 border border-ink-600 text-sm">
                <span class="font-mono">{{ g }}</span>
                <button (click)="removeGroup(u, g)" class="hover:text-red-300 transition" aria-label="Entfernen">
                  <mat-icon class="!text-base !w-4 !h-4 !leading-4 align-middle">close</mat-icon>
                </button>
              </span>
              <span *ngIf="userGroups().length === 0" class="text-sm text-ink-400 italic">keine Gruppen</span>
            </div>

            <!-- Add a group the user doesn't have yet -->
            <div class="flex flex-wrap gap-3 items-center">
              <mat-form-field appearance="outline" class="!mb-0" style="width: 16rem">
                <mat-label>Rang hinzufügen</mat-label>
                <mat-select [(ngModel)]="groupToAdd" [disabled]="addableGroups().length === 0">
                  <mat-option *ngFor="let g of addableGroups()" [value]="g.name">
                    {{ g.name }} <span class="text-ink-400 text-xs">(sortId {{ g.sortId }})</span>
                  </mat-option>
                </mat-select>
              </mat-form-field>
              <button mat-flat-button color="primary" (click)="addGroup(u)" [disabled]="!groupToAdd">
                <mat-icon>add</mat-icon> Hinzufügen
              </button>
              <button mat-stroked-button (click)="setPrimary(u)" [disabled]="!groupToAdd">
                <mat-icon>swap_horiz</mat-icon> Als einzigen Rang setzen
              </button>
            </div>
            <p *ngIf="cloudGroups().length === 0" class="text-xs text-amber-300/80 mt-2">
              Keine CloudNet-Gruppen synchronisiert. Läuft der Proxy mit CloudNet?
            </p>
          </ng-container>
        </div>

        <!-- Per-user permission overrides -->
        <h3 class="font-semibold mb-1">Persönliche Rechte-Overrides</h3>
        <p class="text-sm text-ink-300 mb-4">
          Überschreiben die Rolle. „Rolle" = zurück auf den Rollen-Standard.
        </p>

        <div *ngIf="loadingDetail()" class="flex justify-center py-6"><mat-spinner diameter="28" /></div>

        <div *ngIf="!loadingDetail()">
          <div *ngFor="let cat of categoryKeys()" class="mb-5">
            <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">{{ cat }}</div>
            <div class="space-y-1">
              <div *ngFor="let entry of registry()[cat]"
                   class="flex items-center gap-3 py-1.5 px-2 rounded-lg hover:bg-ink-800/50">
                <div class="flex-1 min-w-0">
                  <div class="text-sm">{{ entry.label }}</div>
                  <div class="text-xs text-ink-400 font-mono truncate">{{ entry.key }}</div>
                </div>
                <mat-button-toggle-group [value]="stateOf(entry.key)"
                                         (change)="onToggle(u, entry.key, $event.value)"
                                         class="et-perm-toggle" hideSingleSelectionIndicator>
                  <mat-button-toggle value="GRANT">An</mat-button-toggle>
                  <mat-button-toggle value="DEFAULT">Rolle</mat-button-toggle>
                  <mat-button-toggle value="DENY">Aus</mat-button-toggle>
                </mat-button-toggle-group>
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
  `,
  styles: [`
    /* Compact, on-brand three-state toggle. Active GRANT = green,
       DENY = red, DEFAULT = pink — never the Material accent blue. */
    ::ng-deep .et-perm-toggle .mat-button-toggle { background: #13131a; color: #9b9bb0; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-button { font-size: .75rem; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-appearance-standard
      .mat-button-toggle-label-content { line-height: 30px; padding: 0 12px; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="GRANT"] { background: #059669; color: #fff; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="DENY"]  { background: #dc2626; color: #fff; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="DEFAULT"] { background: #3a3a4b; color: #fff; }
  `]
})
export class AdminUsersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly search$ = new Subject<string>();

  query = '';
  groupToAdd = '';
  readonly searching = signal(false);
  readonly loadingDetail = signal(false);
  readonly users = signal<AdminUser[]>([]);
  readonly selected = signal<AdminUser | null>(null);
  readonly registry = signal<Record<string, PermissionRegistryEntry[]>>({});
  readonly overrides = signal<Override[]>([]);
  readonly userGroups = signal<string[]>([]);
  readonly cloudGroups = signal<Array<{ name: string; sortId: number }>>([]);

  readonly categoryKeys = computed(() => Object.keys(this.registry()));
  /** CloudNet groups the user does NOT have yet — drives the add dropdown
   *  so the same rank can't be added twice. */
  readonly addableGroups = computed(() => {
    const have = new Set(this.userGroups().map(g => g.toLowerCase()));
    return this.cloudGroups().filter(g => !have.has(g.name.toLowerCase()));
  });

  ngOnInit() {
    this.api.permissionRegistry().subscribe(reg => this.registry.set(reg.categories ?? {}));
    this.api.adminCloudGroups().subscribe(res => this.cloudGroups.set(res.groups ?? []));
    this.search$.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap(q => { this.searching.set(true); return this.api.adminListUsers(q); })
    ).subscribe({
      next: res => { this.users.set(res.users ?? []); this.searching.set(false); },
      error: () => this.searching.set(false)
    });
    this.search$.next('');
  }

  onSearch(q: string) { this.search$.next(q); }

  selectUser(u: AdminUser) {
    this.selected.set(u);
    this.groupToAdd = '';
    this.loadDetail(u.uuid);
  }

  private loadDetail(uuid: string) {
    this.loadingDetail.set(true);
    this.api.adminGetUser(uuid).subscribe({
      next: res => {
        this.overrides.set(res.overrides ?? []);
        this.userGroups.set(res.groups ?? []);
        this.loadingDetail.set(false);
      },
      error: () => { this.overrides.set([]); this.userGroups.set([]); this.loadingDetail.set(false); }
    });
  }

  /* --- permission overrides --- */

  stateOf(key: string): 'GRANT' | 'DENY' | 'DEFAULT' {
    const o = this.overrides().find(x => x.key === key);
    if (!o) return 'DEFAULT';
    return o.granted ? 'GRANT' : 'DENY';
  }

  onToggle(u: AdminUser, key: string, state: 'GRANT' | 'DENY' | 'DEFAULT') {
    if (state === 'DEFAULT') {
      this.api.clearUserPermission(u.uuid, key).subscribe({
        next: () => this.patchOverride(key, null),
        error: e => this.err(e.error?.error ?? 'Update fehlgeschlagen.')
      });
    } else {
      const granted = state === 'GRANT';
      this.api.setUserPermission(u.uuid, key, granted).subscribe({
        next: () => this.patchOverride(key, granted),
        error: e => this.err(e.error?.error ?? 'Update fehlgeschlagen.')
      });
    }
  }

  private patchOverride(key: string, granted: boolean | null) {
    const without = this.overrides().filter(o => o.key !== key);
    if (granted !== null) without.push({ key, granted, updatedAt: Date.now(), updatedBy: 'you' });
    this.overrides.set(without);
  }

  /* --- rank changes --- */

  addGroup(u: AdminUser) {
    const g = this.groupToAdd;
    if (!g) return;
    this.api.changeUserGroup(u.uuid, g, 'ADD').subscribe({
      next: () => {
        this.snack.open(`Rang „${g}" hinzugefügt — wird live übernommen.`, 'OK', { duration: 3000 });
        this.userGroups.set([...this.userGroups(), g]);
        this.groupToAdd = '';
      },
      error: e => this.err(e.error?.error ?? 'Fehlgeschlagen.')
    });
  }

  removeGroup(u: AdminUser, g: string) {
    this.api.changeUserGroup(u.uuid, g, 'REMOVE').subscribe({
      next: () => {
        this.snack.open(`Rang „${g}" entfernt.`, 'OK', { duration: 2500 });
        this.userGroups.set(this.userGroups().filter(x => x !== g));
      },
      error: e => this.err(e.error?.error ?? 'Fehlgeschlagen.')
    });
  }

  setPrimary(u: AdminUser) {
    const g = this.groupToAdd;
    if (!g) return;
    this.api.changeUserGroup(u.uuid, g, 'SET').subscribe({
      next: () => {
        this.snack.open(`„${g}" als einziger Rang gesetzt.`, 'OK', { duration: 3000 });
        this.userGroups.set([g]);
        const updated = { ...u, groupName: g };
        this.selected.set(updated);
        this.users.set(this.users().map(x => x.uuid === u.uuid ? updated : x));
        this.groupToAdd = '';
      },
      error: e => this.err(e.error?.error ?? 'Fehlgeschlagen.')
    });
  }

  head(uuid: string): string {
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/64`;
  }

  private err(msg: string) { this.snack.open(msg, 'OK', { duration: 4000 }); }
}
