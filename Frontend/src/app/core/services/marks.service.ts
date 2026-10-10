import { inject, Injectable } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { TradeService } from './trade.service';
import { CurrencyService } from './currency.service';

/**
 * Live marks for held positions, in the account's ledger currency.
 *
 * Prices are read through the Trade REST API's quote endpoint, never from the
 * market-data API directly: the browser holds no market-data key. A symbol with
 * no quote is left out of the result, and callers mark it at cost and say so.
 */
@Injectable({ providedIn: 'root' })
export class MarksService {
  private readonly trade = inject(TradeService);
  private readonly currency = inject(CurrencyService);

  load(symbols: string[], accountCurrency: string): Observable<Record<string, number>> {
    const unique = [...new Set(symbols)];
    if (!unique.length) return of({});
    return forkJoin(
      unique.map((symbol) => this.trade.getInstrumentQuote(symbol).pipe(catchError(() => of(null)))),
    ).pipe(
      map((quotes) => {
        const marks: Record<string, number> = {};
        quotes.forEach((quote, i) => {
          const native = quote?.price ?? quote?.bid ?? null;
          if (native == null) return;
          const usd = this.currency.toUsd(quote?.quoteCurrency ?? 'USD', native);
          marks[unique[i]] = this.currency.fromUsd(accountCurrency, usd);
        });
        return marks;
      }),
    );
  }
}
