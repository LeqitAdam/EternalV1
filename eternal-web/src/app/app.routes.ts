import { Routes } from '@angular/router';
import { authGuard, anonGuard } from './core/auth.guard';
import { permGuard } from './core/role.guard';
import { teamGuard } from './core/team.guard';
// permGuard reads the required permission from each route's data.perm.

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
        // Self-service: team members (eternal.team) order permissions.
        path: 'access-requests',
        canActivate: [teamGuard],
        loadComponent: () => import('./pages/access-requests/access-requests.component').then(m => m.AccessRequestsComponent)
      },
      {
        path: 'dashboard',
        canActivate: [permGuard], data: { perm: 'eternal.web.dashboard' },
        loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent)
      },
      {
        path: 'reports',
        canActivate: [permGuard], data: { perm: 'eternal.report.handle' },
        loadComponent: () => import('./pages/reports/reports.component').then(m => m.ReportsComponent)
      },
      {
        path: 'bans',
        canActivate: [permGuard], data: { perm: 'eternal.web.dashboard' },
        loadComponent: () => import('./pages/bans/bans.component').then(m => m.BansComponent)
      },
      {
        path: 'players',
        canActivate: [permGuard], data: { perm: 'eternal.web.player.view' },
        loadComponent: () => import('./pages/players/players.component').then(m => m.PlayersComponent)
      },
      {
        path: 'chat-logs',
        canActivate: [permGuard], data: { perm: 'eternal.web.chatlogs' },
        loadComponent: () => import('./pages/chat-logs/chat-logs.component').then(m => m.ChatLogsComponent)
      },
      {
        path: 'players/:name',
        canActivate: [permGuard], data: { perm: 'eternal.web.player.view' },
        loadComponent: () => import('./pages/players/player-detail.component').then(m => m.PlayerDetailComponent)
      },
      {
        path: 'appeals',
        canActivate: [permGuard], data: { perm: 'eternal.web.dashboard' },
        loadComponent: () => import('./pages/appeals/appeals.component').then(m => m.AppealsComponent)
      },
      {
        path: 'active-users',
        canActivate: [permGuard], data: { perm: 'eternal.web.admin' },
        loadComponent: () => import('./pages/active-users/active-users.component').then(m => m.ActiveUsersComponent)
      },
      {
        path: 'admin/permissions',
        canActivate: [permGuard], data: { perm: 'eternal.web.admin' },
        loadComponent: () => import('./pages/admin/permissions.component').then(m => m.PermissionsComponent)
      },
      {
        path: 'admin/users',
        canActivate: [permGuard], data: { perm: 'eternal.web.admin' },
        loadComponent: () => import('./pages/admin/admin-users.component').then(m => m.AdminUsersComponent)
      },
      {
        path: 'admin/permission-requests',
        canActivate: [permGuard], data: { perm: 'eternal.web.admin' },
        loadComponent: () => import('./pages/admin/permission-requests.component').then(m => m.PermissionRequestsComponent)
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
