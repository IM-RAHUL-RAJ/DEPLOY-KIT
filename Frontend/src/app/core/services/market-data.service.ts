import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, shareReplay, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { TradeService, type InstrumentQuote } from './trade.service';

/** One daily bar. {@code time} is the trading day at UTC midnight, in milliseconds. */
export interface Candle {
  time: number;
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number | null;
}

interface CandlesResponse {
  symbol: string;
  currency: string | null;
  interval: string;
  asOf: string | null;
  candles: { t: number; o: number; h: number; l: number; c: number; v: number | null }[];
}

export interface CandleSeries {
  symbol: string;
  currency: string | null;
  candles: Candle[];
}

/** Daily candles change once a day; five minutes in memory is plenty and saves round trips. */
const CANDLE_TTL_MS = 5 * 60 * 1000;

/**
 * Price history and quotes from the Trade REST API. Everything comes from the platform's
 * market-data cache, which the executor's poller fills; nothing here talks to a vendor.
 */
@Injectable({ providedIn: 'root' })
export class MarketDataService {
  private readonly http = inject(HttpClient);
  private readonly trade = inject(TradeService);
  private readonly cache = new Map<string, { at: number; series: Observable<CandleSeries> }>();

  /** The stored year of daily candles, oldest first. */
  candles(symbol: string): Observable<CandleSeries> {
    const key = symbol.trim().toUpperCase();
    const hit = this.cache.get(key);
    if (hit && Date.now() - hit.at < CANDLE_TTL_MS) {
      return hit.series;
    }
    const series = this.http
      .get<CandlesResponse>(`${environment.tradeApiUrl}/api/v1/instruments/${encodeURIComponent(key)}/candles`)
      .pipe(
        map((r) => ({
          symbol: r.symbol ?? key,
          currency: r.currency ?? null,
          candles: (r.candles ?? [])
            .map((c) => ({
              time: c.t * 1000,
              date: new Date(c.t * 1000).toISOString().slice(0, 10),
              open: Number(c.o),
              high: Number(c.h),
              low: Number(c.l),
              close: Number(c.c),
              volume: c.v ?? null,
            }))
            .filter((c) => Number.isFinite(c.close))
            .sort((a, b) => a.time - b.time),
        })),
        // A failed request must not be cached: the next caller tries again.
        tap({ error: () => this.cache.delete(key) }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    this.cache.set(key, { at: Date.now(), series });
    return series;
  }

  quote(symbol: string): Observable<InstrumentQuote> {
    return this.trade.getInstrumentQuote(symbol.trim().toUpperCase());
  }

  /** Forget a symbol's cached candles, so the next request goes to the server. */
  invalidate(symbol: string): void {
    this.cache.delete(symbol.trim().toUpperCase());
  }
}

/**
 * The previous session's close for a day-change figure: the close before the last bar
 * when the last bar is today's (still forming), otherwise the last bar's close.
 */
export function previousClose(candles: Candle[], today = new Date()): number | null {
  if (candles.length === 0) {
    return null;
  }
  const last = candles[candles.length - 1];
  const todayKey = today.toISOString().slice(0, 10);
  if (last.date === todayKey) {
    return candles.length > 1 ? candles[candles.length - 2].close : null;
  }
  return last.close;
}
