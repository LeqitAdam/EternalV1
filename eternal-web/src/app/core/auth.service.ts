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

  /** Does the current user hold {@code key}? The /me permission list is already
   *  expanded server-side (wildcards resolved), so this is a plain membership
   *  check. */
  hasPerm(key: string): boolean {
    return this._me()?.permissions?.includes(key) ?? false;
  }

  /** Reactive variants for templates. Admin = dashboard role editor; staff =
   *  general dashboard access. */
  readonly isAdmin = computed(() => (this._me()?.permissions ?? []).includes('eternal.web.admin'));
  readonly isStaff = computed(() => (this._me()?.permissions ?? []).includes('eternal.web.dashboard'));

  /** Pardon a normal (non-admin-only) ban. */
  canPardon(): boolean { return this.hasPerm('eternal.unban'); }

  loginWith(token: string, me: Me) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(ME_KEY, JSON.stringify(me));
    this._token.set(token);
    this._me.set(me);
  }

  /** Refresh the cached Me (e.g. permissions) without touching the token —
   *  called on app load so newly-granted rights show up after a reload, not
   *  only after a full re-login. */
  updateMe(me: Me) {
    localStorage.setItem(ME_KEY, JSON.stringify(me));
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
