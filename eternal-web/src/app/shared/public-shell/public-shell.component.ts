import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * Bare-bones Shell fuer die /appeal-Seite: kein Sidebar, kein Login-Zwang.
 * Gebannte Spieler kommen so an die Antrag-Form ohne sich erst verlinken
 * zu muessen.
 */
@Component({
  selector: 'et-public-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, MatButtonModule, MatIconModule],
  template: `
    <div class="min-h-screen bg-ink-900">
      <header class="border-b border-ink-700 bg-ink-800">
        <div class="max-w-4xl mx-auto p-4 flex items-center justify-between">
          <div class="text-2xl font-bold">
            <span class="text-eternal-400">Eternal</span>
            <span class="text-ink-300 mx-1">»</span>
            <span class="text-ink-300 text-sm">Entbannungsantrag</span>
          </div>
          <a mat-stroked-button routerLink="/login">
            <mat-icon>login</mat-icon> Login
          </a>
        </div>
      </header>
      <main class="p-8"><router-outlet /></main>
    </div>
  `
})
export class PublicShellComponent {}
