import { Injectable, signal, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Me } from './models';

const TOKEN_KEY = 'eternal-token';
const ME_KEY = 'eternal-me';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly router = inject(Router);

  private readonly _token = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  private readonly _me = signal<Me | null>(this.readMe());

  readonly token = this._token.asReadonly();
  readonly me = this._me.asReadonly();
  readonly isAuthenticated = computed(() => this._token() !== null);
  readonly isAdmin = computed(() => this._me()?.role === 'ADMIN');
  readonly isStaff = computed(() => {
    const r = this._me()?.role;
    return r === 'ADMIN' || r === 'MOD';
  });
  /** Mods + admins can pardon normal (non-admin-only) bans. */
  canPardon(): boolean { return this.isStaff(); }

  loginWith(token: string, me: Me) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(ME_KEY, JSON.stringify(me));
    this._token.set(token);
    this._me.set(me);
  }

  logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ME_KEY);
    this._token.set(null);
    this._me.set(null);
    this.router.navigateByUrl('/login');
  }

  private readMe(): Me | null {
    const raw = localStorage.getItem(ME_KEY);
    if (!raw) return null;
    try { return JSON.parse(raw) as Me; } catch { return null; }
  }
}
