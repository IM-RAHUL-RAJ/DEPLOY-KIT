import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Subscription, timer } from 'rxjs';
import { MarketDataService, type Candle } from '../../core/services/market-data.service';
import {
  CHART_RANGES,
  UiPreferencesService,
  type ChartRange,
  type ChartStyle,
} from '../../core/services/ui-preferences.service';

export type ChartOverlay = 'sma20' | 'sma50' | 'sma200' | 'bollinger';

interface Bar {
  x: number;
  up: boolean;
  bodyY: number;
  bodyH: number;
  wickTop: number;
  wickBottom: number;
  volY: number;
  volH: number;
}

interface Tick {
  pos: number;
  label: string;
}

const PAD = { left: 8, right: 64, top: 14, bottom: 26 };
const OVERLAY_COLORS: Record<string, string> = {
  sma20: '#ffc93c',
  sma50: '#5aa9ff',
  sma200: '#c084fc',
  bollinger: '#9ca3af',
};
const OVERLAY_LABELS: Record<ChartOverlay, string> = {
  sma20: 'SMA 20',
  sma50: 'SMA 50',
  sma200: 'SMA 200',
  bollinger: 'Bollinger 20, 2',
};
const MAX_RETRIES = 6;

/** Plain-language meaning of every line and mark on the chart. */
export const CHART_GLOSSARY: { term: string; meaning: string; color?: string }[] = [
  { term: 'Candle', meaning: 'One trading day. The body runs from the open to the close (green when it closed higher, red when lower); the thin wick shows the day’s high and low.' },
  { term: 'SMA 20', color: '#ffc93c', meaning: 'Simple moving average of the last 20 daily closes (about one trading month): the short-term trend. Price above it is short-term strength.' },
  { term: 'SMA 50', color: '#5aa9ff', meaning: 'Average of the last 50 closes (about a quarter): the medium-term trend. SMA 20 crossing above SMA 50 is a classic buy signal; crossing below, a sell.' },
  { term: 'SMA 200', color: '#c084fc', meaning: 'Average of the last 200 closes (about a year): the long-term trend. Needs 200 days of history.' },
  { term: 'Bollinger bands', color: '#9ca3af', meaning: 'SMA 20 plus and minus two standard deviations. Most closes stay inside; a break below the lower band means stretched down, above the upper band stretched up.' },
  { term: 'Volume', meaning: 'Shares traded each day. Big moves on high volume carry more conviction.' },
  { term: 'Last price line', meaning: 'The dashed line and tag on the right mark the latest close in view.' },
];

/** Rolling simple moving average, aligned to the input; null before a full window. */
export function rollingSma(values: number[], window: number): (number | null)[] {
  const out: (number | null)[] = new Array(values.length).fill(null);
  let sum = 0;
  for (let i = 0; i < values.length; i++) {
    sum += values[i];
    if (i >= window) {
      sum -= values[i - window];
    }
    if (i >= window - 1) {
      out[i] = sum / window;
    }
  }
  return out;
}

/** Rolling population standard deviation, aligned to the input. */
export function rollingStd(values: number[], window: number): (number | null)[] {
  const mean = rollingSma(values, window);
  return values.map((_, i) => {
    const m = mean[i];
    if (m === null) {
      return null;
    }
    let ss = 0;
    for (let j = i - window + 1; j <= i; j++) {
      ss += (values[j] - m) ** 2;
    }
    return Math.sqrt(ss / window);
  });
}

/** The first index inside a range ending at the last candle. */
export function rangeStart(candles: Candle[], range: ChartRange): number {
  if (candles.length === 0) {
    return 0;
  }
  const last = new Date(candles[candles.length - 1].time);
  const cutoff = new Date(last);
  switch (range) {
    case '1W': cutoff.setUTCDate(cutoff.getUTCDate() - 7); break;
    case '1M': cutoff.setUTCMonth(cutoff.getUTCMonth() - 1); break;
    case '3M': cutoff.setUTCMonth(cutoff.getUTCMonth() - 3); break;
    case '6M': cutoff.setUTCMonth(cutoff.getUTCMonth() - 6); break;
    case 'YTD': cutoff.setUTCMonth(0, 0); break; // 31 December of the previous year
    case '1Y': cutoff.setUTCFullYear(cutoff.getUTCFullYear() - 1); break;
  }
  const idx = candles.findIndex((c) => c.time > cutoff.getTime());
  return idx < 0 ? 0 : idx;
}

/** A round step for about {@code count} axis ticks across {@code span}. */
export function niceStep(span: number, count: number): number {
  if (span <= 0) {
    return 1;
  }
  const raw = span / count;
  const pow = Math.pow(10, Math.floor(Math.log10(raw)));
  const n = raw / pow;
  const nice = n < 1.5 ? 1 : n < 3 ? 2 : n < 7 ? 5 : 10;
  return nice * pow;
}

/**
 * Price history for one instrument as candles, a line or an area, with moving averages,
 * Bollinger bands and volume, over a choice of ranges. Hovering reads any bar's open,
 * high, low, close and volume. Everything is computed from the platform's stored daily
 * candles; averages are computed on the full history before the range is cut, so the
 * first bars of a range carry true values rather than averages of a few points.
 */
@Component({
  selector: 'app-price-chart',
  standalone: true,
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './price-chart.component.html',
  styleUrl: './price-chart.component.scss',
})
export class PriceChartComponent {
  private readonly market = inject(MarketDataService);
  private readonly prefs = inject(UiPreferencesService);
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly symbol = input.required<string>();
  readonly height = input(340);
  /** Overlays on by default; the user can toggle any of them. */
  readonly overlays = input<ChartOverlay[] | null>(null);
  readonly showToolbar = input(true);

  readonly ranges = CHART_RANGES;
  readonly styles: { value: ChartStyle; label: string }[] = [
    { value: 'candles', label: 'Candles' },
    { value: 'line', label: 'Line' },
    { value: 'area', label: 'Area' },
  ];
  readonly overlayOptions: ChartOverlay[] = ['sma20', 'sma50', 'sma200', 'bollinger'];
  readonly overlayLabels = OVERLAY_LABELS;
  readonly overlayColors = OVERLAY_COLORS;
  readonly glossary = CHART_GLOSSARY;
  readonly showGlossary = signal(false);
  readonly overlayHelp: Record<ChartOverlay, string> = {
    sma20: CHART_GLOSSARY[1].meaning,
    sma50: CHART_GLOSSARY[2].meaning,
    sma200: CHART_GLOSSARY[3].meaning,
    bollinger: CHART_GLOSSARY[4].meaning,
  };

  readonly candles = signal<Candle[]>([]);
  readonly currency = signal<string | null>(null);
  readonly loading = signal(true);
  readonly pending = signal(false);
  readonly error = signal<string | null>(null);

  readonly range = signal<ChartRange>(this.prefs.chartRange());
  readonly style = signal<ChartStyle>(this.prefs.chartStyle());
  readonly showVolume = signal(this.prefs.showVolume());
  readonly active = signal<Set<ChartOverlay>>(new Set());
  readonly hover = signal<number | null>(null);
  readonly width = signal(720);

  private request?: Subscription;
  private retries = 0;

  constructor() {
    const destroyRef = inject(DestroyRef);

    effect(() => {
      const preset = this.overlays();
      untracked(() =>
        this.active.set(new Set(preset ?? (this.prefs.showAverages() ? (['sma20', 'sma50'] as ChartOverlay[]) : []))),
      );
    });

    effect(() => {
      const symbol = this.symbol();
      untracked(() => {
        this.retries = 0;
        this.load(symbol);
      });
    });

    const observer = new ResizeObserver((entries) => {
      const w = Math.floor(entries[0]?.contentRect.width ?? 0);
      if (w > 0 && w !== this.width()) {
        this.width.set(w);
      }
    });
    observer.observe(this.host.nativeElement);
    destroyRef.onDestroy(() => {
      observer.disconnect();
      this.request?.unsubscribe();
    });
  }

  private load(symbol: string): void {
    this.request?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);
    this.hover.set(null);
    this.request = this.market.candles(symbol).subscribe({
      next: (series) => {
        this.candles.set(series.candles);
        this.currency.set(series.currency);
        this.loading.set(false);
        this.pending.set(false);
      },
      error: (e: { status?: number }) => {
        if (e?.status === 503 && this.retries < MAX_RETRIES) {
          // The poller has the request queued: candles arrive within seconds.
          this.pending.set(true);
          this.retries++;
          this.request = timer(2500).subscribe(() => this.load(symbol));
          return;
        }
        this.loading.set(false);
        this.pending.set(false);
        this.candles.set([]);
        this.error.set(e?.status === 404
          ? `No price history for ${symbol}.`
          : 'Price history is unavailable right now.');
      },
    });
  }

  retry(): void {
    this.retries = 0;
    this.market.invalidate(this.symbol());
    this.load(this.symbol());
  }

  setRange(r: ChartRange): void {
    this.range.set(r);
    this.hover.set(null);
  }

  setStyle(s: ChartStyle): void {
    this.style.set(s);
  }

  toggleOverlay(o: ChartOverlay): void {
    this.active.update((set) => {
      const next = new Set(set);
      if (next.has(o)) next.delete(o); else next.add(o);
      return next;
    });
  }

  // ----- derived series -------------------------------------------------------

  private readonly closes = computed(() => this.candles().map((c) => c.close));
  private readonly sma = computed(() => ({
    sma20: rollingSma(this.closes(), 20),
    sma50: rollingSma(this.closes(), 50),
    sma200: rollingSma(this.closes(), 200),
  }));
  private readonly bands = computed(() => {
    const mid = this.sma().sma20;
    const sd = rollingStd(this.closes(), 20);
    return {
      upper: mid.map((m, i) => (m === null || sd[i] === null ? null : m + 2 * sd[i]!)),
      lower: mid.map((m, i) => (m === null || sd[i] === null ? null : m - 2 * sd[i]!)),
    };
  });

  readonly start = computed(() => rangeStart(this.candles(), this.range()));
  readonly visible = computed(() => this.candles().slice(this.start()));

  /** Overlays that have at least one value in the visible range. */
  readonly availableOverlays = computed(() => {
    const s = this.start();
    const has = (series: (number | null)[]) => series.slice(s).some((v) => v !== null);
    return this.overlayOptions.filter((o) =>
      o === 'bollinger' ? has(this.bands().upper) : has(this.sma()[o]));
  });

  /** Summary of the visible range: change against the close before it began. */
  readonly stats = computed(() => {
    const all = this.candles();
    const vis = this.visible();
    if (vis.length === 0) {
      return null;
    }
    const s = this.start();
    const last = vis[vis.length - 1];
    const reference = s > 0 ? all[s - 1].close : vis[0].open;
    const change = last.close - reference;
    const volumes = vis.map((c) => c.volume).filter((v): v is number => v !== null);
    return {
      last: last.close,
      change,
      changePercent: reference ? (change / reference) * 100 : 0,
      high: Math.max(...vis.map((c) => c.high)),
      low: Math.min(...vis.map((c) => c.low)),
      avgVolume: volumes.length ? volumes.reduce((a, b) => a + b, 0) / volumes.length : null,
      from: vis[0].date,
      to: last.date,
    };
  });

  /** The bar under the pointer, with its change against the bar before it. */
  readonly hovered = computed(() => {
    const i = this.hover();
    const vis = this.visible();
    if (i === null || i >= vis.length) {
      return null;
    }
    const bar = vis[i];
    const absolute = this.start() + i;
    const prev = absolute > 0 ? this.candles()[absolute - 1].close : bar.open;
    const ind = (series: (number | null)[]) => series[absolute] ?? null;
    return {
      bar,
      change: bar.close - prev,
      changePercent: prev ? ((bar.close - prev) / prev) * 100 : 0,
      sma20: ind(this.sma().sma20),
      sma50: ind(this.sma().sma50),
    };
  });

  readonly plot = computed(() => {
    const w = this.width();
    const h = this.height();
    const volumeOn = this.showVolume() && this.visible().some((c) => c.volume);
    const priceBottom = h - PAD.bottom - (volumeOn ? Math.round(h * 0.18) + 8 : 0);
    return {
      w,
      h,
      left: PAD.left,
      right: w - PAD.right,
      top: PAD.top,
      priceBottom,
      volTop: priceBottom + 8,
      volBottom: h - PAD.bottom,
      volumeOn,
    };
  });

  readonly geometry = computed(() => {
    const vis = this.visible();
    const p = this.plot();
    const n = vis.length;
    if (n === 0) {
      return null;
    }
    const s = this.start();
    const active = this.active();
    const style = this.style();

    // Price scale over everything drawn: bars (or closes) and active overlays.
    let lo = Infinity;
    let hi = -Infinity;
    for (const c of vis) {
      lo = Math.min(lo, style === 'candles' ? c.low : c.close);
      hi = Math.max(hi, style === 'candles' ? c.high : c.close);
    }
    const overlaySeries: { key: ChartOverlay | 'bbUpper' | 'bbLower'; values: (number | null)[]; color: string; dashed: boolean }[] = [];
    for (const o of ['sma20', 'sma50', 'sma200'] as const) {
      if (active.has(o)) {
        overlaySeries.push({ key: o, values: this.sma()[o].slice(s), color: OVERLAY_COLORS[o], dashed: false });
      }
    }
    if (active.has('bollinger')) {
      overlaySeries.push({ key: 'bbUpper', values: this.bands().upper.slice(s), color: OVERLAY_COLORS['bollinger'], dashed: true });
      overlaySeries.push({ key: 'bbLower', values: this.bands().lower.slice(s), color: OVERLAY_COLORS['bollinger'], dashed: true });
    }
    for (const o of overlaySeries) {
      for (const v of o.values) {
        if (v !== null) {
          lo = Math.min(lo, v);
          hi = Math.max(hi, v);
        }
      }
    }
    if (hi === lo) {
      hi += 1;
      lo -= 1;
    }
    const padY = (hi - lo) * 0.06;
    lo -= padY;
    hi += padY;

    const plotW = p.right - p.left;
    const band = plotW / n;
    const xAt = (i: number) => p.left + band * (i + 0.5);
    const yAt = (v: number) => p.top + ((hi - v) / (hi - lo)) * (p.priceBottom - p.top);

    const maxVol = Math.max(1, ...vis.map((c) => c.volume ?? 0));
    const bodyW = Math.max(1, Math.min(14, band * 0.62));

    const bars: Bar[] = vis.map((c, i) => {
      const up = c.close >= c.open;
      const top = yAt(Math.max(c.open, c.close));
      const bottom = yAt(Math.min(c.open, c.close));
      const vh = p.volumeOn ? ((c.volume ?? 0) / maxVol) * (p.volBottom - p.volTop) : 0;
      return {
        x: xAt(i),
        up,
        bodyY: top,
        bodyH: Math.max(1, bottom - top),
        wickTop: yAt(c.high),
        wickBottom: yAt(c.low),
        volY: p.volBottom - vh,
        volH: vh,
      };
    });

    const line = vis.map((c, i) => `${i === 0 ? 'M' : 'L'}${xAt(i).toFixed(1)},${yAt(c.close).toFixed(1)}`).join('');
    const area = `${line}L${xAt(n - 1).toFixed(1)},${p.priceBottom}L${xAt(0).toFixed(1)},${p.priceBottom}Z`;

    const overlays = overlaySeries.map((o) => {
      let d = '';
      let pen = false;
      o.values.forEach((v, i) => {
        if (v === null) {
          pen = false;
          return;
        }
        d += `${pen ? 'L' : 'M'}${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`;
        pen = true;
      });
      return { key: o.key, d, color: o.color, dashed: o.dashed };
    });

    const step = niceStep(hi - lo, 5);
    const yTicks: Tick[] = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) {
      yTicks.push({ pos: yAt(v), label: formatPrice(v, step) });
    }

    const xTicks: Tick[] = [];
    const count = Math.min(6, n);
    const longRange = this.range() === '1Y' || this.range() === 'YTD';
    for (let k = 0; k < count; k++) {
      const i = Math.round((k * (n - 1)) / Math.max(1, count - 1));
      const d = new Date(vis[i].time);
      xTicks.push({
        pos: xAt(i),
        label: longRange
          ? d.toLocaleDateString(undefined, { month: 'short', year: '2-digit', timeZone: 'UTC' })
          : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', timeZone: 'UTC' }),
      });
    }

    const lastClose = vis[n - 1].close;
    return {
      bars,
      bodyW,
      band,
      line,
      area,
      overlays,
      yTicks,
      xTicks,
      lastY: yAt(lastClose),
      lastLabel: formatPrice(lastClose, step / 10),
      lastUp: lastClose >= (s > 0 ? this.candles()[s - 1].close : vis[0].open),
      yAt,
      xAt,
      step,
    };
  });

  readonly crosshair = computed(() => {
    const i = this.hover();
    const g = this.geometry();
    const vis = this.visible();
    if (i === null || !g || i >= vis.length) {
      return null;
    }
    const c = vis[i];
    const x = g.xAt(i);
    const y = g.yAt(c.close);
    const p = this.plot();
    return {
      x,
      y,
      label: formatPrice(c.close, g.step / 10),
      tooltipLeft: x > (p.left + p.right) / 2,
    };
  });

  onPointerMove(event: PointerEvent): void {
    const g = this.geometry();
    if (!g) {
      return;
    }
    const svg = event.currentTarget as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    const scale = this.width() / rect.width;
    const x = (event.clientX - rect.left) * scale;
    const i = Math.floor((x - PAD.left) / g.band);
    const n = this.visible().length;
    this.hover.set(i < 0 || i >= n ? null : i);
  }

  onPointerLeave(): void {
    this.hover.set(null);
  }

  formatVolume(v: number | null | undefined): string {
    if (v === null || v === undefined) {
      return '—';
    }
    if (v >= 1e9) return (v / 1e9).toFixed(2) + 'B';
    if (v >= 1e6) return (v / 1e6).toFixed(2) + 'M';
    if (v >= 1e3) return (v / 1e3).toFixed(1) + 'K';
    return String(Math.round(v));
  }

  formatDate(time: number): string {
    return new Date(time).toLocaleDateString(undefined, {
      weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC',
    });
  }
}

/** Enough decimals to tell neighbouring ticks apart, never fewer than two. */
function formatPrice(v: number, step: number): string {
  const decimals = Math.max(2, Math.min(6, -Math.floor(Math.log10(Math.max(step, 1e-9)))));
  return v.toFixed(decimals);
}
