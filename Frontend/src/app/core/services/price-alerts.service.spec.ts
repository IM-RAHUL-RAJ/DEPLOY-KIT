import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { PriceAlertsService } from './price-alerts.service';
import { environment } from '../../../environments/environment';

describe('PriceAlertsService', () => {
  let service: PriceAlertsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PriceAlertsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the account alerts without an account parameter', () => {
    service.list().subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/watchlist/alerts`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('creates an alert with the contract body only', () => {
    service.create({ symbol: 'AAPL', direction: 'ABOVE', threshold: 240 }).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/watchlist/alerts`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ symbol: 'AAPL', direction: 'ABOVE', threshold: 240 });
    req.flush({});
  });

  it('cancels one alert by id', () => {
    service.cancel(12).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/watchlist/alerts/12`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });
});