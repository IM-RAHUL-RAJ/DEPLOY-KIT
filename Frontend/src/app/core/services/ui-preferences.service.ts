import { Injectable, effect, signal } from '@angular/core';

export type DefaultOrderType = 'MARKET' | 'LIMIT';
export type ChartStyle = 'candles' | 'line' | 'area';
export type ChartRange = '1W' | '1M' | '3M' | '6M' | 'YTD' | '1Y';

export const CHART_RANGES: ChartRange[] = ['1W', '1M', '3M', '6M', 'YTD', '1Y'];

interface Stored {
  defaultOrderType: DefaultOrderType;
  defaultQuantity: number;
  chartStyle: ChartStyle;
  chartRange: ChartRange;
  showAverages: boolean;
  showVolume: boolean;
  sidebarCollapsed: boolean;
}

const KEY = 'trading_ui_preferences';

const DEFAULTS: Stored = {
  defaultOrderType: 'MARKET',
  defaultQuantity: 1,
  chartStyle: 'candles',
  chartRange: '3M',
  showAverages: true,
  showVolume: true,
  sidebarCollapsed: false,
};

function load(): Stored {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) {
      return { ...DEFAULTS };
    }
    const parsed = JSON.parse(raw) as Partial<Stored>;
    return { ...DEFAULTS, ...parsed };
  } catch {
    return { ...DEFAULTS };
  }
}

/**
 * Terminal preferences that belong to this device rather than the account: how the
 * order ticket opens and how charts look. Kept in local storage, applied at once.
 * Account-level preferences (alerts, display currency) live with the server.
 */
@Injectable({ providedIn: 'root' })
export class UiPreferencesService {
  private readonly initial = load();

  readonly defaultOrderType = signal<DefaultOrderType>(this.initial.defaultOrderType);
  readonly defaultQuantity = signal<number>(this.initial.defaultQuantity);
  readonly chartStyle = signal<ChartStyle>(this.initial.chartStyle);
  readonly chartRange = signal<ChartRange>(this.initial.chartRange);
  readonly showAverages = signal<boolean>(this.initial.showAverages);
  readonly showVolume = signal<boolean>(this.initial.showVolume);
  /** The navigation rail shows icons only. */
  readonly sidebarCollapsed = signal<boolean>(this.initial.sidebarCollapsed);

  constructor() {
    effect(() => {
      const value: Partial<Stored> = {
        defaultOrderType: this.defaultOrderType(),
        defaultQuantity: this.defaultQuantity(),
        chartStyle: this.chartStyle(),
        chartRange: this.chartRange(),
        showAverages: this.showAverages(),
        showVolume: this.showVolume(),
        sidebarCollapsed: this.sidebarCollapsed(),
      };
      try {
        localStorage.setItem(KEY, JSON.stringify(value));
      } catch {
        // Private mode or storage disabled: the preference still applies this session.
      }
    });
  }

  reset(): void {
    this.defaultOrderType.set(DEFAULTS.defaultOrderType);
    this.defaultQuantity.set(DEFAULTS.defaultQuantity);
    this.chartStyle.set(DEFAULTS.chartStyle);
    this.chartRange.set(DEFAULTS.chartRange);
    this.showAverages.set(DEFAULTS.showAverages);
    this.showVolume.set(DEFAULTS.showVolume);
    this.sidebarCollapsed.set(DEFAULTS.sidebarCollapsed);
  }
}
