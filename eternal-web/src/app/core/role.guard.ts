import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Blockt PLAYER-User aus Staff-Bereichen — direkter URL-Aufruf schickt sie
 * nach /me. Logged-out User werden vom authGuard schon vorher umgeleitet.
 */
export const staffGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const role = auth.me()?.role;
  if (role === 'MOD' || role === 'ADMIN') return true;
  router.navigateByUrl('/dashboard/me');
  return false;
};

/** /appeals (Review) ist Admin-only. */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.me()?.role === 'ADMIN') return true;
  router.navigateByUrl('/dashboard/me');
  return false;
};
