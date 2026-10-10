import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import type { Type } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router, provideRouter } from '@angular/router';
import { of, throwError, type Observable } from 'rxjs';
import { AdminService, type AdminAccount, type AdminOrder, type AdminStats, type AdminUser } from '../../core/services/admin.service';
import { AuthService } from '../../core/services/auth.service';
import { TokenService } from '../../core/services/token.service';
import { AdminAccountsComponent } from './admin-accounts.component';
import { AdminApprovalsComponent } from './admin-approvals.component';
import { AdminOrdersComponent } from './admin-orders.component';
import { AdminOverviewComponent } from './admin-overview.component';
import { AdminShellComponent } from './admin-shell.component';
import { AdminUsersComponent } from './admin-users.component';

function user(overrides: Partial<AdminUser>): AdminUser {
  return {
    userId: 1,
    uuid: 'u',
    firstName: 'Ada',
    lastName: 'Lovelace',
    username: 'ada',
    email: 'ada@example.com',
    userType: 'client',
    phoneCountryCode: '+44',
    phoneNumber: '7700900000',
    country: 'United Kingdom',
    riskAppetite: 'BALANCED',
    status: 'ACTIVE',
    accountId: 10,
    accountStatus: 'ACTIVE',
    currency: 'GBP',
    cashBalance: 100,
    createdOn: '2026-10-01T00:00:00Z',
    approvedOn: null,
    suspendedOn: null,
    rejectionReason: null,
    emailDelivered: null,
    inactive: false,
    ...overrides,
  };
}

function account(overrides: Partial<AdminAccount>): AdminAccount {
  return {
    accountId: 10,
    userId: 1,
    holderName: 'Ada Lovelace',
    username: 'ada',
    userType: 'client',
    currency: 'GBP',
    cashBalance: 100,
    status: 'ACTIVE',
    ...overrides,
  } as AdminAccount;
}

const USERS = [
  user({ userId: 1, username: 'ada', status: 'ACTIVE' }),
  user({ userId: 2, username: 'pending', firstName: 'Pending', lastName: 'Patron', status: 'INACTIVE', inactive: true, riskAppetite: 'CONSERVATIVE' }),
  user({ userId: 3, username: 'sus', status: 'SUSPENDED', riskAppetite: 'AGGRESSIVE' }),
  user({ userId: 4, username: 'rej', status: 'REJECTED', firstName: null, lastName: null, riskAppetite: null }),
];

const STATS: AdminStats = {
  totalUsers: 4,
  pendingAccounts: 1,
  totalOrders: 10,
  filledOrders: 4,
} as AdminStats;

const notFound = () =>
  throwError(() => new HttpErrorResponse({ status: 409, error: { errorCode: 'ORD-409', message: 'x' } }));

describe('Admin console', () => {
  let admin: jasmine.SpyObj<AdminService>;

  beforeEach(() => {
    admin = jasmine.createSpyObj<AdminService>('AdminService', [
      'getStats',
      'getUsers',
      'getPendingUsers',
      'approveUser',
      'rejectUser',
      'suspendUser',
      'reinstateUser',
      'getAccounts',
      'updateAccountStatus',
      'adjustBalance',
      'getOrders',
    ]);
    admin.getUsers.and.returnValue(of(USERS));
    admin.getStats.and.returnValue(of(STATS));
    admin.getAccounts.and.returnValue(of([account({}), account({ accountId: 11, status: 'SUSPENDED' })]));
    admin.getOrders.and.returnValue(of([{ orderId: 'o1', status: 'NEW' } as AdminOrder]));

    const tokens = jasmine.createSpyObj('TokenService', ['getUsername', 'getAccountId', 'isAdmin']);
    tokens.getUsername.and.returnValue('admin');
    const auth = jasmine.createSpyObj('AuthService', ['logout']);

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AdminService, useValue: admin },
        { provide: TokenService, useValue: tokens },
        { provide: AuthService, useValue: auth },
      ],
    });
  });

  function render<T>(type: Type<T>): ComponentFixture<T> {
    const fixture = TestBed.createComponent(type);
    fixture.detectChanges();
    return fixture;
  }

  describe('approval console', () => {
    it('opens on the pending queue with Approve and Reject on every applicant', () => {
      const fixture = render(AdminApprovalsComponent);
      const c = fixture.componentInstance;
      expect(c.visible().map((u) => u.username)).toEqual(['pending']);

      const buttons = Array.from(fixture.nativeElement.querySelectorAll('.queue-row button')).map(
        (b) => (b as HTMLElement).textContent!.trim(),
      );
      expect(buttons).toEqual(['Approve', 'Reject']);
    });

    it('counts each queue', () => {
      const c = render(AdminApprovalsComponent).componentInstance;
      expect(c.count('pending')).toBe(1);
      expect(c.count('active')).toBe(1);
      expect(c.count('suspended')).toBe(1);
      expect(c.count('rejected')).toBe(1);
      expect(c.count('all')).toBe(4);
    });

    it('re-filters on the tab and the search box', () => {
      const fixture = render(AdminApprovalsComponent);
      const c = fixture.componentInstance;
      c.queue.set('all');
      c.query.set('SUS');
      fixture.detectChanges();
      expect(c.visible().map((u) => u.username)).toEqual(['sus']);
      expect(fixture.nativeElement.querySelector('.queue-row').textContent).toContain('Reinstate');
    });

    it('shows Suspend for an active user', () => {
      const fixture = render(AdminApprovalsComponent);
      fixture.componentInstance.queue.set('active');
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('.queue-row').textContent).toContain('Suspend');
    });

    it('keeps the actions in view when the detail panel opens', () => {
      const fixture = render(AdminApprovalsComponent);
      fixture.componentInstance.select(USERS[1]);
      fixture.detectChanges();
      const console = fixture.nativeElement.querySelector('.console') as HTMLElement;
      expect(console.classList).toContain('with-detail');
      expect(fixture.nativeElement.querySelector('.detail-panel').textContent).toContain('Pending Patron');
      expect(fixture.nativeElement.querySelector('.queue-row button').textContent).toContain('Approve');
    });

    it('approves without needing a reason and moves the applicant to active', fakeAsync(() => {
      admin.approveUser.and.returnValue(of(user({ userId: 2, username: 'pending', status: 'ACTIVE', emailDelivered: true })));
      const fixture = render(AdminApprovalsComponent);
      const c = fixture.componentInstance;

      c.startApprove(USERS[1]);
      fixture.detectChanges();
      c.confirm(c.draft()!);

      expect(admin.approveUser).toHaveBeenCalledWith(2, {});
      expect(c.count('pending')).toBe(0);
      expect(c.notice()).toContain('active');
      expect(c.notice()).toContain('Email sent');
      tick(3300);
      expect(c.notice()).toBeNull();
    }));

    it('refuses to reject or suspend without a reason', () => {
      const c = render(AdminApprovalsComponent).componentInstance;
      c.startReject(USERS[1]);
      c.confirm(c.draft()!);
      expect(admin.rejectUser).not.toHaveBeenCalled();
      expect(c.error()).toContain('reason');

      c.startSuspend(USERS[0]);
      c.confirm(c.draft()!);
      expect(admin.suspendUser).not.toHaveBeenCalled();
    });

    it('sends the reason with a rejection, a suspension and a reinstatement', fakeAsync(() => {
      admin.rejectUser.and.returnValue(of(user({ userId: 2, status: 'REJECTED' })));
      admin.suspendUser.and.returnValue(of(user({ userId: 1, status: 'SUSPENDED', emailDelivered: false })));
      admin.reinstateUser.and.returnValue(of(user({ userId: 3, status: 'ACTIVE' })));
      const c = render(AdminApprovalsComponent).componentInstance;

      c.confirm({ action: 'reject', user: USERS[1], reason: ' bad docs ' });
      expect(admin.rejectUser).toHaveBeenCalledWith(2, { reason: 'bad docs' });

      c.confirm({ action: 'suspend', user: USERS[0], reason: 'review' });
      expect(admin.suspendUser).toHaveBeenCalledWith(1, { reason: 'review' });
      expect(c.notice()).toContain('Email delivery failed');

      c.confirm({ action: 'reinstate', user: USERS[2], reason: '' });
      expect(admin.reinstateUser).toHaveBeenCalledWith(3, {});
      tick(3300);
    }));

    it('shows a readable error when an action is refused', () => {
      admin.approveUser.and.returnValue(notFound());
      const c = render(AdminApprovalsComponent).componentInstance;
      c.confirm({ action: 'approve', user: USERS[1], reason: '' });
      expect(c.error()).toBeTruthy();
      expect(c.busyId()).toBeNull();
    });

    it('shows a readable error when the queue cannot load', () => {
      admin.getUsers.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      const fixture = render(AdminApprovalsComponent);
      expect(fixture.componentInstance.error()).toBeTruthy();
      expect(fixture.componentInstance.loading()).toBeFalse();
    });

    it('keeps the selected applicant across a refresh', () => {
      const c = render(AdminApprovalsComponent).componentInstance;
      c.select(USERS[2]);
      c.load();
      expect(c.selected()?.userId).toBe(3);
    });

    it('names, tones and prompts consistently', () => {
      const c = render(AdminApprovalsComponent).componentInstance;
      expect(c.fullName(USERS[3])).toBe('rej');
      expect(c.initials(USERS[0])).toBe('AL');
      expect(c.statusTone('ACTIVE')).toBe('positive');
      expect(c.statusTone('INACTIVE')).toBe('warning');
      expect(c.statusTone('REJECTED')).toBe('negative');
      expect(c.statusTone('OTHER')).toBe('');
      expect(c.riskTone(null)).toBe('');
      expect(c.riskTone('AGGRESSIVE')).toBe('negative');
      for (const a of ['reject', 'suspend', 'reinstate', 'approve'] as const) {
        expect(c.reasonPlaceholder(a)).toBeTruthy();
      }
      c.startReinstate(USERS[2]);
      expect(c.draft()?.action).toBe('reinstate');
      c.cancel();
      expect(c.draft()).toBeNull();
    });

    it('renders the confirm step for each action', () => {
      const fixture = render(AdminApprovalsComponent);
      const c = fixture.componentInstance;
      for (const start of [c.startApprove, c.startReject, c.startSuspend, c.startReinstate]) {
        start.call(c, USERS[1]);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.action-title')).toBeTruthy();
      }
    });
  });

  describe('accounts', () => {
    it('lists accounts and filters them by status', () => {
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;
      expect(c.filtered().length).toBe(2);
      c.statusFilter.set('SUSPENDED');
      fixture.detectChanges();
      expect(c.filtered().map((a) => a.accountId)).toEqual([11]);
    });

    it('opens an in-page confirmation, not a browser prompt, when suspending', () => {
      const prompt = spyOn(window, 'prompt');
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;

      c.setStatus(c.accounts()[0], 'SUSPENDED');
      fixture.detectChanges();

      expect(prompt).not.toHaveBeenCalled();
      expect(admin.updateAccountStatus).not.toHaveBeenCalled();
      const panel = fixture.nativeElement.querySelector('[data-testid=suspend-panel]') as HTMLElement;
      expect(panel).toBeTruthy();
      expect(panel.textContent).toContain('Suspend account #10');
      expect(c.accounts()[0].status).toBe('ACTIVE');
    });

    it('the Suspend button opens the same panel', () => {
      const fixture = render(AdminAccountsComponent);
      (fixture.nativeElement.querySelector('[data-testid=suspend-account]') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid=suspend-panel]')).toBeTruthy();
    });

    it('requires a reason before suspending', () => {
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;
      c.startSuspend(c.accounts()[0]);
      c.suspendReason.set('   ');
      c.confirmSuspend(c.accounts()[0]);
      fixture.detectChanges();

      expect(admin.updateAccountStatus).not.toHaveBeenCalled();
      expect(c.suspendError()).toContain('reason');
      expect(fixture.nativeElement.querySelector('[data-testid=suspend-panel] [role=alert]')).toBeTruthy();
    });

    it('sends the reason, closes the panel and says the holder was emailed', fakeAsync(() => {
      admin.updateAccountStatus.and.returnValue(of(account({ status: 'SUSPENDED', emailDelivered: true })));
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;
      c.startSuspend(c.accounts()[0]);
      c.suspendReason.set('  Unusual activity ');
      c.confirmSuspend(c.accounts()[0]);
      fixture.detectChanges();

      expect(admin.updateAccountStatus).toHaveBeenCalledWith(10, 'SUSPENDED', 'Unusual activity');
      expect(c.suspendingId()).toBeNull();
      expect(c.accounts()[0].status).toBe('SUSPENDED');
      expect(c.notice()).toContain('emailed');
      expect(fixture.nativeElement.querySelector('.success-banner').textContent).toContain('emailed');
      tick(3300);
    }));

    it('says so when the suspension email could not be sent', fakeAsync(() => {
      admin.updateAccountStatus.and.returnValue(of(account({ status: 'SUSPENDED', emailDelivered: false })));
      const c = render(AdminAccountsComponent).componentInstance;
      c.startSuspend(c.accounts()[0]);
      c.suspendReason.set('r');
      c.confirmSuspend(c.accounts()[0]);
      expect(c.notice()).toContain('could not be sent');
      tick(3300);
    }));

    it('cancelling the panel changes nothing', () => {
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;
      c.startSuspend(c.accounts()[0]);
      c.cancelSuspend();
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[data-testid=suspend-panel]')).toBeNull();
      expect(admin.updateAccountStatus).not.toHaveBeenCalled();
    });

    it('opening the funding row closes the suspension panel, and back', () => {
      const c = render(AdminAccountsComponent).componentInstance;
      c.startSuspend(c.accounts()[0]);
      c.startFunding(c.accounts()[0]);
      expect(c.suspendingId()).toBeNull();
      c.startSuspend(c.accounts()[0]);
      expect(c.fundingId()).toBeNull();
    });

    it('re-activates straight away, without a confirmation', fakeAsync(() => {
      admin.updateAccountStatus.and.returnValue(of(account({ accountId: 11, status: 'ACTIVE', emailDelivered: true })));
      const c = render(AdminAccountsComponent).componentInstance;
      c.setStatus(c.accounts()[1], 'ACTIVE');
      expect(c.suspendingId()).toBeNull();
      expect(admin.updateAccountStatus).toHaveBeenCalledWith(11, 'ACTIVE', undefined);
      tick(3300);
    }));

    it('ignores a status that would not change anything', () => {
      const c = render(AdminAccountsComponent).componentInstance;
      c.setStatus(c.accounts()[0], 'ACTIVE');
      c.setStatus(c.accounts()[0], '');
      expect(admin.updateAccountStatus).not.toHaveBeenCalled();
    });

    it('reports a failed status change', () => {
      admin.updateAccountStatus.and.returnValue(throwError(() => new Error('x')));
      const c = render(AdminAccountsComponent).componentInstance;
      c.setStatus(c.accounts()[0], 'CLOSED');
      expect(c.error()).toContain('10');
      expect(c.savingId()).toBeNull();
    });

    it('funds an account with an amount and a reason', fakeAsync(() => {
      admin.adjustBalance.and.returnValue(of(account({ cashBalance: 600 })));
      const fixture = render(AdminAccountsComponent);
      const c = fixture.componentInstance;
      c.startFunding(c.accounts()[0]);
      fixture.detectChanges();
      c.fundAmount.set(500);
      c.fundReason.set('deposit');
      c.applyFunding(c.accounts()[0]);

      expect(admin.adjustBalance).toHaveBeenCalledWith(10, 500, 'deposit');
      expect(c.fundingId()).toBeNull();
      expect(c.accounts()[0].cashBalance).toBe(600);
      tick(3300);
    }));

    it('asks for an amount before funding', () => {
      const c = render(AdminAccountsComponent).componentInstance;
      c.startFunding(c.accounts()[0]);
      c.applyFunding(c.accounts()[0]);
      expect(admin.adjustBalance).not.toHaveBeenCalled();
      expect(c.error()).toContain('amount');
    });

    it('reports a failed funding and a failed load', () => {
      admin.adjustBalance.and.returnValue(throwError(() => new Error('x')));
      const c = render(AdminAccountsComponent).componentInstance;
      c.fundAmount.set(5);
      c.applyFunding(c.accounts()[0]);
      expect(c.error()).toContain('balance');

      admin.getAccounts.and.returnValue(throwError(() => new Error('x')));
      c.load();
      expect(c.error()).toContain('accounts');
    });
  });

  describe('orders', () => {
    it('loads the order book with the chosen filter and limit', () => {
      const c = render(AdminOrdersComponent).componentInstance;
      expect(admin.getOrders).toHaveBeenCalledWith({ status: undefined, limit: 50 });
      c.statusFilter.set('REJECTED');
      c.limit.set(10);
      c.load();
      expect(admin.getOrders).toHaveBeenCalledWith({ status: 'REJECTED', limit: 10 });
      expect(c.orders().length).toBe(1);
    });

    it('reports a failed load', () => {
      admin.getOrders.and.returnValue(throwError(() => new Error('x')));
      const c = render(AdminOrdersComponent).componentInstance;
      expect(c.error()).toContain('orders');
    });
  });

  describe('users', () => {
    it('lists every user and searches across names and email', () => {
      const fixture = render(AdminUsersComponent);
      const c = fixture.componentInstance;
      expect(c.filtered().length).toBe(4);
      c.query.set('patron');
      fixture.detectChanges();
      expect(c.filtered().map((u) => u.username)).toEqual(['pending']);
      expect(c.fullName(USERS[3])).toBe('rej');
    });

    it('reports a failed load', () => {
      admin.getUsers.and.returnValue(throwError(() => new Error('x')));
      expect(render(AdminUsersComponent).componentInstance.error()).toContain('users');
    });
  });

  describe('overview', () => {
    it('splits users across the lifecycle ring', () => {
      const fixture = render(AdminOverviewComponent);
      const ring = fixture.componentInstance.lifecycle();
      expect(ring.map((s) => s.key)).toEqual(['active', 'pending', 'suspended', 'rejected']);
      expect(ring.reduce((s, x) => s + x.percent, 0)).toBeCloseTo(1, 6);
    });

    it('works out the approval backlog and fill rate', () => {
      const c = render(AdminOverviewComponent).componentInstance;
      expect(c.approvalBacklogRatio()).toBeCloseTo(0.25, 6);
      expect(c.fillRate()).toBeCloseTo(0.4, 6);
    });

    it('shows zeros, not NaN, for an empty platform', () => {
      admin.getStats.and.returnValue(of({ ...STATS, totalUsers: 0, totalOrders: 0 } as AdminStats));
      admin.getUsers.and.returnValue(of([]));
      const c = render(AdminOverviewComponent).componentInstance;
      expect(c.approvalBacklogRatio()).toBe(0);
      expect(c.fillRate()).toBe(0);
      expect(c.lifecycle()).toEqual([]);
    });

    it('keeps the metrics when only the user list fails', () => {
      admin.getUsers.and.returnValue(throwError(() => new Error('x')) as Observable<AdminUser[]>);
      const c = render(AdminOverviewComponent).componentInstance;
      expect(c.stats()).toEqual(STATS);
      expect(c.users()).toEqual([]);
    });

    it('explains a failure to load the metrics', () => {
      admin.getStats.and.returnValue(throwError(() => new Error('x')));
      expect(render(AdminOverviewComponent).componentInstance.error()).toContain('administrator');
    });
  });

  describe('admin shell', () => {
    it('shows the page title for the current route and no Instruments link', () => {
      const fixture = render(AdminShellComponent);
      const links = Array.from(fixture.nativeElement.querySelectorAll('.nav-item')).map((a) =>
        (a as HTMLElement).textContent!.trim(),
      );
      expect(links).toEqual(['Overview', 'Accounts', 'Approvals', 'Users', 'Orders']);
      expect(fixture.componentInstance.page().title).toBeTruthy();
      expect(fixture.componentInstance.initials()).toBe('AD');
      expect(fixture.componentInstance.username).toBe('admin');
      fixture.destroy();
    });

    it('follows navigation to the matching page title', async () => {
      const fixture = render(AdminShellComponent);
      const router = TestBed.inject(Router);
      router.resetConfig([{ path: 'admin/users', children: [] }]);
      await router.navigateByUrl('/admin/users');
      expect(fixture.componentInstance.page().title).toBe('User Directory');
      fixture.destroy();
    });

    it('toggles the theme and signs out', () => {
      const fixture = render(AdminShellComponent);
      const c = fixture.componentInstance;
      const before = c.currentTheme;
      c.toggleTheme();
      expect(c.currentTheme).not.toBe(before);
      c.toggleTheme();
      c.logout();
      expect(TestBed.inject(AuthService).logout).toHaveBeenCalled();
      fixture.destroy();
    });
  });
});
