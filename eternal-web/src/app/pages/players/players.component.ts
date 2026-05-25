import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'et-players',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatIconModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Spieler-Suche</h1>
    <p class="text-ink-300 mb-6">Tippe den Namen, um Bann-Historie, Reports und Tier-Info zu sehen.</p>

    <mat-card class="p-6">
      <form (submit)="search(); $event.preventDefault();" class="flex gap-3 items-start">
        <mat-form-field appearance="outline" class="flex-1">
          <mat-label>Spielername</mat-label>
          <input matInput [(ngModel)]="name" name="name" autocomplete="off" />
        </mat-form-field>
        <button mat-flat-button color="primary" [disabled]="!name" type="submit" class="!mt-1">
          <mat-icon>search</mat-icon> Suchen
        </button>
      </form>
    </mat-card>
  `
})
export class PlayersComponent {
  private readonly router = inject(Router);
  name = '';

  search() {
    if (!this.name.trim()) return;
    this.router.navigate(['/players', this.name.trim()]);
  }
}
