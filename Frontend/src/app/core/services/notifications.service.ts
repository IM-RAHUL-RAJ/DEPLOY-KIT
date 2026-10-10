import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type NotificationCategory =
  // Account related
  | 'ACCOUNT_CREATED'
  | 'ACCOUNT_ACTIVATED'
  | 'ACCOUNT_SUSPENDED'
  | 'PASSWORD_CHANGED'
  | 'EMAIL_VERIFIED'
  | 'PROFILE_UPDATED'
  // Order related
  | 'ORDER_PLACED'
  | 'ORDER_FILLED'
  | 'ORDER_REJECTED'
  | 'ORDER_CANCELLED'
  | 'ORDER_PARTIALLY_FILLED'
  | 'ORDER_EXPIRED'
  // Price related
  | 'PRICE_ALERT'
  | 'PRICE_TARGET_REACHED'
  | 'PRICE_DROP_ALERT'
  | 'PRICE_SPIKE_ALERT';

export type NotificationChannel = 'NONE' | 'EMAIL' | 'PUSH' | 'BOTH';

export type NotificationStatus = 'QUEUED' | 'SENT' | 'FAILED' | 'SUPPRESSED';

export interface Notification {
  notificationId: number;
  accountId: number;
  category: NotificationCategory;
  symbol: string | null;
  title: string;
  body: string;
  channel: NotificationChannel;
  status: NotificationStatus;
  read: boolean;
  createdAt: string;
}

export interface NotificationQuery {
  unread?: boolean;
  limit?: number;
}

/**
 * The customer's notification history, served by the notifications module of the
 * Trade REST API. The account in the path comes from the signed-in token only: a
 * caller cannot address another customer's inbox.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.tradeApiUrl;

  list(accountId: number, query: NotificationQuery = {}): Observable<Notification[]> {
    let params = new HttpParams();
    if (query.unread != null) {
      params = params.set('unread', String(query.unread));
    }
    if (query.limit != null) {
      params = params.set('limit', String(query.limit));
    }
    return this.http.get<Notification[]>(`${this.base}/api/v1/notifications/${accountId}`, { params });
  }

  markRead(accountId: number, notificationId: number): Observable<Notification> {
    return this.http.post<Notification>(
      `${this.base}/api/v1/notifications/${accountId}/${notificationId}/read`,
      null,
    );
  }
}