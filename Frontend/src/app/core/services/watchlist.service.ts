import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface WatchlistInstrument {
  watchlistInstrumentId: number;
  instrumentId: number;
  symbol: string;
  ticker: string;
  assetClass: string;
  quoteCurrency: string;
  currentPrice: number | null;
  priceCurrency: string;
  bid: number | null;
  ask: number | null;
  change: number | null;
  changePercent: number | null;
  stale: boolean;
  addedAt: string;
}

export interface Watchlist {
  watchlistId: number;
  accountId: number;
  name: string;
  instruments: WatchlistInstrument[];
  createdAt: string;
  updatedAt: string;
}

export interface AddInstrumentRequest {
  symbol: string;
}

export interface CreateWatchlistRequest {
  name: string;
}

export interface MoveInstrumentRequest {
  targetWatchlistId: number;
}

@Injectable({ providedIn: 'root' })
export class WatchlistService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.tradeApiUrl;

  /**
   * Retrieves the watchlist for the authenticated user's account.
   */
  getWatchlist(): Observable<Watchlist> {
    return this.http.get<Watchlist>(`${this.base}/api/v1/watchlist`);
  }

  /**
   * Adds an instrument to the watchlist by symbol.
   */
  addInstrument(symbol: string): Observable<Watchlist> {
    const request: AddInstrumentRequest = { symbol };
    return this.http.post<Watchlist>(`${this.base}/api/v1/watchlist/instruments`, request);
  }

  /**
   * Removes an instrument from the watchlist.
   */
  removeInstrument(watchlistInstrumentId: number): Observable<Watchlist> {
    return this.http.delete<Watchlist>(`${this.base}/api/v1/watchlist/instruments/${watchlistInstrumentId}`);
  }

  /** Every named watchlist the account owns, oldest first, instruments and prices included. */
  listWatchlists(): Observable<Watchlist[]> {
    return this.http.get<Watchlist[]>(`${this.base}/api/v1/watchlists`);
  }

  /** Creates an empty named watchlist. */
  createWatchlist(name: string): Observable<Watchlist> {
    const request: CreateWatchlistRequest = { name };
    return this.http.post<Watchlist>(`${this.base}/api/v1/watchlists`, request);
  }

  /** Renames one of the account's watchlists. */
  renameWatchlist(watchlistId: number, name: string): Observable<Watchlist> {
    const request: CreateWatchlistRequest = { name };
    return this.http.patch<Watchlist>(`${this.base}/api/v1/watchlists/${watchlistId}`, request);
  }

  /** Deletes a watchlist. The account's last list cannot be deleted. */
  deleteWatchlist(watchlistId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/v1/watchlists/${watchlistId}`);
  }

  /** Adds a symbol to one specific watchlist. */
  addInstrumentToWatchlist(watchlistId: number, symbol: string): Observable<Watchlist> {
    const request: AddInstrumentRequest = { symbol };
    return this.http.post<Watchlist>(`${this.base}/api/v1/watchlists/${watchlistId}/instruments`, request);
  }

  /** Removes one entry from a specific watchlist. */
  removeInstrumentFromList(
    watchlistId: number,
    watchlistInstrumentId: number,
  ): Observable<Watchlist> {
    return this.http.delete<Watchlist>(
      `${this.base}/api/v1/watchlists/${watchlistId}/instruments/${watchlistInstrumentId}`,
    );
  }

  /** Moves an instrument entry to another of the account's watchlists. */
  moveInstrument(watchlistInstrumentId: number, targetWatchlistId: number): Observable<Watchlist> {
    const request: MoveInstrumentRequest = { targetWatchlistId };
    return this.http.post<Watchlist>(
      `${this.base}/api/v1/watchlists/instruments/${watchlistInstrumentId}/move`,
      request,
    );
  }
}
