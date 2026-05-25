import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';

interface BanPreset {
  label: string;
  durationSeconds: number;   // -1 = permanent
  message: string;
}

const BAN_PRESETS: BanPreset[] = [
  { label: 'Hacking / Cheating',     durationSeconds: -1,           message: 'Permanenter Bann: Hacking / Cheating' },
  { label: 'Griefing',                durationSeconds: 30 * 86400,   message: '30 Tage Bann: Griefing' },
  { label: 'Bug Abuse',               durationSeconds: 14 * 86400,   message: '14 Tage Bann: Bug Abuse' },
  { label: 'Schwere Beleidigung',     durationSeconds: 7 * 86400,    message: '7 Tage Bann: Schwere Beleidigung' },
  { label: 'Werbung',                 durationSeconds: 14 * 86400,   message: '14 Tage Bann: Werbung' },
  { label: 'Scamming / Betrug',       durationSeconds: 30 * 86400,   message: '30 Tage Bann: Scamming' },
  { label: 'Bann-Umgehung',           durationSeconds: -1,           message: 'Permanenter Bann: Bann-Umgehung' }
];

export interface BanDialogResult {
  reasonLabel: string;
  durationSeconds: number;
  message: string;
}

@Component({
  selector: 'et-ban-from-report-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatSelectModule, MatCheckboxModule
  ],
  template: `
    <h2 mat-dialog-title>{{ data.targetName }} bannen (Report #{{ data.reportId }})</h2>
    <mat-dialog-content class="!min-w-[420px]">
      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Bann-Grund</mat-label>
        <mat-select [(ngModel)]="selected" (selectionChange)="onPresetChange()">
          <mat-option *ngFor="let p of presets" [value]="p">{{ p.label }} ({{ formatDuration(p.durationSeconds) }})</mat-option>
          <mat-option [value]="customPreset">Sonstiges (eigene Werte)</mat-option>
        </mat-select>
      </mat-form-field>

      <ng-container *ngIf="selected === customPreset">
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
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button color="warn" [disabled]="!resolved()" (click)="submit()">
        Bannen
      </button>
    </mat-dialog-actions>
  `
})
export class BanFromReportDialogComponent {
  readonly presets = BAN_PRESETS;
  readonly customPreset: BanPreset = { label: '', durationSeconds: 0, message: '' };

  selected: BanPreset = BAN_PRESETS[0];
  customLabel = '';
  customSeconds = 86400;
  message = BAN_PRESETS[0].message;

  constructor(
    public ref: MatDialogRef<BanFromReportDialogComponent, BanDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: { reportId: number; targetName: string }
  ) {}

  onPresetChange() {
    if (this.selected !== this.customPreset) {
      this.message = this.selected.message;
    }
  }

  resolved(): BanDialogResult | null {
    if (this.selected === this.customPreset) {
      if (!this.customLabel.trim()) return null;
      return {
        reasonLabel: this.customLabel.trim(),
        durationSeconds: this.customSeconds,
        message: this.message.trim() || this.customLabel.trim()
      };
    }
    return {
      reasonLabel: this.selected.label,
      durationSeconds: this.selected.durationSeconds,
      message: this.message.trim() || this.selected.message
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
