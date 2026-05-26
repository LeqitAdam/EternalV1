import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';

interface MutePreset {
  label: string;
  durationSeconds: number;   // -1 = permanent
  message: string;
}

/** Typical mute durations — chat-violations are usually shorter than
 *  full bans. The mod can still pick "Sonstiges" for any custom
 *  duration the presets don't cover. */
const MUTE_PRESETS: MutePreset[] = [
  { label: 'Spam / Caps',            durationSeconds: 10 * 60,       message: '10 Minuten Mute: Spam' },
  { label: 'Beleidigung im Chat',    durationSeconds: 60 * 60,       message: '1 Stunde Mute: Beleidigung' },
  { label: 'Werbung / Advertising',  durationSeconds: 60 * 60,       message: '1 Stunde Mute: Werbung' },
  { label: 'Schwere Beleidigung',    durationSeconds: 24 * 3600,     message: '24 Stunden Mute: schwere Beleidigung' },
  { label: 'Trolling im Chat',       durationSeconds: 3 * 3600,      message: '3 Stunden Mute: Trolling' },
  { label: 'Wiederholtes Spammen',   durationSeconds: 3 * 86400,     message: '3 Tage Mute: wiederholtes Spammen' },
  { label: 'Hassrede / Diskriminierung', durationSeconds: 7 * 86400, message: '7 Tage Mute: Hassrede' },
  { label: 'Sehr schwere Beleidigung', durationSeconds: -1,          message: 'Permanenter Mute: extreme Beleidigung' }
];

export interface MuteDialogResult {
  reasonLabel: string;
  durationSeconds: number;
  message: string;
}

@Component({
  selector: 'et-mute-from-report-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatSelectModule
  ],
  template: `
    <h2 mat-dialog-title>{{ data.targetName }} muten (Report #{{ data.reportId }})</h2>
    <mat-dialog-content class="!min-w-[420px]">
      <div *ngIf="data.reportReason" class="mb-3 text-sm text-ink-300">
        Report-Grund: <span class="text-cyan-300">{{ data.reportReason }}</span>
        <span *ngIf="matchedPreset" class="text-ink-400"> — Preset wurde vorausgewählt</span>
        <span *ngIf="!matchedPreset" class="text-ink-400"> — kein matching Preset, in &quot;Sonstiges&quot; übernommen</span>
      </div>

      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Mute-Grund</mat-label>
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
        <mat-label>Nachricht an den Spieler</mat-label>
        <textarea matInput rows="2" [(ngModel)]="message"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button color="accent" [disabled]="!resolved()" (click)="submit()">
        Muten
      </button>
    </mat-dialog-actions>
  `
})
export class MuteFromReportDialogComponent {
  readonly presets = MUTE_PRESETS;
  readonly customPreset: MutePreset = { label: '', durationSeconds: 0, message: '' };
  readonly matchedPreset: boolean;

  selected: MutePreset;
  customLabel = '';
  customSeconds = 3600;
  message: string;

  constructor(
    public ref: MatDialogRef<MuteFromReportDialogComponent, MuteDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: { reportId: number; targetName: string; reportReason?: string }
  ) {
    const wanted = (data.reportReason ?? '').trim().toLowerCase();
    const hit = wanted
        ? MUTE_PRESETS.find(p => p.label.toLowerCase() === wanted)
        : null;
    if (hit) {
      this.selected = hit;
      this.matchedPreset = true;
      this.message = hit.message;
    } else if (wanted) {
      this.selected = this.customPreset;
      this.customLabel = data.reportReason ?? '';
      this.message = data.reportReason ?? '';
      this.matchedPreset = false;
    } else {
      this.selected = MUTE_PRESETS[0];
      this.message = MUTE_PRESETS[0].message;
      this.matchedPreset = false;
    }
  }

  onPresetChange() {
    if (this.selected !== this.customPreset) {
      this.message = this.selected.message;
    }
  }

  resolved(): MuteDialogResult | null {
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
