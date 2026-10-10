import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccountStateService } from '../../core/services/account-state.service';
import { CurrencyService } from '../../core/services/currency.service';
import { TokenService } from '../../core/services/token.service';
import { ThemeService } from '../../core/services/theme.service';
import { CHART_RANGES, UiPreferencesService } from '../../core/services/ui-preferences.service';
import {
  PreferencesService,
  type AlertChannel,
  type NotificationCategory,
  type NotificationChannel,
} from '../../core/services/preferences.service';

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

/** In-app, email or both - plus no alerts at all. Only email delivery needs a destination. */
const CHANNELS: { value: AlertChannel; label: string }[] = [
  { value: 'PUSH', label: 'In-app' },
  { value: 'EMAIL', label: 'Email' },
  { value: 'BOTH', label: 'Both' },
  { value: 'NONE', label: 'No alerts' },
];

/** The 3 primary notification categories that have configurable delivery modes */
export type NotificationCategoryLevel = 'ACCOUNT_RELATED' | 'ORDER_RELATED' | 'PRICE_RELATED';

/** Notification category with its 16 specific types */
export interface NotificationCategoryConfig {
  categoryLevel: NotificationCategoryLevel;
  label: string;
  description: string;
  types: string[];
}

/** All notification categories (3 levels) with their specific types that inherit the category's mode */
const NOTIFICATION_CATEGORIES: NotificationCategoryConfig[] = [
  {
    categoryLevel: 'ACCOUNT_RELATED',
    label: 'Account Related',
    description: 'Notifications about your account status and changes',
    types: ['ACCOUNT_CREATED', 'ACCOUNT_ACTIVATED', 'ACCOUNT_SUSPENDED', 'PASSWORD_CHANGED', 'EMAIL_VERIFIED', 'PROFILE_UPDATED'],
  },
  {
    categoryLevel: 'ORDER_RELATED',
    label: 'Order Related',
    description: 'Notifications about your orders and order status',
    types: ['ORDER_PLACED', 'ORDER_FILLED', 'ORDER_REJECTED', 'ORDER_CANCELLED', 'ORDER_PARTIALLY_FILLED', 'ORDER_EXPIRED'],
  },
  {
    categoryLevel: 'PRICE_RELATED',
    label: 'Price Related',
    description: 'Notifications when stocks reach price limits you set',
    types: ['PRICE_ALERT', 'PRICE_TARGET_REACHED', 'PRICE_DROP_ALERT', 'PRICE_SPIKE_ALERT'],
  },
];

const TYPE_CHANNEL_LABELS: Record<NotificationChannel, string> = {
  INHERIT: 'Follow default',
  NONE: 'No alert',
  EMAIL: 'Email',
  PUSH: 'In-app',
  BOTH: 'In-app and email',
};

const TYPE_CHANNELS: NotificationChannel[] = ['INHERIT', 'PUSH', 'EMAIL', 'BOTH', 'NONE'];

/** One editable row of the category table. */
interface CategoryRow {
  categoryLevel: NotificationCategoryLevel;
  label: string;
  channel: NotificationChannel;
  contact: string;
}

/** Whether a channel sends email, and so needs an address. */
function needsEmail(channel: NotificationChannel): boolean {
  return channel === 'EMAIL' || channel === 'BOTH';
}

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './settings.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit {
  readonly accountState = inject(AccountStateService);
  readonly currency = inject(CurrencyService);
  private readonly tokens = inject(TokenService);
  private readonly preferences = inject(PreferencesService);
  /** The theme is saved to the customer's preference the moment it changes. */
  readonly theme = inject(ThemeService);
  /** Device preferences: applied and stored the moment they change, no save needed. */
  readonly ui = inject(UiPreferencesService);
  readonly chartRanges = CHART_RANGES;
  readonly chartStyles = [
    { value: 'candles', label: 'Candles' },
    { value: 'line', label: 'Line' },
    { value: 'area', label: 'Area' },
  ] as const;

  setDefaultQuantity(value: number | string): void {
    const n = Math.floor(Number(value));
    this.ui.defaultQuantity.set(Number.isFinite(n) && n > 0 ? Math.min(n, 1_000_000) : 1);
  }

  readonly accountId = computed<number | null>(() => this.tokens.getAccountId() ?? null);

  readonly channel = signal<AlertChannel>('NONE');
  readonly contact = signal('');
  readonly displayCurrency = signal('USD');
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly contactError = signal<string | null>(null);

  readonly categoriesLoading = signal(true);
  readonly categoriesError = signal<string | null>(null);
  readonly categorySaveError = signal<string | null>(null);
  readonly categories = signal<CategoryRow[]>([]);

  private pendingSave = false;

  readonly channels = CHANNELS;
  readonly categoryChannels = TYPE_CHANNELS;
  readonly notificationCategories = NOTIFICATION_CATEGORIES;

  /** Whether the email below is deliverable for the chosen channel. Mirrors the service. */
  readonly contactProblem = computed<string | null>(() => {
    if (!needsEmail(this.channel())) {
      return null;
    }
    const value = this.contact().trim();
    if (!value) {
      return 'An email address is required for email delivery.';
    }
    return EMAIL_RE.test(value) ? null : 'Enter a valid email address, like name@example.com.';
  });

  needsEmail(channel: NotificationChannel): boolean {
    return needsEmail(channel);
  }

  constructor() {
    // Preference answers are applied as they arrive, so the screen is correct whether the
    // signed-in shell already loaded them or this is a fresh refresh straight to /settings.
    effect(() => {
      const preference = this.accountState.preference();
      if (!preference) {
        return;
      }
      this.channel.set(preference.alertChannel ?? 'NONE');
      this.contact.set(preference.alertContact ?? '');
      this.displayCurrency.set(preference.displayCurrency ?? this.currency.ledger());
      if (this.pendingSave) {
        this.pendingSave = false;
        this.saving.set(false);
        this.saved.set(true);
      }
    });

    effect(() => {
      if (this.accountState.preferenceSaveError()) {
        this.pendingSave = false;
        this.saving.set(false);
        this.saved.set(false);
      }
    });
  }

  ngOnInit(): void {
    this.accountState.loadPreference();
    this.loadCategories();
  }

  loadCategories(): void {
    const id = this.accountId();
    if (id === null) {
      this.categoriesLoading.set(false);
      return;
    }
    this.categoriesLoading.set(true);
    this.categoriesError.set(null);
    this.preferences.getNotifications(id).subscribe({
      next: (response) => {
        this.categories.set(
          NOTIFICATION_CATEGORIES.map((cat) => {
            const entry = response.preferences.find((e) => e.category === cat.categoryLevel);
            return {
              categoryLevel: cat.categoryLevel,
              label: cat.label,
              channel: entry?.channel ?? 'INHERIT',
              contact: entry?.contact ?? '',
            };
          }),
        );
        this.categoriesLoading.set(false);
      },
      error: () => {
        this.categoriesError.set('The notification delivery preferences could not be loaded.');
        this.categoriesLoading.set(false);
      },
    });
  }

  chooseChannel(value: AlertChannel): void {
    this.channel.set(value);
    this.contactError.set(null);
    this.categorySaveError.set(null);
    this.saved.set(false);
  }

  updateCategory(row: CategoryRow, patch: Partial<CategoryRow>): void {
    this.categories.update((rows) => rows.map((r) => (r.categoryLevel === row.categoryLevel ? { ...r, ...patch } : r)));
    this.categorySaveError.set(null);
    this.saved.set(false);
  }

  categoryChannelLabel(channel: NotificationChannel): string {
    return TYPE_CHANNEL_LABELS[channel];
  }


  /** What a "Follow default" row really means today, given the account-wide channel. */
  effectiveChannel(row: CategoryRow): NotificationChannel {
    return row.channel === 'INHERIT'
      ? (this.accountState.preference()?.alertChannel ?? 'NONE')
      : row.channel;
  }

  /** A friendly description of a row's effective delivery, including "→ Email" style hints. */
  deliveryNote(row: CategoryRow): string {
    const effective = this.effectiveChannel(row);
    if (effective === 'NONE') {
      return 'Not delivered';
    }
    const global = TYPE_CHANNEL_LABELS[effective];
    return row.channel === 'INHERIT' ? `→ ${global}` : global;
  }

  categoryContactProblem(row: CategoryRow): string | null {
    if (!needsEmail(row.channel)) {
      return null;
    }
    const value = row.contact.trim();
    if (!value) {
      return 'An email address is needed.';
    }
    return EMAIL_RE.test(value) ? null : 'Enter a valid email address.';
  }

  save(): void {
    const globalProblem = this.contactProblem();
    if (globalProblem) {
      this.contactError.set(globalProblem);
      this.saved.set(false);
      return;
    }
    const categoryProblem = this.categories().map((r) => this.categoryContactProblem(r)).find((p) => p !== null);
    if (categoryProblem) {
      this.categorySaveError.set('A category below needs a valid email address for the channel you chose.');
      this.saved.set(false);
      return;
    }
    const id = this.accountId();
    if (id === null) {
      return;
    }
    this.contactError.set(null);
    this.categorySaveError.set(null);
    this.saved.set(false);
    this.pendingSave = true;
    this.saving.set(true);

    this.accountState.savePreference({
      defaultAccountId: id,
      alertChannel: this.channel(),
      alertContact: needsEmail(this.channel()) ? this.contact().trim() : null,
      displayCurrency: this.displayCurrency(),
    });

    const request = this.categories().map((r) => ({
      category: r.categoryLevel,
      channel: r.channel,
      contact: needsEmail(r.channel) ? r.contact.trim() : null,
    }));

    this.preferences.putNotifications(id, request).subscribe({
      next: (response) => {
        this.categories.set(
          NOTIFICATION_CATEGORIES.map((cat) => {
            const entry = response.preferences.find((e) => e.category === cat.categoryLevel);
            return {
              categoryLevel: cat.categoryLevel,
              label: cat.label,
              channel: entry?.channel ?? 'INHERIT',
              contact: entry?.contact ?? '',
            };
          }),
        );
      },
      error: () => {
        this.pendingSave = false;
        this.saving.set(false);
        this.saved.set(false);
        this.categorySaveError.set(
          'The notification delivery choices could not be saved. Check the values and try again.',
        );
      },
    });
  }
}