import { Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { FLASH_MS, WatchlistComponent } from './watchlist.component';
import {
  WatchlistService,
  type Watchlist,
  type WatchlistInstrument,
} from '../../core/services/watchlist.service';
import { TokenService } from '../../core/services/token.service';

function instrument(id: number, symbol: string): WatchlistInstrument {
  return {
    watchlistInstrumentId: id,
    instrumentId: id,
    symbol,
    ticker: symbol,
    assetClass: 'EQUITY',
    quoteCurrency: 'USD',
    currentPrice: 100,
    priceCurrency: 'USD',
    bid: 99,
    ask: 101,
    change: 1.5,
    changePercent: 1.52,
    stale: false,
    addedAt: '2026-10-05T00:00:00Z',
  };
}

function list(id: number, name: string, ...items: WatchlistInstrument[]): Watchlist {
  return { watchlistId: id, accountId: 7, name, instruments: items, createdAt: '', updatedAt: '' };
}

const TWO_LISTS = [list(1, 'My Watchlist', instrument(1, 'AAPL')), list(2, 'Tech')];

describe('WatchlistComponent', () => {
  let service: jasmine.SpyObj<WatchlistService>;

  function create(): WatchlistComponent {
    const fixture = TestBed.createComponent(WatchlistComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj('WatchlistService', [
      'listWatchlists',
      'createWatchlist',
      'renameWatchlist',
      'deleteWatchlist',
      'addInstrumentToWatchlist',
      'removeInstrumentFromList',
      'moveInstrument',
    ]);
    service.listWatchlists.and.returnValue(of(TWO_LISTS));
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(7);
    spyOn(console, 'error');
    TestBed.configureTestingModule({
      imports: [WatchlistComponent],
      providers: [
        { provide: WatchlistService, useValue: service },
        { provide: TokenService, useValue: tokens },
      ],
    });
  });

  it('loads every watchlist and selects the first', () => {
    const c = create();
    expect(c.loading()).toBeFalse();
    expect(c.lists().map((l) => l.name)).toEqual(['My Watchlist', 'Tech']);
    expect(c.selected()?.watchlistId).toBe(1);
  });

  it('charts the first instrument by default, the picked one after, and resets on a list switch', () => {
    const c = create();
    const first = c.selected()!.instruments[0]?.symbol ?? null;
    expect(c.focused()?.symbol ?? null).toBe(first);
    const second = TWO_LISTS[1].instruments[0];
    c.select(2);
    expect(c.focused()?.symbol ?? null).toBe(second?.symbol ?? null);
    if (second) {
      c.focus(second.symbol);
      expect(c.focused()!.symbol).toBe(second.symbol);
      c.focus('NOT-IN-LIST');
      expect(c.focused()!.symbol).toBe(second.symbol);
    }
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    c.go('/order', 'AAPL');
    expect(navigate).toHaveBeenCalledWith(['/order'], { queryParams: { symbol: 'AAPL' } });
  });

    it('reports a failed load', () => {
    service.listWatchlists.and.returnValue(throwError(() => new Error('x')));
    expect(create().errorMessage()).toContain('An unexpected error');
  });

  it('politely refuses to open with no signed-in account', () => {
    service.listWatchlists.and.returnValue(of([]));
    const tokens = jasmine.createSpyObj('TokenService', ['getAccountId']);
    tokens.getAccountId.and.returnValue(null);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [WatchlistComponent],
      providers: [
        { provide: WatchlistService, useValue: service },
        { provide: TokenService, useValue: tokens },
      ],
    });
    expect(create().errorMessage()).toBe('No account is signed in.');
  });

  it('creates a list, selects it and clears the box', () => {
    service.createWatchlist.and.returnValue(of(list(5, 'Earnings watch')));
    const c = create();
    c.newListName.set('  Earnings watch  ');
    c.createList();
    expect(service.createWatchlist).toHaveBeenCalledWith('Earnings watch');
    expect(c.newListName()).toBe('');
    expect(c.selected()?.watchlistId).toBe(5);
    expect(c.successMessage()).toContain('Earnings watch');
  });

  it('clears a confirmation after a few seconds, keeping a newer one for its own full time', () => {
    jasmine.clock().install();
    service.createWatchlist.and.returnValues(of(list(5, 'One')), of(list(6, 'Two')));
    const c = create();
    c.newListName.set('One');
    c.createList();
    jasmine.clock().tick(FLASH_MS - 1000);
    c.newListName.set('Two');
    c.createList();
    jasmine.clock().tick(1500);
    expect(c.successMessage()).toContain('Two');
    jasmine.clock().tick(FLASH_MS);
    expect(c.successMessage()).toBeNull();
    c.ngOnDestroy();
    jasmine.clock().uninstall();
  });

  it('asks for a name before creating', () => {
    const c = create();
    c.createList();
    expect(service.createWatchlist).not.toHaveBeenCalled();
    expect(c.errorMessage()).toContain('name');
  });

  it('renames the selected list', () => {
    service.renameWatchlist.and.returnValue(of(list(1, 'Dividends', instrument(1, 'AAPL'))));
    const c = create();
    c.beginRename(c.selected()!);
    expect(c.renamingId()).toBe(1);
    c.renameValue.set('Dividends');
    c.commitRename();
    expect(service.renameWatchlist).toHaveBeenCalledWith(1, 'Dividends');
    expect(c.renamingId()).toBeNull();
    expect(c.selected()?.name).toBe('Dividends');
  });

  it('deletes a list after confirmation and selects the survivor', () => {
    service.deleteWatchlist.and.returnValue(of(undefined));
    const c = create();
    c.deleteList(TWO_LISTS[0]);
    expect(service.deleteWatchlist).not.toHaveBeenCalled();
    expect(c.confirmRequest()).not.toBeNull();
    c.runConfirm();
    expect(service.deleteWatchlist).toHaveBeenCalledWith(1);
    expect(c.lists().map((l) => l.watchlistId)).toEqual([2]);
    expect(c.selected()?.watchlistId).toBe(2);
  });

  it('does not delete when the confirmation is dismissed', () => {
    const c = create();
    c.deleteList(TWO_LISTS[0]);
    c.dismissConfirm();
    expect(service.deleteWatchlist).not.toHaveBeenCalled();
    expect(c.confirmRequest()).toBeNull();
  });

  it('shows the server message when an instrument cannot be added', () => {
    service.addInstrumentToWatchlist.and.returnValue(
      throwError(() => ({ error: { errorCode: 'VAL-422', message: "Instrument 'ZZZ' not found in market data" }, status: 422 })),
    );
    const c = create();
    c.newSymbol.set('ZZZ');
    c.addInstrument();
    expect(c.errorMessage()).toBe("Instrument 'ZZZ' not found in market data");
    expect(c.adding()).toBeFalse();
  });

  it('adds a symbol to the selected list, upper-cased', () => {
    service.addInstrumentToWatchlist.and.returnValue(
      of(list(1, 'My Watchlist', instrument(1, 'AAPL'), instrument(2, 'MSFT'))),
    );
    const c = create();
    c.newSymbol.set('  msft ');
    c.addInstrument();
    expect(service.addInstrumentToWatchlist).toHaveBeenCalledWith(1, 'MSFT');
    expect(c.newSymbol()).toBe('');
    expect(c.selected()?.instruments.length).toBe(2);
  });

  it('asks for a symbol before adding', () => {
    const c = create();
    c.addInstrument();
    expect(service.addInstrumentToWatchlist).not.toHaveBeenCalled();
    expect(c.errorMessage()).toContain('symbol');
  });

  it('refuses to add past 25 instruments on the selected list', () => {
    service.listWatchlists.and.returnValue(
      of([list(1, 'Full', ...Array.from({ length: 25 }, (_, i) => instrument(i, `S${i}`)))]),
    );
    const c = create();
    c.newSymbol.set('X');
    c.addInstrument();
    expect(c.isFull()).toBeTrue();
    expect(service.addInstrumentToWatchlist).not.toHaveBeenCalled();
  });

  it('removes an instrument from the selected list only after confirmation', () => {
    service.removeInstrumentFromList.and.returnValue(of(list(1, 'My Watchlist')));
    const c = create();
    const entry = c.selected()!.instruments[0];

    c.removeInstrument(entry);
    expect(service.removeInstrumentFromList).not.toHaveBeenCalled();
    expect(c.confirmRequest()).not.toBeNull();
    c.dismissConfirm();
    expect(service.removeInstrumentFromList).not.toHaveBeenCalled();

    c.removeInstrument(entry);
    c.runConfirm();
    expect(service.removeInstrumentFromList).toHaveBeenCalledWith(1, entry.watchlistInstrumentId);
    expect(c.selected()?.instruments.length).toBe(0);
  });

  it('renders the destructive action in the confirmation message', () => {
    const c = create();
    c.deleteList(TWO_LISTS[0]);
    expect(c.confirmRequest()?.title).toBe('Delete watchlist?');
    expect(c.confirmRequest()?.message).toContain('"My Watchlist"');
    expect(c.confirmRequest()?.confirmLabel).toBe('Delete');
  });

  it('moves an instrument to another list on drop', () => {
    service.moveInstrument.and.returnValue(of(list(2, 'Tech', instrument(1, 'AAPL'))));
    const c = create();
    const entry = c.selected()!.instruments[0];

    c.onDragStart(entry);
    expect(c.draggingId()).toBe(entry.watchlistInstrumentId);
    c.onDrop(c.lists()[1]);

    expect(service.moveInstrument).toHaveBeenCalledWith(entry.watchlistInstrumentId, 2);
    expect(c.lists()[0].instruments.length).toBe(0);
    expect(c.lists()[1].instruments.length).toBe(1);
    expect(c.draggingId()).toBeNull();
    expect(c.successMessage()).toContain('Tech');
  });

  it('does not call the server when the target is the current list', () => {
    const c = create();
    const entry = c.selected()!.instruments[0];
    c.onDragStart(entry);
    c.onDrop(c.lists()[0]);
    expect(service.moveInstrument).not.toHaveBeenCalled();
  });

  it('shows the error when a move fails and keeps the source list intact', () => {
    service.moveInstrument.and.returnValue(
      throwError(() => ({ error: { errorCode: 'VAL-422', message: 'Target watchlist is at maximum capacity (25 instruments)' }, status: 422 })),
    );
    const c = create();
    const entry = c.selected()!.instruments[0];
    c.onDragStart(entry);
    c.onDrop(c.lists()[1]);
    expect(c.errorMessage()).toContain('maximum capacity');
    expect(c.lists()[0].instruments.length).toBe(1);
    expect(c.movingId()).toBeNull();
  });

  it('formats prices and changes, and says N/A when missing', () => {
    const c = create();
    expect(c.formatPrice(1234.5, 'USD')).toBe('USD 1,234.50');
    expect(c.formatPrice(null, 'USD')).toBe('N/A');
    expect(c.formatChange(1.5, 1.52)).toBe('+1.50 (+1.52%)');
    expect(c.formatChange(-2, null)).toBe('-2.00');
    expect(c.formatChange(undefined, 1)).toBe('N/A');
    expect(c.getChangeClass(1)).toBe('positive');
    expect(c.getChangeClass(-1)).toBe('negative');
    expect(c.getChangeClass(null)).toBe('');
  });

  it('gives a drag a move payload so every browser starts it', () => {
    const c = create();
    const entry = c.selected()!.instruments[0];
    const dataTransfer = new DataTransfer();
    c.onDragStart(entry, new DragEvent('dragstart', { dataTransfer }));
    expect(c.draggingId()).toBe(entry.watchlistInstrumentId);
    expect(dataTransfer.getData('text/plain')).toBe('AAPL');
  });
});
