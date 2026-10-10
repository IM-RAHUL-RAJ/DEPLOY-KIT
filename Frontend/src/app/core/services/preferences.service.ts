import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** In-app (PUSH), email, or both. */
export type AlertChannel = 'NONE' | 'EMAIL' | 'PUSH' | 'BOTH';

/** How one notification category may be delivered. INHERIT = follow the global alert channel. */
export type NotificationChannel = 'INHERIT' | 'NONE' | 'EMAIL' | 'PUSH' | 'BOTH';

/** The 3 category levels for preferences (all 16 specific types inherit their category's delivery mode). */
export type NotificationCategory = 'ACCOUNT_RELATED' | 'ORDER_RELATED' | 'PRICE_RELATED';

/** All 16 specific notification types (used in notifications inbox). */
export type NotificationType =
  | 'ACCOUNT_CREATED' | 'ACCOUNT_ACTIVATED' | 'ACCOUNT_SUSPENDED' | 'PASSWORD_CHANGED' | 'EMAIL_VERIFIED' | 'PROFILE_UPDATED'
  | 'ORDER_PLACED' | 'ORDER_FILLED' | 'ORDER_REJECTED' | 'ORDER_CANCELLED' | 'ORDER_PARTIALLY_FILLED' | 'ORDER_EXPIRED'
  | 'PRICE_ALERT' | 'PRICE_TARGET_REACHED' | 'PRICE_DROP_ALERT' | 'PRICE_SPIKE_ALERT';

/** One category's stored delivery answer, as the service answers it. */
export interface NotificationPreferenceEntry {
  category: NotificationCategory;
  channel: NotificationChannel;
  contact: string | null;
  updatedAt: string | null;
}

export interface NotificationPreferencesResponse {
  accountId: number | null;
  preferences: NotificationPreferenceEntry[];
}

/** One category's choice as the customer states it. Contact is null for INHERIT/NONE. */
export interface NotificationPreferenceRequest {
  category: NotificationCategory;
  channel: NotificationChannel;
  contact: string | null;
}

/**
 * The stored preference, as the service answers it. An account that has never saved
 * one answers `alertChannel: 'NONE'` with every other field null - a value, not an
 * error, and not the same thing as a customer who chose no channel.
 */
export interface PreferenceResponse {
  accountId: number | null;
  defaultAccountId: number | null;
  alertChannel: AlertChannel;
  alertContact: string | null;
  displayCurrency: string | null;
  /** dark or light; an account with no row answers dark. */
  theme?: 'dark' | 'light' | null;
  updatedAt: string | null;
}

/** A whole-record replace: the caller states the preference it wants. */
export interface PreferenceRequest {
  defaultAccountId?: number | null;
  alertChannel: AlertChannel;
  alertContact?: string | null;
  displayCurrency?: string | null;
}

/**
 * The preferences module's two routes.
 *
 * <p>Hand-written rather than generated, for the same reason the watchlist service is:
 * contracts/preferences-api.yaml is this sprint's own contract and the generated client is
 * built from the two platform contracts alone. The `accountId` in the path is always the
 * account in the verified token - the service never takes one from anywhere else, so a
 * caller cannot address another customer's preference through this class.
 */
@Injectable({ providedIn: 'root' })
export class PreferencesService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.tradeApiUrl;

  get(accountId: number): Observable<PreferenceResponse> {
    return this.http.get<PreferenceResponse>(`${this.base}/api/v1/preferences/${accountId}`);
  }

  put(accountId: number, request: PreferenceRequest): Observable<PreferenceResponse> {
    return this.http.put<PreferenceResponse>(
      `${this.base}/api/v1/preferences/${accountId}`,
      request,
    );
  }

  /** Saves the colour theme alone; the rest of the preference is untouched. */
  putTheme(accountId: number, theme: 'dark' | 'light'): Observable<PreferenceResponse> {
    return this.http.put<PreferenceResponse>(
      `${this.base}/api/v1/preferences/${accountId}/theme`,
      { theme },
    );
  }

  getNotifications(accountId: number): Observable<NotificationPreferencesResponse> {
    return this.http.get<NotificationPreferencesResponse>(
      `${this.base}/api/v1/preferences/notifications/${accountId}`,
    );
  }

  /**
   * Whole-record replace over the named categories; categories not named keep their stored
   * value. The settings screen always sends all three, which is also how a customer resets.
   */
  putNotifications(
    accountId: number,
    preferences: NotificationPreferenceRequest[],
  ): Observable<NotificationPreferencesResponse> {
    return this.http.put<NotificationPreferencesResponse>(
      `${this.base}/api/v1/preferences/notifications/${accountId}`,
      { preferences },
    );
  }
}
