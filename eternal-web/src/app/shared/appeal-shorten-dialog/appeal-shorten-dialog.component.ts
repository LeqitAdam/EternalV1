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

/** Hard-coded fallback templates so the dropdown is never empty, even
 *  when the /reasons endpoint hasn't shipped them yet (old API JAR,
 *  empty reasons.yml, etc.). Module-level so the constructor doesn't
 *  rebuild the array on every dialog open. */
const DEFAULT_TEMPLATES: ReadonlyArray<{ id: string; label: string; durationSeconds: number; message: string }> = [
  { id: 'immediate', label: 'Sofort entbannen',         durationSeconds: 0,        message: '' },
  { id: '1h',        label: 'Auf 1 Stunde verkürzen',   durationSeconds: 3600,     message: '' },
  { id: '6h',        label: 'Auf 6 Stunden verkürzen',  durationSeconds: 21600,    message: '' },
  { id: '1d',        label: 'Auf 1 Tag verkürzen',      durationSeconds: 86400,    message: '' },
  { id: '3d',        label: 'Auf 3 Tage verkürzen',     durationSeconds: 259200,   message: '' },
  { id: '7d',        label: 'Auf 1 Woche verkürzen',    durationSeconds: 604800,   message: '' },
  { id: '14d',       label: 'Auf 2 Wochen verkürzen',   durationSeconds: 1209600,  message: '' },
  { id: '30d',       label: 'Auf 30 Tage verkürzen',    durationSeconds: 2592000,  message: '' }
];

/**
 * Verkürzen-Dialog. Pattern matches close-report-dialog (the only
 * other MatSelect dialog that demonstrably works) — Constructor
 * uses {@code @Inject(MAT_DIALOG_DATA) public data}, no readonly
 * re-assignment, templates live as their own property. Avoiding the
 * earlier `public readonly data` + manual reassignment which seemed
 * to confuse the MatSelect overlay positioner (dropdown highlighted
 * pink on click but panel never opened).
 */
@Component({
  selector: 'et-appeal-shorten-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatDialogModule,
    MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Antrag verkürzen — {{ data.applicantName }}</h2>
    <mat-dialog-content class="!pt-2 !min-w-[420px] space-y-4">

      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Vorlage wählen</mat-label>
        <mat-select [(ngModel)]="selectedTemplateId" (selectionChange)="applyTemplate()">
          <mat-option *ngFor="let t of templates" [value]="t.id">
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
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button color="primary" (click)="confirm()" [disabled]="!isValid()">
        Verkürzen
      </button>
    </mat-dialog-actions>
  `
})
export class AppealShortenDialogComponent {
  /** Effective template list — API-provided when available, otherwise
   *  the hardcoded defaults. Bound directly into the *ngFor. */
  readonly templates: ReadonlyArray<{ id: string; label: string; durationSeconds: number; message: string }>;

  selectedTemplateId = '';
  duration = '0s';

  constructor(
    public ref: MatDialogRef<AppealShortenDialogComponent, AppealShortenResult>,
    @Inject(MAT_DIALOG_DATA) public data: AppealShortenDialogData
  ) {
    this.templates = (data.templates && data.templates.length > 0)
        ? data.templates
        : DEFAULT_TEMPLATES;
    if (this.templates.length > 0) {
      this.selectedTemplateId = this.templates[0].id;
      this.duration = this.secondsToString(this.templates[0].durationSeconds);
    }
  }

  applyTemplate() {
    const t = this.templates.find(x => x.id === this.selectedTemplateId);
    if (!t) return;
    this.duration = this.secondsToString(t.durationSeconds);
  }

  /** Same DurationParser-syntax as backend — 1d, 6h, 30m, 7d… etc.
   *  Combinations like "1d 12h" are NOT supported (the backend
   *  would parse them, but no template emits one). */
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

  confirm() {
    if (!this.isValid()) return;
    this.ref.close({ duration: this.duration.trim() });
  }
}
