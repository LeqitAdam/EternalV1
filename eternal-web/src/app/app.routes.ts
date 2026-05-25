import { Routes } from '@angular/router';
import { authGuard, anonGuard } from './core/auth.guard';
import { adminGuard, staffGuard } from './core/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    canActivate: [anonGuard],
    loadComponent: () => import('./pages/login/login.component').then(m => m.LoginComponent)
  },
  // Public Seiten (kein Login noetig). Jede hat ihre eigene Top-Level-Route,
  // sonst schluckt path:'' alles und Authenticated-User landen in der
  // PublicShell ohne Inhalt.
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
  {
    path: '',
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
        // Admin-only — also gated server-side by auth.requireAdmin.
        path: 'active-users',
        canActivate: [staffGuard],
        loadComponent: () => import('./pages/active-users/active-users.component').then(m => m.ActiveUsersComponent)
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
