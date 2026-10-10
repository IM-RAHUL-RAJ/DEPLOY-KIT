import {
  ChangeDetectionStrategy,
  Component, OnDestroy, computed, inject, signal
} from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';
import { CommonModule } from '@angular/common';
import { filter } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { TokenService } from '../../core/services/token.service';
import { UiPreferencesService } from '../../core/services/ui-preferences.service';
import { ThemeService } from '../../core/services/theme.service';
import { CurrencyService } from '../../core/services/currency.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { NotificationCenterService } from '../../core/services/notification-center.service';
import { AssistantComponent } from '../assistant/assistant.component';
import { CloudMarkComponent } from '../../shared/cloud-mark.component';
import { InstrumentChartDialogComponent } from '../../shared/instrument-chart/instrument-chart-dialog.component';

interface PageMeta {
  title: string;
  subtitle: string;
  tag: string;
}

const PAGE_MAP: { prefix: string; meta: PageMeta }[] = [
  { prefix: '/dashboard', meta: { title: 'Executive Dashboard', subtitle: 'Real-time valuation & position monitoring', tag: 'OVERVIEW' } },
  { prefix: '/order', meta: { title: 'Order Ticket', subtitle: 'Place market and limit orders', tag: 'EXECUTION' } },
  { prefix: '/blotter', meta: { title: 'Order Blotter', subtitle: 'Live audit trail of all submitted orders', tag: 'AUDIT' } },
  { prefix: '/holdings', meta: { title: 'Holdings', subtitle: 'Open positions, cost basis and return', tag: 'PORTFOLIO' } },
  { prefix: '/portfolio', meta: { title: 'Portfolio & P&L', subtitle: 'Priced holdings, cost basis and realised and unrealised profit and loss', tag: 'PORTFOLIO' } },
  { prefix: '/profile', meta: { title: 'Profile', subtitle: 'Your identity, trading profile and account', tag: 'ACCOUNT' } },
  { prefix: '/watchlist', meta: { title: 'Watchlist', subtitle: 'Monitor favorite instruments with live market data', tag: 'WATCHLIST' } },
  { prefix: '/notifications', meta: { title: 'Notifications', subtitle: 'Order outcomes and price alerts', tag: 'INBOX' } },
  { prefix: '/alerts', meta: { title: 'Price Alerts', subtitle: 'Arm a one-shot threshold on a watched instrument', tag: 'ALERTS' } },
  { prefix: '/chatbot', meta: { title: 'AI Trading Assistant', subtitle: 'Ask questions about your account, portfolio, and market data', tag: 'ASSISTANT' } },
  { prefix: '/advice', meta: { title: 'Trade Advice', subtitle: 'Buy, sell and hold signals from five standard indicators, with every number shown', tag: 'SIGNALS' } },
  { prefix: '/automated-trading', meta: { title: 'Automated Trading', subtitle: 'Rules that trade for you on the live price stream, within limits you set', tag: 'AUTOMATED' } },
  { prefix: '/settings', meta: { title: 'Settings', subtitle: 'Notifications, display, trading defaults and charts', tag: 'PREFERENCES' } },
];

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommonModule, AssistantComponent, CloudMarkComponent,
    InstrumentChartDialogComponent],
  templateUrl: './shell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './shell.component.scss',
})
export class ShellComponent implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly accountState = inject(AccountStateService);
  readonly tokens = inject(TokenService);
  readonly themeService = inject(ThemeService);
  readonly currency = inject(CurrencyService);
  readonly notificationsCenter = inject(NotificationCenterService);
  readonly ui = inject(UiPreferencesService);

  readonly showFx = signal(false);
  readonly showLogoutConfirm = signal(false);
  readonly url = signal(this.router.url);

  readonly page = computed<PageMeta>(() => {
    const match = PAGE_MAP.find((p) => this.url().startsWith(p.prefix));
    return match?.meta ?? { title: 'Akatsuki Terminal', subtitle: 'Institutional trading workspace', tag: 'PLATFORM' };
  });

  readonly initials = computed(() => {
    const name = this.tokens.getUsername() ?? 'Trader';
    return name.slice(0, 2).toUpperCase();
  });

  constructor() {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.url.set(e.urlAfterRedirects));

    // Fetch account currency on app initialization
    this.accountState.fetchAccountCurrency();

    // Poll for new notifications while the trader is signed in.
    const accountId = this.tokens.getAccountId();
    if (accountId !== null && accountId !== undefined) {
      this.notificationsCenter.start(accountId);
    }
  }

  get username(): string {
    return this.tokens.getUsername() ?? 'Trader';
  }

  get currentTheme(): string {
    return this.themeService.currentTheme();
  }

  toggleSidebar(): void {
    this.ui.sidebarCollapsed.update((v) => !v);
  }

  toggleTheme(): void {
    this.themeService.toggleTheme();
  }

  toggleFx(): void {
    this.showFx.update((v) => !v);
  }

  selectCurrency(code: string): void {
    this.accountState.selectDisplayCurrency(code);
    this.showFx.set(false);
  }

  requestLogout(): void {
    this.showLogoutConfirm.set(true);
  }

  cancelLogout(): void {
    this.showLogoutConfirm.set(false);
  }

  logout(): void {
    this.showLogoutConfirm.set(false);
    this.auth.logout();
  }

  ngOnDestroy(): void {
    this.notificationsCenter.stop();
  }
}
