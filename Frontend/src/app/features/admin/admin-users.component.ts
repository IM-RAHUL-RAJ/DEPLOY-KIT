import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
   Component, computed, inject, signal
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminService, AdminUser } from '../../core/services/admin.service';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  styleUrl: './admin-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="admin-toolbar">
      <div class="admin-toolbar-group">
        <span class="panel-title">Users</span>
        <input class="admin-input" style="width: 220px" type="search" placeholder="Search name, username, email" [ngModel]="query()" (ngModelChange)="query.set($event)" />
        <span class="text-muted">{{ filtered().length }} shown</span>
      </div>
      <button class="admin-btn" (click)="load()" [disabled]="loading()">Refresh</button>
    </div>

    @if (error()) {
      <div class="error-banner"><span class="icon">!</span>{{ error() }}</div>
    }

    @if (loading()) {
      <div class="admin-loading"><span class="spinner"></span> Loading users…</div>
    } @else {
      <div class="card">
        <div class="admin-table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Name</th>
                <th>Username</th>
                <th>Email</th>
                <th>Type</th>
                <th>Account</th>
                <th class="right">Cash</th>
                <th>Joined</th>
              </tr>
            </thead>
            <tbody>
              @for (u of filtered(); track u.userId) {
                <tr>
                  <td class="mono-id">{{ u.userId }}</td>
                  <td class="text-bold">{{ fullName(u) }}</td>
                  <td class="mono">{{ u.username }}</td>
                  <td class="text-secondary">{{ u.email }}</td>
                  <td>
                    <span class="type-badge" [class.admin]="u.userType === 'admin'">{{ u.userType }}</span>
                  </td>
                  <td>
                    @if (u.accountId) {
                      <span class="mono-id">#{{ u.accountId }}</span>
                      <span class="tone-badge" [class.positive]="u.accountStatus === 'ACTIVE'">{{ u.accountStatus }}</span>
                    } @else {
                      <span class="text-muted">none</span>
                    }
                  </td>
                  <td class="right mono">
                    @if (u.cashBalance !== null) {
                      {{ u.cashBalance | number: '1.2-2' }}
                    } @else {
                      <span class="text-muted">—</span>
                    }
                  </td>
                  <td class="text-muted">{{ u.createdOn | date: 'mediumDate' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="8" class="admin-empty">No users match this search.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    }
  `,
})
export class AdminUsersComponent {
  private readonly admin = inject(AdminService);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly users = signal<AdminUser[]>([]);
  readonly query = signal('');

  readonly filtered = computed(() => {
    const q = this.query().trim().toLowerCase();
    if (!q) return this.users();
    return this.users().filter((u) =>
      [u.firstName, u.lastName, u.username, u.email, u.userType]
        .filter((v): v is string => !!v)
        .some((v) => v.toLowerCase().includes(q)),
    );
  });

  constructor() {
    this.load();
  }

  fullName(u: AdminUser): string {
    const name = `${u.firstName ?? ''} ${u.lastName ?? ''}`.trim();
    return name || u.username;
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.admin.getUsers().subscribe({
      next: (list) => {
        this.users.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load users.');
        this.loading.set(false);
      },
    });
  }
}
