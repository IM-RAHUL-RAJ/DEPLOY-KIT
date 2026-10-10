import { Directive, HostListener, inject, input } from '@angular/core';
import { InstrumentChartService } from './instrument-chart.service';

/**
 * Makes a symbol clickable: click or Enter opens its chart. Usage:
 * {@code <td [appChartSymbol]="row.symbol">{{ row.symbol }}</td>}
 */
@Directive({
  selector: '[appChartSymbol]',
  standalone: true,
  host: {
    class: 'chart-symbol',
    role: 'button',
    tabindex: '0',
    '[attr.title]': '"View " + appChartSymbol() + " chart"',
  },
})
export class ChartSymbolDirective {
  private readonly charts = inject(InstrumentChartService);

  readonly appChartSymbol = input.required<string | null | undefined>();

  @HostListener('click', ['$event'])
  @HostListener('keydown.enter', ['$event'])
  openChart(event: Event): void {
    event.stopPropagation();
    this.charts.open(this.appChartSymbol());
  }
}
