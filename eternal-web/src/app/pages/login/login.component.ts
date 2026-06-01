import { Component, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { Subscription, interval, switchMap } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'et-login',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, MatTabsModule, MatInputModule, MatButtonModule,
    MatCardModule, MatProgressSpinnerModule, MatIconModule
  ],
  template: `
    <div class="min-h-screen flex items-center justify-center p-8 bg-ink-900">
      <mat-card class="w-full max-w-md p-8">
        <div class="text-center mb-6">
          <div class="text-4xl font-bold">
            <span class="text-eternal-400">Eternal</span>
            <span class="text-ink-300 mx-1">»</span>
            <span class="text-ink-300">Dashboard</span>
          </div>
          <p class="text-sm text-ink-300 mt-2">Mit deinem Minecraft-Account anmelden</p>
        </div>

        <mat-tab-group mat-stretch-tabs="false" mat-align-tabs="center">
          <mat-tab label="Account-Link">
            <div class="py-6 space-y-4">
              <ng-container *ngIf="!code()">
                <p class="text-sm text-ink-300">Generiere einen Code und gib ihn ingame mit
                  <code class="text-eternal-300">/eternal link &lt;code&gt;</code> ein.</p>
                <button mat-flat-button color="primary" class="w-full"
                        [disabled]="loading()" (click)="startLink()">
                  Code generieren
                </button>
              </ng-container>

              <ng-container *ngIf="code()">
                <div class="text-center py-4">
                  <div class="text-sm text-ink-300 mb-2">Dein Code:</div>
                  <div class="text-5xl font-mono font-bold tracking-widest text-eternal-300">
                    {{ code() }}
                  </div>
                  <div class="mt-4 text-sm text-ink-300 flex items-center justify-center gap-2">
                    <mat-spinner diameter="16" />
                    Warte auf Bestätigung ingame…
                  </div>
                  <div *ngIf="error()" class="mt-3 text-sm text-red-400">{{ error() }}</div>
                </div>
                <button mat-stroked-button class="w-full" (click)="cancel()">Abbrechen</button>
              </ng-container>
            </div>
          </mat-tab>

          <mat-tab label="API-Key">
            <div class="py-6 space-y-4">
              <p class="text-sm text-ink-300">
                Für Admins: trag den statischen API-Key aus
                <code class="text-eternal-300">api.yml</code> ein.
              </p>
              <mat-form-field appearance="outline" class="w-full">
                <mat-label>API-Key</mat-label>
                <input matInput type="password" [(ngModel)]="apiKey" autocomplete="off" />
              </mat-form-field>
              <button mat-flat-button color="primary" class="w-full"
                      [disabled]="!apiKey || loading()" (click)="useApiKey()">
                Einloggen
              </button>
              <div *ngIf="apiKeyError()" class="text-sm text-red-400">{{ apiKeyError() }}</div>
            </div>
          </mat-tab>
        </mat-tab-group>

        <div class="mt-4 pt-4 border-t border-ink-700 text-center">
          <a mat-button routerLink="/help" class="!text-ink-200 hover:!text-eternal-300">
            <mat-icon class="!align-middle text-base">help_outline</mat-icon>
            Einloggen nicht möglich?
          </a>
        </div>
      </mat-card>
    </div>
  `
})
export class LoginComponent implements OnDestroy {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly code = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  apiKey = '';
  readonly apiKeyError = signal<string | null>(null);

  private linkToken: string | null = null;
  private poll: Subscription | null = null;

  startLink() {
    this.loading.set(true);
    this.error.set(null);
    this.api.linkInit().subscribe({
      next: res => {
        this.code.set(res.code);
        this.linkToken = res.linkToken;
        this.loading.set(false);
        this.beginPolling();
      },
      error: err => {
        this.loading.set(false);
        this.error.set('API nicht erreichbar.');
        console.error(err);
      }
    });
  }

  private beginPolling() {
    this.poll?.unsubscribe();
    this.poll = interval(2000)
      .pipe(switchMap(() => this.api.linkStatus(this.linkToken!)))
      .subscribe({
        next: status => {
          if (status.status === 'CONFIRMED' && status.sessionToken && status.user) {
            this.poll?.unsubscribe();
            this.auth.loginWith(status.sessionToken, status.user);
            this.router.navigateByUrl('/dashboard');
          } else if (status.status === 'EXPIRED') {
            this.poll?.unsubscribe();
            this.error.set('Code abgelaufen — bitte einen neuen generieren.');
            this.code.set(null);
          }
        },
        error: () => {
          this.poll?.unsubscribe();
          this.error.set('Verbindung verloren.');
        }
      });
  }

  cancel() {
    this.poll?.unsubscribe();
    this.code.set(null);
    this.linkToken = null;
    this.error.set(null);
  }

  useApiKey() {
    this.loading.set(true);
    this.apiKeyError.set(null);
    // Use the API key as a session token directly. /me will validate.
    this.auth.loginWith(this.apiKey, { name: '...', role: 'MOD', uuid: null });
    this.api.me().subscribe({
      next: me => {
        this.auth.loginWith(this.apiKey, me);
        this.router.navigateByUrl('/dashboard');
      },
      error: () => {
        this.auth.logout(); // clears the trial token
        this.apiKeyError.set('API-Key ungültig.');
        this.loading.set(false);
      }
    });
  }

  ngOnDestroy() {
    this.poll?.unsubscribe();
  }
}
