import { inject, Injectable, signal } from '@angular/core';
import { interval, Subscription } from 'rxjs';
import { NotificationsService, type Notification } from './notifications.service';

/** One transient in-app alert surfaced because a PUSH-channel message arrived. */
export interface AppToast {
  id: number;
  title: string;
  body: string;
}

const POLL_INTERVAL_MS = 10_000;
const MAX_TOASTS = 3;
const TOAST_TTL_MS = 8_000;

/**
 * The in-app notification centre. Polls the unread inbox while a customer is signed in,
 * keeps the unread badge on the shell in step, and turns newly delivered PUSH-channel
 * messages into on-screen toasts. The first poll only seeds the "have you seen this"
 * set, so the message history does not toast retroactively.
 */
@Injectable({ providedIn: 'root' })
export class NotificationCenterService {
  private readonly notifications = inject(NotificationsService);

  readonly unreadCount = signal(0);
  readonly toasts = signal<AppToast[]>([]);

  private accountId: number | null = null;
  private seeded = false;
  private seen = new Set<number>();
  private polling: Subscription | null = null;
  private timers = new Map<number, ReturnType<typeof setTimeout>>();

  start(accountId: number): void {
    this.accountId = accountId;
    this.seeded = false;
    this.seen.clear();
    this.unreadCount.set(0);
    this.toasts.set([]);
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
    this.polling?.unsubscribe();
    this.polling = interval(POLL_INTERVAL_MS).subscribe(() => this.refresh());
    this.refresh();
  }

  stop(): void {
    this.accountId = null;
    this.seeded = false;
    this.polling?.unsubscribe();
    this.polling = null;
    this.unreadCount.set(0);
    this.toasts.set([]);
    this.timers.forEach((t) => clearTimeout(t));
    this.timers.clear();
    this.seen.clear();
  }

  dismissToast(id: number): void {
    this.toasts.update((all) => all.filter((toast) => toast.id !== id));
    this.clearTimer(id);
  }

  /** One poll of the unread inbox. Public so tests can drive it without the clock. */
  refresh(): void {
    const accountId = this.accountId;
    if (accountId === null) {
      return;
    }
    this.notifications.list(accountId, { unread: true, limit: 100 }).subscribe({
      next: (notifications) => {
        this.unreadCount.set(notifications.length);
        if (!this.seeded) {
          notifications.forEach((n) => this.seen.add(n.notificationId));
          this.seeded = true;
          return;
        }
        for (const notification of notifications) {
          if ((notification.channel !== 'PUSH' && notification.channel !== 'BOTH') || this.seen.has(notification.notificationId)) {
            continue;
          }
          this.seen.add(notification.notificationId);
          this.pushToast(notification);
        }
      },
      error: () => {
        // A failed poll leaves the badge and toasts as they are; the next cycle retries.
      },
    });
  }

  private pushToast(notification: Notification): void {
    this.toasts.update((all) => [
      ...all,
      { id: notification.notificationId, title: notification.title, body: notification.body },
    ]);
    this.trimToasts();
    const timer = setTimeout(() => this.dismissToast(notification.notificationId), TOAST_TTL_MS);
    this.timers.set(notification.notificationId, timer);
  }

  private trimToasts(): void {
    this.toasts().slice(0, this.toasts().length - MAX_TOASTS).forEach((t) => this.dismissToast(t.id));
  }

  private clearTimer(id: number): void {
    const timer = this.timers.get(id);
    if (timer !== undefined) {
      clearTimeout(timer);
      this.timers.delete(id);
    }
  }
}