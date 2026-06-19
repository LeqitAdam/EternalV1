import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Blockt User ohne Dashboard-Recht aus Staff-Bereichen — direkter URL-Aufruf
 * schickt sie nach /me. Logged-out User leitet der authGuard schon vorher um.
 */
export const staffGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.hasPerm('eternal.web.dashboard')) return true;
  router.navigateByUrl('/me');
  return false;
};

/** Admin-Bereiche (Rollen/Rechte, aktive User) — eternal.web.admin nötig. */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.hasPerm('eternal.web.admin')) return true;
  router.navigateByUrl('/me');
  return false;
};

/**
 * Generic per-permission guard — the required key comes from the route's
 * `data.perm`, so each page gates on exactly the permission its API enforces
 * (e.g. Reports → eternal.report.handle), not a coarse "is staff" lump.
 */
export const permGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const perm = route.data?.['perm'] as string | undefined;
  if (!perm || auth.hasPerm(perm)) return true;
  router.navigateByUrl('/me');
  return false;
};
