import { Injectable, signal, effect } from '@angular/core';

export type Theme = 'dark' | 'light';

/**
 * The colour theme on screen. The customer's stored preference owns it: nothing is kept in
 * the browser. The account state applies the stored answer once it is read and registers a
 * saver, so a change made from the top bar or the settings screen is written back to the
 * preference. Before sign-in (and for an operator) the terminal is simply dark.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly currentTheme = signal<Theme>('dark');

  /** Writes a customer's choice to their preference; set by the account state. */
  private saver: ((theme: Theme) => void) | null = null;

  constructor() {
    effect(() => {
      const theme = this.currentTheme();
      document.documentElement.setAttribute('data-theme', theme);
      document.body.classList.remove('theme-dark', 'theme-light');
      document.body.classList.add(`theme-${theme}`);
    });
  }

  toggleTheme(): void {
    this.setTheme(this.currentTheme() === 'dark' ? 'light' : 'dark');
  }

  /** A choice by the customer: shown at once and saved to their preference. */
  setTheme(theme: Theme): void {
    this.currentTheme.set(theme);
    this.saver?.(theme);
  }

  /** The stored answer, shown without saving it again. Anything unknown reads as dark. */
  apply(theme: string | null | undefined): void {
    this.currentTheme.set(theme === 'light' ? 'light' : 'dark');
  }

  registerSaver(saver: ((theme: Theme) => void) | null): void {
    this.saver = saver;
  }
}
