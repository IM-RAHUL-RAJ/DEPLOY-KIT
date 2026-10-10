import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
   Component, computed, inject, signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { AdminService, AdminUser } from '../../core/services/admin.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';

type Queue = 'pending' | 'active' | 'suspended' | 'rejected' | 'all';

/** The four lifecycle states an admin can move a user between, in gate order. */
const QUEUES: { key: Queue; label: string; hint: string }[] = [
  { key: 'pending', label: 'Awaiting review', hint: 'Email verified, waiting on an administrator' },
  { key: 'active', label: 'Active', hint: 'Approved and able to trade' },
  { key: 'suspended', label: 'Suspended', hint: 'Trading blocked pending reinstatement' },
  { key: 'rejected', label: 'Rejected', hint: 'Application declined, reason recorded' },
  { key: 'all', label: 'Everyone', hint: 'All registered identities' },
];

const RISK_TONE: Record<string, string> = {
  CONSERVATIVE: 'positive',
  BALANCED: 'warning',
  AGGRESSIVE: 'negative',
};

/** One in-flight lifecycle action awaiting confirmation. */
interface ApprovalDraft {
  action: 'approve' | 'reject' | 'suspend' | 'reinstate';
  user: AdminUser;
  reason: string;
}

@Component({
  selector: 'app-admin-approvals',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin-toolbar">
      <div class="admin-toolbar-group">
        <span class="panel-title">Approval console</span>
        <input
          class="admin-input"
          style="width: 240px"
          type="search"
          placeholder="Search name, username, email, country"
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
        />
        <span class="text-muted">{{ visible().length }} shown</span>
      </div>
      <button class="admin-btn" (click)="load()" [disabled]="loading()">Refresh</button>
    </div>

    <div class="queue-tabs">
      @for (q of queues; track q.key) {
        <button class="queue-tab" [class.active]="queue() === q.key" (click)="queue.set(q.key)">
          <span class="queue-label">{{ q.label }}</span>
          <span class="queue-count">{{ count(q.key) }}</span>
          <span class="queue-hint">{{ q.hint }}</span>
        </button>
      }
    </div>

    @if (error()) {
      <div class="error-banner"><span class="icon">!</span>{{ error() }}</div>
    }
    @if (notice()) {
      <div class="success-banner"><span class="icon">&#10003;</span>{{ notice() }}</div>
    }

    @if (loading()) {
      <div class="admin-loading"><span class="spinner"></span> Loading the approval queue&hellip;</div>
    } @else if (visible().length === 0) {
      <div class="card empty-state">
        <span class="empty-mark">
          <svg viewBox="0 0 24 24" class="i"><path d="M20 6 9 17l-5-5" /></svg>
        </span>
        <span class="empty-title">Nothing here</span>
        <span class="text-muted">No identity matches this view.</span>
      </div>
    } @else {
      <div class="console" [class.with-detail]="!!selected()">
        <div class="card queue-panel">
          <table class="data-table">
            <thead>
              <tr>
                <th>Applicant</th>
                <th>Country</th>
                <th>Risk</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (u of visible(); track u.userId) {
                <tr
                  class="queue-row"
                  [class.selected]="selected()?.userId === u.userId"
                  (click)="select(u)"
                >
                  <td>
                    <div class="text-bold">{{ fullName(u) }}</div>
                    <div class="text-muted">{{ u.username }} &middot; {{ u.email }}</div>
                  </td>
                  <td>
                    <div>{{ u.country ?? '—' }}</div>
                    <div class="text-muted mono">
                      {{ u.phoneCountryCode ?? '' }}{{ u.phoneNumber ?? '' }}
                    </div>
                  </td>
                  <td>
                    <span class="tone-badge" [class]="'tone-badge ' + riskTone(u.riskAppetite)">
                      {{ u.riskAppetite ?? 'UNSET' }}
                    </span>
                  </td>
                  <td>
                    <span class="tone-badge" [class]="'tone-badge ' + statusTone(u.status)">{{ u.status }}</span>
                  </td>
                  <td (click)="$event.stopPropagation()">
                    <div class="admin-actions">
                      @if (u.inactive) {
                        <button class="admin-btn primary" (click)="startApprove(u)" [disabled]="busyId() === u.userId">
                          Approve
                        </button>
                        <button class="admin-btn danger" (click)="startReject(u)" [disabled]="busyId() === u.userId">
                          Reject
                        </button>
                      } @else if (u.status === 'ACTIVE') {
                        <button class="admin-btn danger" (click)="startSuspend(u)" [disabled]="busyId() === u.userId">
                          Suspend
                        </button>
                      } @else if (u.status === 'SUSPENDED') {
                        <button class="admin-btn primary" (click)="startReinstate(u)" [disabled]="busyId() === u.userId">
                          Reinstate
                        </button>
                      } @else {
                        <span class="text-muted">—</span>
                      }
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        @if (selected(); as s) {
          <div class="card detail-panel">
            <div class="detail-head">
              <span class="avatar-lg">{{ initials(s) }}</span>
              <div>
                <div class="detail-name">{{ fullName(s) }}</div>
                <div class="text-muted">{{ s.username }}</div>
              </div>
              <span class="tone-badge" [class]="'tone-badge ' + statusTone(s.status)">{{ s.status }}</span>
            </div>

            <dl class="detail-grid">
              <dt>Email</dt>
              <dd class="mono">{{ s.email }}</dd>
              <dt>User ID</dt>
              <dd class="mono-id">{{ s.userId }}</dd>
              <dt>UUID</dt>
              <dd class="mono">{{ s.uuid }}</dd>
              <dt>Type</dt>
              <dd>{{ s.userType }}</dd>
              <dt>Country</dt>
              <dd>{{ s.country ?? '—' }}</dd>
              <dt>Phone</dt>
              <dd class="mono">{{ s.phoneCountryCode ?? '—' }} {{ s.phoneNumber ?? '' }}</dd>
              <dt>Risk appetite</dt>
              <dd>
                <span class="tone-badge" [class]="'tone-badge ' + riskTone(s.riskAppetite)">
                  {{ s.riskAppetite ?? 'UNSET' }}
                </span>
              </dd>
              <dt>Account</dt>
              <dd>
                @if (s.accountId) {
                  <span class="mono-id">#{{ s.accountId }}</span>
                } @else {
                  <span class="text-muted">none</span>
                }
              </dd>
              <dt>Account status</dt>
              <dd>{{ s.accountStatus ?? '—' }}</dd>
              <dt>Ledger currency</dt>
              <dd>{{ s.currency ?? '—' }}</dd>
              <dt>Cash balance</dt>
              <dd class="mono">
                @if (s.cashBalance !== null) {
                  {{ s.cashBalance | number: '1.2-2' }} {{ s.currency }}
                } @else {
                  <span class="text-muted">—</span>
                }
              </dd>
              <dt>Registered</dt>
              <dd>{{ s.createdOn | date: 'medium' }}</dd>
              @if (s.approvedOn) {
                <dt>Approved</dt>
                <dd>{{ s.approvedOn | date: 'medium' }}</dd>
              }
              @if (s.suspendedOn) {
                <dt>Suspended</dt>
                <dd>{{ s.suspendedOn | date: 'medium' }}</dd>
              }
              @if (s.rejectionReason) {
                <dt>Rejection reason</dt>
                <dd class="neg-text">{{ s.rejectionReason }}</dd>
              }
              @if (s.emailDelivered !== null) {
                <dt>Last email</dt>
                <dd [class.pos-text]="s.emailDelivered" [class.neg-text]="!s.emailDelivered">
                  @if (s.emailDelivered) {
                    ✓ Successfully sent
                  } @else {
                    ⚠ Failed to deliver
                  }
                </dd>
              }
            </dl>

            @if (draft(); as d) {
              <div class="action-box" [class.danger-box]="d.action === 'reject' || d.action === 'suspend'">
                <div class="action-title">
                  @switch (d.action) {
                    @case ('approve') { Approve {{ fullName(d.user) }} }
                    @case ('reject') { Reject {{ fullName(d.user) }} }
                    @case ('suspend') { Suspend {{ fullName(d.user) }} }
                    @case ('reinstate') { Reinstate {{ fullName(d.user) }} }
                  }
                </div>

                @if (d.action === 'reinstate') {
                  <p class="text-secondary">
                    Returns the user and account to ACTIVE. The balance is untouched: the account was frozen,
                    not drained, and reinstatement is not a second approval.
                  </p>
                } @else if (d.action === 'approve') {
                  <p class="text-secondary">
                    Moves the user and account {{ d.user.accountId ? '#' + d.user.accountId : '' }} to ACTIVE.
                    The cash balance stays at zero: fund the account from Accounts once it is open.
                  </p>
                } @else {
                  <p class="text-secondary">
                    This is recorded in the status change log against your account and shown on the applicant record.
                  </p>
                  <label class="field-label" for="action-reason">Reason (required)</label>
                  <input
                    id="action-reason"
                    class="admin-input wide"
                    type="text"
                    [placeholder]="reasonPlaceholder(d.action)"
                    [ngModel]="d.reason"
                    (ngModelChange)="d.reason = $event"
                  />
                }

                <div class="admin-actions">
                  <button
                    class="admin-btn"
                    [class.primary]="d.action === 'approve' || d.action === 'reinstate'"
                    [class.danger]="d.action === 'reject' || d.action === 'suspend'"
                    (click)="confirm(d)"
                    [disabled]="busyId() === d.user.userId"
                  >
                    Confirm {{ d.action }}
                  </button>
                  <button class="admin-btn" (click)="cancel()">Cancel</button>
                </div>
              </div>
            }
          </div>
        }
      </div>
    }
  `,
})
export class AdminApprovalsComponent {
  private readonly admin = inject(AdminService);

  readonly queues = QUEUES;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly users = signal<AdminUser[]>([]);
  readonly selected = signal<AdminUser | null>(null);
  readonly busyId = signal<number | null>(null);

  /** Non-null while a confirm step is open for one user. */
  readonly draft = signal<ApprovalDraft | null>(null);

  /**
   * Signals, not plain fields: `visible` is a computed, and a computed only
   * recomputes when a signal it reads changes. As plain properties these two
   * left the list frozen on whatever the tab and search box were first set to.
   */
  readonly queue = signal<Queue>('pending');
  readonly query = signal('');

  readonly visible = computed(() => {
    const q = this.query().trim().toLowerCase();
    const inQueue = this.users().filter((u) => this.matchesQueue(u, this.queue()));
    if (!q) return inQueue;
    return inQueue.filter((u) =>
      [u.firstName, u.lastName, u.username, u.email, u.country, u.userType, u.status]
        .filter((v): v is string => !!v)
        .some((v) => v.toLowerCase().includes(q)),
    );
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.admin.getUsers().subscribe({
      next: (list) => {
        this.users.set(list);
        const sel = this.selected();
        // Keep the detail panel on the same person across a refresh.
        if (sel) {
          this.selected.set(list.find((u) => u.userId === sel.userId) ?? null);
        }
        this.loading.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(
          resolveErrorMessage(err.error?.errorCode, err.status) || 'Could not load the approval queue.',
        );
        this.loading.set(false);
      },
    });
  }

  count(queue: Queue): number {
    return this.users().filter((u) => this.matchesQueue(u, queue)).length;
  }

  private matchesQueue(u: AdminUser, queue: Queue): boolean {
    switch (queue) {
      case 'all':
        return true;
      case 'pending':
        return u.inactive || u.status === 'INACTIVE';
      case 'active':
        return u.status === 'ACTIVE';
      case 'suspended':
        return u.status === 'SUSPENDED';
      case 'rejected':
        return u.status === 'REJECTED';
    }
  }

  fullName(u: AdminUser): string {
    const name = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim();
    return name || u.username;
  }

  initials(u: AdminUser): string {
    const first = (u.firstName ?? u.username).charAt(0);
    const last = (u.lastName ?? '').charAt(0);
    return (first + last).toUpperCase();
  }

  statusTone(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'positive';
      case 'INACTIVE':
        return 'warning';
      case 'SUSPENDED':
      case 'REJECTED':
        return 'negative';
      default:
        return '';
    }
  }

  riskTone(risk: string | null): string {
    return risk ? (RISK_TONE[risk] ?? '') : '';
  }

  select(u: AdminUser): void {
    this.draft.set(null);
    this.selected.set(u);
  }

  reasonPlaceholder(action: ApprovalDraft['action']): string {
    switch (action) {
      case 'reject':
        return 'Identity documents could not be verified';
      case 'suspend':
        return 'Suspected market manipulation';
      case 'reinstate':
        return 'Investigation closed, trading permitted again';
      default:
        return 'Reason';
    }
  }

  startApprove(u: AdminUser): void {
    this.selected.set(u);
    this.draft.set({ action: 'approve', user: u, reason: '' });
  }

  startReject(u: AdminUser): void {
    this.selected.set(u);
    this.draft.set({ action: 'reject', user: u, reason: '' });
  }

  startSuspend(u: AdminUser): void {
    this.selected.set(u);
    this.draft.set({ action: 'suspend', user: u, reason: '' });
  }

  startReinstate(u: AdminUser): void {
    this.selected.set(u);
    this.draft.set({ action: 'reinstate', user: u, reason: '' });
  }

  cancel(): void {
    this.draft.set(null);
  }

  confirm(draft: ApprovalDraft): void {
    const reason = draft.reason.trim();
    if (draft.action !== 'approve' && draft.action !== 'reinstate' && !reason) {
      this.error.set('A reason is required so the applicant record explains what happened.');
      return;
    }

    this.busyId.set(draft.user.userId);
    this.error.set(null);

    let call: Observable<AdminUser>;
    if (draft.action === 'approve') {
      call = this.admin.approveUser(draft.user.userId, {
        ...(reason ? { reason } : {}),
      });
    } else if (draft.action === 'reject') {
      call = this.admin.rejectUser(draft.user.userId, { reason });
    } else if (draft.action === 'suspend') {
      call = this.admin.suspendUser(draft.user.userId, { reason });
    } else {
      call = this.admin.reinstateUser(draft.user.userId, reason ? { reason } : {});
    }

    call.subscribe({
      next: (updated) => {
        this.users.set(this.users().map((u) => (u.userId === updated.userId ? updated : u)));
        this.selected.set(updated);
        this.busyId.set(null);
        this.draft.set(null);
        
        // Build notification message with email status
        let message = `${this.fullName(updated)} is now ${updated.status.replace('_', ' ').toLowerCase()}.`;
        if ((updated.status === 'SUSPENDED' || updated.status === 'ACTIVE') && updated.emailDelivered !== null) {
          const emailStatus = updated.emailDelivered 
            ? '✓ Email sent successfully'
            : '⚠ Email delivery failed or SMTP not configured';
          message += ` ${emailStatus}`;
        }
        
        this.flash(message);
      },
      error: (err: HttpErrorResponse) => {
        this.error.set(resolveErrorMessage(err.error?.errorCode, err.status) || 'That action could not be completed.');
        this.busyId.set(null);
      },
    });
  }

  private flash(message: string): void {
    this.notice.set(message);
    setTimeout(() => this.notice.set(null), 3200);
  }
}