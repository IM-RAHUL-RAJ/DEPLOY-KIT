import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type StrategyType = 'PRICE_TARGET' | 'MA_CROSSOVER' | 'BOLLINGER';
export type OrderSide = 'BUY' | 'SELL';
export type StrategyOrderType = 'MARKET' | 'LIMIT';
export type StrategyStatus = 'ACTIVE' | 'DISABLED' | 'STOPPED' | 'COMPLETED';

export interface Strategy {
  strategyId: number;
  accountId: number;
  name: string;
  symbol: string;
  strategyType: StrategyType;
  side: OrderSide | null;
  targetPrice: number | null;
  orderType: StrategyOrderType;
  shortWindow: number | null;
  longWindow: number | null;
  bandPeriod: number | null;
  bandK: number | null;
  quantity: number;
  maxNotional: number;
  maxOrdersPerDay: number;
  maxPosition: number;
  status: StrategyStatus;
  stopReason: string | null;
  lastState: string | null;
  lastPrice: number | null;
  lastEvaluatedAt: string | null;
  consecutiveFailures: number;
  createdAt: string;
}

/** Required: symbol, rule, quantity, maximum order value. The rest default on the server. */
export interface CreateStrategy {
  name?: string;
  symbol: string;
  strategyType: StrategyType;
  /** Price target only. */
  side?: OrderSide;
  targetPrice?: number;
  orderType?: StrategyOrderType;
  shortWindow?: number;
  longWindow?: number;
  bandPeriod?: number;
  bandK?: number;
  quantity: number;
  /** Required for indicator rules; a price target defaults it from the target. */
  maxNotional?: number;
  maxOrdersPerDay?: number;
  /** The most shares the account may hold after a strategy BUY; five orders' worth by default. */
  maxPosition?: number;
}

export interface StrategyExecution {
  executionId: number;
  signal: 'BUY' | 'SELL';
  price: number | null;
  quantity: number;
  status: 'PENDING' | 'PLACED' | 'SKIPPED' | 'FAILED';
  orderId: string | null;
  reason: string | null;
  createdAt: string;
}

export interface StrategyPreview {
  strategyId: number;
  ready: boolean;
  state: string | null;
  price: number | null;
  shortAverage: number | null;
  longAverage: number | null;
  middleBand: number | null;
  lowerBand: number | null;
  upperBand: number | null;
  closesAvailable: number;
  closesNeeded: number;
  explanation: string;
}

/** The server's cap on strategies per account. */
export const MAX_STRATEGIES = 10;

/**
 * Automated strategies on the Trade REST API. The server evaluates them on the live
 * price stream and places their orders through the same order API the ticket uses.
 */
@Injectable({ providedIn: 'root' })
export class StrategiesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.tradeApiUrl}/api/v1/strategies`;

  list(accountId: number): Observable<Strategy[]> {
    return this.http.get<Strategy[]>(`${this.base}/${accountId}`);
  }

  create(accountId: number, body: CreateStrategy): Observable<Strategy> {
    return this.http.post<Strategy>(`${this.base}/${accountId}`, body);
  }

  setStatus(accountId: number, strategyId: number, status: 'ACTIVE' | 'DISABLED'): Observable<Strategy> {
    return this.http.patch<Strategy>(`${this.base}/${accountId}/${strategyId}`, { status });
  }

  delete(accountId: number, strategyId: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${accountId}/${strategyId}`);
  }

  executions(accountId: number, strategyId: number): Observable<StrategyExecution[]> {
    return this.http.get<StrategyExecution[]>(`${this.base}/${accountId}/${strategyId}/executions`);
  }

  preview(accountId: number, strategyId: number): Observable<StrategyPreview> {
    return this.http.get<StrategyPreview>(`${this.base}/${accountId}/${strategyId}/preview`);
  }
}
