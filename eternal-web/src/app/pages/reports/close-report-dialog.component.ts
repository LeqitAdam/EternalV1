import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';

const PRESET_RESOLUTIONS = [
  'Bearbeitet — Spieler verwarnt',
  'Bearbeitet — keine Regelverletzung',
  'Bearbeitet — Spieler war schon offline',
  'Bearbeitet — Duplikat eines anderen Reports',
  'Sonstiges (eigene Begründung)'
];

@Component({
  selector: 'et-close-report-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatSelectModule
  ],
  template: `
    <h2 mat-dialog-title>Report #{{ data.reportId }} schließen</h2>
    <mat-dialog-content class="!min-w-[400px]">
      <mat-form-field appearance="outline" class="w-full">
        <mat-label>Begründung</mat-label>
        <mat-select [(ngModel)]="preset">
          <mat-option *ngFor="let r of presets" [value]="r">{{ r }}</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field *ngIf="preset === custom" appearance="outline" class="w-full">
        <mat-label>Eigene Begründung</mat-label>
        <textarea matInput rows="3" [(ngModel)]="customText"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button color="warn" [disabled]="!resolution()" (click)="ref.close(resolution())">
        Schließen
      </button>
    </mat-dialog-actions>
  `
})
export class CloseReportDialogComponent {
  readonly presets = PRESET_RESOLUTIONS;
  readonly custom = PRESET_RESOLUTIONS[PRESET_RESOLUTIONS.length - 1];

  preset = PRESET_RESOLUTIONS[0];
  customText = '';

  constructor(
    public ref: MatDialogRef<CloseReportDialogComponent, string>,
    @Inject(MAT_DIALOG_DATA) public data: { reportId: number }
  ) {}

  resolution(): string {
    return this.preset === this.custom ? this.customText.trim() : this.preset;
  }
}
