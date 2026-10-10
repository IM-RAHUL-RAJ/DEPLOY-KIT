import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { StrategiesService } from './strategies.service';
import { AdviceService } from './advice.service';
import { environment } from '../../../environments/environment';

describe('StrategiesService', () => {
  const base = `${environment.tradeApiUrl}/api/v1/strategies`;
  let service: StrategiesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(StrategiesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the account strategies', () => {
    service.list(1).subscribe();
    const req = http.expectOne(`${base}/1`);
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('creates with only the required fields', () => {
    const body = { symbol: 'AAPL', strategyType: 'MA_CROSSOVER' as const, quantity: 1, maxNotional: 300 };
    service.create(1, body).subscribe();
    const req = http.expectOne(`${base}/1`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('pauses and resumes with PATCH status', () => {
    service.setStatus(1, 7, 'DISABLED').subscribe();
    const req = http.expectOne(`${base}/1/7`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'DISABLED' });
    req.flush({});
  });

  it('deletes, and reads executions and the preview', () => {
    service.delete(1, 7).subscribe();
    expect(http.expectOne(`${base}/1/7`).request.method).toBe('DELETE');
    service.executions(1, 7).subscribe();
    http.expectOne(`${base}/1/7/executions`).flush([]);
    service.preview(1, 7).subscribe();
    http.expectOne(`${base}/1/7/preview`).flush({});
  });
});

describe('AdviceService', () => {
  let service: AdviceService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AdviceService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the account advice and one symbol, encoding the symbol', () => {
    service.forAccount(3).subscribe();
    http.expectOne(`${environment.tradeApiUrl}/api/v1/advice/3`).flush({ signals: [] });
    service.forSymbol(3, 'BRK.B').subscribe();
    http.expectOne(`${environment.tradeApiUrl}/api/v1/advice/3/BRK.B`).flush({});
  });

  it('fetches the methodology once per session', () => {
    service.methodology().subscribe();
    service.methodology().subscribe();
    http.expectOne(`${environment.tradeApiUrl}/api/v1/advice/methodology`).flush({ factors: [] });
  });
});
