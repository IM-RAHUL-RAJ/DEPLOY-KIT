import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Subscription, timer } from 'rxjs';
import { MarketDataService } from '../../core/services/market-data.service';

/**
 * A one-month closing-price line for a card, from the stored daily candles (cached per
 * symbol, so a carousel of twenty costs twenty requests once, not per render). Draws
 * nothing rather than a misleading flat line when there is no history yet.
 */
@Component({
  selector: 'app-sparkline',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (path(); as p) {
      <svg [attr.viewBox]="'0 0 ' + width() + ' ' + height()" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient [attr.id]="gradId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" [attr.stop-color]="up() ? 'var(--spark-up)' : 'var(--spark-down)'" stop-opacity="0.35"/>
            <stop offset="100%" [attr.stop-color]="up() ? 'var(--spark-up)' : 'var(--spark-down)'" stop-opacity="0"/>
          </linearGradient>
        </defs>
        <path [attr.d]="p.area" [attr.fill]="'url(#' + gradId + ')'"/>
        <path [attr.d]="p.line" fill="none" [attr.stroke]="up() ? 'var(--spark-up)' : 'var(--spark-down)'"
              stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round" vector-effect="non-scaling-stroke"/>
      </svg>
    } @else {
      <div class="spark-empty"></div>
    }
  `,
  styles: [`
    :host { display: block; width: 100%; --spark-up: #2fcf86; --spark-down: #e0283c; }
    :host-context([data-theme='light']) { --spark-up: #0f8f55; --spark-down: #b3122a; }
    svg { display: block; width: 100%; height: 100%; }
    .spark-empty { height: 100%; border-radius: 6px;
      background: repeating-linear-gradient(90deg, var(--divider) 0 6px, transparent 6px 12px); opacity: 0.5; }
  `],
  host: { '[style.height.px]': 'height()' },
})
export class SparklineComponent {
  private readonly market = inject(MarketDataService);

  readonly symbol = input.required<string>();
  readonly days = input(30);
  readonly height = input(44);
  readonly width = input(160);

  readonly closes = signal<number[]>([]);
  readonly gradId = `spark-${Math.random().toString(36).slice(2, 9)}`;
  private sub?: Subscription;
  private retried = false;

  readonly up = computed(() => {
    const c = this.closes();
    return c.length < 2 || c[c.length - 1] >= c[0];
  });

  readonly path = computed(() => {
    const c = this.closes();
    if (c.length < 2) {
      return null;
    }
    const w = this.width();
    const h = this.height();
    const lo = Math.min(...c);
    const hi = Math.max(...c);
    const span = hi - lo || 1;
    const pts = c.map((v, i) => [(i / (c.length - 1)) * w, h - 3 - ((v - lo) / span) * (h - 6)]);
    const line = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
    return { line, area: `${line}L${w},${h}L0,${h}Z` };
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.sub?.unsubscribe());
    effect(() => {
      const symbol = this.symbol();
      untracked(() => {
        this.retried = false;
        this.load(symbol);
      });
    });
  }

  private load(symbol: string): void {
    this.sub?.unsubscribe();
    this.sub = this.market.candles(symbol).subscribe({
      next: (s) => this.closes.set(s.candles.slice(-this.days()).map((x) => x.close)),
      error: () => {
        this.closes.set([]);
        // The first request asks the poller for the history; one retry picks it up.
        if (!this.retried) {
          this.retried = true;
          this.sub = timer(4000).subscribe(() => this.load(symbol));
        }
      },
    });
  }
}
