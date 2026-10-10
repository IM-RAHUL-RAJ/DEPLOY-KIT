import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CloudMarkComponent } from '../../shared/cloud-mark.component';

interface Feature {
  title: string;
  text: string;
  icon: string;
}

/** A deterministic, decorative price path: no data, nothing a visitor could mistake for a quote. */
function decorativeSeries(n: number): number[] {
  const out: number[] = [];
  let v = 50;
  for (let i = 0; i < n; i++) {
    v += Math.sin(i * 0.55) * 3.2 + Math.cos(i * 0.21) * 2.1 + 0.9;
    out.push(v);
  }
  return out;
}

/**
 * The branded half of the sign-in and registration screens: what the platform does,
 * over an animated, purely decorative chart. Hidden on small screens, where the form
 * is all that matters.
 */
@Component({
  selector: 'app-auth-showcase',
  standalone: true,
  imports: [CloudMarkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './auth-showcase.component.scss',
  template: `
    <aside class="show" aria-hidden="false">

      <header class="show-brand">
        <app-cloud-mark [size]="44" [glow]="true" />
        <div>
          <span class="show-name">Akatsuki</span>
          <span class="show-sub">Trading platform</span>
        </div>
      </header>

      <div class="show-copy">
        <span class="show-kicker">{{ variant() === 'login' ? 'Welcome back' : 'Open an account' }}</span>
        <h1>{{ headline() }}</h1>
        <p>{{ blurb() }}</p>
      </div>

      <figure class="show-chart" aria-hidden="true">
        <svg viewBox="0 0 600 220" preserveAspectRatio="none">
          <defs>
            <linearGradient id="show-area" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#ff4b3a" stop-opacity="0.35" />
              <stop offset="100%" stop-color="#ff4b3a" stop-opacity="0" />
            </linearGradient>
            <linearGradient id="show-line" x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stop-color="#7a0a16" />
              <stop offset="60%" stop-color="#e1263d" />
              <stop offset="100%" stop-color="#ffc93c" />
            </linearGradient>
          </defs>
          @for (y of [44, 88, 132, 176]; track y) {
            <line class="grid-line" x1="0" x2="600" [attr.y1]="y" [attr.y2]="y" />
          }
          @for (c of candles; track $index) {
            <line class="wick" [class.up]="c.up" [attr.x1]="c.x" [attr.x2]="c.x" [attr.y1]="c.hi" [attr.y2]="c.lo" />
            <rect class="body" [class.up]="c.up" [attr.x]="c.x - 4" [attr.y]="c.top" width="8" [attr.height]="c.h" rx="1.5"
                  [style.animation-delay.ms]="$index * 35" />
          }
          <path class="area" [attr.d]="area" fill="url(#show-area)" />
          <path class="line" [attr.d]="line" stroke="url(#show-line)" />
          <circle class="dot" [attr.cx]="last.x" [attr.cy]="last.y" r="5" />
          <circle class="dot-ring" [attr.cx]="last.x" [attr.cy]="last.y" r="5" />
        </svg>
      </figure>

      <ul class="show-features">
        @for (f of features; track f.title) {
          <li>
            <span class="f-icon"><svg viewBox="0 0 24 24"><path [attr.d]="f.icon" /></svg></span>
            <div>
              <b>{{ f.title }}</b>
              <span>{{ f.text }}</span>
            </div>
          </li>
        }
      </ul>

      <footer class="show-foot">Market data is delayed. Signals inform; they are not investment advice.</footer>
    </aside>
  `,
})
export class AuthShowcaseComponent {
  readonly variant = input<'login' | 'register'>('login');

  readonly headline = computed(() =>
    this.variant() === 'login' ? 'Trade from the shadows, profit in the spotlight.' : 'Start trading in two quick steps.');
  readonly blurb = computed(() =>
    this.variant() === 'login'
      ? 'Live quotes, candlestick charts, advice with every formula shown, and automated orders that trade at your price.'
      : 'Tell us who you are, confirm your email, and once an administrator approves the account you are ready to trade.');

  readonly features: Feature[] = [
    { title: 'Live charts', text: 'Candles, averages and Bollinger bands over any range', icon: 'M3 3v18h18M7 15l3-4 3 3 5-7' },
    { title: 'Trade advice', text: 'Buy, sell or hold from five standard indicators', icon: 'M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z' },
    { title: 'Automated orders', text: 'Set a target; it trades once the price gets there', icon: 'M12 2v4M12 18v4M4.9 4.9l2.8 2.8M16.3 16.3l2.8 2.8M2 12h4M18 12h4M4.9 19.1l2.8-2.8M16.3 7.7l2.8-2.8' },
    { title: 'Alerts your way', text: 'In-app, email or both, per category', icon: 'M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0' },
  ];

  readonly line: string;
  readonly area: string;
  readonly last: { x: number; y: number };
  readonly candles: { x: number; up: boolean; top: number; h: number; hi: number; lo: number }[];

  constructor() {
    const s = decorativeSeries(46);
    const lo = Math.min(...s);
    const hi = Math.max(...s);
    const x = (i: number) => 8 + (i / (s.length - 1)) * 584;
    const y = (v: number) => 196 - ((v - lo) / (hi - lo)) * 160;
    this.line = s.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
    this.area = `${this.line}L${x(s.length - 1)},220L${x(0)},220Z`;
    this.last = { x: x(s.length - 1), y: y(s[s.length - 1]) };
    this.candles = s.slice(1).filter((_, i) => i % 2 === 0).map((v, k) => {
      const i = k * 2 + 1;
      const prev = s[i - 1];
      const up = v >= prev;
      const a = y(Math.max(v, prev));
      const b = y(Math.min(v, prev));
      return { x: x(i), up, top: a, h: Math.max(2, b - a), hi: a - 7, lo: b + 7 };
    });
  }
}
