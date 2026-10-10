import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { WatchlistCarouselComponent } from './watchlist-carousel.component';
import { SparklineComponent } from './sparkline.component';
import { MarketDataService } from '../../core/services/market-data.service';
import type { WatchlistInstrument } from '../../core/services/watchlist.service';

function instrument(id: number, symbol: string, change: number | null, price: number | null = 100): WatchlistInstrument {
  return {
    watchlistInstrumentId: id, instrumentId: id, symbol, ticker: symbol, assetClass: 'equity', quoteCurrency: 'USD',
    currentPrice: price, priceCurrency: 'USD', bid: price === null ? null : price - 0.1, ask: price === null ? null : price + 0.1,
    change, changePercent: change, stale: false, addedAt: '2026-10-01T00:00:00Z',
  } as WatchlistInstrument;
}

const bar = (close: number, i: number) => ({ time: i, date: '2026-01-0' + i, open: close, high: close, low: close, close, volume: 1 });

describe('WatchlistCarouselComponent', () => {
  let candles: jasmine.Spy;

  beforeEach(() => {
    candles = jasmine.createSpy('candles').and.returnValue(of({ symbol: 'X', currency: 'USD', candles: [bar(10, 1), bar(12, 2), bar(11, 3)] }));
    TestBed.configureTestingModule({
      imports: [WatchlistCarouselComponent],
      providers: [{ provide: MarketDataService, useValue: { candles } }],
    });
  });

  function create(items: WatchlistInstrument[], extra: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(WatchlistCarouselComponent);
    fixture.componentRef.setInput('items', items);
    for (const [k, v] of Object.entries(extra)) {
      fixture.componentRef.setInput(k, v);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('renders a card per instrument with price, change and direction', () => {
    const fixture = create([instrument(1, 'AAPL', 2), instrument(2, 'MSFT', -1), instrument(3, 'NEW', null, null)]);
    const cards = fixture.nativeElement.querySelectorAll('.wc-card') as NodeListOf<HTMLElement>;
    expect(cards.length).toBe(3);
    expect(cards[0].classList).toContain('up');
    expect(cards[1].classList).toContain('down');
    expect(cards[2].classList).toContain('flat');
    expect(cards[0].textContent).toContain('100.00');
    expect(cards[0].textContent).toContain('+2.00');
    expect(cards[2].textContent).toContain('No change yet');
    expect(fixture.nativeElement.querySelectorAll('app-sparkline svg').length).toBe(3);
  });

  it('emits a pick on click and on Enter, and a remove without picking', () => {
    const fixture = create([instrument(1, 'AAPL', 2)], { removable: true });
    const c = fixture.componentInstance;
    const picked: string[] = [];
    const removed: string[] = [];
    c.pick.subscribe((i) => picked.push(i.symbol));
    c.remove.subscribe((i) => removed.push(i.symbol));

    const card = fixture.nativeElement.querySelector('.wc-card') as HTMLElement;
    card.click();
    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    card.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }));
    (fixture.nativeElement.querySelector('.wc-remove') as HTMLElement).click();
    expect(picked).toEqual(['AAPL', 'AAPL']);
    expect(removed).toEqual(['AAPL']);
  });

  it('marks the selected card, hides remove when compact, and scrolls by a page', () => {
    const fixture = create([instrument(1, 'AAPL', 2), instrument(2, 'MSFT', 1)], { selected: 'MSFT', compact: true });
    const cards = fixture.nativeElement.querySelectorAll('.wc-card') as NodeListOf<HTMLElement>;
    expect(cards[1].classList).toContain('selected');
    expect(fixture.nativeElement.querySelector('.wc-remove')).toBeNull();
    expect(fixture.nativeElement.querySelector('.wc-foot')).toBeNull();

    const track = fixture.nativeElement.querySelector('.wc-track') as HTMLElement;
    const scrollBy = spyOn(track, 'scrollBy');
    fixture.componentInstance.scroll(1);
    expect(scrollBy).toHaveBeenCalled();
    fixture.componentInstance.updateArrows();
    expect(fixture.componentInstance.canPrev()).toBeFalse();
  });

  it('forwards drag start and end for moving between lists', () => {
    const fixture = create([instrument(1, 'AAPL', 2)], { removable: true });
    const starts: number[] = [];
    let ends = 0;
    fixture.componentInstance.dragStart.subscribe((e) => starts.push(e.item.watchlistInstrumentId));
    fixture.componentInstance.dragEnd.subscribe(() => ends++);
    const card = fixture.nativeElement.querySelector('.wc-card') as HTMLElement;
    expect(card.getAttribute('draggable')).toBe('true');
    card.dispatchEvent(new DragEvent('dragstart'));
    card.dispatchEvent(new DragEvent('dragend'));
    expect(starts).toEqual([1]);
    expect(ends).toBe(1);
  });
});

describe('SparklineComponent', () => {
  it('draws a rising month green and a falling one red', () => {
    const candles = jasmine.createSpy('candles').and.returnValue(of({ symbol: 'X', currency: 'USD', candles: [bar(10, 1), bar(9, 2)] }));
    TestBed.configureTestingModule({ imports: [SparklineComponent], providers: [{ provide: MarketDataService, useValue: { candles } }] });
    const fixture = TestBed.createComponent(SparklineComponent);
    fixture.componentRef.setInput('symbol', 'X');
    fixture.detectChanges();
    expect(fixture.componentInstance.up()).toBeFalse();
    expect(fixture.componentInstance.path()!.line).toMatch(/^M0\.0,/);
  });

  it('shows an empty track without history, and retries once', fakeAsync(() => {
    const candles = jasmine.createSpy('candles').and.returnValue(throwError(() => ({ status: 503 })));
    TestBed.configureTestingModule({ imports: [SparklineComponent], providers: [{ provide: MarketDataService, useValue: { candles } }] });
    const fixture = TestBed.createComponent(SparklineComponent);
    fixture.componentRef.setInput('symbol', 'X');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.spark-empty')).not.toBeNull();
    tick(4000);
    tick(4000);
    expect(candles).toHaveBeenCalledTimes(2);
    fixture.destroy();
  }));
});
