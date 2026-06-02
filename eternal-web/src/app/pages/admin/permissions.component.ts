import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
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
import { ApiService } from '../../core/api.service';
import { PermissionRegistryEntry, Role } from '../../core/models';

/**
 * Admin role + permission editor — house-style Material layout.
 *
 * Left: role list + create. Right: the selected role's metadata
 * (display name, CloudNet group binding — picked from the synced group
 * list, sort order, colour) and a category-grouped permission matrix.
 * Each key is a three-state Material toggle group: An (grant) / Std
 * (clear → hardcoded default) / Aus (deny). Changes propagate to
 * Minecraft live for online players of the bound group.
 */
@Component({
  selector: 'et-admin-permissions',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonToggleModule,
    MatProgressSpinnerModule, MatSnackBarModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Rollen &amp; Rechte</h1>
    <p class="text-ink-300 mb-6">
      <ng-container *ngIf="cloudActive()">
        Die Rollen sind die CloudNet-Gruppen — identisch zu ingame. Rechte
        gelten für jeden Spieler der Gruppe und werden live übernommen.
      </ng-container>
      <ng-container *ngIf="!cloudActive()">
        Kein CloudNet erkannt — du kannst eigene Rollen anlegen und sie
        einer Gruppe zuordnen.
      </ng-container>
    </p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <div *ngIf="!loading()" class="grid lg:grid-cols-[280px_1fr] gap-5">
      <!-- ===== Role list ===== -->
      <mat-card class="p-4 h-fit">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-semibold">{{ cloudActive() ? 'Ränge' : 'Rollen' }}</h2>
          <button *ngIf="!cloudActive()" mat-icon-button (click)="startNewRole()" aria-label="Neue Rolle">
            <mat-icon class="text-eternal-300">add</mat-icon>
          </button>
        </div>
        <div *ngIf="roles().length === 0" class="text-ink-300 text-sm py-2">
          {{ cloudActive() ? 'Noch keine Gruppen synchronisiert.' : 'Noch keine Rollen — lege eine an.' }}
        </div>
        <div class="space-y-1">
          <button *ngFor="let r of roles()"
                  (click)="selectRole(r)"
                  class="w-full text-left px-3 py-2 rounded-lg transition flex items-center gap-2 text-ink-100 hover:bg-ink-700"
                  [class.bg-ink-700]="selected()?.name === r.name">
            <span class="w-2.5 h-2.5 rounded-full shrink-0" [style.background]="hexFor(r.color)"></span>
            <span class="flex-1 truncate" [style.color]="hexFor(r.color)">{{ r.displayName }}</span>
            <span class="text-xs text-ink-400 font-mono">#{{ r.sortOrder }}</span>
          </button>
        </div>
      </mat-card>

      <!-- ===== Editor pane ===== -->
      <mat-card class="p-6" *ngIf="selected() as role; else noSelection">
        <div class="flex items-start justify-between mb-5">
          <h2 class="text-xl font-semibold flex items-center gap-2">
            <span class="w-3 h-3 rounded-full" [style.background]="hexFor(form.color)"></span>
            <span [style.color]="hexFor(form.color)">{{ isNew() ? 'Neue Rolle' : role.displayName }}</span>
          </h2>
          <button *ngIf="!cloudActive() && !isNew()" mat-stroked-button color="warn" (click)="removeRole(role)">
            <mat-icon>delete</mat-icon> Löschen
          </button>
        </div>

        <!-- CloudNet mode: read-only metadata summary. -->
        <div *ngIf="cloudActive()" class="rounded-lg border border-ink-700 bg-ink-800/40 p-4 mb-6 text-sm">
          <div class="grid sm:grid-cols-3 gap-2 text-ink-300">
            <div>CloudNet-Gruppe: <span class="font-mono text-cyan-300">{{ role.mcGroupName }}</span></div>
            <div>SortID: <span class="text-ink-100">{{ role.sortOrder }}</span> <span class="text-ink-500 text-xs">(kleiner = höher)</span></div>
            <div>Farbe: <span class="font-mono" [style.color]="hexFor(role.color)">{{ role.color }}</span></div>
          </div>
          <p class="text-xs text-ink-400 mt-2">
            Name, Gruppe, Sortierung und Farbe kommen direkt aus CloudNet und
            sind hier nicht editierbar. Nur die Rechte unten passt du an.
          </p>
        </div>

        <!-- Manual mode (no CloudNet): editable metadata. -->
        <ng-container *ngIf="!cloudActive()">
          <div class="grid sm:grid-cols-2 gap-x-4">
            <mat-form-field appearance="outline">
              <mat-label>Interner Name (eindeutig)</mat-label>
              <input matInput [(ngModel)]="form.name" [disabled]="!isNew()" placeholder="z.B. moderator" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Anzeigename</mat-label>
              <input matInput [(ngModel)]="form.displayName" placeholder="z.B. Moderator" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Gruppe</mat-label>
              <input matInput [(ngModel)]="form.mcGroupName" placeholder="exakter Gruppenname" />
            </mat-form-field>
            <div class="grid grid-cols-2 gap-x-3">
              <mat-form-field appearance="outline">
                <mat-label>Sortierung</mat-label>
                <input matInput type="number" [(ngModel)]="form.sortOrder" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Farbe (&amp;-Code)</mat-label>
                <input matInput [(ngModel)]="form.color" placeholder="&amp;a" />
              </mat-form-field>
            </div>
          </div>
          <div class="mb-6 -mt-1">
            <button mat-flat-button color="primary" (click)="saveRole()" [disabled]="!formValid()">
              <mat-icon>save</mat-icon> {{ isNew() ? 'Rolle anlegen' : 'Metadaten speichern' }}
            </button>
          </div>
        </ng-container>

        <!-- Permission matrix (existing roles only) -->
        <ng-container *ngIf="!isNew()">
          <h3 class="font-semibold mb-1">Berechtigungen</h3>
          <p class="text-sm text-ink-300 mb-4">
            <span class="text-emerald-300">An</span> /
            <span class="text-red-300">Aus</span> überschreiben den Standard.
            <span class="text-ink-300">Std</span> = die fest hinterlegte Regel.
          </p>

          <div *ngFor="let cat of categoryKeys()" class="mb-5">
            <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">{{ cat }}</div>
            <div class="space-y-1">
              <div *ngFor="let entry of registry()[cat]"
                   class="flex items-center gap-3 py-1.5 px-2 rounded-lg hover:bg-ink-800/50">
                <div class="flex-1 min-w-0">
                  <div class="text-sm">{{ entry.label }}</div>
                  <div class="text-xs text-ink-400 font-mono truncate">{{ entry.key }}</div>
                </div>
                <span class="text-[10px] text-ink-500 hidden md:inline">
                  Standard: {{ defaultLabel(entry.defaultGrant) }}
                </span>
                <mat-button-toggle-group [value]="stateOf(role, entry.key)"
                                         (change)="onToggle(role, entry.key, $event.value)"
                                         class="et-perm-toggle" hideSingleSelectionIndicator>
                  <mat-button-toggle value="GRANT">An</mat-button-toggle>
                  <mat-button-toggle value="DEFAULT">Std</mat-button-toggle>
                  <mat-button-toggle value="DENY">Aus</mat-button-toggle>
                </mat-button-toggle-group>
              </div>
            </div>
          </div>
        </ng-container>
      </mat-card>

      <ng-template #noSelection>
        <mat-card class="p-12 text-center text-ink-300">
          <mat-icon class="!text-5xl !w-12 !h-12 mb-2 text-ink-500">arrow_back</mat-icon>
          <div>Wähle links eine Rolle oder lege eine neue an.</div>
        </mat-card>
      </ng-template>
    </div>
  `,
  styles: [`
    ::ng-deep et-admin-permissions mat-form-field { width: 100%; }
    ::ng-deep .et-perm-toggle .mat-button-toggle { background: #13131a; color: #9b9bb0; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-appearance-standard
      .mat-button-toggle-label-content { line-height: 30px; padding: 0 12px; font-size: .75rem; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="GRANT"] { background: #059669; color: #fff; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="DENY"]  { background: #dc2626; color: #fff; }
    ::ng-deep .et-perm-toggle .mat-button-toggle-checked[value="DEFAULT"] { background: #3a3a4b; color: #fff; }
  `]
})
export class PermissionsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly loading = signal(true);
  readonly roles = signal<Role[]>([]);
  readonly registry = signal<Record<string, PermissionRegistryEntry[]>>({});
  readonly cloudGroups = signal<Array<{ name: string; sortId: number; color: string }>>([]);
  readonly selected = signal<Role | null>(null);
  readonly isNew = signal(false);

  form = { name: '', displayName: '', mcGroupName: '', sortOrder: 0, color: '&7' };

  readonly categoryKeys = computed(() => Object.keys(this.registry()));
  /** When CloudNet is present, roles ARE the CloudNet groups (auto-synced
   *  by the proxy). Metadata is then read-only — the admin only edits
   *  permissions. Manual role create/edit/delete is the no-CloudNet
   *  fallback. */
  readonly cloudActive = computed(() => this.cloudGroups().length > 0);

  ngOnInit() { this.reload(); }

  private reload() {
    this.loading.set(true);
    this.api.permissionRegistry().subscribe({
      next: reg => {
        this.registry.set(reg.categories ?? {});
        this.api.adminCloudGroups().subscribe(res => this.cloudGroups.set(res.groups ?? []));
        this.api.listRoles().subscribe({
          next: res => { this.roles.set(res.roles ?? []); this.loading.set(false); },
          error: () => { this.loading.set(false); this.err('Rollen konnten nicht geladen werden.'); }
        });
      },
      error: () => { this.loading.set(false); this.err('Registry konnte nicht geladen werden.'); }
    });
  }

  selectRole(r: Role) {
    this.isNew.set(false);
    this.selected.set(r);
    this.form = { name: r.name, displayName: r.displayName, mcGroupName: r.mcGroupName, sortOrder: r.sortOrder, color: r.color };
  }

  startNewRole() {
    this.isNew.set(true);
    this.selected.set({ name: '', displayName: '', mcGroupName: '', sortOrder: 0, color: '&7', permissions: [] });
    this.form = { name: '', displayName: '', mcGroupName: '', sortOrder: 0, color: '&7' };
  }

  formValid(): boolean {
    return this.form.name.trim().length > 0
        && this.form.displayName.trim().length > 0
        && this.form.mcGroupName.trim().length > 0;
  }

  saveRole() {
    if (!this.formValid()) return;
    const name = this.form.name.trim();
    this.api.upsertRole(name, {
      displayName: this.form.displayName.trim(),
      mcGroupName: this.form.mcGroupName.trim(),
      sortOrder: Number(this.form.sortOrder) || 0,
      color: this.form.color.trim() || '&7'
    }).subscribe({
      next: () => {
        this.snack.open('Rolle gespeichert.', 'OK', { duration: 2000 });
        this.api.listRoles().subscribe(res => {
          this.roles.set(res.roles ?? []);
          const saved = (res.roles ?? []).find(r => r.name === name);
          if (saved) this.selectRole(saved);
        });
      },
      error: e => this.err(e.error?.error ?? 'Speichern fehlgeschlagen.')
    });
  }

  removeRole(r: Role) {
    if (!confirm(`Rolle "${r.displayName}" wirklich löschen? Alle ihre Rechte-Zuweisungen gehen verloren.`)) return;
    this.api.deleteRole(r.name).subscribe({
      next: () => { this.snack.open('Rolle gelöscht.', 'OK', { duration: 2000 }); this.selected.set(null); this.reload(); },
      error: e => this.err(e.error?.error ?? 'Löschen fehlgeschlagen.')
    });
  }

  stateOf(role: Role, key: string): 'GRANT' | 'DENY' | 'DEFAULT' {
    const g = role.permissions.find(p => p.key === key);
    if (!g) return 'DEFAULT';
    return g.granted ? 'GRANT' : 'DENY';
  }

  onToggle(role: Role, key: string, state: 'GRANT' | 'DENY' | 'DEFAULT') {
    if (state === 'DEFAULT') {
      this.api.clearRolePermission(role.name, key).subscribe({
        next: () => this.patchLocalGrant(role, key, null),
        error: e => this.err(e.error?.error ?? 'Rechte-Update fehlgeschlagen.')
      });
    } else {
      const granted = state === 'GRANT';
      this.api.setRolePermission(role.name, key, granted).subscribe({
        next: () => this.patchLocalGrant(role, key, granted),
        error: e => this.err(e.error?.error ?? 'Rechte-Update fehlgeschlagen.')
      });
    }
  }

  private patchLocalGrant(role: Role, key: string, granted: boolean | null) {
    const without = role.permissions.filter(p => p.key !== key);
    if (granted !== null) without.push({ key, granted, updatedAt: Date.now(), updatedBy: 'you' });
    const updated: Role = { ...role, permissions: without };
    this.selected.set(updated);
    this.roles.set(this.roles().map(r => r.name === role.name ? updated : r));
  }

  defaultLabel(d: string): string {
    switch (d) {
      case 'EVERYONE': return 'an für alle';
      case 'STAFF_ANY': return 'an für Team';
      case 'ADMIN_ONLY': return 'nur Admin';
      default: return 'aus';
    }
  }

  hexFor(code: string): string {
    const map: Record<string, string> = {
      '&0': '#000000', '&1': '#0000aa', '&2': '#00aa00', '&3': '#00aaaa',
      '&4': '#aa0000', '&5': '#aa00aa', '&6': '#ffaa00', '&7': '#aaaaaa',
      '&8': '#555555', '&9': '#5555ff', '&a': '#55ff55', '&b': '#55ffff',
      '&c': '#ff5555', '&d': '#ff55ff', '&e': '#ffff55', '&f': '#ffffff'
    };
    return map[(code || '').toLowerCase()] ?? '#aaaaaa';
  }

  private err(msg: string) { this.snack.open(msg, 'OK', { duration: 4000 }); }
}
