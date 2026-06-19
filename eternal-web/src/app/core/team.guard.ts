import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/** Self-service access-request page — nur Teammitglieder (eternal.team). */
export const teamGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.hasPerm('eternal.team')) return true;
  router.navigateByUrl('/me');
  return false;
};
