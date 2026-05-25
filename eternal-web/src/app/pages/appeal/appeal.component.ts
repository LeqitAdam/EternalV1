import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { Punishment } from '../../core/models';

@Component({
  selector: 'et-appeal',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule,
    MatInputModule, MatButtonModule, MatIconModule, MatSnackBarModule
  ],
  template: `
    <div class="max-w-2xl mx-auto">
      <h1 class="text-3xl font-bold mb-2">Entbannungsantrag stellen</h1>
      <p class="text-ink-300 mb-6" *ngIf="auth.isAuthenticated(); else publicHint">
        Dein Account ist verlinkt — wir haben die Bann-Daten schon ausgefuellt.
      </p>
      <ng-template #publicHint>
        <p class="text-ink-300 mb-6">
          Du bist gebannt und kannst dich nicht ingame verlinken? Trag deinen
          Spielernamen und die Bann-ID aus dem Kick-Screen unten manuell ein.
        </p>
      </ng-template>

      <mat-card class="p-6">
        <ng-container *ngIf="auth.isAuthenticated() && activeBan() as ban">
          <div class="p-3 mb-4 bg-red-900/30 border border-red-700/40 rounded text-sm">
            <strong class="text-red-300">Aktiver Bann #{{ ban.id }}</strong>:
            {{ ban.reasonLabel }}
            <span *ngIf="ban.expiresAt; else perm"> bis {{ ban.expiresAt | date:'yyyy-MM-dd HH:mm' }}</span>
            <ng-template #perm> · permanent</ng-template>
          </div>
        </ng-container>

        <div *ngIf="auth.isAuthenticated() && !activeBan() && !loading()"
             class="p-3 mb-4 bg-green-900/30 border border-green-700/40 rounded text-sm text-green-200">
          <mat-icon class="!align-middle">check_circle</mat-icon>
          Du hast keinen aktiven Bann — kein Antrag noetig.
        </div>

        <form *ngIf="showForm()" (submit)="submit(); $event.preventDefault();" class="space-y-4">
          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Spielername</mat-label>
            <input matInput [(ngModel)]="form.playerName" name="playerName"
                   [readonly]="auth.isAuthenticated()" required />
            <mat-icon matSuffix *ngIf="auth.isAuthenticated()">lock</mat-icon>
          </mat-form-field>

          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Bann-ID (aus dem Kick-Screen)</mat-label>
            <input matInput type="number" [(ngModel)]="form.banId" name="banId"
                   [readonly]="auth.isAuthenticated() && !!activeBan()" required />
            <mat-icon matSuffix *ngIf="auth.isAuthenticated() && !!activeBan()">lock</mat-icon>
          </mat-form-field>

          <mat-form-field appearance="outline" class="w-full">
            <mat-label>Warum sollten wir dich entbannen?</mat-label>
            <textarea matInput rows="6" [(ngModel)]="form.text" name="text" required
                      placeholder="Beschreib, was passiert ist und warum du entbannt werden willst..."></textarea>
          </mat-form-field>

          <button mat-flat-button color="primary" type="submit"
                  [disabled]="submitting() || !valid()" class="w-full">
            <mat-icon>send</mat-icon> Antrag senden
          </button>
        </form>

        <div *ngIf="success()" class="mt-4 p-3 bg-green-900/30 border border-green-700/40 rounded text-green-200">
          <strong>Antrag #{{ success() }} eingereicht.</strong>
          Ein Admin wird sich darum kuemmern.
        </div>
      </mat-card>
    </div>
  `
})
export class AppealComponent implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly snack = inject(MatSnackBar);

  readonly activeBan = signal<Punishment | null>(null);
  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly success = signal<number | null>(null);

  form = { playerName: '', banId: null as number | null, text: '' };

  ngOnInit() {
    if (this.auth.isAuthenticated()) {
      const me = this.auth.me();
      this.form.playerName = me?.name ?? '';
      this.api.myActiveBan().subscribe({
        next: ban => {
          this.activeBan.set(ban);
          if (ban) this.form.banId = ban.id;
          this.loading.set(false);
        },
        error: () => this.loading.set(false)
      });
    } else {
      this.loading.set(false);
    }
  }

  valid() {
    return this.form.playerName.trim() !== ''
        && this.form.banId !== null
        && this.form.text.trim().length >= 10;
  }

  showForm() {
    if (!this.auth.isAuthenticated()) return true;
    return !!this.activeBan();
  }

  submit() {
    if (!this.valid()) return;
    this.submitting.set(true);
    const obs: Observable<{ ok: boolean; appealId: number }> = this.auth.isAuthenticated()
        ? this.api.fileAppeal(this.form.text.trim())
        : this.api.filePublicAppeal({
            playerName: this.form.playerName.trim(),
            banId: this.form.banId!,
            text: this.form.text.trim()
          });
    obs.subscribe({
      next: (res: { ok: boolean; appealId: number }) => {
        this.success.set(res.appealId);
        this.submitting.set(false);
        this.form.text = '';
        this.snack.open('Antrag eingereicht.', 'OK', { duration: 3000 });
      },
      error: (e: HttpErrorResponse) => {
        this.submitting.set(false);
        this.snack.open(`Fehler: ${e.error?.error ?? e.message}`, 'OK', { duration: 4000 });
      }
    });
  }
}
