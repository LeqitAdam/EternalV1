import { InjectionToken } from '@angular/core';

export interface ApiConfig {
  baseUrl: string;
}

/**
 * URL der Eternal-REST-API. In dev (`ng serve`) zeigt sie auf den lokalen
 * Standalone-Server; in prod kannst du sie auf die gehostete API umbiegen.
 */
export const API_CONFIG = new InjectionToken<ApiConfig>('ApiConfig', {
  providedIn: 'root',
  factory: () => ({
    baseUrl: '/api'
  })
});
