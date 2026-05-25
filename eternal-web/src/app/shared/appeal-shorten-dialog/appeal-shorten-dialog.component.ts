import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

/** Input contract: list of templates from /reasons.appealShortenTemplates. */
export interface AppealShortenDialogData {
  applicantName: string;
  templates: Array<{ id: string; label: string; durationSeconds: number; message: string }>;
}

/** Result on confirm — duration is a DurationParser-compatible string
 *  (1d / 6h / 30m / permanent). */
export interface AppealShortenResult {
  duration: string;
}

/**
 * Verkürzen-Dialog. Plain properties (kein Signal) damit two-way-binding
 * mit ngModel + mat-input einwandfrei funktioniert — Signals + ngModel
 * vertragen sich nicht ohne extra Wiring.
 */
@Component({
  selector: 'et-appeal-shorten-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Antrag verkürzen — {{ data.applicantName }}</h2>
    <mat-dialog-content class="!pt-2 space-y-4">

      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Vorlage wählen</mat-label>
        <mat-select [(ngModel)]="selectedTemplateId" (selectionChange)="applyTemplate()">
          <mat-option *ngFor="let t of data.templates" [value]="t.id">
            {{ t.label }}
          </mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Restliche Dauer</mat-label>
        <input matInput [(ngModel)]="duration" placeholder="z.B. 1d, 6h, 30m, permanent" />
        <mat-hint>
          Syntax: <code>1d</code>=1&nbsp;Tag, <code>6h</code>=6&nbsp;Std., <code>30m</code>=30&nbsp;Min.,
          <code>0s</code>=sofort entbannen, <code>permanent</code>=nicht ändern.
        </mat-hint>
        <mat-error *ngIf="!isValid()">Ungültiges Format. Beispiele: <code>1d</code>, <code>12h</code>, <code>30m</code>, <code>permanent</code>.</mat-error>
      </mat-form-field>

      <div class="text-sm text-ink-300" *ngIf="isValid() && parsedSeconds() >= 0">
        Resultat: Bann läuft in {{ humanise(parsedSeconds()) }} ab.
      </div>
      <div class="text-sm text-ink-300" *ngIf="isValid() && parsedSeconds() < 0">
        Resultat: permanent — Bann wird nicht verkürzt (nur Antrag-Status setzen).
      </div>

    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-flat-button color="primary" (click)="confirm()" [disabled]="!isValid()">
        Verkürzen
      </button>
    </mat-dialog-actions>
  `
})
export class AppealShortenDialogComponent {
  duration = '0s';
  selectedTemplateId = '';

  constructor(
    @Inject(MAT_DIALOG_DATA) public readonly data: AppealShortenDialogData,
    private readonly ref: MatDialogRef<AppealShortenDialogComponent, AppealShortenResult>
  ) {
    if (data.templates.length > 0) {
      this.selectedTemplateId = data.templates[0].id;
      this.applyTemplate();
    }
  }

  applyTemplate() {
    const t = this.data.templates.find(x => x.id === this.selectedTemplateId);
    if (!t) return;
    this.duration = this.secondsToString(t.durationSeconds);
  }

  /** Same DurationParser-Syntax wie Backend — 1d, 6h, 30m, 7d… etc.
   *  Kombinationen wie "1d 12h" werden NICHT unterstützt (würde im
   *  Backend zwar parsen, aber Templates erzeugen sie nicht). */
  parsedSeconds(): number {
    const s = this.duration.trim().toLowerCase();
    if (!s) return Number.NaN;
    if (s === 'permanent' || s === 'perm' || s === 'forever' || s === '-1') return -1;
    const m = s.match(/^(\d+)([smhdwy])$/);
    if (!m) return Number.NaN;
    const value = Number(m[1]);
    const mult: Record<string, number> = { s: 1, m: 60, h: 3600, d: 86400, w: 604800, y: 31536000 };
    return value * mult[m[2]];
  }

  isValid(): boolean {
    return !Number.isNaN(this.parsedSeconds());
  }

  humanise(s: number): string {
    if (s <= 0) return 'sofort';
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const parts: string[] = [];
    if (d) parts.push(`${d}d`);
    if (h) parts.push(`${h}h`);
    if (m) parts.push(`${m}m`);
    return parts.length ? parts.join(' ') : `${s}s`;
  }

  private secondsToString(s: number): string {
    if (s < 0) return 'permanent';
    if (s === 0) return '0s';
    if (s % 86400 === 0) return `${s / 86400}d`;
    if (s % 3600 === 0)  return `${s / 3600}h`;
    if (s % 60 === 0)    return `${s / 60}m`;
    return `${s}s`;
  }

  cancel() { this.ref.close(); }
  confirm() {
    if (!this.isValid()) return;
    this.ref.close({ duration: this.duration.trim() });
  }
}
