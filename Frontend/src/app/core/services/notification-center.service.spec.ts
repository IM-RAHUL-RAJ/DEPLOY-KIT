import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { NotificationCenterService } from './notification-center.service';
import {
  NotificationsService,
  type Notification,
} from './notifications.service';

function unread(id: number, overrides: Partial<Notification> = {}): Notification {
  return {
    notificationId: id,
    accountId: 7,
    category: 'PRICE_ALERT',
    symbol: 'AAPL',
    title: 'Price alert',
    body: 'AAPL crossed its threshold',
    channel: 'PUSH',
    status: 'SENT',
    read: false,
    createdAt: '2026-10-05T09:00:00Z',
    ...overrides,
  };
}

describe('NotificationCenterService', () => {
  let notifications: jasmine.SpyObj<NotificationsService>;
  let service: NotificationCenterService;

  beforeEach(() => {
    notifications = jasmine.createSpyObj('NotificationsService', ['list', 'markRead']);
    notifications.list.and.returnValue(of([]));
    TestBed.configureTestingModule({
      providers: [{ provide: NotificationsService, useValue: notifications }],
    });
    service = TestBed.inject(NotificationCenterService);
  });

  afterEach(() => {
    service.stop();
  });

  it('seeds the seen set on the first poll without toasting history', () => {
    notifications.list.and.returnValue(of([unread(1), unread(2)]));
    service.start(7);
    expect(notifications.list).toHaveBeenCalledWith(7, { unread: true, limit: 100 });
    expect(service.unreadCount()).toBe(2);
    expect(service.toasts().length).toBe(0);
  });

  it('handles an account with no unread messages without re-seeding later on', () => {
    notifications.list.and.returnValue(of([]));
    service.start(7);
    expect(service.unreadCount()).toBe(0);

    notifications.list.and.returnValue(of([unread(1)]));
    service.refresh();
    expect(service.unreadCount()).toBe(1);
    expect(service.toasts().length).toBe(1);
  });

  it('toasts only newly delivered PUSH messages, never other channels or repeats', () => {
    notifications.list.and.returnValue(of([unread(1, { channel: 'EMAIL' }), unread(2)]));
    service.start(7);
    expect(service.toasts().length).toBe(0);

    notifications.list.and.returnValue(of([unread(1, { channel: 'EMAIL' }), unread(2), unread(3)]));
    service.refresh();
    expect(service.unreadCount()).toBe(3);
    expect(service.toasts().map((t) => t.id)).toEqual([3]);

    notifications.list.and.returnValue(of([unread(1, { channel: 'EMAIL' }), unread(2), unread(3)]));
    service.refresh();
    expect(service.toasts().length).toBe(1);
  });

  it('dismisses a toast', () => {
    notifications.list.and.returnValue(of([unread(1)]));
    service.start(7);
    service.refresh();

    notifications.list.and.returnValue(of([unread(1), unread(2)]));
    service.refresh();
    expect(service.toasts().length).toBe(1);

    service.dismissToast(2);
    expect(service.toasts().length).toBe(0);
  });

  it('a failed poll leaves the badge and toasts untouched', () => {
    notifications.list.and.returnValue(of([unread(1)]));
    service.start(7);
    expect(service.unreadCount()).toBe(1);

    notifications.list.and.returnValue(throwError(() => new Error('x')));
    service.refresh();
    expect(service.unreadCount()).toBe(1);
    expect(service.toasts().length).toBe(0);
  });

  it('stop clears the badge, the seen set and any toasts', () => {
    notifications.list.and.returnValue(of([unread(1), unread(2)]));
    service.start(7);

    notifications.list.and.returnValue(of([unread(1), unread(2), unread(3)]));
    service.refresh();
    expect(service.unreadCount()).toBe(3);
    expect(service.toasts().length).toBe(1);

    service.stop();
    expect(service.unreadCount()).toBe(0);
    expect(service.toasts().length).toBe(0);

    notifications.list.and.returnValue(of([unread(1)]));
    service.refresh();
    expect(service.unreadCount()).toBe(0);
  });
});