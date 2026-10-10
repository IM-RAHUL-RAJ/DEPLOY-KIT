import { Injectable, signal } from '@angular/core';

/**
 * Opens the instrument chart from anywhere a symbol is shown: the watchlist, holdings,
 * portfolio, blotter, alerts. One dialog, hosted by the shell.
 */
@Injectable({ providedIn: 'root' })
export class InstrumentChartService {
  readonly symbol = signal<string | null>(null);

  open(symbol: string | null | undefined): void {
    const s = (symbol ?? '').trim().toUpperCase();
    if (s) {
      this.symbol.set(s);
    }
  }

  close(): void {
    this.symbol.set(null);
  }
}
