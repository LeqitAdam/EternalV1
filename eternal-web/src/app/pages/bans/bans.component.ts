import { Component, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { Punishment } from '../../core/models';
import { TextPromptDialogComponent, TextPromptDialogData }
    from '../../shared/text-prompt-dialog/text-prompt-dialog.component';

@Component({
  selector: 'et-bans',
  standalone: true,
  imports: [
    CommonModule, DatePipe, MatCardModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule, MatTableModule, MatSnackBarModule, MatDialogModule,
    MatTooltipModule, LegacyTextPipe
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Aktive Bans</h1>
    <p class="text-ink-300 mb-6">{{ bans().length }} aktive Eintraege.</p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <mat-card *ngIf="!loading()" class="p-0 overflow-hidden">
      <table mat-table [dataSource]="bans()" class="!bg-transparent w-full">
        <ng-container matColumnDef="id">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">#</th>
          <td mat-cell *matCellDef="let b" class="!text-eternal-300 font-mono">{{ b.id }}</td>
        </ng-container>

        <ng-container matColumnDef="target">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Spieler</th>
          <td mat-cell *matCellDef="let b">{{ b.targetName }}</td>
        </ng-container>

        <ng-container matColumnDef="reason">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Grund</th>
          <!-- Reason labels stay aqua in-game and on the dashboard. -->
          <td mat-cell *matCellDef="let b" class="!text-cyan-300">{{ b.reasonLabel }}</td>
        </ng-container>

        <ng-container matColumnDef="issuer">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Von</th>
          <!-- Rank-coloured DisplayName from the displayNames map; falls
               back to plain name when no profile is cached. -->
          <td mat-cell *matCellDef="let b"
              [innerHTML]="(displayFor(b.issuerUuid) || b.issuerName) | legacy"></td>
        </ng-container>

        <ng-container matColumnDef="when">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Wann</th>
          <td mat-cell *matCellDef="let b" class="text-ink-300 text-sm">{{ b.issuedAt | date:'yyyy-MM-dd HH:mm' }}</td>
        </ng-container>

        <ng-container matColumnDef="expires">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Ablauf</th>
          <td mat-cell *matCellDef="let b" class="text-ink-300 text-sm">
            <span *ngIf="b.expiresAt; else perm">{{ b.expiresAt | date:'yyyy-MM-dd HH:mm' }}</span>
            <ng-template #perm><span class="text-red-400">permanent</span></ng-template>
          </td>
        </ng-container>

        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef class="!text-right"></th>
          <td mat-cell *matCellDef="let b" class="!text-right">
            <!-- Admin bans can only be lifted by full admins. Mods see a
                 disabled badge instead of the pardon button. -->
            <ng-container *ngIf="isAdminBan(b); else normalBan">
              <button mat-stroked-button *ngIf="auth.isAdmin(); else adminLockedTpl" (click)="pardon(b)">
                <mat-icon>restore</mat-icon> Aufheben
              </button>
              <ng-template #adminLockedTpl>
                <span class="text-amber-400 text-xs inline-flex items-center gap-1" matTooltip="Nur Admins können diesen Bann aufheben.">
                  <mat-icon class="!text-base">lock</mat-icon> Admin-Bann
                </span>
              </ng-template>
            </ng-container>
            <ng-template #normalBan>
              <button mat-stroked-button *ngIf="auth.canPardon()" (click)="pardon(b)">
                <mat-icon>restore</mat-icon> Aufheben
              </button>
            </ng-template>
          </td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="cols"></tr>
        <tr mat-row *matRowDef="let row; columns: cols;"></tr>
      </table>
    </mat-card>
  `
})
export class BansComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly bans = signal<Punishment[]>([]);
  readonly loading = signal(true);
  readonly cols = ['id', 'target', 'reason', 'issuer', 'when', 'expires', 'actions'];
  /** id -> adminOnly, populated once from /reasons so we can grey-out pardon. */
  private readonly adminReasons = new Set<number>();
  /** uuid -> &-coded display, from /bans payload. Used to render the
   *  "Von" column with rank-coloured names. */
  readonly displayNames = signal<Record<string, string>>({});

  constructor() {
    this.api.bans().subscribe({
      next: res => {
        this.bans.set(res.bans);
        this.displayNames.set(res.displayNames ?? {});
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
    this.api.reasons().subscribe({
      next: res => res.reasons.filter(r => r.adminOnly).forEach(r => this.adminReasons.add(r.id)),
      error: () => { /* non-fatal: pardon button defaults to "available" */ }
    });
  }

  isAdminBan(b: Punishment): boolean {
    const idNum = Number(b.reasonId);
    return Number.isFinite(idNum) && this.adminReasons.has(idNum);
  }

  /** Cached &-coded display for an issuer UUID, or null if we have none. */
  displayFor(uuid: string | null): string | null {
    return uuid ? (this.displayNames()[uuid] ?? null) : null;
  }

  pardon(b: Punishment) {
    const data: TextPromptDialogData = {
      title: `Bann #${b.id} aufheben`,
      label: `Grund (${b.targetName})`,
      placeholder: 'z.B. "Antrag genehmigt" oder "Falsch gebannt"',
      confirmText: 'Aufheben',
      confirmColor: 'warn',
      multiline: true
    };
    this.dialog.open<TextPromptDialogComponent, TextPromptDialogData, string>(
      TextPromptDialogComponent, { data }
    ).afterClosed().subscribe(reason => {
      if (!reason) return;
      this.api.pardon(b.id, reason).subscribe({
        next: () => {
          this.snack.open(`Bann #${b.id} aufgehoben`, 'OK', { duration: 2500 });
          this.bans.set(this.bans().filter(x => x.id !== b.id));
        },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }
}
