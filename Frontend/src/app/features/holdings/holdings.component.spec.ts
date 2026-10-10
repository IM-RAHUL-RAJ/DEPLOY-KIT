import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HoldingsComponent } from './holdings.component';
import { TradeService } from '../../core/services/trade.service';
import { TokenService } from '../../core/services/token.service';
import { fakeTrade, position, quote, type FakeTradeData } from '../../testing/fake-trade';

describe('HoldingsComponent', () => {
  function create(data: FakeTradeData): HoldingsComponent {
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(1);
    TestBed.configureTestingModule({
      imports: [HoldingsComponent],
      providers: [
        provideRouter([]),
        { provide: TradeService, useValue: fakeTrade(data) },
        { provide: TokenService, useValue: tokens },
      ],
    });
    const holdings = TestBed.createComponent(HoldingsComponent).componentInstance;
    holdings.ngOnInit();
    return holdings;
  }

  it('calculates return as (live price - average cost) / average cost', () => {
    const h = create({ positions: [position('AAPL', 10, 100)], quotes: [quote('AAPL', 125)] });
    expect(h.rows()[0].returnPct).toBeCloseTo(25, 6);
  });

  it('reports a loss as a negative return', () => {
    const h = create({ positions: [position('AAPL', 10, 100)], quotes: [quote('AAPL', 80)] });
    expect(h.rows()[0].returnPct).toBeCloseTo(-20, 6);
  });

  it('has no return, rather than 0%, when there is no live price', () => {
    const h = create({ positions: [position('AAPL', 10, 100)] });
    expect(h.rows()[0].returnPct).toBeNull();
    expect(h.portfolioReturn()).toBeNull();
  });

  it('converts a foreign quote into the account currency before comparing', () => {
    // 10 shares bought at $12.00; quoted at 1,000 INR = $12.0048.
    const h = create({ positions: [position('INFY.NS', 10, 12)], quotes: [quote('INFY.NS', 1000, 'INR')] });
    expect(h.rows()[0].mark).toBeCloseTo(1000 / 83.3, 6);
    expect(h.rows()[0].returnPct!).toBeCloseTo(0.04, 1);
  });

  it('weights the portfolio return by invested value', () => {
    const h = create({
      positions: [position('AAPL', 10, 100), position('MSFT', 1, 100)], // 1,000 and 100 invested
      quotes: [quote('AAPL', 110), quote('MSFT', 200)], // +10% and +100%
    });
    // (1,100 + 200 - 1,100) / 1,100
    expect(h.portfolioReturn()!).toBeCloseTo((200 / 1100) * 100, 6);
  });

  it('values positions at cost and sorts the largest first', () => {
    const h = create({ cash: 500, positions: [position('AAPL', 1, 100), position('TSLA', 1, 900)] });
    expect(h.positionsValue()).toBe(1000);
    expect(h.totalEquity()).toBe(1500);
    expect(h.rows().map((r) => r.symbol)).toEqual(['TSLA', 'AAPL']);
  });

  it('gives each holding its share of the invested total', () => {
    const h = create({ positions: [position('AAPL', 1, 250), position('TSLA', 1, 750)] });
    expect(h.rows().map((r) => r.weight)).toEqual([75, 25]);
  });
});
