import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, shareReplay } from 'rxjs';
import { environment } from '../../../environments/environment';

export type SignalAction = 'BUY' | 'SELL' | 'HOLD';

export interface SignalFactor {
  key: 'TREND' | 'POSITION' | 'RSI' | 'MACD' | 'BOLLINGER';
  name: string;
  score: -1 | 0 | 1;
  reason: string;
}

export interface SignalIndicators {
  lastPrice: number | null;
  sma20: number | null;
  sma50: number | null;
  sma200: number | null;
  rsi14: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  bollingerUpper: number | null;
  bollingerMiddle: number | null;
  bollingerLower: number | null;
  percentB: number | null;
  high52w: number | null;
  low52w: number | null;
  /** Annualised, in percent. */
  volatility: number | null;
  dailyCloses: number;
}

export interface TradeSignal {
  symbol: string;
  status: 'SIGNAL' | 'INSUFFICIENT_DATA';
  action: SignalAction | null;
  /** Sum of the five factor scores, -5 to +5. */
  score: number;
  /** How firmly the factors support the view, 0 to 100. */
  strength: number;
  summary: string;
  reasons: string[];
  factors: SignalFactor[];
  indicators: SignalIndicators;
  holding: { quantity: number; averageCost: number | null; unrealisedPnlPercent: number | null } | null;
  sources: string[];
  priceAsOf: string | null;
}

export interface AdviceResponse {
  accountId: number;
  methodology: string;
  disclaimer: string;
  asOf: string;
  signals: TradeSignal[];
}

export interface Methodology {
  summary: string;
  factors: { key: string; name: string; formula: string; rule: string }[];
  scoring: string;
  disclaimer: string;
}

/**
 * Trade advice from the Trade REST API: a BUY, SELL or HOLD view with its reasoning,
 * computed server-side from the market-data cache and the portfolio. Read-only;
 * nothing here places an order.
 */
@Injectable({ providedIn: 'root' })
export class AdviceService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.tradeApiUrl}/api/v1/advice`;
  private methodology$?: Observable<Methodology>;

  forAccount(accountId: number): Observable<AdviceResponse> {
    return this.http.get<AdviceResponse>(`${this.base}/${accountId}`);
  }

  forSymbol(accountId: number, symbol: string): Observable<TradeSignal> {
    return this.http.get<TradeSignal>(`${this.base}/${accountId}/${encodeURIComponent(symbol)}`);
  }

  /** The formulas never change at run time: fetched once per session. */
  methodology(): Observable<Methodology> {
    this.methodology$ ??= this.http.get<Methodology>(`${this.base}/methodology`).pipe(shareReplay(1));
    return this.methodology$;
  }
}
