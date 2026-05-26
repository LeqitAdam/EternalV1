import { Component, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { ApiService } from '../../core/api.service';
import { Report, ReportStatusFilter } from '../../core/models';
import { CloseReportDialogComponent } from './close-report-dialog.component';
import { BanFromReportDialogComponent, BanDialogResult } from './ban-from-report-dialog.component';
import { MuteFromReportDialogComponent, MuteDialogResult } from './mute-from-report-dialog.component';

@Component({
  selector: 'et-reports',
  standalone: true,
  imports: [
    CommonModule, DatePipe, MatCardModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule, MatSnackBarModule, MatTabsModule, MatDialogModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Reports</h1>
    <p class="text-ink-300 mb-4">Klick "TP" sendet dich ingame zum Ziel. "Bannen" macht beides in einem.</p>

    <mat-tab-group (selectedTabChange)="onTab($event.index)" class="mb-4">
      <mat-tab label="Aktiv"></mat-tab>
      <mat-tab label="Offen"></mat-tab>
      <mat-tab label="In Bearbeitung"></mat-tab>
      <mat-tab label="Geschlossen"></mat-tab>
      <mat-tab label="Alle"></mat-tab>
    </mat-tab-group>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <div *ngIf="!loading() && reports().length === 0" class="text-center py-12 text-ink-300">
      <mat-icon class="text-6xl !w-16 !h-16">inbox</mat-icon>
      <div class="mt-2">Keine Reports in dieser Ansicht.</div>
    </div>

    <div *ngIf="!loading() && total() > 0" class="text-sm text-ink-300 mb-3">
      {{ reports().length }} von {{ total() }} angezeigt.
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
      <mat-card *ngFor="let r of reports()" class="p-5">
        <div class="flex items-start gap-4">
          <img [src]="head(r.targetUuid)" class="w-12 h-12 rounded" alt="head" />
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <span class="text-eternal-300 font-mono text-sm">#{{ r.id }}</span>
              <span [class]="statusClass(r)">{{ r.status }}</span>
            </div>
            <div class="text-lg font-semibold">{{ r.targetName }}</div>
            <div class="text-sm text-ink-300 mt-1">
              <span class="font-medium">{{ r.reasonLabel }}</span>
              · gemeldet von {{ r.reporterName }}
            </div>
            <div *ngIf="r.comment" class="text-sm text-ink-300/80 italic mt-2 line-clamp-2">
              "{{ r.comment }}"
            </div>
            <div class="text-xs text-ink-300 mt-2">
              {{ r.serverName }} · {{ r.createdAt | date:'yyyy-MM-dd HH:mm' }}
            </div>
            <div *ngIf="r.handlerName && r.status === 'CLAIMED'" class="text-xs text-eternal-300 mt-1">
              In Bearbeitung von: {{ r.handlerName }}
            </div>
            <div *ngIf="r.handlerName && r.status === 'CLOSED'" class="text-xs text-ink-200 mt-1">
              Bearbeitet von: {{ r.handlerName }}<span *ngIf="r.closedAt"> · {{ r.closedAt | date:'yyyy-MM-dd HH:mm' }}</span>
            </div>
            <div *ngIf="r.resolution" class="text-xs text-ink-300 mt-1 italic">
              Auflösung: {{ r.resolution }}
            </div>
          </div>
        </div>

        <div class="flex flex-wrap gap-2 mt-4" *ngIf="r.status !== 'CLOSED'">
          <button mat-flat-button color="primary" (click)="claim(r)"
                  [disabled]="r.status === 'CLAIMED'">
            <mat-icon>how_to_reg</mat-icon> Annehmen
          </button>
          <button mat-stroked-button (click)="tp(r)">
            <mat-icon>place</mat-icon> TP
          </button>
          <button mat-stroked-button color="warn" (click)="ban(r)">
            <mat-icon>gavel</mat-icon> Bannen
          </button>
          <button mat-stroked-button color="accent" (click)="mute(r)">
            <mat-icon>volume_off</mat-icon> Muten
          </button>
          <button mat-stroked-button (click)="close(r)">
            <mat-icon>close</mat-icon> Schließen
          </button>
        </div>
      </mat-card>
    </div>
  `
})
export class ReportsComponent {
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly reports = signal<Report[]>([]);
  readonly total = signal(0);
  readonly loading = signal(true);
  private filter: ReportStatusFilter = 'active';

  constructor() { this.refresh(); }

  onTab(idx: number) {
    this.filter = (['active', 'open', 'claimed', 'closed', 'all'] as ReportStatusFilter[])[idx];
    this.refresh();
  }

  refresh() {
    this.loading.set(true);
    this.api.reports(this.filter, 100, 0).subscribe({
      next: page => { this.reports.set(page.items); this.total.set(page.total); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  head(uuid: string) {
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/64`;
  }

  statusClass(r: Report) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (r.status === 'OPEN') return `${base} bg-orange-900/40 text-orange-300`;
    if (r.status === 'CLAIMED') return `${base} bg-eternal-900/40 text-eternal-300`;
    return `${base} bg-ink-700 text-ink-300`;
  }

  claim(r: Report) {
    // "Annehmen" = claim + sofort TP. Der Spigot-ActionPoller loggt
    // den Mod automatisch in /reportsystem ein und teleportiert ihn
    // ins Replay (oder live, falls keins existiert).
    this.api.claimReport(r.id).subscribe({
      next: () => {
        this.snack.open(`Report #${r.id} angenommen – TP wird ingame angefordert.`, 'OK', { duration: 3000 });
        this.api.teleportToReport(r.id).subscribe({
          error: e => this.snack.open(`TP-Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
        });
        this.refresh();
      },
      error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
    });
  }

  tp(r: Report) {
    this.api.teleportToReport(r.id).subscribe({
      next: () => this.snack.open('Teleport ingame angefordert.', 'OK', { duration: 2500 }),
      error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
    });
  }

  close(r: Report) {
    this.dialog.open<CloseReportDialogComponent, { reportId: number }, string>(
      CloseReportDialogComponent, { data: { reportId: r.id } }
    ).afterClosed().subscribe(resolution => {
      if (!resolution) return;
      this.api.closeReport(r.id, resolution).subscribe({
        next: () => { this.snack.open(`Report #${r.id} geschlossen`, 'OK', { duration: 2500 }); this.refresh(); },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }

  ban(r: Report) {
    this.dialog.open<BanFromReportDialogComponent, { reportId: number; targetName: string; reportReason?: string }, BanDialogResult>(
      BanFromReportDialogComponent,
      { data: { reportId: r.id, targetName: r.targetName, reportReason: r.reasonLabel } }
    ).afterClosed().subscribe(result => {
      if (!result) return;
      this.api.banFromReport(r.id, result).subscribe({
        next: res => {
          this.snack.open(`${r.targetName} gebannt (#${res.banId}), Report #${r.id} geschlossen.`, 'OK', { duration: 3500 });
          this.refresh();
        },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }

  mute(r: Report) {
    this.dialog.open<MuteFromReportDialogComponent, { reportId: number; targetName: string; reportReason?: string }, MuteDialogResult>(
      MuteFromReportDialogComponent,
      { data: { reportId: r.id, targetName: r.targetName, reportReason: r.reasonLabel } }
    ).afterClosed().subscribe(result => {
      if (!result) return;
      this.api.muteFromReport(r.id, result).subscribe({
        next: res => {
          this.snack.open(`${r.targetName} gemutet (#${res.muteId}), Report #${r.id} geschlossen.`, 'OK', { duration: 3500 });
          this.refresh();
        },
        error: e => this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 })
      });
    });
  }
}
