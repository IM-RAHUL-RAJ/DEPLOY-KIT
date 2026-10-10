import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
   Component, inject, signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminOrder, AdminService } from '../../core/services/admin.service';

const ORDER_STATUSES = ['NEW', 'FILLED', 'REJECTED', 'CANCELLED'];

@Component({
  selector: 'app-admin-orders',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin-toolbar">
      <div class="admin-toolbar-group">
        <span class="panel-title">Order book</span>
        <select class="admin-select" [ngModel]="statusFilter()" (ngModelChange)="statusFilter.set($event); load()">
          <option value="">All statuses</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s }}</option>
          }
        </select>
        <select class="admin-select" [ngModel]="limit()" (ngModelChange)="limit.set(+$event); load()">
          <option [ngValue]="50">Latest 50</option>
          <option [ngValue]="100">Latest 100</option>
          <option [ngValue]="250">Latest 250</option>
        </select>
      </div>
      <button class="admin-btn" (click)="load()" [disabled]="loading()">Refresh</button>
    </div>

    @if (error()) {
      <div class="error-banner"><span class="icon">!</span>{{ error() }}</div>
    }

    @if (loading()) {
      <div class="admin-loading"><span class="spinner"></span> Loading orders…</div>
    } @else {
      <div class="card">
        <div class="admin-table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>Order</th>
                <th>Account</th>
                <th>Symbol</th>
                <th>Side</th>
                <th class="right">Qty</th>
                <th class="right">Price</th>
                <th>Type</th>
                <th class="right">Exec</th>
                <th>Status</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              @for (o of orders(); track o.orderId) {
                <tr>
                  <td class="mono-id">{{ o.orderId }}</td>
                  <td>
                    <span class="mono-id">#{{ o.accountId }}</span>
                    <div class="text-muted">{{ o.holderName }}</div>
                  </td>
                  <td class="text-bold">{{ o.symbol }}</td>
                  <td>
                    <span class="text-bold" [class.positive]="o.side === 'BUY'" [class.negative]="o.side === 'SELL'">
                      {{ o.side }}
                    </span>
                  </td>
                  <td class="right mono">{{ o.quantity }}</td>
                  <td class="right mono">{{ o.price | number: '1.2-2' }}</td>
                  <td><span class="tone-badge">{{ o.orderType }}</span></td>
                  <td class="right mono">
                    @if (o.executedPrice !== null) {
                      {{ o.executedPrice | number: '1.2-2' }}
                    } @else {
                      <span class="text-muted">—</span>
                    }
                  </td>
                  <td><span [class]="'badge badge-' + o.status.toLowerCase()">{{ o.status }}</span></td>
                  <td class="text-muted">{{ o.createdOn | date: 'short' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="10" class="admin-empty">No orders match this filter.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  `,
})
export class AdminOrdersComponent {
  private readonly admin = inject(AdminService);

  readonly statuses = ORDER_STATUSES;
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly orders = signal<AdminOrder[]>([]);

  readonly statusFilter = signal('');
  readonly limit = signal(50);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.admin.getOrders({ status: this.statusFilter() || undefined, limit: this.limit() }).subscribe({
      next: (list) => {
        this.orders.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load orders.');
        this.loading.set(false);
      },
    });
  }
}
