import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard } from './core/guards/admin.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    loadComponent: () =>
      import('./features/admin/admin-shell.component').then((m) => m.AdminShellComponent),
    children: [
      { path: '', redirectTo: 'overview', pathMatch: 'full' },
      {
        path: 'overview',
        loadComponent: () =>
          import('./features/admin/admin-overview.component').then((m) => m.AdminOverviewComponent),
      },
      {
        path: 'accounts',
        loadComponent: () =>
          import('./features/admin/admin-accounts.component').then((m) => m.AdminAccountsComponent),
      },
      {
        path: 'approvals',
        loadComponent: () =>
          import('./features/admin/admin-approvals.component').then((m) => m.AdminApprovalsComponent),
      },
      {
        path: 'users',
        loadComponent: () =>
          import('./features/admin/admin-users.component').then((m) => m.AdminUsersComponent),
      },
      {
        path: 'orders',
        loadComponent: () =>
          import('./features/admin/admin-orders.component').then((m) => m.AdminOrdersComponent),
      },
    ],
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/shell/shell.component').then((m) => m.ShellComponent),
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        path: 'dashboard',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
      },
      {
        path: 'order',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/order-ticket/order-ticket.component').then((m) => m.OrderTicketComponent),
      },
      {
        path: 'blotter',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/blotter/blotter.component').then((m) => m.BlotterComponent),
      },
      {
        path: 'holdings',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/holdings/holdings.component').then((m) => m.HoldingsComponent),
      },
      {
        path: 'portfolio',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/portfolio/portfolio.component').then((m) => m.PortfolioComponent),
      },
      {
        path: 'profile',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/profile/profile.component').then((m) => m.ProfileComponent),
      },
      {
        path: 'watchlist',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/watchlist/watchlist.component').then((m) => m.WatchlistComponent),
      },
      {
        path: 'notifications',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/notifications/notifications.component').then((m) => m.NotificationsComponent),
      },
      {
        path: 'alerts',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/price-alerts/price-alerts.component').then((m) => m.PriceAlertsComponent),
      },
      {
        path: 'chatbot',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/chatbot-page/chatbot-page.component').then((m) => m.ChatbotPageComponent),
      },
      {
        path: 'advice',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/advice/advice.component').then((m) => m.AdviceComponent),
      },
      {
        path: 'automated-trading',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/automated-trading/automated-trading.component').then((m) => m.AutomatedTradingComponent),
      },
      {
        path: 'settings',
        canActivate: [authGuard],
        loadComponent: () =>
          import('./features/settings/settings.component').then((m) => m.SettingsComponent),
      },
    ],
  },
  { path: '**', redirectTo: 'dashboard' },
];
