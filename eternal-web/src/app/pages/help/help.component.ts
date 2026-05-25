import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';

/**
 * Login-Hilfe. Erreichbar ohne Login. Bietet die zwei wahrscheinlichsten
 * Probleme als grosse Karten zur Auswahl.
 */
@Component({
  selector: 'et-help',
  standalone: true,
  imports: [CommonModule, RouterLink, MatCardModule, MatIconModule, MatButtonModule],
  template: `
    <div class="max-w-3xl mx-auto">
      <h1 class="text-3xl font-bold mb-2">Einloggen nicht möglich?</h1>
      <p class="text-ink-200 mb-8">
        Such dir aus, was auf dich zutrifft. Wir helfen dir weiter.
      </p>

      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <mat-card class="p-6 cursor-pointer hover:bg-ink-700/40 transition-colors"
                  routerLink="/appeal">
          <div class="flex items-start gap-4">
            <div class="w-12 h-12 rounded-full bg-red-900/40 flex items-center justify-center flex-shrink-0">
              <mat-icon class="!text-red-300">gavel</mat-icon>
            </div>
            <div>
              <h2 class="text-xl font-semibold mb-1">Ich bin gebannt</h2>
              <p class="text-sm text-ink-200">
                Du kannst dich ingame nicht mit <code class="text-eternal-300">/eternal link</code>
                verknüpfen, weil du gebannt bist? Stell einen Entbannungsantrag.
              </p>
              <button mat-flat-button color="primary" class="mt-4">
                <mat-icon>arrow_forward</mat-icon> Entbannungsantrag stellen
              </button>
            </div>
          </div>
        </mat-card>

        <mat-card class="p-6">
          <div class="flex items-start gap-4">
            <div class="w-12 h-12 rounded-full bg-eternal-900/40 flex items-center justify-center flex-shrink-0">
              <mat-icon class="!text-eternal-300">support_agent</mat-icon>
            </div>
            <div class="flex-1">
              <h2 class="text-xl font-semibold mb-1">Allgemeine Probleme</h2>
              <p class="text-sm text-ink-200 mb-3">
                Account-Link funktioniert nicht, Server nicht erreichbar oder
                etwas ganz anderes? Schreib uns:
              </p>
              <div class="space-y-2 text-sm">
                <div class="flex items-center gap-2">
                  <mat-icon class="!text-ink-100">forum</mat-icon>
                  <span class="text-ink-100">Discord:</span>
                  <code class="text-eternal-300">discord.gg/eternal</code>
                </div>
                <div class="flex items-center gap-2">
                  <mat-icon class="!text-ink-100">mail</mat-icon>
                  <span class="text-ink-100">Mail:</span>
                  <code class="text-eternal-300">support&#64;adambn.dev</code>
                </div>
              </div>
            </div>
          </div>
        </mat-card>
      </div>

      <div class="mt-8 text-center">
        <a mat-stroked-button routerLink="/login">
          <mat-icon>arrow_back</mat-icon> Zurück zum Login
        </a>
      </div>
    </div>
  `
})
export class HelpComponent {}
