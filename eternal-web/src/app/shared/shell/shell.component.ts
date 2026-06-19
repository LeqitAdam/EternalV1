import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { AuthService } from '../../core/auth.service';
import { ApiService } from '../../core/api.service';

@Component({
  selector: 'et-shell',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive, RouterOutlet, MatIconModule, MatButtonModule],
  template: `
    <div class="flex h-screen">
      <aside class="w-60 bg-ink-800 border-r border-ink-700 flex flex-col">
        <div class="p-5 border-b border-ink-700">
          <div class="text-2xl font-bold text-eternal-400">Eternal</div>
          <div class="text-xs text-ink-300">Dashboard</div>
        </div>

        <nav class="flex-1 p-3 space-y-1">
          <a routerLink="/me" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>person</mat-icon><span>Mein Konto</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.team')" routerLink="/access-requests"
             routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>add_moderator</mat-icon><span>Rechte bestellen</span>
          </a>
          <!-- Each item gates on its own permission so e.g. eternal.report.handle
               alone reveals Reports — no coarse "is staff" lump. -->
          <a *ngIf="auth.hasPerm('eternal.web.dashboard')" routerLink="/dashboard" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>dashboard</mat-icon><span>Übersicht</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.report.handle')" routerLink="/reports" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>report</mat-icon><span>Reports</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.web.dashboard')" routerLink="/bans" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>gavel</mat-icon><span>Aktive Strafen</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.web.player.view')" routerLink="/players" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>person_search</mat-icon><span>Spieler</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.web.chatlogs')" routerLink="/chat-logs" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>forum</mat-icon><span>Chat-Logs</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.web.dashboard')" routerLink="/appeals" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>contact_support</mat-icon><span>Entbannungsanträge</span>
          </a>
          <a *ngIf="auth.hasPerm('eternal.web.admin')" routerLink="/active-users" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
            <mat-icon>groups</mat-icon><span>Aktive User</span>
          </a>
          <!-- Admin section divider + permission / user management. -->
          <ng-container *ngIf="auth.hasPerm('eternal.web.admin')">
            <div class="px-3 pt-4 pb-1 text-xs uppercase tracking-wide text-ink-400">Administration</div>
            <a routerLink="/admin/users" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
              <mat-icon>manage_accounts</mat-icon><span>Benutzer &amp; Ränge</span>
            </a>
            <a routerLink="/admin/permissions" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
              <mat-icon>security</mat-icon><span>Rollen &amp; Rechte</span>
            </a>
            <a routerLink="/admin/permission-requests" routerLinkActive="bg-ink-700 text-eternal-300" class="nav-item">
              <mat-icon>inbox</mat-icon><span>Rechte-Anfragen</span>
            </a>
          </ng-container>
        </nav>

        <div class="p-3 border-t border-ink-700">
          <div class="flex items-center gap-3 p-2">
            <div class="w-8 h-8 rounded-full bg-eternal-600 flex items-center justify-center font-bold">
              {{ initial() }}
            </div>
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium truncate">{{ auth.me()?.name }}</div>
            </div>
            <button mat-icon-button (click)="logout()" matTooltip="Logout">
              <mat-icon>logout</mat-icon>
            </button>
          </div>
        </div>
      </aside>

      <main class="flex-1 overflow-y-auto p-8">
        <router-outlet />
      </main>
    </div>
  `,
  styles: [`
    :host { display: block; height: 100%; }
    .nav-item {
      display: flex; align-items: center; gap: .75rem;
      padding: .6rem .75rem; border-radius: .5rem; cursor: pointer;
      color: rgb(180,180,200); text-decoration: none; font-size: .9rem;
    }
    .nav-item:hover { background: rgb(28,28,37); color: white; }
    .nav-item mat-icon { font-size: 20px; width: 20px; height: 20px; }
  `]
})
export class ShellComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);

  constructor() {
    // Re-fetch /me on every app load so permission changes (e.g. a freshly
    // granted eternal.team) take effect on reload — not only after a re-login.
    if (this.auth.isAuthenticated()) {
      this.api.me().subscribe({ next: me => this.auth.updateMe(me), error: () => {} });
    }
  }

  initial() {
    return (this.auth.me()?.name?.[0] ?? '?').toUpperCase();
  }

  isStaff() {
    return this.auth.isStaff();
  }

  isAdmin() {
    return this.auth.isAdmin();
  }

  logout() {
    this.api.logout().subscribe({ next: () => this.auth.logout(), error: () => this.auth.logout() });
  }
}
