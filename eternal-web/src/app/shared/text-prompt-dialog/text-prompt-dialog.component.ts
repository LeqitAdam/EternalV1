import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';

export interface TextPromptDialogData {
  title: string;
  label: string;
  initialValue?: string;
  placeholder?: string;
  confirmText?: string;
  confirmColor?: 'primary' | 'warn' | 'accent';
  multiline?: boolean;
}

/**
 * Drop-in replacement for window.prompt() with a Material dialog. Returns the
 * trimmed input (or undefined if cancelled).
 */
@Component({
  selector: 'et-text-prompt-dialog',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatDialogModule, MatFormFieldModule,
    MatInputModule, MatButtonModule
  ],
  template: `
    <h2 mat-dialog-title>{{ data.title }}</h2>
    <mat-dialog-content class="!min-w-[400px]">
      <mat-form-field appearance="outline" class="w-full">
        <mat-label>{{ data.label }}</mat-label>
        <textarea *ngIf="data.multiline; else single"
                  matInput rows="3" [(ngModel)]="value"
                  [placeholder]="data.placeholder ?? ''"></textarea>
        <ng-template #single>
          <input matInput [(ngModel)]="value" [placeholder]="data.placeholder ?? ''" />
        </ng-template>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="ref.close()">Abbrechen</button>
      <button mat-flat-button [color]="data.confirmColor ?? 'primary'"
              [disabled]="!value.trim()" (click)="ref.close(value.trim())">
        {{ data.confirmText ?? 'OK' }}
      </button>
    </mat-dialog-actions>
  `
})
export class TextPromptDialogComponent {
  value: string;

  constructor(
    public ref: MatDialogRef<TextPromptDialogComponent, string>,
    @Inject(MAT_DIALOG_DATA) public data: TextPromptDialogData
  ) {
    this.value = data.initialValue ?? '';
  }
}
