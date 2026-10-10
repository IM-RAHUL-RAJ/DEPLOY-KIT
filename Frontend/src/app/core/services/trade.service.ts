import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AccountsService } from '@trade-api/api/accounts.service';
import { InstrumentsService } from '@trade-api/api/instruments.service';
import { OrdersService } from '@trade-api/api/orders.service';
import type {
  PlaceOrderRequest,
  OrderResponse,
  OrderHistoryEntry,
  OrderStatus,
  AccountResponse,
  BalanceResponse,
  PositionResponse,
  InstrumentQuoteResponse,
} from '@trade-api/model/models';

/**
 * Live quote projection used by the order ticket to show the native price and the
 * spread against the account currency. Informational only: a MARKET order is priced
 * server-side at submission, so this is already stale by the time an order is sent.
 */
export type InstrumentQuote = InstrumentQuoteResponse;

/**
 * The application's view of the Trade REST API.
 *
 * Every call goes through the client generated from trade-api.yaml, so a path, a
 * parameter or a field the contract changes is a compile error here rather than an
 * undefined at runtime. The base URL is configured once in app.config.ts.
 */
@Injectable({ providedIn: 'root' })
export class TradeService {
  private readonly orders = inject(OrdersService);
  private readonly accounts = inject(AccountsService);
  private readonly instruments = inject(InstrumentsService);

  /**
   * `price` is left undefined for a MARKET order on purpose. The service prices it
   * from the live book, and sending a client number here would only be ignored.
   */
  placeOrder(req: PlaceOrderRequest): Observable<OrderResponse> {
    return this.orders.placeOrder(req);
  }

  cancelOrder(id: string): Observable<OrderResponse> {
    return this.orders.cancelOrder(id);
  }

  getAccount(id: number): Observable<AccountResponse> {
    return this.accounts.getAccount(id);
  }

  getBalance(id: number): Observable<BalanceResponse> {
    return this.accounts.getBalance(id);
  }

  getPositions(id: number): Observable<PositionResponse[]> {
    return this.accounts.getPositions(id);
  }

  getOrders(id: number, status?: OrderStatus): Observable<OrderHistoryEntry[]> {
    return this.accounts.getOrders(id, status);
  }

  getInstrumentQuote(symbol: string): Observable<InstrumentQuote> {
    return this.instruments.getInstrumentQuote(symbol);
  }
}
