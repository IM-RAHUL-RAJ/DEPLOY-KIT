import {
  ChangeDetectionStrategy,
   Component, inject
} from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: '<router-outlet />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  // Inject ThemeService to initialize default/saved theme on app boot
  private readonly themeService = inject(ThemeService);
}
