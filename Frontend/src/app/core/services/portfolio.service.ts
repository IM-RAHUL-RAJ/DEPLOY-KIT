import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface PortfolioSummary {
  accountId: number;
  baseCurrency: string;
  cashBalance: number;
  marketValue: number;
  costBasis: number;
  unrealisedPnl: number;
  unrealisedPnlPercent: number | null;
  realisedPnl: number;
  totalValue: number;
  positionCount: number;
  partial: boolean;
  asOf: string;
}

export interface PricedPosition {
  accountId: number;
  symbol: string;
  quantity: number;
  averageCost: number;
  costBasis: number;
  lastPrice: number | null;
  marketValue: number | null;
  unrealisedPnl: number | null;
  unrealisedPnlPercent: number | null;
  currency: string;
  priceAsOf: string | null;
  stale: boolean;
}

export interface SymbolPnl {
  symbol: string;
  realisedPnl: number;
  unrealisedPnl: number;
  totalPnl: number;
}

export interface PnlResponse {
  accountId: number;
  baseCurrency: string;
  from: string | null;
  to: string | null;
  realisedPnl: number;
  unrealisedPnl: number;
  totalPnl: number;
  bySymbol: SymbolPnl[] | null;
  asOf: string;
}

export interface PnlQuery {
  from?: string;
  to?: string;
  bySymbol?: boolean;
}

/**
 * Priced holdings and profit and loss, served by the portfolio module of the Trade
 * REST API. The account in the path must match the signed-in token's claim.
 */
@Injectable({ providedIn: 'root' })
export class PortfolioService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.tradeApiUrl;

  summary(accountId: number): Observable<PortfolioSummary> {
    return this.http.get<PortfolioSummary>(`${this.base}/api/v1/portfolio/${accountId}`);
  }

  positions(accountId: number, symbol?: string): Observable<PricedPosition[]> {
    const params = symbol ? new HttpParams().set('symbol', symbol) : undefined;
    return this.http.get<PricedPosition[]>(
      `${this.base}/api/v1/portfolio/${accountId}/positions`,
      { params },
    );
  }

  pnl(accountId: number, query: PnlQuery = {}): Observable<PnlResponse> {
    let params = new HttpParams();
    if (query.from != null) {
      params = params.set('from', query.from);
    }
    if (query.to != null) {
      params = params.set('to', query.to);
    }
    if (query.bySymbol != null) {
      params = params.set('bySymbol', String(query.bySymbol));
    }
    return this.http.get<PnlResponse>(`${this.base}/api/v1/portfolio/${accountId}/pnl`, {
      params,
    });
  }
}