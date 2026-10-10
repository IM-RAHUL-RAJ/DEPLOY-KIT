import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { PortfolioService } from './portfolio.service';
import { environment } from '../../../environments/environment';

describe('PortfolioService', () => {
  let service: PortfolioService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PortfolioService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fetches the summary for the account', () => {
    service.summary(7).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/portfolio/7`);
    expect(req.request.method).toBe('GET');
    req.flush({});
  });

  it('fetches all positions, or one symbol', () => {
    service.positions(7).subscribe();
    const all = http.expectOne(`${environment.tradeApiUrl}/api/v1/portfolio/7/positions`);
    expect(all.request.params.keys().length).toBe(0);
    all.flush([]);

    service.positions(7, 'AAPL').subscribe();
    const one = http.expectOne(`${environment.tradeApiUrl}/api/v1/portfolio/7/positions?symbol=AAPL`);
    expect(one.request.params.get('symbol')).toBe('AAPL');
    one.flush([]);
  });

  it('fetches P&L, optionally windowed and broken down by symbol', () => {
    service.pnl(7).subscribe();
    const plain = http.expectOne(`${environment.tradeApiUrl}/api/v1/portfolio/7/pnl`);
    expect(plain.request.params.keys().length).toBe(0);
    plain.flush({});

    service.pnl(7, { from: '2026-09-01', to: '2026-10-19', bySymbol: true }).subscribe();
    const windowed = http.expectOne(
      `${environment.tradeApiUrl}/api/v1/portfolio/7/pnl?from=2026-09-01&to=2026-10-19&bySymbol=true`,
    );
    expect(windowed.request.params.get('bySymbol')).toBe('true');
    windowed.flush({});
  });
});