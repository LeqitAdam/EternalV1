import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { Punishment, UnbanAppeal } from '../../core/models';

@Component({
  selector: 'et-me',
  standalone: true,
  imports: [
    CommonModule, FormsModule, DatePipe, MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatProgressSpinnerModule, MatSnackBarModule
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Mein Konto</h1>
    <p class="text-ink-300 mb-6">Eingeloggt als {{ auth.me()?.name }}.</p>

    <div *ngIf="loading()" class="flex justify-center py-12"><mat-spinner /></div>

    <ng-container *ngIf="!loading()">
      <!-- Active Ban + Appeal -->
      <mat-card *ngIf="activeBan() as ban" class="p-6 mb-4 border-l-4 border-red-500">
        <h2 class="text-xl font-semibold text-red-300 mb-2">
          <mat-icon class="!align-middle">gavel</mat-icon> Du bist aktuell gebannt
        </h2>
        <div class="grid grid-cols-2 gap-3 text-sm mb-4">
          <div><span class="text-ink-300">Grund:</span> <span class="ml-2 font-medium">{{ ban.reasonLabel }}</span></div>
          <div><span class="text-ink-300">Seit:</span> <span class="ml-2">{{ ban.issuedAt | date:'yyyy-MM-dd HH:mm' }}</span></div>
          <div><span class="text-ink-300">Bis:</span>
            <span class="ml-2" *ngIf="ban.expiresAt; else perm">{{ ban.expiresAt | date:'yyyy-MM-dd HH:mm' }}</span>
            <ng-template #perm><span class="ml-2 text-red-400 font-semibold">permanent</span></ng-template>
          </div>
          <div><span class="text-ink-300">Bann-ID:</span> <span class="ml-2 font-mono">#{{ ban.id }}</span></div>
        </div>

        <ng-container *ngIf="pendingAppeal() as a; else appealForm">
          <div class="p-3 bg-orange-900/30 border border-orange-700/40 rounded">
            <strong class="text-orange-300">Entbannungsantrag #{{ a.id }} laeuft</strong>
            <div class="text-sm mt-1">Eingereicht am {{ a.createdAt | date:'yyyy-MM-dd HH:mm' }} — wird gerade gepruft.</div>
          </div>
        </ng-container>

        <ng-template #appealForm>
          <h3 class="text-lg font-semibold mb-2 mt-4">Entbannungsantrag stellen</h3>
          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Warum sollten wir dich entbannen?</mat-label>
            <textarea matInput rows="4" [(ngModel)]="appealText" name="appeal"
                      placeholder="Erklaer, was passiert ist und warum du entbannt werden willst..."></textarea>
          </mat-form-field>
          <button mat-flat-button color="primary" [disabled]="!appealText.trim() || submitting()"
                  (click)="submitAppeal()">
            <mat-icon>send</mat-icon> Antrag senden
          </button>
        </ng-template>
      </mat-card>

      <!-- Past appeals -->
      <mat-card class="p-6 mb-4" *ngIf="otherAppeals().length > 0">
        <h2 class="text-xl font-semibold mb-3">Frühere Anträge</h2>
        <div *ngFor="let a of otherAppeals()" class="py-2 border-b border-ink-700/40 text-sm">
          <div class="flex items-center gap-2">
            <span class="font-mono text-ink-300">#{{ a.id }}</span>
            <span [class]="appealBadge(a.status)">{{ a.status }}</span>
            <span class="text-ink-300 ml-auto">{{ a.createdAt | date:'yyyy-MM-dd HH:mm' }}</span>
          </div>
          <div class="text-ink-300/80 italic mt-1">"{{ a.text }}"</div>
          <div *ngIf="a.decisionReason" class="text-ink-300 text-xs mt-1">
            Entscheidung von {{ a.reviewerName }}: {{ a.decisionReason }}
          </div>
        </div>
      </mat-card>

      <!-- History -->
      <mat-card class="p-6">
        <h2 class="text-xl font-semibold mb-3">Deine Historie</h2>
        <div *ngIf="punishments().length === 0" class="text-ink-300 text-sm">Du hast eine saubere Weste.</div>
        <div *ngFor="let p of punishments()" class="py-2 border-b border-ink-700/30 text-sm flex items-center gap-3">
          <span [class]="kindBadge(p.type)">{{ p.type }}</span>
          <span class="font-mono text-ink-300">#{{ p.id }}</span>
          <span class="flex-1">{{ p.reasonLabel }}</span>
          <span class="text-ink-300 text-xs">{{ p.issuedAt | date:'yyyy-MM-dd HH:mm' }}</span>
          <span class="text-ink-300 text-xs" *ngIf="!p.active">aufgehoben</span>
        </div>
      </mat-card>
    </ng-container>
  `
})
export class MeComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly activeBan = signal<Punishment | null>(null);
  readonly punishments = signal<Punishment[]>([]);
  readonly appeals = signal<UnbanAppeal[]>([]);
  readonly loading = signal(true);
  readonly submitting = signal(false);
  appealText = '';

  ngOnInit() {
    let pending = 3;
    const done = () => { if (--pending === 0) this.loading.set(false); };

    this.api.myActiveBan().subscribe({
      next: b => { this.activeBan.set(b); done(); },
      error: () => { this.activeBan.set(null); done(); }
    });
    this.api.myPunishments().subscribe({ next: list => { this.punishments.set(list); done(); }, error: done });
    this.api.myAppeals().subscribe({ next: list => { this.appeals.set(list); done(); }, error: done });
  }

  pendingAppeal() {
    const ban = this.activeBan();
    if (!ban) return null;
    return this.appeals().find(a => a.banId === ban.id && a.status === 'PENDING') ?? null;
  }

  otherAppeals() {
    const ban = this.activeBan();
    return this.appeals().filter(a => !ban || a.banId !== ban.id || a.status !== 'PENDING');
  }

  submitAppeal() {
    if (!this.appealText.trim()) return;
    this.submitting.set(true);
    this.api.fileAppeal(this.appealText.trim()).subscribe({
      next: () => {
        this.snack.open('Antrag eingereicht. Ein Admin schaut sich das an.', 'OK', { duration: 3000 });
        this.appealText = '';
        this.api.myAppeals().subscribe(list => this.appeals.set(list));
        this.submitting.set(false);
      },
      error: e => {
        this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 });
        this.submitting.set(false);
      }
    });
  }

  kindBadge(kind: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (kind === 'BAN') return `${base} bg-red-900/40 text-red-300`;
    return `${base} bg-orange-900/40 text-orange-300`;
  }
  appealBadge(s: string) {
    const base = 'text-xs px-2 py-0.5 rounded font-medium';
    if (s === 'PENDING') return `${base} bg-orange-900/40 text-orange-300`;
    if (s === 'APPROVED') return `${base} bg-green-900/40 text-green-300`;
    return `${base} bg-ink-700 text-ink-300`;
  }
}
