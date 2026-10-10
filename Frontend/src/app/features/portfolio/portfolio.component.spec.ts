import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PortfolioComponent } from './portfolio.component';
import { PortfolioService, type PnlResponse, type PricedPosition, type PortfolioSummary } from '../../core/services/portfolio.service';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { fakeTrade } from '../../testing/fake-trade';

const SUMMARY: PortfolioSummary = {
  accountId: 7,
  baseCurrency: 'USD',
  cashBalance: 24500.75,
  marketValue: 65120,
  costBasis: 61300,
  unrealisedPnl: 3820,
  unrealisedPnlPercent: 6.23,
  realisedPnl: 1145.5,
  totalValue: 89620.75,
  positionCount: 3,
  partial: false,
  asOf: '2026-10-19T14:05:11Z',
};

const POSITION: PricedPosition = {
  accountId: 7,
  symbol: 'AAPL',
  quantity: 200,
  averageCost: 228.4,
  costBasis: 45680,
  lastPrice: 232.71,
  marketValue: 46542,
  unrealisedPnl: 862,
  unrealisedPnlPercent: 1.89,
  currency: 'USD',
  priceAsOf: '2026-10-19T14:04:58Z',
  stale: false,
};

const PNL: PnlResponse = {
  accountId: 7,
  baseCurrency: 'USD',
  from: null,
  to: null,
  realisedPnl: 1145.5,
  unrealisedPnl: 3820,
  totalPnl: 4965.5,
  bySymbol: [{ symbol: 'AAPL', realisedPnl: 1145.5, unrealisedPnl: 862, totalPnl: 2007.5 }],
  asOf: '2026-10-19T14:05:11Z',
};

describe('PortfolioComponent', () => {
  let portfolio: jasmine.SpyObj<PortfolioService>;
  let trade: jasmine.SpyObj<TradeService>;

  function create(): PortfolioComponent {
    const fixture = TestBed.createComponent(PortfolioComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    portfolio = jasmine.createSpyObj('PortfolioService', ['summary', 'positions', 'pnl']);
    portfolio.summary.and.returnValue(of(SUMMARY));
    portfolio.positions.and.returnValue(of([POSITION]));
    portfolio.pnl.and.returnValue(of(PNL));
    trade = fakeTrade({ positions: [{ accountId: 7, symbol: 'AAPL', quantity: 200, averageCost: 228.4 }] });
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(7);
    TestBed.configureTestingModule({
      imports: [PortfolioComponent],
      providers: [
        provideRouter([]),
        { provide: PortfolioService, useValue: portfolio },
        { provide: TradeService, useValue: trade },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('prices the portfolio: summary, positions and P&L together', () => {
    const c = create();
    expect(c.loading()).toBeFalse();
    expect(portfolio.summary).toHaveBeenCalledWith(7);
    expect(portfolio.positions).toHaveBeenCalledWith(7);
    expect(portfolio.pnl).toHaveBeenCalledWith(7, { bySymbol: true });
    expect(c.summary()!.totalValue).toBe(89620.75);
    expect(c.positions()[0].symbol).toBe('AAPL');
    expect(c.bySymbol().length).toBe(1);
    expect(c.pricedCount()).toBe(1);
  });

  it('reports a non-pricing failure verbatim', () => {
    portfolio.summary.and.returnValue(throwError(() => ({ status: 403, error: { errorCode: 'ACC-403' } })));
    const c = create();
    expect(c.errorMessage()).toContain('not authorised');
    expect(c.loading()).toBeFalse();
  });

  it('renders a visible stale marker and the partial-pricing banner', () => {
    portfolio.positions.and.returnValue(of([{ ...POSITION, stale: true }]));
    portfolio.summary.and.returnValue(of({ ...SUMMARY, partial: true }));
    const fixture = TestBed.createComponent(PortfolioComponent);
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('stale');
    expect(text).toContain('could not be priced');
  });

  it('degrades to unpriced holdings on MKT-503', () => {
    portfolio.summary.and.returnValue(throwError(() => ({ status: 503, error: { errorCode: 'MKT-503', message: 'Pricing unavailable' } })));
    const fixture = TestBed.createComponent(PortfolioComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(trade.getPositions).toHaveBeenCalledWith(7);
    expect(c.degraded()).toBeTrue();
    expect(c.degradedPositions()[0].symbol).toBe('AAPL');
    expect(c.errorMessage()).toContain('No live market price');
    expect(c.loading()).toBeFalse();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('amounts in USD');
    expect(text).toContain('$228.40');
  });

  it('formats ledger amounts in the selected display currency', () => {
    const c = create();
    const text = c.fmtBase(1234.5);
    expect(text).toContain('$');
    expect(text).toContain('1,234.50');
  });

  it('formats native prices with the instrument currency symbol', () => {
    const c = create();
    expect(c.fmtNative(1234.5, 'USD')).toBe('$1,234.50');
    expect(c.fmtNative(null, 'INR')).toBe('N/A');
  });

  it('classifies P&L signs for colouring', () => {
    const c = create();
    expect(c.pnlClass(1)).toBe('positive');
    expect(c.pnlClass(-1)).toBe('negative');
    expect(c.pnlClass(0)).toBe('flat');
  });
});