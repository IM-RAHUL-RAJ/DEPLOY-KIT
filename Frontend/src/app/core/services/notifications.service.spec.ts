import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  provideHttpClientTesting,
  HttpTestingController,
} from '@angular/common/http/testing';
import { NotificationsService } from './notifications.service';
import { environment } from '../../../environments/environment';

describe('NotificationsService', () => {
  let service: NotificationsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(NotificationsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists notifications for the account', () => {
    service.list(7).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/notifications/7`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('passes unread and limit as query parameters', () => {
    service.list(7, { unread: true, limit: 10 }).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/notifications/7?unread=true&limit=10`);
    expect(req.request.params.get('unread')).toBe('true');
    expect(req.request.params.get('limit')).toBe('10');
    req.flush([]);
  });

  it('marks one notification read', () => {
    service.markRead(7, 42).subscribe();
    const req = http.expectOne(`${environment.tradeApiUrl}/api/v1/notifications/7/42/read`);
    expect(req.request.method).toBe('POST');
    req.flush({});
  });
});