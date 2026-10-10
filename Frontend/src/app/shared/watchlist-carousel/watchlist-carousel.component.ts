import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import type { WatchlistInstrument } from '../../core/services/watchlist.service';
import { SparklineComponent } from './sparkline.component';

/**
 * Watchlist instruments as a horizontal carousel of cards: price, day change and a
 * one-month sparkline each. Full size on the Watchlist page (selectable, removable,
 * draggable onto another list); compact on the dashboard.
 */
@Component({
  selector: 'app-watchlist-carousel',
  standalone: true,
  imports: [DecimalPipe, SparklineComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './watchlist-carousel.component.html',
  styleUrl: './watchlist-carousel.component.scss',
  host: { '[class.compact]': 'compact()' },
})
export class WatchlistCarouselComponent {
  readonly items = input.required<WatchlistInstrument[]>();
  readonly compact = input(false);
  readonly selected = input<string | null>(null);
  readonly removable = input(false);
  readonly removingId = input<number | null>(null);
  readonly draggingId = input<number | null>(null);

  readonly pick = output<WatchlistInstrument>();
  readonly remove = output<WatchlistInstrument>();
  readonly dragStart = output<{ item: WatchlistInstrument; event: DragEvent }>();
  readonly dragEnd = output<void>();

  private readonly track = viewChild<ElementRef<HTMLElement>>('track');
  readonly canPrev = signal(false);
  readonly canNext = signal(false);

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const el = this.track()?.nativeElement;
      if (!el) {
        return;
      }
      const update = () => this.updateArrows();
      el.addEventListener('scroll', update, { passive: true });
      const observer = new ResizeObserver(update);
      observer.observe(el);
      update();
      destroyRef.onDestroy(() => {
        el.removeEventListener('scroll', update);
        observer.disconnect();
      });
    });
  }

  updateArrows(): void {
    const el = this.track()?.nativeElement;
    if (!el) {
      return;
    }
    this.canPrev.set(el.scrollLeft > 4);
    this.canNext.set(el.scrollLeft + el.clientWidth < el.scrollWidth - 4);
  }

  scroll(direction: 1 | -1): void {
    const el = this.track()?.nativeElement;
    if (!el) {
      return;
    }
    el.scrollBy({ left: direction * el.clientWidth * 0.85, behavior: 'smooth' });
  }

  onKey(event: KeyboardEvent, item: WatchlistInstrument): void {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.pick.emit(item);
    }
  }

  direction(item: WatchlistInstrument): 'up' | 'down' | 'flat' {
    const c = item.change ?? item.changePercent ?? 0;
    return c > 0 ? 'up' : c < 0 ? 'down' : 'flat';
  }
}
