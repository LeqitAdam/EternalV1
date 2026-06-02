import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { PermissionRegistryEntry, Role } from '../../core/models';

/**
 * Admin role + permission editor.
 *
 * Left column: the role list (+ "new role"). Right column: the selected
 * role's metadata (display name, CloudNet group binding, sort order,
 * colour) and a category-grouped permission matrix. Each permission key
 * is a three-state control:
 *
 *   - GRANT   (explicit allow, green)   → PUT  .../permissions/{key} {granted:true}
 *   - DENY    (explicit deny, red)      → PUT  .../permissions/{key} {granted:false}
 *   - DEFAULT (no row, grey)            → DELETE .../permissions/{key}
 *
 * DEFAULT means the hardcoded registry policy applies. The little tag
 * next to each key shows what that default is so the admin knows what
 * "DEFAULT" resolves to.
 */
@Component({
  selector: 'et-admin-permissions',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatProgressSpinnerModule, MatSnackBarModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Rollen &amp; Rechte</h1>
    <p class="text-ink-300 mb-6">
      Rollen werden über die CloudNet-Gruppe zugeordnet. Rechte gelten für
      jeden Spieler dieser Gruppe — sofern kein User-Override greift.
    </p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <div *ngIf="!loading()" class="grid lg:grid-cols-[280px_1fr] gap-5">
      <!-- ===== Role list ===== -->
      <mat-card class="p-4 h-fit">
        <div class="flex items-center justify-between mb-3">
          <h2 class="font-semibold">Rollen</h2>
          <button mat-icon-button (click)="startNewRole()" aria-label="Neue Rolle">
            <mat-icon class="text-eternal-300">add</mat-icon>
          </button>
        </div>
        <div *ngIf="roles().length === 0" class="text-ink-300 text-sm py-2">
          Noch keine Rollen. Lege eine an, die auf eine CloudNet-Gruppe zeigt.
        </div>
        <div class="space-y-1">
          <button *ngFor="let r of roles()"
                  (click)="selectRole(r)"
                  class="w-full text-left px-3 py-2 rounded transition flex items-center gap-2"
                  [class.bg-ink-700]="selected()?.name === r.name"
                  [class.hover:bg-ink-700]="selected()?.name !== r.name">
            <span class="w-2 h-2 rounded-full" [style.background]="hexFor(r.color)"></span>
            <span class="flex-1 truncate">{{ r.displayName }}</span>
            <span class="text-xs text-ink-400 font-mono">{{ r.mcGroupName }}</span>
          </button>
        </div>
      </mat-card>

      <!-- ===== Editor pane ===== -->
      <mat-card class="p-6" *ngIf="selected() as role; else noSelection">
        <div class="flex items-start justify-between mb-5">
          <h2 class="text-xl font-semibold">
            {{ isNew() ? 'Neue Rolle' : role.displayName }}
          </h2>
          <button *ngIf="!isNew()" mat-stroked-button color="warn" (click)="removeRole(role)">
            <mat-icon>delete</mat-icon> Löschen
          </button>
        </div>

        <!-- Metadata grid -->
        <div class="grid sm:grid-cols-2 gap-4 mb-6">
          <label class="block">
            <span class="text-sm text-ink-300">Interner Name (eindeutig)</span>
            <input [(ngModel)]="form.name" [disabled]="!isNew()"
                   class="mt-1 w-full bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm disabled:opacity-50"
                   placeholder="z.B. moderator" />
          </label>
          <label class="block">
            <span class="text-sm text-ink-300">Anzeigename</span>
            <input [(ngModel)]="form.displayName"
                   class="mt-1 w-full bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm"
                   placeholder="z.B. Moderator" />
          </label>
          <label class="block">
            <span class="text-sm text-ink-300">CloudNet-Gruppe</span>
            <input [(ngModel)]="form.mcGroupName"
                   class="mt-1 w-full bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm font-mono"
                   placeholder="exakter Gruppenname" />
          </label>
          <div class="grid grid-cols-2 gap-3">
            <label class="block">
              <span class="text-sm text-ink-300">Sortierung</span>
              <input type="number" [(ngModel)]="form.sortOrder"
                     class="mt-1 w-full bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm" />
            </label>
            <label class="block">
              <span class="text-sm text-ink-300">Farbe (&amp;-Code)</span>
              <input [(ngModel)]="form.color"
                     class="mt-1 w-full bg-ink-800 border border-ink-600 rounded px-3 py-2 text-sm font-mono"
                     placeholder="&amp;a" />
            </label>
          </div>
        </div>
        <div class="mb-6">
          <button mat-flat-button color="primary" (click)="saveRole()" [disabled]="!formValid()">
            <mat-icon>save</mat-icon> {{ isNew() ? 'Rolle anlegen' : 'Metadaten speichern' }}
          </button>
        </div>

        <!-- Permission matrix (only for existing roles) -->
        <ng-container *ngIf="!isNew()">
          <h3 class="font-semibold mb-1">Berechtigungen</h3>
          <p class="text-sm text-ink-300 mb-4">
            <span class="text-emerald-300">Erlaubt</span> /
            <span class="text-red-300">Verboten</span> überschreiben den Standard.
            <span class="text-ink-400">Standard</span> = die fest hinterlegte Regel.
          </p>

          <div *ngFor="let cat of categoryKeys()" class="mb-5">
            <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">{{ cat }}</div>
            <div class="space-y-1.5">
              <div *ngFor="let entry of registry()[cat]"
                   class="flex items-center gap-3 py-1.5 px-2 rounded hover:bg-ink-800/50">
                <div class="flex-1 min-w-0">
                  <div class="text-sm">{{ entry.label }}</div>
                  <div class="text-xs text-ink-400 font-mono truncate">{{ entry.key }}</div>
                </div>
                <span class="text-[10px] text-ink-500 hidden sm:inline">
                  Standard: {{ defaultLabel(entry.defaultGrant) }}
                </span>
                <!-- three-state segmented control -->
                <div class="flex rounded-md overflow-hidden border border-ink-600 shrink-0">
                  <button (click)="setPerm(role, entry.key, true)"
                          class="px-2.5 py-1 text-xs transition"
                          [class.bg-emerald-600]="stateOf(role, entry.key) === 'GRANT'"
                          [class.text-white]="stateOf(role, entry.key) === 'GRANT'"
                          [class.text-ink-300]="stateOf(role, entry.key) !== 'GRANT'"
                          [class.hover:bg-ink-700]="stateOf(role, entry.key) !== 'GRANT'">An</button>
                  <button (click)="clearPerm(role, entry.key)"
                          class="px-2.5 py-1 text-xs transition border-x border-ink-600"
                          [class.bg-ink-500]="stateOf(role, entry.key) === 'DEFAULT'"
                          [class.text-white]="stateOf(role, entry.key) === 'DEFAULT'"
                          [class.text-ink-300]="stateOf(role, entry.key) !== 'DEFAULT'"
                          [class.hover:bg-ink-700]="stateOf(role, entry.key) !== 'DEFAULT'">Std</button>
                  <button (click)="setPerm(role, entry.key, false)"
                          class="px-2.5 py-1 text-xs transition"
                          [class.bg-red-600]="stateOf(role, entry.key) === 'DENY'"
                          [class.text-white]="stateOf(role, entry.key) === 'DENY'"
                          [class.text-ink-300]="stateOf(role, entry.key) !== 'DENY'"
                          [class.hover:bg-ink-700]="stateOf(role, entry.key) !== 'DENY'">Aus</button>
                </div>
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
  `
})
export class PermissionsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly loading = signal(true);
  readonly roles = signal<Role[]>([]);
  readonly registry = signal<Record<string, PermissionRegistryEntry[]>>({});
  readonly selected = signal<Role | null>(null);
  readonly isNew = signal(false);

  /** Editable copy of the selected role's metadata. */
  form = { name: '', displayName: '', mcGroupName: '', sortOrder: 0, color: '&7' };

  readonly categoryKeys = computed(() => Object.keys(this.registry()));

  ngOnInit() {
    this.reload();
  }

  private reload() {
    this.loading.set(true);
    this.api.permissionRegistry().subscribe({
      next: reg => {
        this.registry.set(reg.categories ?? {});
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
    this.form = {
      name: r.name, displayName: r.displayName, mcGroupName: r.mcGroupName,
      sortOrder: r.sortOrder, color: r.color
    };
  }

  startNewRole() {
    this.isNew.set(true);
    // Synthetic empty role so the editor renders; not yet persisted.
    const blank: Role = { name: '', displayName: '', mcGroupName: '', sortOrder: 0, color: '&7', permissions: [] };
    this.selected.set(blank);
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
        // Reload, then re-select the saved role so the matrix appears.
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
      next: () => {
        this.snack.open('Rolle gelöscht.', 'OK', { duration: 2000 });
        this.selected.set(null);
        this.reload();
      },
      error: e => this.err(e.error?.error ?? 'Löschen fehlgeschlagen.')
    });
  }

  /** Current tri-state of a permission key on this role. */
  stateOf(role: Role, key: string): 'GRANT' | 'DENY' | 'DEFAULT' {
    const g = role.permissions.find(p => p.key === key);
    if (!g) return 'DEFAULT';
    return g.granted ? 'GRANT' : 'DENY';
  }

  setPerm(role: Role, key: string, granted: boolean) {
    this.api.setRolePermission(role.name, key, granted).subscribe({
      next: () => this.patchLocalGrant(role, key, granted),
      error: e => this.err(e.error?.error ?? 'Rechte-Update fehlgeschlagen.')
    });
  }

  clearPerm(role: Role, key: string) {
    this.api.clearRolePermission(role.name, key).subscribe({
      next: () => this.patchLocalGrant(role, key, null),
      error: e => this.err(e.error?.error ?? 'Rechte-Update fehlgeschlagen.')
    });
  }

  /** Mutate the in-memory role so the toggle reflects immediately
   *  without a full reload. null = remove the override. */
  private patchLocalGrant(role: Role, key: string, granted: boolean | null) {
    const without = role.permissions.filter(p => p.key !== key);
    if (granted !== null) {
      without.push({ key, granted, updatedAt: Date.now(), updatedBy: 'you' });
    }
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

  /** Maps a Minecraft &-colour code to a CSS hex for the role dot. */
  hexFor(code: string): string {
    const map: Record<string, string> = {
      '&0': '#000000', '&1': '#0000aa', '&2': '#00aa00', '&3': '#00aaaa',
      '&4': '#aa0000', '&5': '#aa00aa', '&6': '#ffaa00', '&7': '#aaaaaa',
      '&8': '#555555', '&9': '#5555ff', '&a': '#55ff55', '&b': '#55ffff',
      '&c': '#ff5555', '&d': '#ff55ff', '&e': '#ffff55', '&f': '#ffffff'
    };
    return map[(code || '').toLowerCase()] ?? '#aaaaaa';
  }

  private err(msg: string) {
    this.snack.open(msg, 'OK', { duration: 4000 });
  }
}
