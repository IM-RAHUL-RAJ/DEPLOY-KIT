import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { NotificationsComponent } from './notifications.component';
import {
  NotificationsService,
  type Notification,
} from '../../core/services/notifications.service';
import { TokenService } from '../../core/services/token.service';
import { NotificationCenterService } from '../../core/services/notification-center.service';

function item(id: number, overrides: Partial<Notification> = {}): Notification {
  return {
    notificationId: id,
    accountId: 7,
    category: 'ORDER_FILLED',
    symbol: 'AAPL',
    title: 'Order filled',
    body: 'Bought 10 AAPL.',
    channel: 'EMAIL',
    status: 'SENT',
    read: false,
    createdAt: '2026-10-05T09:00:00Z',
    ...overrides,
  };
}

describe('NotificationsComponent', () => {
  let service: jasmine.SpyObj<NotificationsService>;

  function create(): NotificationsComponent {
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('NotificationsService', ['list', 'markRead']);
    service.list.and.returnValue(of([item(1), item(2, { read: true })]));
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(7);
    spyOn(console, 'error');
    TestBed.configureTestingModule({
      imports: [NotificationsComponent],
      providers: [
        provideRouter([]),
        { provide: NotificationsService, useValue: service },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('loads the inbox for the signed-in account', () => {
    const c = create();
    expect(c.loading()).toBeFalse();
    expect(service.list).toHaveBeenCalledWith(7);
    expect(c.inbox().length).toBe(2);
    expect(c.unreadCount()).toBe(1);
  });

  it('reports a failed load', () => {
    service.list.and.returnValue(throwError(() => new Error('x')));
    const c = create();
    expect(c.errorMessage()).toContain('could not be loaded');
    expect(c.loading()).toBeFalse();
  });

  it('filters to unread and back', () => {
    const c = create();
    expect(c.notifications().length).toBe(2);
    c.toggleUnreadOnly();
    expect(c.notifications().map((n) => n.notificationId)).toEqual([1]);
    c.toggleUnreadOnly();
    expect(c.notifications().length).toBe(2);
  });

  it('marks one notification read and updates it in place', () => {
    service.markRead.and.returnValue(of(item(1, { read: true })));
    const c = create();
    c.markRead(c.inbox()[0]);
    expect(service.markRead).toHaveBeenCalledWith(7, 1);
    expect(c.inbox().find((n) => n.notificationId === 1)?.read).toBeTrue();
    expect(c.unreadCount()).toBe(0);
  });

  it('marks all unread notifications as read', () => {
    service.list.and.returnValue(of([item(1), item(2), item(3, { read: true })]));
    service.markRead.and.callFake((_, id) => of(item(id, { read: true })));

    const c = create();
    c.markAllRead();

    expect(service.markRead).toHaveBeenCalledTimes(2);
    expect(service.markRead).toHaveBeenCalledWith(7, 1);
    expect(service.markRead).toHaveBeenCalledWith(7, 2);
    expect(c.unreadCount()).toBe(0);
    expect(c.markingAll()).toBeFalse();
  });

  it('keeps failed notifications unread when mark-all partially fails', () => {
    service.list.and.returnValue(of([item(1), item(2)]));
    service.markRead.and.callFake((_, id) =>
      id === 1 ? of(item(1, { read: true })) : throwError(() => new Error('x')),
    );

    const c = create();
    c.markAllRead();

    expect(c.inbox().find((n) => n.notificationId === 1)?.read).toBeTrue();
    expect(c.inbox().find((n) => n.notificationId === 2)?.read).toBeFalse();
    expect(c.unreadCount()).toBe(1);
    expect(c.errorMessage()).toContain('could not be marked read');
  });

  it('does not call the service for an already-read notification', () => {
    const c = create();
    const read = c.inbox().find((n) => n.notificationId === 2)!;
    c.markRead(read);
    expect(service.markRead).not.toHaveBeenCalled();
  });

  it('reports a failed mark-read without losing the row', () => {
    service.markRead.and.returnValue(throwError(() => new Error('x')));
    const c = create();
    c.markRead(c.inbox()[0]);
    expect(c.errorMessage()).toContain('marked read');
    expect(c.inbox()[0].read).toBeFalse();
  });

  it('labels categories and statuses', () => {
    const c = create();
    expect(c.categoryLabel('PRICE_ALERT')).toBe('Price alert triggered');
    expect(c.categoryLabel('ORDER_CANCELLED')).toBe('Order cancelled');
    expect(c.statusLabel('FAILED')).toBe('Delivery failed');
    expect(c.statusLabel('SUPPRESSED')).toBe('Suppressed');
  });

  it('shows the load error alone, without the "no notifications" empty state', () => {
    service.list.and.returnValue(throwError(() => new Error('x')));
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.loadFailed()).toBeTrue();
    expect(fixture.nativeElement.querySelector('.state-title')).toBeNull();
  });

  it('refreshes the shell badge as soon as a notification is marked read', () => {
    const center = TestBed.inject(NotificationCenterService);
    spyOn(center, 'refresh');
    service.markRead.and.returnValue(of(item(1, { read: true })));
    const c = create();
    c.markRead(c.inbox()[0]);
    expect(center.refresh).toHaveBeenCalled();
  });

  it('reloads the open inbox when the shell sees more unread than it holds', () => {
    const center = TestBed.inject(NotificationCenterService);
    const fixture = TestBed.createComponent(NotificationsComponent);
    fixture.detectChanges();
    expect(service.list).toHaveBeenCalledTimes(1);

    service.list.and.returnValue(of([item(3), item(1), item(2, { read: true })]));
    center.unreadCount.set(2);
    TestBed.flushEffects();

    expect(service.list).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.inbox().length).toBe(3);
    expect(fixture.componentInstance.loading()).toBeFalse();
  });
});
