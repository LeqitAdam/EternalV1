import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./landing.component').then(m => m.LandingComponent)
  },
  // Anything else falls back to the landing page so a typo'd URL
  // doesn't 404 the prospective buyer.
  { path: '**', redirectTo: '' }
];
