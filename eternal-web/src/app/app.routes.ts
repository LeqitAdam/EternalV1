import { Routes } from '@angular/router';
import { authGuard, anonGuard } from './core/auth.guard';
import { staffGuard } from './core/role.guard';

export const routes: Routes = [
  // Public sales / landing page. Lives at the root because it's the
  // first thing recruiters / prospective buyers should see.
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./pages/landing/landing.component').then(m => m.LandingComponent)
  },
  {
    path: 'login',
    canActivate: [anonGuard],
    loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent)
  },
  // Public-by-design pages. PublicShell gives them the same header/footer
  // as the dashboard but skips the sidebar.
  {
    path: 'help',
    loadComponent: () => import('./shared/public-shell/public-shell.component').then(m => m.PublicShellComponent),
    children: [
      { path: '', loadComponent: () => import('./pages/help/help.component').then(m => m.HelpComponent) }
    ]
  },
  {
    path: 'appeal',
    loadComponent: () => import('./shared/public-shell/public-shell.component').then(m => m.PublicShellComponent),
    children: [
      { path: '', loadComponent: () => import('./pages/appeal/appeal.component').then(m => m.AppealComponent) }
    ]
  },
  // Authenticated dashboard. Was at '' before — now lives at /dashboard/*
  // so the public landing page can own the root URL.
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./shared/shell/shell.component').then(m => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'me' },
      {
        path: 'me',
        loadComponent: () => import('./pages/me/me.component').then(m => m.MeComponent)
      },
      {
        path: 'appeal',
        loadComponent: () => import('./pages/appeal/appeal.component').then(m => m.AppealComponent)
      },
      {
        path: 'dashboard',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'reports',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/reports/reports.component').then(m => m.ReportsComponent)
      },
      {
        path: 'bans',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/bans/bans.component').then(m => m.BansComponent)
      },
      {
        path: 'players',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/players/players.component').then(m => m.PlayersComponent)
      },
      {
        path: 'players/:name',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/players/player-detail.component').then(m => m.PlayerDetailComponent)
      },
      {
        path: 'appeals',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/appeals/appeals.component').then(m => m.AppealsComponent)
      },
      {
        path: 'active-users',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/active-users/active-users.component').then(m => m.ActiveUsersComponent)
      }
    ]
  },
  // Catch-all goes back to the landing page so unknown URLs don't dead-end.
  { path: '**', redirectTo: '' }
];
