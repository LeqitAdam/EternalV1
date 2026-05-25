import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { UnbanAppeal } from '../../core/models';
import { TextPromptDialogComponent, TextPromptDialogData }
    from '../../shared/text-prompt-dialog/text-prompt-dialog.component';
import { AppealShortenDialogComponent, AppealShortenDialogData, AppealShortenResult }
    from '../../shared/appeal-shorten-dialog/appeal-shorten-dialog.component';

@Component({
  selector: 'et-appeals',
  standalone: true,
  imports: [
    CommonModule, DatePipe, MatCardModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule, MatSnackBarModule, MatTabsModule, MatDialogModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Entbannungsantraege</h1>
    <p class="text-ink-300 mb-6">{{ subtitle() }}</p>

    <mat-tab-group (selectedTabChange)="loadTab($event.index)">
      <mat-tab label="Offen"></mat-tab>
      <mat-tab label="Genehmigt"></mat-tab>
      <mat-tab label="Verkürzt"></mat-tab>
      <mat-tab label="Abgelehnt"></mat-tab>
    </mat-tab-group>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <div *ngIf="!loading()" class="grid grid-cols-1 md:grid-cols-2 gap-4 mt-4">
      <mat-card *ngFor="let a of appeals()" class="p-5">
        <div class="flex items-start gap-3 mb-3">
          <span class="text-ink-300 font-mono text-sm">#{{ a.id }}</span>
          <span [class]="badge(a.status)">{{ a.status }}</span>
          <span class="text-ink-300 text-xs ml-auto">{{ a.createdAt | date:'yyyy-MM-dd HH:mm' }}</span>
        </div>
        <div class="mb-2">
          <span class="text-ink-300 text-sm">Spieler:</span>
          <span class="ml-2 font-medium">{{ a.applicantName }}</span>
        </div>
        <div class="mb-3">
          <span class="text-ink-300 text-sm">Bann-ID:</span>
          <span class="ml-2 font-mono">#{{ a.banId }}</span>
        </div>
        <div class="p-3 bg-ink-800 rounded text-sm italic mb-3">"{{ a.text }}"</div>

        <div *ngIf="a.status === 'PENDING'" class="flex flex-wrap gap-2">
          <!-- Annehmen + Ablehnen brauchen Admin-Rolle (volles Entbannen
               bzw. abschliessende Ablehnung). Verkuerzen darf jeder Staff
               ohne eternal.modify.duration-Perm. -->
          <button mat-flat-button color="primary" *ngIf="auth.isAdmin()" (click)="approve(a)">
            <mat-icon>check</mat-icon> Genehmigen + Entbannen
          </button>
          <button mat-flat-button class="!bg-cyan-700" (click)="shorten(a)">
            <mat-icon>schedule</mat-icon> Verkürzen
          </button>
          <button mat-stroked-button color="warn" *ngIf="auth.isAdmin()" (click)="deny(a)">
            <mat-icon>close</mat-icon> Ablehnen
          </button>
        </div>

        <div *ngIf="a.status !== 'PENDING'" class="text-sm text-ink-300">
          {{ statusLabel(a.status) }} von {{ a.reviewerName }}
          am {{ a.reviewedAt | date:'yyyy-MM-dd HH:mm' }}
          <div *ngIf="a.decisionReason" class="italic mt-1">„{{ a.decisionReason }}"</div>
          <div *ngIf="a.decisionMessage" class="mt-2 p-2 bg-cyan-900/30 border border-cyan-700/40 rounded text-cyan-200">
            <strong>Nachricht an den Spieler:</strong> {{ a.decisionMessage }}
          </div>
        </div>
      </mat-card>
    </div>

    <div *ngIf="!loading() && appeals().length === 0" class="text-center py-12 text-ink-300">
      <mat-icon class="text-6xl !w-16 !h-16">inbox</mat-icon>
      <div class="mt-2">Keine Eintraege.</div>
    </div>
  `
})
export class AppealsComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly appeals = signal<UnbanAppeal[]>([]);
  readonly loading = signal(true);
  readonly tab = signal<'PENDING' | 'APPROVED' | 'DENIED' | 'SHORTENED'>('PENDING');
  /** Cached shorten templates from /reasons, fetched once at mount. */
  readonly shortenTemplates = signal<Array<{ id: string; label: string; durationSeconds: number; message: string }>>([]);

  ngOnInit() {
    this.loadTab(0);
    this.api.reasons().subscribe({
      next: res => this.shortenTemplates.set(res.appealShortenTemplates ?? []),
      error: () => { /* non-fatal — Verkürzen-Dialog läuft auch mit leerer Liste */ }
    });
  }

  loadTab(idx: number) {
    // Tab order: Offen, Genehmigt, Verkürzt, Abgelehnt
    const states: Array<typeof this.tab extends import('@angular/core').WritableSignal<infer T> ? T : never> =
        ['PENDING', 'APPROVED', 'SHORTENED', 'DENIED'];
    this.tab.set(states[idx] ?? 'PENDING');
    this.loading.set(true);
    this.api.appeals(this.tab() as any).subscribe({
      next: list => { this.appeals.set(list); this.loading.set(false); },
      error: () => { this.appeals.set([]); this.loading.set(false); }
    });
  }

  approve(a: UnbanAppeal) {
    this.askText({
      title: `Antrag #${a.id} genehmigen`,
      label: 'Begründung für die Genehmigung',
      initialValue: 'Antrag bestätigt',
      confirmText: 'Genehmigen + Entbannen',
      confirmColor: 'primary',
      multiline: true
    }).subscribe(reason => {
      if (!reason) return;
      this.api.approveAppeal(a.id, reason).subscribe({
        next: () => { this.snack.open(`Antrag #${a.id} genehmigt + Bann aufgehoben`, 'OK', { duration: 3000 }); this.loadTab(0); },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }

  deny(a: UnbanAppeal) {
    this.askText({
      title: `Antrag #${a.id} ablehnen`,
      label: 'Begründung für die Ablehnung',
      initialValue: 'Antrag abgelehnt',
      confirmText: 'Ablehnen',
      confirmColor: 'warn',
      multiline: true
    }).subscribe(reason => {
      if (!reason) return;
      this.api.denyAppeal(a.id, reason).subscribe({
        next: () => { this.snack.open(`Antrag #${a.id} abgelehnt`, 'OK', { duration: 3000 }); this.loadTab(0); },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }

  private askText(data: TextPromptDialogData) {
    return this.dialog.open<TextPromptDialogComponent, TextPromptDialogData, string>(
      TextPromptDialogComponent, { data }
    ).afterClosed();
  }

  /** Opens the verkürzen-dialog; on confirm calls /appeals/{id}/shorten
   *  with the remaining duration + message the player will see. */
  shorten(a: UnbanAppeal) {
    const data: AppealShortenDialogData = {
      applicantName: a.applicantName,
      templates: this.shortenTemplates()
    };
    this.dialog.open<AppealShortenDialogComponent, AppealShortenDialogData, AppealShortenResult>(
      AppealShortenDialogComponent, { data, width: '480px' }
    ).afterClosed().subscribe(result => {
      if (!result) return;
      this.api.shortenAppeal(a.id, result).subscribe({
        next: () => {
          this.snack.open(`Antrag #${a.id} verkürzt`, 'OK', { duration: 3000 });
          this.loadTab(0);
        },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }

  statusLabel(s: string): string {
    return s === 'APPROVED' ? 'Genehmigt'
        : s === 'SHORTENED' ? 'Verkürzt'
        : 'Abgelehnt';
  }

  subtitle() {
    return this.auth.isAdmin()
        ? 'Bearbeite Antraege oder schau dir die History an.'
        : 'Alle eingegangenen Antraege.';
  }

  badge(s: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (s === 'PENDING') return `${base} bg-orange-900/40 text-orange-300`;
    if (s === 'APPROVED') return `${base} bg-green-900/40 text-green-300`;
    if (s === 'SHORTENED') return `${base} bg-cyan-900/40 text-cyan-300`;
    return `${base} bg-red-900/40 text-red-300`;
  }
}
