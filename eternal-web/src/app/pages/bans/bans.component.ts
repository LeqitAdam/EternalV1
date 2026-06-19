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
import { forkJoin } from 'rxjs';
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
    <h1 class="text-3xl font-bold mb-2">Aktive Strafen</h1>
    <p class="text-ink-300 mb-6">{{ punishments().length }} aktive Eintraege (Bans + Mutes).</p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <mat-card *ngIf="!loading()" class="p-0 overflow-hidden">
      <table mat-table [dataSource]="punishments()" class="!bg-transparent w-full">
        <ng-container matColumnDef="id">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">#</th>
          <td mat-cell *matCellDef="let b" class="!text-eternal-300 font-mono">{{ b.id }}</td>
        </ng-container>

        <ng-container matColumnDef="type">
          <th mat-header-cell *matHeaderCellDef class="!text-ink-300">Typ</th>
          <td mat-cell *matCellDef="let b">
            <span [class]="typeClass(b)">{{ b.type === 'BAN' ? 'Bann' : 'Mute' }}</span>
          </td>
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
            <!-- Admin-flagged punishments can only be lifted with
                 eternal.unban.admin. Others see a disabled badge. -->
            <ng-container *ngIf="isAdminPunishment(b); else normalP">
              <button mat-stroked-button *ngIf="auth.hasPerm('eternal.unban.admin'); else adminLockedTpl" (click)="pardon(b)">
                <mat-icon>restore</mat-icon> Aufheben
              </button>
              <ng-template #adminLockedTpl>
                <span class="text-amber-400 text-xs inline-flex items-center gap-1" matTooltip="Erfordert eternal.unban.admin.">
                  <mat-icon class="!text-base">lock</mat-icon> Admin-Strafe
                </span>
              </ng-template>
            </ng-container>
            <ng-template #normalP>
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

  /** Combined active bans + mutes, newest first. */
  readonly punishments = signal<Punishment[]>([]);
  readonly loading = signal(true);
  readonly cols = ['id', 'type', 'target', 'reason', 'issuer', 'when', 'expires', 'actions'];
  /** id -> adminOnly, populated once from /reasons so we can grey-out pardon. */
  private readonly adminReasons = new Set<number>();
  /** uuid -> &-coded display, from /bans payload. Used to render the
   *  "Von" column with rank-coloured names. */
  readonly displayNames = signal<Record<string, string>>({});

  constructor() {
    // Bans carry a displayNames map; mutes don't — merge both lists and reuse
    // the bans' name map (mute issuers fall back to plain issuerName).
    forkJoin({ bans: this.api.bans(), mutes: this.api.mutes() }).subscribe({
      next: ({ bans, mutes }) => {
        const all = [...bans.bans, ...mutes].sort((a, b) => b.issuedAt - a.issuedAt);
        this.punishments.set(all);
        this.displayNames.set(bans.displayNames ?? {});
        this.loading.set(false);
      },
      error: () => this.loading.set(false)
    });
    this.api.reasons().subscribe({
      next: res => res.reasons.filter(r => r.adminOnly).forEach(r => this.adminReasons.add(r.id)),
      error: () => { /* non-fatal: pardon button defaults to "available" */ }
    });
  }

  typeClass(b: Punishment): string {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    return b.type === 'BAN' ? `${base} bg-red-900/40 text-red-300` : `${base} bg-amber-900/40 text-amber-300`;
  }

  isAdminPunishment(b: Punishment): boolean {
    const idNum = Number(b.reasonId);
    return Number.isFinite(idNum) && this.adminReasons.has(idNum);
  }

  /** Cached &-coded display for an issuer UUID, or null if we have none. */
  displayFor(uuid: string | null): string | null {
    return uuid ? (this.displayNames()[uuid] ?? null) : null;
  }

  pardon(b: Punishment) {
    const noun = b.type === 'BAN' ? 'Bann' : 'Mute';
    const data: TextPromptDialogData = {
      title: `${noun} #${b.id} aufheben`,
      label: `Grund (${b.targetName})`,
      placeholder: 'z.B. "Antrag genehmigt" oder "Falsch bestraft"',
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
          this.snack.open(`${noun} #${b.id} aufgehoben`, 'OK', { duration: 2500 });
          this.punishments.set(this.punishments().filter(x => x.id !== b.id));
        },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }
}
