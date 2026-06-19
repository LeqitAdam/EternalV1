import { Component, Inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';

/** A ban reason the mod can pick — sourced from reasons.yml via /reasons and
 *  filtered to the ones THIS mod may actually use (`usable`). The catch-all
 *  "Sonstiges" entry has id === null and means a free-form custom ban. */
interface BanReasonOption {
  id: number | null;
  label: string;
  durationSeconds: number;   // -1 = permanent
}

export interface BanDialogResult {
  reasonLabel: string;
  durationSeconds: number;
  message: string;
  /** Numeric reasons.yml id; omitted for the free-form custom path. */
  reasonId?: number;
}

@Component({
  selector: 'et-ban-from-report-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatSelectModule, MatProgressSpinnerModule
  ],
  template: `
    <h2 mat-dialog-title>{{ data.targetName }} bannen (Report #{{ data.reportId }})</h2>
    <mat-dialog-content class="!min-w-[420px]">
      <div *ngIf="loading()" class="flex justify-center py-8"><mat-spinner diameter="32" /></div>

      <ng-container *ngIf="!loading()">
        <div *ngIf="data.reportReason" class="mb-3 text-sm text-ink-300">
          Report-Grund: <span class="text-cyan-300">{{ data.reportReason }}</span>
          <span *ngIf="matchedReason" class="text-ink-400"> — Grund wurde vorausgewählt</span>
          <span *ngIf="!matchedReason" class="text-ink-400"> — kein passender Grund, in &quot;Sonstiges&quot; übernommen</span>
        </div>

        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Bann-Grund</mat-label>
          <mat-select [(ngModel)]="selected" (selectionChange)="onReasonChange()">
            <mat-option *ngFor="let r of options" [value]="r">{{ r.label }} ({{ formatDuration(r.durationSeconds) }})</mat-option>
            <mat-option [value]="customOption">Sonstiges (eigene Werte)</mat-option>
          </mat-select>
        </mat-form-field>

        <ng-container *ngIf="selected === customOption">
          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Label</mat-label>
            <input matInput [(ngModel)]="customLabel" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Dauer in Sekunden (-1 = permanent)</mat-label>
            <input matInput type="number" [(ngModel)]="customSeconds" />
          </mat-form-field>
        </ng-container>

        <mat-form-field appearance="outline" class="w-full">
          <mat-label>Kick-Nachricht</mat-label>
          <textarea matInput rows="2" [(ngModel)]="message"></textarea>
        </mat-form-field>
      </ng-container>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button color="warn" [disabled]="loading() || !resolved()" (click)="submit()">
        Bannen
      </button>
    </mat-dialog-actions>
  `
})
export class BanFromReportDialogComponent implements OnInit {
  /** Configured ban reasons the mod may use (filled from /reasons). */
  options: BanReasonOption[] = [];
  /** The catch-all custom entry (id === null). */
  readonly customOption: BanReasonOption = { id: null, label: '', durationSeconds: 0 };
  /** Did a configured reason match the report-reason label? Drives the hint. */
  matchedReason = false;

  readonly loading = signal(true);
  selected: BanReasonOption = this.customOption;
  customLabel = '';
  customSeconds = 86400;
  message = '';

  constructor(
    public ref: MatDialogRef<BanFromReportDialogComponent, BanDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: { reportId: number; targetName: string; reportReason?: string },
    private readonly api: ApiService
  ) {}

  ngOnInit() {
    this.api.reasons().subscribe({
      next: res => {
        // Only ban reasons the current mod is actually permitted to use.
        this.options = res.reasons
          .filter(r => r.type === 'BAN' && r.usable)
          .map(r => ({ id: r.id, label: r.label, durationSeconds: r.durationSeconds }));
        this.preselect();
        this.loading.set(false);
      },
      // On error fall back to the custom-only path so a mod is never blocked.
      error: () => { this.options = []; this.preselect(); this.loading.set(false); }
    });
  }

  /** Match the report's reason against a usable configured reason (case-insensitive
   *  label compare). Falls back to the custom entry pre-filled with the report's
   *  reason so the mod still gets a sensible default to tweak. */
  private preselect() {
    const wanted = (this.data.reportReason ?? '').trim().toLowerCase();
    const hit = wanted ? this.options.find(r => r.label.toLowerCase() === wanted) : undefined;
    if (hit) {
      this.selected = hit;
      this.matchedReason = true;
      this.message = hit.label;
    } else if (wanted) {
      this.selected = this.customOption;
      this.customLabel = this.data.reportReason ?? '';
      this.message = this.data.reportReason ?? '';
      this.matchedReason = false;
    } else if (this.options.length > 0) {
      this.selected = this.options[0];
      this.message = this.options[0].label;
      this.matchedReason = false;
    } else {
      this.selected = this.customOption;
      this.matchedReason = false;
    }
  }

  onReasonChange() {
    if (this.selected !== this.customOption) {
      this.message = this.selected.label;
    }
  }

  resolved(): BanDialogResult | null {
    if (this.selected === this.customOption) {
      if (!this.customLabel.trim()) return null;
      return {
        reasonLabel: this.customLabel.trim(),
        durationSeconds: this.customSeconds,
        message: this.message.trim() || this.customLabel.trim()
      };
    }
    return {
      reasonId: this.selected.id ?? undefined,
      reasonLabel: this.selected.label,
      durationSeconds: this.selected.durationSeconds,
      message: this.message.trim() || this.selected.label
    };
  }

  submit() {
    const r = this.resolved();
    if (r) this.ref.close(r);
  }

  formatDuration(s: number) {
    if (s < 0) return 'permanent';
    const days = Math.floor(s / 86400);
    if (days > 0) return `${days}d`;
    const hours = Math.floor(s / 3600);
    if (hours > 0) return `${hours}h`;
    return `${Math.floor(s / 60)}m`;
  }
}
