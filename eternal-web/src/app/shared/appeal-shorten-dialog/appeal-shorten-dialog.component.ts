import { Component, Inject, signal } from '@angular/core';
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
 * Verkürzen-Dialog v2 — keine Nachricht mehr, Dauer als String (kein
 * Sekunden-Rechnen). Templates füllen die Dauer voraus; Mod kann sie
 * frei überschreiben.
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
          <code>0s</code>=sofort entbannen, <code>permanent</code>=nicht ändern (selten sinnvoll).
        </mat-hint>
      </mat-form-field>

    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-flat-button color="primary" (click)="confirm()"
              [disabled]="!duration().trim()">Verkürzen</button>
    </mat-dialog-actions>
  `
})
export class AppealShortenDialogComponent {
  readonly duration = signal<string>('0s');
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
    // Template-durationSeconds zurück in lesbaren String konvertieren —
    // der Mod kann ihn dann frei überschreiben.
    this.duration.set(this.secondsToString(t.durationSeconds));
  }

  /** 86400→"1d", 3600→"1h", 60→"1m", 0→"0s". */
  private secondsToString(s: number): string {
    if (s < 0) return 'permanent';
    if (s === 0) return '0s';
    if (s % 86400 === 0) return `${s / 86400}d`;
    if (s % 3600 === 0)  return `${s / 3600}h`;
    if (s % 60 === 0)    return `${s / 60}m`;
    return `${s}s`;
  }

  cancel() { this.ref.close(); }
  confirm() { this.ref.close({ duration: this.duration().trim() }); }
}
