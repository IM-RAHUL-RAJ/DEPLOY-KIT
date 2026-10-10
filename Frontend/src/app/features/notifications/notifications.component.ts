import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NotificationCenterService } from '../../core/services/notification-center.service';
import { TokenService } from '../../core/services/token.service';
import {
  NotificationsService,
  type Notification,
  type NotificationCategory,
  type NotificationStatus,
} from '../../core/services/notifications.service';
import { catchError, forkJoin, map, of } from 'rxjs';

const CATEGORY_LABELS: Record<NotificationCategory, { group: string; label: string }> = {
  // Account related
  ACCOUNT_CREATED: { group: 'Account', label: 'Account created' },
  ACCOUNT_ACTIVATED: { group: 'Account', label: 'Account activated' },
  ACCOUNT_SUSPENDED: { group: 'Account', label: 'Account suspended' },
  PASSWORD_CHANGED: { group: 'Account', label: 'Password changed' },
  EMAIL_VERIFIED: { group: 'Account', label: 'Email verified' },
  PROFILE_UPDATED: { group: 'Account', label: 'Profile updated' },
  // Order related
  ORDER_PLACED: { group: 'Orders', label: 'Order placed' },
  ORDER_FILLED: { group: 'Orders', label: 'Order filled' },
  ORDER_REJECTED: { group: 'Orders', label: 'Order rejected' },
  ORDER_CANCELLED: { group: 'Orders', label: 'Order cancelled' },
  ORDER_PARTIALLY_FILLED: { group: 'Orders', label: 'Order partially filled' },
  ORDER_EXPIRED: { group: 'Orders', label: 'Order expired' },
  // Price related
  PRICE_ALERT: { group: 'Price Alerts', label: 'Price alert triggered' },
  PRICE_TARGET_REACHED: { group: 'Price Alerts', label: 'Price target reached' },
  PRICE_DROP_ALERT: { group: 'Price Alerts', label: 'Price drop alert' },
  PRICE_SPIKE_ALERT: { group: 'Price Alerts', label: 'Price spike alert' },
};

const STATUS_LABELS: Record<NotificationStatus, string> = {
  QUEUED: 'Queued',
  SENT: 'Sent',
  FAILED: 'Delivery failed',
  SUPPRESSED: 'Suppressed',
};

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './notifications.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './notifications.component.scss',
})
export class NotificationsComponent implements OnInit {
  private readonly notificationsService = inject(NotificationsService);
  private readonly tokens = inject(TokenService);
  private readonly center = inject(NotificationCenterService);

  readonly loading = signal(true);
  /** True when the list itself failed to load, so the page shows the error, not "empty". */
  readonly loadFailed = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly unreadOnly = signal(false);
  readonly selectedGroup = signal<string>('ALL');
  readonly inbox = signal<Notification[]>([]);
  readonly markingAll = signal(false);

  readonly groups = [
    { value: 'ALL', label: 'All notifications' },
    { value: 'Account', label: 'Account related' },
    { value: 'Orders', label: 'Order related' },
    { value: 'Price Alerts', label: 'Price alerts' },
  ];

  readonly notifications = computed(() => {
    let filtered = this.unreadOnly() ? this.inbox().filter((n) => !n.read) : this.inbox();
    
    const group = this.selectedGroup();
    if (group !== 'ALL') {
      filtered = filtered.filter(n => CATEGORY_LABELS[n.category]?.group === group);
    }

    return [...filtered].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
  });

  readonly unreadCount = computed(() => this.inbox().filter((n) => !n.read).length);
  readonly isEmpty = computed(() => this.notifications().length === 0);

  constructor() {
    // The shell polls the unread inbox; when it sees more unread than this page holds, a
    // message arrived while the page was open, so fetch the list again without a spinner.
    effect(() => {
      const shellUnread = this.center.unreadCount();
      if (!this.loading() && shellUnread > untracked(() => this.unreadCount())) {
        untracked(() => this.reloadQuietly());
      }
    });
  }

  ngOnInit(): void {
    this.load();
  }

  private reloadQuietly(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      return;
    }
    this.notificationsService.list(accountId).subscribe({
      next: (notifications) => this.inbox.set(notifications),
      error: () => {
        // The list on screen stays as it was; the next poll tries again.
      },
    });
  }

  load(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId) {
      this.loading.set(false);
      this.errorMessage.set('No account is signed in.');
      return;
    }
    this.loading.set(true);
    this.loadFailed.set(false);
    this.errorMessage.set(null);
    this.notificationsService.list(accountId).subscribe({
      next: (notifications) => {
        this.inbox.set(notifications);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('The notification history could not be loaded. Try again in a moment.');
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }

  toggleUnreadOnly(): void {
    this.unreadOnly.update((v) => !v);
  }

  markRead(notification: Notification): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId || notification.read) {
      return;
    }
    this.notificationsService.markRead(accountId, notification.notificationId).subscribe({
      next: (updated) => {
        this.inbox.update((all) =>
          all.map((n) => (n.notificationId === updated.notificationId ? updated : n)),
        );
        // Keep the shell's unread badge in step now rather than at the next poll.
        this.center.refresh();
      },
      error: () => {
        this.errorMessage.set('That message could not be marked read. Try again in a moment.');
      },
    });
  }

  markAllRead(): void {
    const accountId = this.tokens.getAccountId();
    if (!accountId || this.markingAll()) {
      return;
    }

    const unread = this.inbox().filter((n) => !n.read);
    if (unread.length === 0) {
      return;
    }

    this.markingAll.set(true);
    this.errorMessage.set(null);

    const requests = unread.map((n) =>
      this.notificationsService.markRead(accountId, n.notificationId).pipe(
        map((updated) => ({ ok: true as const, notificationId: n.notificationId, updated })),
        catchError(() => of({ ok: false as const, notificationId: n.notificationId, updated: null })),
      ),
    );

    forkJoin(requests).subscribe((results) => {
      this.markingAll.set(false);

      const updatedById = new Map<number, Notification>(
        results.filter((r) => r.ok && r.updated).map((r) => [r.notificationId, r.updated as Notification]),
      );
      this.inbox.update((all) =>
        all.map((n) => updatedById.get(n.notificationId) ?? n),
      );

      const failed = results.filter((r) => !r.ok).length;
      if (failed > 0) {
        this.errorMessage.set(`${failed} notification${failed === 1 ? '' : 's'} could not be marked read. Try again in a moment.`);
      }

      if (updatedById.size > 0) {
        // Keep the shell badge in step with the page actions.
        this.center.refresh();
      }
    });
  }

  categoryLabel(category: NotificationCategory): string {
    return CATEGORY_LABELS[category]?.label ?? category;
  }

  categoryGroup(category: NotificationCategory): string {
    return CATEGORY_LABELS[category]?.group ?? 'Other';
  }

  statusLabel(status: NotificationStatus): string {
    return STATUS_LABELS[status] ?? status;
  }

  selectGroup(group: string): void {
    this.selectedGroup.set(group);
  }

  getGroupLabel(group: string): string {
    return this.groups.find(g => g.value === group)?.label ?? group;
  }
}