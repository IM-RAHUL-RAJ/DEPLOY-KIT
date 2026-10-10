import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
   Component, computed, inject, signal
} from '@angular/core';
import { AdminService, AdminStats, AdminUser } from '../../core/services/admin.service';

@Component({
  selector: 'app-admin-overview',
  standalone: true,
  imports: [CommonModule],
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin-toolbar">
      <span class="panel-title">Platform metrics</span>
      <button class="admin-btn" (click)="load()" [disabled]="loading()">Refresh</button>
    </div>

    @if (error()) {
      <div class="error-banner"><span class="icon">!</span>{{ error() }}</div>
    }

    @if (loading()) {
      <div class="admin-loading"><span class="spinner"></span> Loading platform metrics…</div>
    } @else if (stats(); as s) {
      <div class="admin-grid">
        <div class="card admin-stat">
          <span class="stat-label">Users</span>
          <span class="stat-value">{{ s.totalUsers }}</span>
          <span class="stat-foot">Registered identities</span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Accounts</span>
          <span class="stat-value">{{ s.totalAccounts }}</span>
          <span class="stat-foot">
            <span class="tone-badge positive">{{ s.activeAccounts }} active</span>
            <span class="tone-badge warning">{{ s.pendingAccounts }} pending</span>
            @if (s.suspendedAccounts) {
              <span class="tone-badge negative">{{ s.suspendedAccounts }} suspended</span>
            }
          </span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Total cash</span>
          <span class="stat-value">$ {{ s.totalCash | number: '1.2-2' }}</span>
          <span class="stat-foot">Across all accounts</span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Instruments</span>
          <span class="stat-value">{{ s.totalInstruments }}</span>
          <span class="stat-foot">Tradable symbols</span>
        </div>
      </div>

      <div class="admin-grid">
        <div class="card admin-stat">
          <span class="stat-label">Orders (all time)</span>
          <span class="stat-value">{{ s.totalOrders }}</span>
          <span class="stat-foot">Submitted platform-wide</span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Working</span>
          <span class="stat-value">{{ s.newOrders }}</span>
          <span class="stat-foot"><span class="tone-badge warning">new</span> awaiting fill</span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Filled</span>
          <span class="stat-value">{{ s.filledOrders }}</span>
          <span class="stat-foot"><span class="tone-badge positive">executed</span></span>
        </div>
        <div class="card admin-stat">
          <span class="stat-label">Rejected / Cancelled</span>
          <span class="stat-value">{{ s.rejectedOrders + s.cancelledOrders }}</span>
          <span class="stat-foot">
            <span class="tone-badge negative">{{ s.rejectedOrders }} rejected</span>
            <span class="tone-badge">{{ s.cancelledOrders }} cancelled</span>
          </span>
        </div>
      </div>

      <div class="admin-viz">
        <div class="card admin-stat viz-card">
          <div class="admin-toolbar">
            <span class="panel-title">Onboarding pipeline</span>
            @if (approvalBacklogRatio() > 0) {
              <span class="tone-badge warning">
                {{ (approvalBacklogRatio() * 100) | number: '1.0-0' }}% awaiting review
              </span>
            }
          </div>
          @if (lifecycle().length === 0) {
            <div class="viz-empty">No identities registered yet.</div>
          } @else {
            <div class="viz-body">
              <div class="donut-wrap">
                <svg class="donut" viewBox="0 0 42 42" role="img"
                     aria-label="Account lifecycle split across registered identities">
                  <circle class="donut-track" cx="21" cy="21" r="15.9155" />
                  @for (slice of lifecycle(); track slice.key) {
                    <circle class="donut-slice" cx="21" cy="21" r="15.9155"
                            [attr.stroke-dasharray]="slice.dash"
                            [attr.stroke-dashoffset]="slice.offset"
                            [attr.stroke]="slice.color" />
                  }
                </svg>
                <div class="donut-hole">
                  <span class="donut-total">{{ stats()?.totalUsers }}</span>
                  <span class="donut-caption">identities</span>
                </div>
              </div>
              <ul class="viz-legend">
                @for (slice of lifecycle(); track slice.key) {
                  <li>
                    <span class="swatch" [style.background]="slice.color"></span>
                    <span class="legend-symbol">{{ slice.label }}</span>
                    <span class="legend-value">{{ slice.count }}</span>
                    <span class="legend-pct">{{ slice.percent * 100 | number: '1.1-1' }}%</span>
                  </li>
                }
              </ul>
            </div>
          }
        </div>

        <div class="card admin-stat viz-card">
          <div class="admin-toolbar">
            <span class="panel-title">Order execution</span>
            <span class="tone-badge positive">
              {{ (fillRate() * 100) | number: '1.0-0' }}% filled
            </span>
          </div>
          <div class="exec-bar" role="img"
               [attr.aria-label]="(fillRate() * 100 | number: '1.0-0') + ' percent of orders filled'">
            <div class="exec-filled" [style.width.%]="fillRate() * 100"></div>
          </div>
          <div class="exec-rows">
            <div class="exec-row">
              <span class="tone-badge positive">Filled</span>
              <span class="legend-value">{{ stats()?.filledOrders }}</span>
            </div>
            <div class="exec-row">
              <span class="tone-badge warning">Working</span>
              <span class="legend-value">{{ stats()?.newOrders }}</span>
            </div>
            <div class="exec-row">
              <span class="tone-badge negative">Rejected</span>
              <span class="legend-value">{{ stats()?.rejectedOrders }}</span>
            </div>
            <div class="exec-row">
              <span class="tone-badge">Cancelled</span>
              <span class="legend-value">{{ stats()?.cancelledOrders }}</span>
            </div>
          </div>
        </div>
      </div>
    }
  `,
})
export class AdminOverviewComponent {
  private readonly admin = inject(AdminService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly stats = signal<AdminStats | null>(null);
  readonly users = signal<AdminUser[]>([]);

  /**
   * Account lifecycle split, for the ring.
   *
   * Derived from the user list rather than the stats aggregate, because the
   * aggregate has no rejected bucket and the console's job is to show who is
   * stuck at each gate.
   */
  readonly lifecycle = computed(() => {
const buckets: { key: string; label: string; count: number; color: string }[] = [
      { key: 'active', label: 'Active', count: 0, color: '#ff5a3c' },
      { key: 'pending', label: 'Awaiting review', count: 0, color: '#f5c542' },
      { key: 'suspended', label: 'Suspended', count: 0, color: '#d8492c' },
      { key: 'rejected', label: 'Rejected', count: 0, color: '#8a757a' },
    ];
    for (const u of this.users()) {
      const state =
        u.status === 'ACTIVE' ? 'active'
        : u.status === 'SUSPENDED' ? 'suspended'
        : u.status === 'REJECTED' || u.status === 'CLOSED' ? 'rejected'
        : 'pending';
      buckets.find((b) => b.key === state)!.count++;
    }
    const total = buckets.reduce((sum, b) => sum + b.count, 0);
    if (total === 0) return [];

    let drawn = 0;
    return buckets
      .filter((b) => b.count > 0)
      .map((b) => {
        const percent = b.count / total;
        const slice = {
          ...b,
          percent,
          dash: `${percent * 100} 100`,
          offset: `${-drawn * 100}`,
        };
        drawn += percent;
        return slice;
      });
  });

  /** Approvals needed before anyone can trade, as a share of all identities. */
  readonly approvalBacklogRatio = computed(() => {
    const s = this.stats();
    if (!s || s.totalUsers === 0) return 0;
    return s.pendingAccounts / s.totalUsers;
  });

  /** Executed orders as a share of everything submitted. */
  readonly fillRate = computed(() => {
    const s = this.stats();
    if (!s || s.totalOrders === 0) return 0;
    return s.filledOrders / s.totalOrders;
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.admin.getStats().subscribe({
      next: (s) => {
        this.stats.set(s);
        // The lifecycle ring is a nice-to-have; a failure here must not blank
        // the metrics above it.
        this.admin.getUsers().subscribe({
          next: (list) => this.users.set(list),
          error: () => this.users.set([]),
        });
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load platform metrics. Confirm you are signed in as an administrator.');
        this.loading.set(false);
      },
    });
  }
}
