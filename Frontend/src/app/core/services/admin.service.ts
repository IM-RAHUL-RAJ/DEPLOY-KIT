import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AdminStats {
  totalUsers: number;
  totalAccounts: number;
  activeAccounts: number;
  pendingAccounts: number;
  suspendedAccounts: number;
  totalOrders: number;
  newOrders: number;
  filledOrders: number;
  rejectedOrders: number;
  cancelledOrders: number;
  totalInstruments: number;
  totalCash: number;
}

/**
 * A user together with the trading account opened for them. Registration always
 * creates both together and they share a status, so the console shows them as one
 * row: approving a user has to move the account with it or they would be approved
 * against an account they still cannot trade with.
 */
export interface AdminUser {
  userId: number;
  uuid: string;
  firstName: string | null;
  lastName: string | null;
  username: string;
  email: string;
  userType: string;
  phoneCountryCode: string | null;
  phoneNumber: string | null;
  country: string | null;
  riskAppetite: string | null;
  status: string;
  accountId: number | null;
  accountStatus: string | null;
  currency: string | null;
  cashBalance: number | null;
  createdOn: string;
  approvedOn: string | null;
  suspendedOn: string | null;
  rejectionReason: string | null;
  emailDelivered: boolean | null;
  /** Serialised from AdminUserDto.isInactive(). */
  inactive: boolean;
}

export interface AdminAccount {
  accountId: number;
  userId: number;
  holderName: string;
  username: string;
  userType: string;
  currency: string;
  cashBalance: number;
  status: string;
  version: number;
  /**
   * Only on a status change: whether the holder was emailed about it. Null when
   * the change sends no notice (closing an account, say).
   */
  emailDelivered?: boolean | null;
}

export interface AdminOrder {
  orderId: string;
  accountId: number;
  holderName: string;
  symbol: string;
  side: string;
  quantity: number;
  price: number;
  orderType: string;
  executedPrice: number | null;
  status: string;
  idempotencyKey: string | null;
  createdOn: string;
}

/** Optional on approval: the server falls back to the demo opening balance. */
export interface ApproveUserRequest {
  reason?: string;
}

/** A reason is mandatory, because the applicant is told it. */
export interface RejectUserRequest {
  reason: string;
}

export interface SuspendUserRequest {
  reason: string;
}

/** The server supplies its own wording when the body omits the reason. */
export type ReinstateUserRequest = Partial<RejectUserRequest>;

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.tradeApiUrl}/api/v1/admin`;

  getStats(): Observable<AdminStats> {
    return this.http.get<AdminStats>(`${this.base}/stats`);
  }

  getUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(`${this.base}/users`);
  }

  /** The approval queue: users whose email is verified but who are not yet active. */
  getPendingUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(`${this.base}/users/pending`);
  }

  /**
   * Approve a verified applicant. Moves the user and their account to ACTIVE in one
   * transaction and credits the opening balance, so an approved user can trade at once.
   */
  approveUser(userId: number, request?: ApproveUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users/${userId}/approve`, request ?? {});
  }

  rejectUser(userId: number, request: RejectUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users/${userId}/reject`, request);
  }

  /** Suspends the account with the user: the trade API gates on the account. */
  suspendUser(userId: number, request: SuspendUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users/${userId}/suspend`, request);
  }

  /**
   * Returns a suspended user to ACTIVE without touching the balance. Kept apart from
   * approveUser because approval credits an opening balance.
   */
  reinstateUser(userId: number, request?: ReinstateUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users/${userId}/reinstate`, request ?? {});
  }

  getAccounts(): Observable<AdminAccount[]> {
    return this.http.get<AdminAccount[]>(`${this.base}/accounts`);
  }

  /**
   * Suspending an account suspends its holder and emails them the reason;
   * re-activating a suspended holder's account reinstates and emails them.
   */
  updateAccountStatus(accountId: number, status: string, reason?: string): Observable<AdminAccount> {
    return this.http.post<AdminAccount>(`${this.base}/accounts/${accountId}/status`, {
      status,
      ...(reason ? { reason } : {}),
    });
  }

  adjustBalance(accountId: number, amount: number, reason?: string): Observable<AdminAccount> {
    return this.http.post<AdminAccount>(`${this.base}/accounts/${accountId}/balance`, { amount, reason });
  }

  getOrders(filter?: { status?: string; accountId?: number; limit?: number }): Observable<AdminOrder[]> {
    let params = new HttpParams();
    if (filter?.status) params = params.set('status', filter.status);
    if (filter?.accountId) params = params.set('accountId', String(filter.accountId));
    if (filter?.limit) params = params.set('limit', String(filter.limit));
    return this.http.get<AdminOrder[]>(`${this.base}/orders`, { params });
  }

}