import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TokenService } from '../../core/services/token.service';
import {
  WatchlistService,
  type Watchlist,
  type WatchlistInstrument,
} from '../../core/services/watchlist.service';
import { resolveErrorMessage } from '../../core/services/error-catalogue';
import { Router } from '@angular/router';
import { WatchlistCarouselComponent } from '../../shared/watchlist-carousel/watchlist-carousel.component';
import { PriceChartComponent } from '../../shared/price-chart/price-chart.component';

const MAX_INSTRUMENTS = 25;

/** One destructive action awaiting the customer's explicit go-ahead in the dialog. */
interface ConfirmRequest {
  title: string;
  message: string;
  confirmLabel: string;
  run: () => void;
}

/** How long a success confirmation stays on screen. */
export const FLASH_MS = 4000;

@Component({
  selector: 'app-watchlist',
  standalone: true,
  imports: [CommonModule, FormsModule, WatchlistCarouselComponent, PriceChartComponent],
  templateUrl: './watchlist.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './watchlist.component.scss',
})
export class WatchlistComponent implements OnInit, OnDestroy {
  private readonly watchlists = inject(WatchlistService);
  private readonly tokens = inject(TokenService);
  private readonly router = inject(Router);

  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  readonly lists = signal<Watchlist[]>([]);
  readonly selectedId = signal<number | null>(null);

  readonly newListName = signal('');
  readonly newSymbol = signal('');
  readonly renamingId = signal<number | null>(null);
  readonly renameValue = signal('');

  readonly creating = signal(false);
  readonly savingRename = signal(false);
  readonly deletingId = signal<number | null>(null);
  readonly adding = signal(false);
  readonly removingId = signal<number | null>(null);
  readonly movingId = signal<number | null>(null);

  readonly confirmRequest = signal<ConfirmRequest | null>(null);

  readonly draggingId = signal<number | null>(null);
  readonly dropTargetId = signal<number | null>(null);

  readonly maxInstruments = MAX_INSTRUMENTS;

  readonly selected = computed<Watchlist | null>(() => {
    const current = this.selectedId();
    return this.lists().find((l) => l.watchlistId === current) ?? this.lists()[0] ?? null;
  });

  /** The instrument charted under the carousel: the one picked, else the list's first. */
  readonly focusedSymbol = signal<string | null>(null);
  readonly focused = computed<WatchlistInstrument | null>(() => {
    const instruments = this.selected()?.instruments ?? [];
    return instruments.find((i) => i.symbol === this.focusedSymbol()) ?? instruments[0] ?? null;
  });

  readonly hasLists = computed(() => this.lists().length > 0);
  readonly isFull = computed(() => (this.selected()?.instruments.length ?? 0) >= MAX_INSTRUMENTS);
  readonly countText = computed(() => {
    const count = this.selected()?.instruments.length ?? 0;
    return `${count}/${MAX_INSTRUMENTS} instruments`;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    if (!this.tokens.getAccountId()) {
      this.loading.set(false);
      this.errorMessage.set('No account is signed in.');
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    this.watchlists.listWatchlists().subscribe({
      next: (lists) => {
        this.lists.set(lists);
        this.selectedId.set(lists.length > 0 ? lists[0].watchlistId : null);
        this.loading.set(false);
      },
      error: (error) => {
        this.errorMessage.set(this.errorText(error));
        this.loading.set(false);
      },
    });
  }

  focus(symbol: string): void {
    this.focusedSymbol.set(symbol);
  }

  go(path: string, symbol: string): void {
    this.router.navigate([path], { queryParams: { symbol } });
  }

  select(id: number): void {
    this.selectedId.set(id);
    this.focusedSymbol.set(null);
    this.errorMessage.set(null);
  }

  createList(): void {
    const name = this.newListName().trim();
    if (!name) {
      this.errorMessage.set('Enter a list name first.');
      this.successMessage.set(null);
      return;
    }
    this.creating.set(true);
    this.errorMessage.set(null);
    this.watchlists.createWatchlist(name).subscribe({
      next: (list) => {
        this.lists.update((all) => [...all, list]);
        this.selectedId.set(list.watchlistId);
        this.newListName.set('');
        this.creating.set(false);
        this.flash(`Watchlist "${list.name}" created.`);
      },
      error: (error) => {
        this.creating.set(false);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  beginRename(list: Watchlist): void {
    this.renamingId.set(list.watchlistId);
    this.renameValue.set(list.name);
    this.errorMessage.set(null);
  }

  cancelRename(): void {
    this.renamingId.set(null);
  }

  commitRename(): void {
    const id = this.renamingId();
    const name = this.renameValue().trim();
    if (id === null || !name) {
      this.errorMessage.set('Enter a list name first.');
      return;
    }
    this.savingRename.set(true);
    this.errorMessage.set(null);
    this.watchlists.renameWatchlist(id, name).subscribe({
      next: (list) => {
        this.lists.update((all) => all.map((l) => (l.watchlistId === id ? list : l)));
        this.renamingId.set(null);
        this.savingRename.set(false);
        this.flash(`Watchlist renamed to "${list.name}".`);
      },
      error: (error) => {
        this.savingRename.set(false);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  deleteList(list: Watchlist): void {
    this.askConfirm({
      title: 'Delete watchlist?',
      message: `"${list.name}" and its ${list.instruments.length} instrument${
        list.instruments.length === 1 ? '' : 's'
      } will be removed. This cannot be undone.`,
      confirmLabel: 'Delete',
      run: () => this.performDelete(list),
    });
  }

  private performDelete(list: Watchlist): void {
    this.deletingId.set(list.watchlistId);
    this.errorMessage.set(null);
    this.watchlists.deleteWatchlist(list.watchlistId).subscribe({
      next: () => {
        this.lists.update((all) => all.filter((l) => l.watchlistId !== list.watchlistId));
        if (this.selectedId() === list.watchlistId) {
          this.selectedId.set(this.lists()[0]?.watchlistId ?? null);
        }
        this.deletingId.set(null);
        this.flash(`Watchlist "${list.name}" deleted.`);
      },
      error: (error) => {
        this.deletingId.set(null);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  addInstrument(): void {
    const list = this.selected();
    const symbol = this.newSymbol().trim();
    if (!list) {
      return;
    }
    if (!symbol) {
      this.errorMessage.set('Enter a symbol first.');
      this.successMessage.set(null);
      return;
    }
    if (this.isFull()) {
      this.errorMessage.set('This watchlist is full (25/25 instruments).');
      this.successMessage.set(null);
      return;
    }
    this.adding.set(true);
    this.errorMessage.set(null);
    this.watchlists.addInstrumentToWatchlist(list.watchlistId, symbol.toUpperCase()).subscribe({
      next: (updated) => {
        this.replaceList(updated);
        this.newSymbol.set('');
        this.adding.set(false);
        this.flash(`Added ${symbol.toUpperCase()} to "${list.name}".`);
      },
      error: (error) => {
        this.adding.set(false);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  removeInstrument(entry: WatchlistInstrument): void {
    const list = this.selected();
    if (!list) {
      return;
    }
    this.askConfirm({
      title: 'Remove instrument?',
      message: `${entry.symbol} will be removed from "${list.name}".`,
      confirmLabel: 'Remove',
      run: () => this.performRemove(list, entry),
    });
  }

  private performRemove(list: Watchlist, entry: WatchlistInstrument): void {
    this.removingId.set(entry.watchlistInstrumentId);
    this.errorMessage.set(null);
    this.watchlists.removeInstrumentFromList(list.watchlistId, entry.watchlistInstrumentId).subscribe({
      next: (updated) => {
        this.replaceList(updated);
        this.removingId.set(null);
        this.flash(`Removed ${entry.symbol} from "${list.name}".`);
      },
      error: (error) => {
        this.removingId.set(null);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  onDragStart(entry: WatchlistInstrument, event?: DragEvent): void {
    this.draggingId.set(entry.watchlistInstrumentId);
    // Firefox starts no drag without a payload; the id itself lives in draggingId.
    event?.dataTransfer?.setData('text/plain', entry.symbol);
    if (event?.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  onDragEnd(): void {
    this.draggingId.set(null);
    this.dropTargetId.set(null);
  }

  onDragOver(list: Watchlist): void {
    if (this.draggingId() !== null) {
      this.dropTargetId.set(list.watchlistId);
    }
  }

  onDrop(list: Watchlist): void {
    const entryId = this.draggingId();
    if (entryId === null) {
      return;
    }
    this.onDragEnd();
    const fromList = this.lists().find((l) =>
      l.instruments.some((i) => i.watchlistInstrumentId === entryId),
    );
    if (!fromList || fromList.watchlistId === list.watchlistId) {
      return;
    }
    this.movingId.set(entryId);
    this.errorMessage.set(null);
    this.watchlists.moveInstrument(entryId, list.watchlistId).subscribe({
      next: (target) => {
        this.lists.update((all) =>
          all.map((l) => {
            if (l.watchlistId === target.watchlistId) {
              return target;
            }
            if (l.watchlistId === fromList.watchlistId) {
              return {
                ...l,
                instruments: l.instruments.filter((i) => i.watchlistInstrumentId !== entryId),
              };
            }
            return l;
          }),
        );
        this.movingId.set(null);
        this.flash(`Moved ${fromList.instruments.find((i) => i.watchlistInstrumentId === entryId)?.symbol} to "${target.name}".`);
      },
      error: (error) => {
        this.movingId.set(null);
        this.errorMessage.set(this.errorText(error));
      },
    });
  }

  formatPrice(price: number | null | undefined, code: string): string {
    if (price === null || price === undefined) {
      return 'N/A';
    }
    return `${code} ${price.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  }

  formatChange(change: number | null | undefined, changePercent: number | null | undefined): string {
    if (change === null || change === undefined) {
      return 'N/A';
    }
    const signed = `${change > 0 ? '+' : ''}${change.toFixed(2)}`;
    return changePercent !== null && changePercent !== undefined
      ? `${signed} (${changePercent > 0 ? '+' : ''}${changePercent.toFixed(2)}%)`
      : signed;
  }

  getChangeClass(change: number | null | undefined): string {
    if (change === null || change === undefined || change === 0) {
      return '';
    }
    return change > 0 ? 'positive' : 'negative';
  }

  askConfirm(request: ConfirmRequest): void {
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.confirmRequest.set(request);
  }

  dismissConfirm(): void {
    this.confirmRequest.set(null);
  }

  runConfirm(): void {
    const request = this.confirmRequest();
    if (!request) {
      return;
    }
    this.confirmRequest.set(null);
    request.run();
  }

  private errorText(error: { error?: { errorCode?: string; message?: string }; status?: number }): string {
    const code = error?.error?.errorCode;
    if (code === 'VAL-422' && error?.error?.message) {
      return error.error.message;
    }
    return resolveErrorMessage(code, error?.status);
  }

  private replaceList(updated: Watchlist): void {
    this.lists.update((all) => all.map((l) => (l.watchlistId === updated.watchlistId ? updated : l)));
  }

  private flashTimer: ReturnType<typeof setTimeout> | null = null;

  /** A confirmation shown for a few seconds, then cleared; a newer one restarts the clock. */
  private flash(message: string): void {
    this.successMessage.set(message);
    if (this.flashTimer) {
      clearTimeout(this.flashTimer);
    }
    this.flashTimer = setTimeout(() => {
      this.flashTimer = null;
      if (this.successMessage() === message) {
        this.successMessage.set(null);
      }
    }, FLASH_MS);
  }

  ngOnDestroy(): void {
    if (this.flashTimer) {
      clearTimeout(this.flashTimer);
    }
  }
}
