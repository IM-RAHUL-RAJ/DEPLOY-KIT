import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type AlertDirection = 'ABOVE' | 'BELOW';

export type AlertStatus = 'ACTIVE' | 'TRIGGERED' | 'CANCELLED';

export interface PriceAlert {
  alertId: number;
  accountId: number;
  symbol: string;
  direction: AlertDirection;
  threshold: number;
  currency: string | null;
  status: AlertStatus;
  triggeredPrice: number | null;
  triggeredAt: string | null;
  createdAt: string;
}

export interface CreatePriceAlert {
  symbol: string;
  direction: AlertDirection;
  threshold: number;
}

/** The contract's cap: an account may hold at most 20 armed alerts. */
export const MAX_ACTIVE_ALERTS = 20;

/**
 * Price thresholds on watched instruments, served by the watchlists module of the
 * Trade REST API. The routes are keyed to the verified account claim on the token;
 * there is no path or body account a caller could point at someone else.
 */
@Injectable({ providedIn: 'root' })
export class PriceAlertsService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.tradeApiUrl;

  list(): Observable<PriceAlert[]> {
    return this.http.get<PriceAlert[]>(`${this.base}/api/v1/watchlist/alerts`);
  }

  create(alert: CreatePriceAlert): Observable<PriceAlert> {
    return this.http.post<PriceAlert>(`${this.base}/api/v1/watchlist/alerts`, alert);
  }

  cancel(alertId: number): Observable<PriceAlert> {
    return this.http.delete<PriceAlert>(`${this.base}/api/v1/watchlist/alerts/${alertId}`);
  }
}