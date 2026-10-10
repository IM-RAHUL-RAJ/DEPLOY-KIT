import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The Akatsuki red cloud: the platform's mark. A red cloud with a pale outline
 * and the curls inside its puffs, on a black tile edged in yellow.
 *
 * Decorative wherever it is used beside the product name, so it is hidden from
 * assistive technology; the name next to it is what gets read.
 */
@Component({
  selector: 'app-cloud-mark',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    'aria-hidden': 'true',
    '[style.--mark-size.px]': 'size()',
    '[class.glow]': 'glow()',
  },
  template: `
    <svg viewBox="0 0 64 40" class="cloud" focusable="false">
      <path
        class="cloud-body"
        d="M12 34C5.5 34 2.5 27.5 6.5 23 3 17.5 8 11 14.5 12.5 16 6 24.5 3 30 8 34 2.5 44 3.5 46 11c6.5-2.5 14 2.5 11.5 9.5C63 23.5 61 34 53 34Z"
      />
      <path class="cloud-curl" d="M15.5 25.5c-.3-3.6 4.6-4.4 5.6-1.4.7 2.3-1.7 3.8-3.2 2.5" />
      <path class="cloud-curl" d="M29.5 18c.2-3.7 5.3-3.9 5.9-.7.4 2.3-2.1 3.4-3.4 2.1" />
      <path class="cloud-curl" d="M45 24.5c.1-3.4 4.8-3.7 5.4-.7" />
    </svg>
  `,
  styles: `
    :host {
      --mark-size: 46px;
      display: inline-grid;
      place-items: center;
      flex-shrink: 0;
      width: var(--mark-size);
      height: var(--mark-size);
      border-radius: calc(var(--mark-size) * 0.28);
      background: radial-gradient(circle at 50% 30%, #2a080e 0%, #0b0405 70%);
      border: 1px solid rgba(255, 201, 60, 0.45);
      box-shadow: inset 0 0 0 1px rgba(225, 38, 61, 0.25);
    }
    :host(.glow) {
      box-shadow: inset 0 0 0 1px rgba(225, 38, 61, 0.25), 0 0 22px rgba(225, 38, 61, 0.35);
    }
    .cloud {
      width: 82%;
      height: auto;
      overflow: visible;
    }
    .cloud-body {
      fill: #e1263d;
      stroke: #fff4f5;
      stroke-width: 2.6;
      stroke-linejoin: round;
    }
    .cloud-curl {
      fill: none;
      stroke: #fff4f5;
      stroke-width: 2.2;
      stroke-linecap: round;
    }
  `,
})
export class CloudMarkComponent {
  /** Tile edge length in pixels. */
  readonly size = input(46);
  /** A soft red glow, for the sign-in page where the mark stands alone. */
  readonly glow = input(false);
}
