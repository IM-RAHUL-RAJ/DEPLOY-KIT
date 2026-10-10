import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import type { RiskAppetite, UserResponse } from '@auth-api/model/models';
import type { AccountResponse } from '@trade-api/model/models';
import { AuthService } from '../../core/services/auth.service';
import {
  PreferencesService,
  type NotificationCategory,
  type NotificationChannel,
} from '../../core/services/preferences.service';
import { AccountStateService } from '../../core/services/account-state.service';
import { CurrencyService } from '../../core/services/currency.service';
import { TokenService } from '../../core/services/token.service';
import { TradeService } from '../../core/services/trade.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { RISK_APPETITES } from '../../core/services/country-catalog.service';

const TYPE_LABELS: Record<NotificationCategory, string> = {
  ACCOUNT_RELATED: 'Account Related',
  ORDER_RELATED: 'Order Related',
  PRICE_RELATED: 'Price Related',
};

const CHANNEL_LABELS: Record<NotificationChannel, string> = {
  INHERIT: 'Follow default',
  NONE: 'Not delivered',
  EMAIL: 'Email',
  PUSH: 'In-app',
  BOTH: 'In-app and email',
};

const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Administrator',
  TRADER: 'Trader',
  OPS: 'Operations',
};

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, RouterModule, ReactiveFormsModule],
  templateUrl: './profile.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './profile.component.scss',
})
export class ProfileComponent implements OnInit {
  readonly currency = inject(CurrencyService);
  readonly accountState = inject(AccountStateService);
  private readonly auth = inject(AuthService);
  private readonly tokens = inject(TokenService);
  private readonly trade = inject(TradeService);
  private readonly preferences = inject(PreferencesService);
  private readonly fb = inject(FormBuilder);

  private readonly loadingAccount = signal(true);
  private readonly loadingProfile = signal(true);

  readonly account = signal<AccountResponse | null>(null);
  readonly accountError = signal<string | null>(null);
  readonly savingProfile = signal(false);
  readonly showAccountNumber = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly profileMessage = signal<string | null>(null);
  readonly profile = signal<UserResponse | null>(null);
  readonly loading = computed(() => this.loadingAccount() || this.loadingProfile());
  readonly profileTypes = RISK_APPETITES;

  readonly fullName = computed(() => {
    const p = this.profile();
    const parts = [p?.firstName, p?.middleName, p?.lastName].filter((x) => !!x && String(x).trim());
    return parts.length ? parts.join(' ') : this.username() ?? 'Trader';
  });

  readonly initials = computed(() => {
    const p = this.profile();
    const a = (p?.firstName ?? this.username() ?? '?').trim().charAt(0);
    const b = (p?.lastName ?? '').trim().charAt(0);
    return (a + b).toUpperCase();
  });

  /** The risk profile currently picked in the form, for the selectable tiles. */
  readonly selectedRisk = signal<string>('');

  chooseRisk(value: string): void {
    this.selectedRisk.set(value);
    this.profileForm.controls.riskAppetite.setValue(value);
    this.profileForm.markAsDirty();
  }

  readonly accountId = computed<number | null>(() => this.tokens.getAccountId() ?? null);
  readonly username = computed<string | null>(() => this.tokens.getUsername());
  readonly roles = computed<string[]>(() => this.tokens.getUserRoles());
  readonly roleLabel = computed(() =>
    this.roles().map((r) => ROLE_LABELS[r] ?? r.replace(/_/g, ' ')).join(', ') || 'Trader',
  );

  readonly accountNumber = computed(() => {
    const raw = this.profile()?.accountId ?? this.tokens.getAccountId();
    return raw === null || raw === undefined ? null : String(raw).padStart(8, '0');
  });

  readonly visibleAccountNumber = computed(() => {
    const account = this.accountNumber();
    if (!account) {
      return 'Unavailable';
    }
    return this.showAccountNumber() ? account : `••••${account.slice(-4)}`;
  });

  readonly profileForm = this.fb.group({
    riskAppetite: ['', [Validators.required]],
  });

  readonly delivery = signal<{ category: NotificationCategory; label: string; channel: NotificationChannel }[]>([]);
  readonly deliveryNote = (entry: { channel: NotificationChannel }): string => {
    const channel = entry.channel === 'INHERIT' ? this.accountState.preference()?.alertChannel ?? 'NONE' : entry.channel;
    return CHANNEL_LABELS[channel];
  };

  readonly defaultChannelLabel = computed(() => CHANNEL_LABELS[this.accountState.preference()?.alertChannel ?? 'NONE']);

  readonly display = computed(() =>
    this.accountState.preference()?.displayCurrency
      ? `${this.accountState.preference()!.displayCurrency} (chosen in Settings)`
      : this.currency.ledger(),
  );

  ngOnInit(): void {
    this.accountState.loadPreference();
    this.loadAccount();
    this.loadDelivery();
    this.loadProfile();
  }

  loadAccount(): void {
    const id = this.accountId();
    if (id === null) {
      this.loadingAccount.set(false);
      return;
    }

    this.trade.getAccount(id).subscribe({
      next: (account) => {
        this.account.set(account);
        this.currency.setLedger(account.currency ?? 'USD');
        this.loadingAccount.set(false);
      },
      error: () => {
        this.accountError.set('The account details could not be loaded right now.');
        this.loadingAccount.set(false);
      },
    });
  }

  loadDelivery(): void {
    const id = this.accountId();
    if (id === null) {
      return;
    }

    this.preferences.getNotifications(id).subscribe({
      next: (response) => {
        this.delivery.set(
          (Object.keys(TYPE_LABELS) as NotificationCategory[]).map((category) => ({
            category,
            label: TYPE_LABELS[category],
            channel: response.preferences.find((entry) => entry.category === category)?.channel ?? 'INHERIT',
          })),
        );
      },
      error: () => {
        // The account identity remains visible even without the delivery summary.
      },
    });
  }

  loadProfile(): void {
    this.loadingProfile.set(true);
    this.errorMessage.set(null);

    this.auth.me().subscribe({
      next: (user) => {
        this.profile.set(user);
        this.profileForm.patchValue({ riskAppetite: user.riskAppetite ?? '' });
        this.selectedRisk.set(user.riskAppetite ?? '');
        this.loadingProfile.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
        this.loadingProfile.set(false);
      },
    });
  }

  toggleAccountMask(): void {
    this.showAccountNumber.update((value) => !value);
  }

  saveProfile(): void {
    if (this.profileForm.invalid || this.savingProfile()) {
      this.profileForm.markAllAsTouched();
      return;
    }

    this.savingProfile.set(true);
    this.profileMessage.set(null);
    this.errorMessage.set(null);

    this.auth
      .updateProfile({ riskAppetite: this.profileForm.controls.riskAppetite.value as RiskAppetite })
      .subscribe({
        next: (user) => {
          this.profile.set(user);
          this.profileMessage.set('Profile type updated successfully.');
          this.savingProfile.set(false);
        },
        error: (err: HttpErrorResponse) => {
          this.errorMessage.set(resolveErrorMessage(err.error?.errorCode, err.status));
          this.savingProfile.set(false);
        },
      });
  }
}
