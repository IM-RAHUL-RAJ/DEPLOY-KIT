import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
   Component, computed, inject, signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminAccount, AdminService } from '../../core/services/admin.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';

const ACCOUNT_STATUSES = ['ACTIVE', 'INACTIVE', 'SUSPENDED', 'CLOSED'];

@Component({
  selector: 'app-admin-accounts',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin-toolbar">
      <div class="admin-toolbar-group">
        <span class="panel-title">Accounts</span>
        <select class="admin-select" [ngModel]="statusFilter()" (ngModelChange)="statusFilter.set($event)">
          <option value="">All statuses</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s }}</option>
          }
        </select>
        <span class="text-muted">{{ filtered().length }} shown</span>
      </div>
      <button class="admin-btn" (click)="load()" [disabled]="loading()">Refresh</button>
    </div>

    @if (error()) {
      <div class="error-banner"><span class="icon">!</span>{{ error() }}</div>
    }
    @if (notice()) {
      <div class="success-banner"><span class="icon">✓</span>{{ notice() }}</div>
    }

    @if (loading()) {
      <div class="admin-loading"><span class="spinner"></span> Loading accounts…</div>
    } @else {
      <div class="card">
        <div class="admin-table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Holder</th>
                <th>Type</th>
                <th class="right">Cash balance</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (a of filtered(); track a.accountId) {
                <tr>
                  <td class="mono-id">{{ a.accountId }}</td>
                  <td>
                    <div class="text-bold">{{ a.holderName }}</div>
                    <div class="text-muted">{{ a.username }} · user {{ a.userId }}</div>
                  </td>
                  <td>
                    <span class="type-badge" [class.admin]="a.userType === 'admin'">{{ a.userType }}</span>
                  </td>
                  <td class="right mono">{{ a.cashBalance | number: '1.2-2' }} {{ a.currency }}</td>
                  <td>
                    <select
                      class="admin-select"
                      [ngModel]="a.status"
                      (ngModelChange)="setStatus(a, $event)"
                      [disabled]="savingId() === a.accountId"
                    >
                      @for (s of statuses; track s) {
                        <option [value]="s">{{ s }}</option>
                      }
                    </select>
                  </td>
                  <td>
                    <div class="admin-actions">
                      <button class="admin-btn" (click)="startFunding(a)">Adjust</button>
                      <button
                        class="admin-btn danger"
                        (click)="startSuspend(a)"
                        [disabled]="a.status === 'SUSPENDED' || savingId() === a.accountId"
                        data-testid="suspend-account"
                      >
                        Suspend
                      </button>
                    </div>
                  </td>
                </tr>
                @if (suspendingId() === a.accountId) {
                  <tr>
                    <td colspan="6">
                      <div class="action-box danger-box" role="group" [attr.aria-labelledby]="'suspend-title-' + a.accountId"
                           data-testid="suspend-panel">
                        <div class="action-title" [id]="'suspend-title-' + a.accountId">
                          Suspend account #{{ a.accountId }} · {{ a.holderName }}
                        </div>
                        <p class="text-secondary">
                          The holder is suspended with the account and can place no orders until reinstated.
                          They are emailed this reason.
                        </p>
                        <label class="field-label" [for]="'suspend-reason-' + a.accountId">Reason (required)</label>
                        <input
                          [id]="'suspend-reason-' + a.accountId"
                          class="admin-input wide"
                          type="text"
                          maxlength="255"
                          placeholder="Suspected market manipulation"
                          [ngModel]="suspendReason()"
                          (ngModelChange)="suspendReason.set($event)"
                          (keydown.enter)="confirmSuspend(a)"
                          (keydown.escape)="cancelSuspend()"
                          data-testid="suspend-reason"
                        />
                        @if (suspendError()) {
                          <span class="neg-text" role="alert">{{ suspendError() }}</span>
                        }
                        <div class="admin-actions">
                          <button class="admin-btn danger" (click)="confirmSuspend(a)"
                                  [disabled]="savingId() === a.accountId" data-testid="confirm-suspend">
                            Confirm suspend
                          </button>
                          <button class="admin-btn" (click)="cancelSuspend()">Cancel</button>
                        </div>
                      </div>
                    </td>
                  </tr>
                }
                @if (fundingId() === a.accountId) {
                  <tr>
                    <td colspan="6">
                      <div class="admin-actions">
                        <span class="text-secondary">Adjust balance (use a negative amount to debit):</span>
                        <input class="admin-input" type="number" step="0.01" placeholder="0.00" [ngModel]="fundAmount()" (ngModelChange)="fundAmount.set($event)" />
                        <input class="admin-input" style="width: 220px" type="text" placeholder="Reason" [ngModel]="fundReason()" (ngModelChange)="fundReason.set($event)" />
                        <button class="admin-btn primary" (click)="applyFunding(a)" [disabled]="savingId() === a.accountId">
                          Apply
                        </button>
                        <button class="admin-btn" (click)="fundingId.set(null)">Cancel</button>
                      </div>
                    </td>
                  </tr>
                }
              } @empty {
                <tr><td colspan="6" class="admin-empty">No accounts match this filter.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  `,
})
export class AdminAccountsComponent {
  private readonly admin = inject(AdminService);

  readonly statuses = ACCOUNT_STATUSES;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly accounts = signal<AdminAccount[]>([]);
  readonly savingId = signal<number | null>(null);
  readonly fundingId = signal<number | null>(null);

  /** The account whose suspension is being confirmed in its inline panel, if any. */
  readonly suspendingId = signal<number | null>(null);
  readonly suspendReason = signal('');
  readonly suspendError = signal<string | null>(null);

  /** Signals, not fields: OnPush will not re-render a plain property change. */
  readonly statusFilter = signal('');
  readonly fundAmount = signal<number | null>(null);
  readonly fundReason = signal('');

  readonly filtered = computed(() => {
    const f = this.statusFilter();
    return f ? this.accounts().filter((a) => a.status === f) : this.accounts();
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.admin.getAccounts().subscribe({
      next: (list) => {
        this.accounts.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load accounts.');
        this.loading.set(false);
      },
    });
  }

  /**
   * A change from the status dropdown. A suspension opens the inline confirm
   * panel, because it needs a reason the holder is emailed; anything else is sent.
   */
  setStatus(account: AdminAccount, status: string): void {
    if (!status || status === account.status) return;
    if (status === 'SUSPENDED') {
      // Put the dropdown back until the suspension is confirmed.
      this.accounts.set([...this.accounts()]);
      this.startSuspend(account);
      return;
    }
    this.send(account, status);
  }

  startSuspend(account: AdminAccount): void {
    this.fundingId.set(null);
    this.suspendingId.set(account.accountId);
    this.suspendReason.set('');
    this.suspendError.set(null);
  }

  cancelSuspend(): void {
    this.suspendingId.set(null);
    this.suspendError.set(null);
  }

  confirmSuspend(account: AdminAccount): void {
    const reason = this.suspendReason().trim();
    if (!reason) {
      this.suspendError.set('Enter a reason: the account holder is emailed it.');
      return;
    }
    this.send(account, 'SUSPENDED', reason);
  }

  private send(account: AdminAccount, status: string, reason?: string): void {
    this.savingId.set(account.accountId);
    this.error.set(null);
    this.admin.updateAccountStatus(account.accountId, status, reason).subscribe({
      next: (updated) => {
        this.suspendingId.set(null);
        let message = `Account ${updated.accountId} set to ${updated.status}.`;
        if (updated.emailDelivered === true) message += ' ✓ The account holder was emailed.';
        if (updated.emailDelivered === false) message += ' ⚠ The email could not be sent.';
        this.replace(updated, message);
      },
      error: (err: HttpErrorResponse) => {
        // A refusal with a catalogue code explains itself; anything else names the account.
        this.error.set(
          err?.error?.errorCode
            ? resolveErrorMessage(err.error.errorCode, err.status)
            : `Could not update account ${account.accountId}.`,
        );
        this.savingId.set(null);
      },
    });
  }

  startFunding(account: AdminAccount): void {
    this.suspendingId.set(null);
    this.fundingId.set(account.accountId);
    this.fundAmount.set(null);
    this.fundReason.set('');
  }

  applyFunding(account: AdminAccount): void {
    const amount = this.fundAmount();
    if (amount === null || Number.isNaN(amount)) {
      this.error.set('Enter an amount before applying.');
      return;
    }
    this.savingId.set(account.accountId);
    this.error.set(null);
    this.admin.adjustBalance(account.accountId, amount, this.fundReason() || undefined).subscribe({
      next: (updated) => {
        this.fundingId.set(null);
        this.replace(updated, `Balance updated for account ${updated.accountId}.`);
      },
      error: () => {
        this.error.set(`Could not adjust the balance for account ${account.accountId}.`);
        this.savingId.set(null);
      },
    });
  }

  private replace(updated: AdminAccount, message: string): void {
    this.accounts.set(this.accounts().map((a) => (a.accountId === updated.accountId ? updated : a)));
    this.savingId.set(null);
    this.notice.set(message);
    setTimeout(() => this.notice.set(null), 3200);
  }
}
