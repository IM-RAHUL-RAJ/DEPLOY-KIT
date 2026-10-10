import {
  ChangeDetectionStrategy,
  Component, computed, inject, signal
} from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';
import { CommonModule } from '@angular/common';
import { filter } from 'rxjs';
import { AuthService } from '../../core/services/auth.service';
import { TokenService } from '../../core/services/token.service';
import { ThemeService } from '../../core/services/theme.service';
import { CloudMarkComponent } from '../../shared/cloud-mark.component';

interface PageMeta {
  title: string;
  subtitle: string;
  tag: string;
}

const PAGE_MAP: { prefix: string; meta: PageMeta }[] = [
  { prefix: '/admin/overview', meta: { title: 'Platform Overview', subtitle: 'Users, accounts, orders and cash at a glance', tag: 'CONTROL' } },
  { prefix: '/admin/accounts', meta: { title: 'Account Management', subtitle: 'Approve, suspend, re-activate and fund accounts', tag: 'ACCOUNTS' } },
  { prefix: '/admin/approvals', meta: { title: 'Approval Console', subtitle: 'Review verified applicants and move them through the trading gate', tag: 'ONBOARDING' } },
  { prefix: '/admin/users', meta: { title: 'User Directory', subtitle: 'Every registered client and administrator', tag: 'USERS' } },
  { prefix: '/admin/orders', meta: { title: 'Platform Order Book', subtitle: 'Every order submitted across the platform', tag: 'ORDERS' } },
];

@Component({
  selector: 'app-admin-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, CommonModule, CloudMarkComponent],
  templateUrl: './admin-shell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './admin-shell.component.scss',
})
export class AdminShellComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly tokens = inject(TokenService);
  readonly themeService = inject(ThemeService);

  readonly showLogoutConfirm = signal(false);
  readonly url = signal(this.router.url);

  readonly page = computed<PageMeta>(() => {
    const match = PAGE_MAP.find((p) => this.url().startsWith(p.prefix));
    return match?.meta ?? PAGE_MAP[0].meta;
  });

  readonly initials = computed(() => {
    const name = this.tokens.getUsername() ?? 'Admin';
    return name.slice(0, 2).toUpperCase();
  });

  constructor() {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.url.set(e.urlAfterRedirects));
  }

  get username(): string {
    return this.tokens.getUsername() ?? 'Admin';
  }

  get currentTheme(): string {
    return this.themeService.currentTheme();
  }

  toggleTheme(): void {
    this.themeService.toggleTheme();
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

}
