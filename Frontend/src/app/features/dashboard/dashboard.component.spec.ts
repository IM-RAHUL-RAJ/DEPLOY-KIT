import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { throwError } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { fakeTrade, historyEntry, position, quote, type FakeTradeData } from '../../testing/fake-trade';

describe('DashboardComponent', () => {
  function create(data: FakeTradeData): DashboardComponent {
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(1);
    TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: fakeTrade(data) },
        { provide: TokenService, useValue: tokens },
      ],
    });
    const dashboard = TestBed.createComponent(DashboardComponent).componentInstance;
    dashboard.ngOnInit();
    return dashboard;
  }

  const fourHoldings = [
    position('AAPL', 10, 100), // 1,000
    position('MSFT', 2, 300), //    600
    position('TSLA', 1, 2000), // 2,000
    position('INFY.NS', 50, 10), //  500
  ];

  it('values positions at average cost x quantity', () => {
    const d = create({ cash: 1000, positions: fourHoldings });
    expect(d.positionsValue()).toBe(4100);
  });

  it('computes total equity as cash plus positions at cost', () => {
    const d = create({ cash: 1000, positions: fourHoldings });
    expect(d.totalEquity()).toBe(5100);
  });

  it('ignores live prices for positions value and equity', () => {
    const d = create({ cash: 0, positions: [position('AAPL', 10, 100)], quotes: [quote('AAPL', 150)] });
    expect(d.positionsValue()).toBe(1000);
  });

  it('shows only the top three holdings by invested value', () => {
    const d = create({ positions: fourHoldings });
    expect(d.topHoldings().map((h) => h.symbol)).toEqual(['TSLA', 'AAPL', 'MSFT']);
  });

  it('can show the three most recently bought instead', () => {
    const d = create({
      positions: fourHoldings,
      orders: [
        historyEntry({ symbol: 'INFY.NS', status: 'FILLED', createdOn: '2026-10-05T10:00:00Z' }),
        historyEntry({ symbol: 'MSFT', status: 'FILLED', createdOn: '2026-10-04T10:00:00Z' }),
        historyEntry({ symbol: 'AAPL', status: 'FILLED', createdOn: '2026-10-03T10:00:00Z' }),
        historyEntry({ symbol: 'TSLA', status: 'FILLED', createdOn: '2026-09-01T10:00:00Z' }),
      ],
    });
    d.setHoldingsView('recent');
    expect(d.topHoldings().map((h) => h.symbol)).toEqual(['INFY.NS', 'MSFT', 'AAPL']);
  });

  it('does not count a rejected or working buy as a purchase', () => {
    const d = create({
      positions: [position('AAPL', 1, 10), position('MSFT', 1, 10)],
      orders: [
        historyEntry({ symbol: 'MSFT', status: 'REJECTED', createdOn: '2026-10-05T12:00:00Z' }),
        historyEntry({ symbol: 'MSFT', status: 'NEW', createdOn: '2026-10-05T11:00:00Z' }),
        historyEntry({ symbol: 'AAPL', status: 'FILLED', createdOn: '2026-10-01T10:00:00Z' }),
      ],
    });
    d.setHoldingsView('recent');
    expect(d.topHoldings()[0].symbol).toBe('AAPL');
  });

  it("works out each holding's return from the live price", () => {
    const d = create({ positions: [position('AAPL', 10, 100)], quotes: [quote('AAPL', 120)] });
    const aapl = d.holdings()[0];
    expect(aapl.live).toBeTrue();
    expect(aapl.returnPct!).toBeCloseTo(20, 6);
  });

  it('has no return for a holding without a live price', () => {
    const d = create({ positions: [position('AAPL', 10, 100)] });
    expect(d.holdings()[0].live).toBeFalse();
    expect(d.holdings()[0].returnPct).toBeNull();
  });

  it('writes amounts short for the donut and the legend', () => {
    const d = create({ cash: 1000, positions: fourHoldings });
    expect(d.fmtCompact(950)).toBe('$950');
    expect(d.fmtCompact(5100)).toBe('$5.1K');
    expect(d.fmtCompact(-2_500_000)).toBe('-$2.5M');
    expect(d.fmtCompact(123_000_000)).toBe('$123M');
    expect(d.fmtCompact(4e9)).toBe('$4.0B');
    expect(d.fmtCompact(null)).toBe('$0');
  });

  it('counts working orders separately from settled ones', () => {
    const d = create({
      orders: [historyEntry({ status: 'NEW' }), historyEntry({ status: 'FILLED' }), historyEntry({ status: 'REJECTED' })],
    });
    expect(d.openOrderCount()).toBe(1);
  });

  describe('rendered page', () => {
    function render(data: FakeTradeData): HTMLElement {
      create(data);
      const fixture = TestBed.createComponent(DashboardComponent);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it('shows equity, cash, the top holdings and recent orders', () => {
      const el = render({
        cash: 1000,
        positions: [position('AAPL', 10, 100), position('MSFT', 2, 300), position('TSLA', 1, 2000), position('X', 1, 1)],
        orders: [historyEntry({ status: 'NEW' }), historyEntry({ status: 'FILLED' })],
        quotes: [quote('AAPL', 120)],
      });
      expect(el.querySelector('.stat-hero .stat-value')!.textContent).toContain('4,601.00');
      expect(el.querySelectorAll('.holding-row').length).toBe(3);
      expect(el.querySelector('.holdings-more')!.textContent).toContain('4');
      expect(el.querySelectorAll('.data-table tbody tr').length).toBe(2);
      expect(el.textContent).not.toMatch(/unreali[sz]ed/i);
    });

    it('shows empty states for a new account', () => {
      const el = render({ cash: 0 });
      expect(el.textContent).toContain('No orders recorded yet');
      expect(el.textContent).toContain('No open positions');
    });

    it('shows a readable error when the account cannot be read', () => {
      const trade = fakeTrade();
      trade.getAccount.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
      const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
      tokens.getAccountId.and.returnValue(1);
      TestBed.configureTestingModule({
        imports: [DashboardComponent],
        providers: [provideRouter([]), { provide: TradeService, useValue: trade }, { provide: TokenService, useValue: tokens }],
      });
      const fixture = TestBed.createComponent(DashboardComponent);
      fixture.detectChanges();
      expect(fixture.componentInstance.errorMessage()).toBeTruthy();
      expect(fixture.nativeElement.querySelector('.error-banner')).toBeTruthy();
    });

    it('does nothing without an account on the session', () => {
      const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
      tokens.getAccountId.and.returnValue(null);
      TestBed.configureTestingModule({
        imports: [DashboardComponent],
        providers: [provideRouter([]), { provide: TradeService, useValue: fakeTrade() }, { provide: TokenService, useValue: tokens }],
      });
      const d = TestBed.createComponent(DashboardComponent).componentInstance;
      d.ngOnInit();
      expect(d.loading()).toBeFalse();
      expect(d.statusClass('FILLED')).toBe('badge badge-filled');
      expect(d.fmtInt(1234)).toBe('1,234');
    });
  });
});
