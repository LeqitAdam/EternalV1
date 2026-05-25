import { Component, Inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

/** Input contract for the dialog: list of pre-configured templates from
 *  /reasons.appealShortenTemplates and the applicant's current ban id
 *  (only used in the title bar). */
export interface AppealShortenDialogData {
  applicantName: string;
  templates: Array<{ id: string; label: string; durationSeconds: number; message: string }>;
}

/** Result the dialog returns on confirm. {@code null} == user cancelled. */
export interface AppealShortenResult {
  remainingSeconds: number;
  message: string;
}

/**
 * Two-field dialog the moderator fills in when picking the third appeal
 * decision path ("Verkürzen"). Default values come from a template
 * dropdown so the most common cases are one click + confirm; the mod can
 * still edit the duration or message before sending.
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
        <mat-label>Restliche Dauer in Sekunden (0 = sofort entbannen)</mat-label>
        <input matInput type="number" min="0" [(ngModel)]="seconds" />
        <mat-hint>Aktuell: {{ formatHuman(seconds()) }}</mat-hint>
      </mat-form-field>

      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Nachricht an den Spieler</mat-label>
        <textarea matInput rows="4" [(ngModel)]="message"></textarea>
        <mat-hint>Erscheint im Dashboard + Bann-Screen.</mat-hint>
      </mat-form-field>

    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button (click)="cancel()">Abbrechen</button>
      <button mat-flat-button color="primary" (click)="confirm()"
              [disabled]="!message().trim() || seconds() < 0">Verkürzen</button>
    </mat-dialog-actions>
  `
})
export class AppealShortenDialogComponent {
  readonly seconds = signal<number>(0);
  readonly message = signal<string>('');
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
    this.seconds.set(t.durationSeconds);
    this.message.set(t.message);
  }

  formatHuman(s: number): string {
    if (s <= 0) return 'sofort entbannen';
    const d = Math.floor(s / 86400);
    const h = Math.floor((s % 86400) / 3600);
    const m = Math.floor((s % 3600) / 60);
    const parts: string[] = [];
    if (d) parts.push(`${d}d`);
    if (h) parts.push(`${h}h`);
    if (m) parts.push(`${m}m`);
    return parts.length ? parts.join(' ') : `${s}s`;
  }

  cancel() { this.ref.close(); }
  confirm() {
    this.ref.close({ remainingSeconds: this.seconds(), message: this.message().trim() });
  }
}
