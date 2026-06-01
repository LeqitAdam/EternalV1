import { ApplicationConfig, importProvidersFrom } from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideAnimations } from '@angular/platform-browser/animations';
import { HttpClient, provideHttpClient } from '@angular/common/http';
import { TranslateLoader, TranslateModule } from '@ngx-translate/core';
import { TranslateHttpLoader } from '@ngx-translate/http-loader';

import { routes } from './app.routes';

/** JSON-over-HTTP loader. Files live under src/assets/i18n/<lang>.json,
 *  the build copies them next to the bundle as static assets. */
export function translateLoaderFactory(http: HttpClient): TranslateLoader {
  return new TranslateHttpLoader(http, 'assets/i18n/', '.json');
}

export const appConfig: ApplicationConfig = {
  providers: [
    // anchor-scrolling makes #features / #pricing / etc. jump the
    // viewport to the section instead of just changing the URL hash.
    provideRouter(routes,
        withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' })),
    provideAnimations(),
    provideHttpClient(),
    importProvidersFrom(
      TranslateModule.forRoot({
        defaultLanguage: 'de',
        loader: {
          provide: TranslateLoader,
          useFactory: translateLoaderFactory,
          deps: [HttpClient]
        }
      })
    )
  ]
};
