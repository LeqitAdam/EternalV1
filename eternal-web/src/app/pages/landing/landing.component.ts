import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { AuthService } from '../../core/auth.service';

/**
 * Public sales / docs landing page for EternalSystem. Single component,
 * many sections — keeps the file long but avoids a constellation of
 * tiny components that don't get reused anywhere. Everything is
 * translated via ngx-translate; switching DE↔EN happens at runtime
 * with no rebuild.
 *
 * <p>Structure (each section is anchor-scrollable via the header nav):</p>
 * <ul>
 *     <li>Sticky header — logo, anchor nav, lang switcher, login CTA</li>
 *     <li>Hero — full-bleed pink gradient, tagline, dual CTA</li>
 *     <li>Why — four USP cards</li>
 *     <li>Highlights — six flagship features with image slots</li>
 *     <li>All features — every category in a compact grid</li>
 *     <li>Architecture — six-module overview</li>
 *     <li>Pricing — three tiers, "coming soon via Shopify"</li>
 *     <li>FAQ — accordion</li>
 *     <li>Footer — legal + tagline</li>
 * </ul>
 */
@Component({
  selector: 'et-landing',
  standalone: true,
  imports: [CommonModule, RouterLink, MatButtonModule, MatIconModule, MatExpansionModule, TranslateModule],
  styles: [`
    :host { display: block; }
    /* Pink gradient backdrop for the hero. Sits behind the content so
       the dark theme stays readable above-the-fold. */
    .hero-bg {
      background:
        radial-gradient(ellipse 80% 60% at 50% -10%, rgba(255, 33, 197, 0.35), transparent 70%),
        radial-gradient(ellipse 60% 50% at 80% 100%, rgba(255, 33, 197, 0.18), transparent 70%),
        linear-gradient(180deg, #0a0a0e 0%, #13131a 100%);
    }
    .section-bg-alt { background: #0f0f15; }
    .card-bg { background: #1c1c25; border: 1px solid #262633; }
    .card-bg-hover { transition: border-color .2s, transform .2s; }
    .card-bg-hover:hover { border-color: #ff21c5; transform: translateY(-2px); }
    /* Image slots are dashed boxes that still look intentional —
       the page works without screenshots, and looks better with them. */
    .image-slot {
      background: linear-gradient(135deg, #1c1c25 0%, #262633 100%);
      border: 2px dashed #3a3a4b;
      min-height: 220px;
    }
    .image-slot.has-image { border: 1px solid #262633; padding: 0; }
    .image-slot img { width: 100%; height: 100%; object-fit: cover; border-radius: inherit; }
    /* Pill tag colour palette — matches the in-game brand. */
    .tag {
      display: inline-block; padding: .2rem .65rem; border-radius: 9999px;
      font-size: .7rem; font-weight: 600; letter-spacing: .04em;
      text-transform: uppercase;
    }
    .tag-flagship { background: rgba(255, 33, 197, .15); color: #ffb1ec; border: 1px solid rgba(255, 33, 197, .4); }
    .tag-popular  { background: rgba(34, 197, 94, .15); color: #86efac; border: 1px solid rgba(34, 197, 94, .35); }
    .tag-new      { background: rgba(56, 189, 248, .15); color: #7dd3fc; border: 1px solid rgba(56, 189, 248, .35); }
    .tag-default  { background: rgba(155, 155, 176, .12); color: #c7c7d4; border: 1px solid rgba(155, 155, 176, .25); }
    /* Material accordion overrides — dashboard theme alignment. */
    ::ng-deep .faq-panel .mat-expansion-panel {
      background: #1c1c25 !important; color: #e7e7ee !important;
      border: 1px solid #262633; border-radius: 12px !important;
      margin-bottom: .75rem !important;
    }
    ::ng-deep .faq-panel .mat-expansion-panel-header-title { color: #e7e7ee; }
    ::ng-deep .faq-panel .mat-expansion-indicator::after { color: #ff21c5; }
  `],
  template: `
    <!-- =========================================================== -->
    <!-- Header: sticky bar with anchor nav + language switch + CTA  -->
    <!-- =========================================================== -->
    <header class="sticky top-0 z-50 backdrop-blur bg-ink-900/80 border-b border-ink-700">
      <div class="max-w-7xl mx-auto px-6 py-3 flex items-center gap-6">
        <a href="#top" class="flex items-center gap-2 shrink-0">
          <span class="text-2xl font-bold text-eternal-400">»</span>
          <span class="text-xl font-bold">EternalSystem</span>
        </a>
        <nav class="hidden md:flex items-center gap-6 text-sm text-ink-200 flex-1">
          <a href="#highlights" class="hover:text-eternal-300">{{ 'header.nav.highlights' | translate }}</a>
          <a href="#features" class="hover:text-eternal-300">{{ 'header.nav.features' | translate }}</a>
          <a href="#architecture" class="hover:text-eternal-300">{{ 'header.nav.architecture' | translate }}</a>
          <a href="#pricing" class="hover:text-eternal-300">{{ 'header.nav.pricing' | translate }}</a>
          <a href="#faq" class="hover:text-eternal-300">{{ 'header.nav.faq' | translate }}</a>
        </nav>
        <div class="flex items-center gap-2 ml-auto">
          <button
            (click)="setLang('de')"
            class="text-xs px-2 py-1 rounded transition"
            [class.text-eternal-300]="currentLang() === 'de'"
            [class.text-ink-300]="currentLang() !== 'de'">DE</button>
          <span class="text-ink-500">/</span>
          <button
            (click)="setLang('en')"
            class="text-xs px-2 py-1 rounded transition"
            [class.text-eternal-300]="currentLang() === 'en'"
            [class.text-ink-300]="currentLang() !== 'en'">EN</button>
          <a [routerLink]="loggedIn() ? '/dashboard' : '/login'"
             mat-stroked-button color="primary" class="!ml-2 hidden sm:inline-flex">
            {{ 'header.loginCta' | translate }}
          </a>
        </div>
      </div>
    </header>

    <!-- =========================================================== -->
    <!-- Hero                                                         -->
    <!-- =========================================================== -->
    <section id="top" class="hero-bg">
      <div class="max-w-7xl mx-auto px-6 py-20 lg:py-28 text-center">
        <div class="inline-block tag tag-flagship mb-6">{{ 'hero.eyebrow' | translate }}</div>
        <h1 class="text-5xl md:text-7xl font-bold mb-4">
          <span class="text-eternal-400">»</span>
          <span class="text-white">{{ 'hero.title' | translate }}</span>
        </h1>
        <p class="text-xl md:text-2xl text-ink-100 mb-3 max-w-3xl mx-auto">{{ 'hero.tagline' | translate }}</p>
        <p class="text-base text-ink-300 mb-10 max-w-2xl mx-auto">{{ 'hero.description' | translate }}</p>
        <div class="flex flex-wrap gap-4 justify-center mb-12">
          <a href="#pricing" mat-flat-button color="primary" class="!px-8 !py-6 !text-base">
            {{ 'hero.ctaPrimary' | translate }}
          </a>
          <a href="#highlights" mat-stroked-button class="!px-8 !py-6 !text-base !text-white">
            {{ 'hero.ctaSecondary' | translate }}
          </a>
        </div>
        <!-- Tiny stat strip — anchors the title with three concrete numbers. -->
        <div class="grid grid-cols-3 gap-8 max-w-md mx-auto text-center">
          <div>
            <div class="text-3xl font-bold text-eternal-300">6</div>
            <div class="text-xs text-ink-300">{{ 'hero.stats.modules' | translate }}</div>
          </div>
          <div>
            <div class="text-3xl font-bold text-eternal-300">60+</div>
            <div class="text-xs text-ink-300">{{ 'hero.stats.features' | translate }}</div>
          </div>
          <div>
            <div class="text-3xl font-bold text-eternal-300">2</div>
            <div class="text-xs text-ink-300">{{ 'hero.stats.languages' | translate }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- Why — four USP cards                                         -->
    <!-- =========================================================== -->
    <section class="section-bg-alt py-20">
      <div class="max-w-7xl mx-auto px-6">
        <h2 class="text-3xl md:text-4xl font-bold text-center mb-3">{{ 'why.title' | translate }}</h2>
        <p class="text-ink-300 text-center mb-12 max-w-2xl mx-auto">{{ 'why.subtitle' | translate }}</p>
        <div class="grid sm:grid-cols-2 lg:grid-cols-4 gap-5">
          <div *ngFor="let key of ['allInOne','replay','network','gdpr']"
               class="card-bg card-bg-hover rounded-xl p-6">
            <mat-icon class="!text-eternal-400 !text-4xl !w-10 !h-10 mb-3">{{ iconFor(key) }}</mat-icon>
            <h3 class="font-semibold text-lg mb-2">{{ 'why.cards.' + key + '.title' | translate }}</h3>
            <p class="text-sm text-ink-300 leading-relaxed">{{ 'why.cards.' + key + '.body' | translate }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- Highlight features — flagship six with image slots          -->
    <!-- =========================================================== -->
    <section id="highlights" class="py-20">
      <div class="max-w-7xl mx-auto px-6">
        <h2 class="text-3xl md:text-4xl font-bold text-center mb-3">{{ 'highlights.title' | translate }}</h2>
        <p class="text-ink-300 text-center mb-16 max-w-2xl mx-auto">{{ 'highlights.subtitle' | translate }}</p>

        <!-- Six stacked rows, alternating image-left / image-right. -->
        <div class="space-y-20">
          <div *ngFor="let h of highlightKeys; let i = index"
               class="grid lg:grid-cols-2 gap-10 items-center"
               [class.lg:flex-row-reverse]="i % 2 !== 0">
            <!-- text column -->
            <div [class.lg:order-2]="i % 2 !== 0">
              <span class="tag" [ngClass]="tagClass(h)">{{ 'highlights.items.' + h + '.tag' | translate }}</span>
              <h3 class="text-2xl md:text-3xl font-bold mt-3 mb-4">{{ 'highlights.items.' + h + '.title' | translate }}</h3>
              <p class="text-ink-200 leading-relaxed mb-5">{{ 'highlights.items.' + h + '.body' | translate }}</p>
              <ul class="space-y-2">
                <li *ngFor="let b of bulletsFor(h)" class="flex gap-2 text-sm text-ink-200">
                  <mat-icon class="!text-eternal-400 !text-base !w-4 !h-4 !leading-4 mt-0.5">check_circle</mat-icon>
                  <span>{{ b }}</span>
                </li>
              </ul>
            </div>
            <!-- image slot -->
            <div [class.lg:order-1]="i % 2 !== 0"
                 class="image-slot rounded-xl flex items-center justify-center">
              <!-- Drop a screenshot here: src/assets/landing/<key>.png.
                   If the file exists the <img> renders; otherwise the
                   placeholder label below shows. -->
              <img *ngIf="hasImage(h)" [src]="'assets/landing/' + h + '.png'" [alt]="h" />
              <div *ngIf="!hasImage(h)" class="text-center text-ink-400 px-6">
                <mat-icon class="!text-5xl !w-12 !h-12 mb-2">image</mat-icon>
                <div class="text-sm">{{ 'highlights.imagePlaceholder' | translate }}</div>
                <code class="text-xs text-ink-500">assets/landing/{{ h }}.png</code>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- All features — compact grid grouped by category              -->
    <!-- =========================================================== -->
    <section id="features" class="section-bg-alt py-20">
      <div class="max-w-7xl mx-auto px-6">
        <h2 class="text-3xl md:text-4xl font-bold text-center mb-3">{{ 'allFeatures.title' | translate }}</h2>
        <p class="text-ink-300 text-center mb-12 max-w-2xl mx-auto">{{ 'allFeatures.subtitle' | translate }}</p>

        <div class="grid md:grid-cols-2 lg:grid-cols-3 gap-5">
          <div *ngFor="let cat of categoryKeys" class="card-bg rounded-xl p-6">
            <div class="flex items-center gap-2 mb-4">
              <mat-icon class="!text-eternal-400">{{ iconFor(cat) }}</mat-icon>
              <h3 class="text-lg font-semibold">{{ 'allFeatures.categories.' + cat + '.title' | translate }}</h3>
            </div>
            <ul class="space-y-2">
              <li *ngFor="let item of categoryItems(cat)" class="flex gap-2 text-sm text-ink-200">
                <span class="text-eternal-400 shrink-0">›</span>
                <span>{{ item }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- Architecture — six module cards                              -->
    <!-- =========================================================== -->
    <section id="architecture" class="py-20">
      <div class="max-w-7xl mx-auto px-6">
        <h2 class="text-3xl md:text-4xl font-bold text-center mb-3">{{ 'architecture.title' | translate }}</h2>
        <p class="text-ink-300 text-center mb-12 max-w-2xl mx-auto">{{ 'architecture.subtitle' | translate }}</p>
        <div class="grid sm:grid-cols-2 lg:grid-cols-3 gap-5">
          <div *ngFor="let m of moduleKeys" class="card-bg card-bg-hover rounded-xl p-6">
            <code class="text-eternal-300 text-sm font-mono block mb-2">{{ 'architecture.modules.' + m + '.title' | translate }}</code>
            <p class="text-sm text-ink-200 leading-relaxed">{{ 'architecture.modules.' + m + '.body' | translate }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- Pricing                                                     -->
    <!-- =========================================================== -->
    <section id="pricing" class="section-bg-alt py-20">
      <div class="max-w-7xl mx-auto px-6">
        <div class="text-center mb-12">
          <span class="tag tag-flagship mb-4 inline-block">{{ 'pricing.comingSoonBadge' | translate }}</span>
          <h2 class="text-3xl md:text-4xl font-bold mt-3 mb-3">{{ 'pricing.title' | translate }}</h2>
          <p class="text-ink-300 max-w-2xl mx-auto">{{ 'pricing.subtitle' | translate }}</p>
        </div>
        <div class="grid md:grid-cols-3 gap-5 max-w-5xl mx-auto">
          <div *ngFor="let tier of tierKeys"
               class="card-bg card-bg-hover rounded-xl p-8 relative"
               [class.!border-eternal-500]="tier === 'pro'">
            <div *ngIf="tier === 'pro'" class="absolute -top-3 left-1/2 -translate-x-1/2 tag tag-flagship whitespace-nowrap">
              {{ 'pricing.tiers.pro.popular' | translate }}
            </div>
            <h3 class="text-2xl font-bold mb-1">{{ 'pricing.tiers.' + tier + '.title' | translate }}</h3>
            <div class="flex items-baseline gap-2 mb-4">
              <span class="text-4xl font-bold text-eternal-300">{{ 'pricing.tiers.' + tier + '.price' | translate }}</span>
              <span class="text-sm text-ink-300">{{ 'pricing.tiers.' + tier + '.frequency' | translate }}</span>
            </div>
            <p class="text-sm text-ink-200 mb-5">{{ 'pricing.tiers.' + tier + '.for' | translate }}</p>
            <ul class="space-y-2 mb-6">
              <li *ngFor="let f of tierFeatures(tier)" class="flex gap-2 text-sm text-ink-200">
                <mat-icon class="!text-eternal-400 !text-base !w-4 !h-4 !leading-4 mt-0.5">check</mat-icon>
                <span>{{ f }}</span>
              </li>
            </ul>
            <!-- Shopify-CTA: currently a placeholder; later this becomes
                 an href to the Shopify product page for the tier. -->
            <a [href]="shopifyUrl(tier)" target="_blank" rel="noopener"
               mat-flat-button color="primary" class="!w-full">
              {{ 'pricing.tiers.' + tier + '.cta' | translate }}
            </a>
          </div>
        </div>
        <p class="text-center text-sm text-ink-300 mt-8 max-w-xl mx-auto">{{ 'pricing.note' | translate }}</p>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- FAQ — accordion                                              -->
    <!-- =========================================================== -->
    <section id="faq" class="py-20">
      <div class="max-w-3xl mx-auto px-6">
        <h2 class="text-3xl md:text-4xl font-bold text-center mb-12">{{ 'faq.title' | translate }}</h2>
        <mat-accordion class="faq-panel">
          <mat-expansion-panel *ngFor="let item of faqItems()">
            <mat-expansion-panel-header>
              <mat-panel-title>{{ item.q }}</mat-panel-title>
            </mat-expansion-panel-header>
            <p class="text-sm text-ink-200 leading-relaxed">{{ item.a }}</p>
          </mat-expansion-panel>
        </mat-accordion>
      </div>
    </section>

    <!-- =========================================================== -->
    <!-- Footer                                                       -->
    <!-- =========================================================== -->
    <footer class="section-bg-alt border-t border-ink-700">
      <div class="max-w-7xl mx-auto px-6 py-10">
        <div class="flex flex-col sm:flex-row gap-6 justify-between items-start sm:items-center">
          <div>
            <div class="flex items-center gap-2 mb-2">
              <span class="text-2xl font-bold text-eternal-400">»</span>
              <span class="text-lg font-bold">EternalSystem</span>
            </div>
            <p class="text-sm text-ink-300 max-w-md">{{ 'footer.tagline' | translate }}</p>
          </div>
          <nav class="text-sm text-ink-200 flex gap-4">
            <a routerLink="/help" class="hover:text-eternal-300">{{ 'footer.imprint' | translate }}</a>
            <a routerLink="/help" class="hover:text-eternal-300">{{ 'footer.privacy' | translate }}</a>
          </nav>
        </div>
        <div class="text-xs text-ink-400 mt-6">{{ 'footer.copyright' | translate: { year: currentYear } }}</div>
      </div>
    </footer>
  `
})
export class LandingComponent implements OnInit {
  private readonly translate = inject(TranslateService);
  private readonly auth = inject(AuthService);

  readonly currentLang = signal<string>('de');
  readonly currentYear = new Date().getFullYear();
  readonly loggedIn = computed(() => this.auth.isAuthenticated());

  // Static lists drive the *ngFor loops — keeping translation keys
  // structured in the JSON file (one object per item) means we don't
  // need a separate model class.
  readonly highlightKeys = ['replay', 'reports', 'dashboard', 'permissions', 'gdpr', 'punishments'];
  readonly categoryKeys = [
    'punishments', 'reports', 'replay', 'appeals',
    'permissions', 'dashboard', 'gdpr', 'integration', 'i18n'
  ];
  readonly moduleKeys = ['core', 'spigot', 'bungee', 'api', 'replay', 'web'];
  readonly tierKeys = ['starter', 'pro', 'network'];

  /** Which highlight keys ship with a screenshot in src/assets/landing/.
   *  Add filenames here as they land — the placeholder shows otherwise. */
  private readonly imagedHighlights = new Set<string>();

  ngOnInit() {
    // Initial language: previously-saved choice, else browser hint, else DE.
    const saved = localStorage.getItem('eternal-lang');
    const browser = (this.translate.getBrowserLang() || '').toLowerCase();
    const initial = saved && ['de', 'en'].includes(saved) ? saved
        : ['de', 'en'].includes(browser) ? browser : 'de';
    this.translate.use(initial);
    this.currentLang.set(initial);
  }

  setLang(lang: 'de' | 'en') {
    this.translate.use(lang);
    this.currentLang.set(lang);
    localStorage.setItem('eternal-lang', lang);
  }

  hasImage(key: string): boolean {
    return this.imagedHighlights.has(key);
  }

  /** Pulls the highlight's bullet array out of the translation file —
   *  ngx-translate's instant() returns either the resolved array or
   *  the key itself if missing. */
  bulletsFor(key: string): string[] {
    const out = this.translate.instant('highlights.items.' + key + '.bullets');
    return Array.isArray(out) ? out : [];
  }

  /** Same trick for the per-category item lists in the all-features grid. */
  categoryItems(cat: string): string[] {
    const out = this.translate.instant('allFeatures.categories.' + cat + '.items');
    return Array.isArray(out) ? out : [];
  }

  tierFeatures(tier: string): string[] {
    const out = this.translate.instant('pricing.tiers.' + tier + '.features');
    return Array.isArray(out) ? out : [];
  }

  /** FAQ items come as an array of {q,a} objects from the JSON. */
  faqItems(): Array<{ q: string; a: string }> {
    const out = this.translate.instant('faq.items');
    return Array.isArray(out) ? out : [];
  }

  /** Tailwind ngClass mapping for the highlight pill tags. */
  tagClass(key: string): string {
    const map: Record<string, string> = {
      replay: 'tag-flagship',
      reports: 'tag-popular',
      permissions: 'tag-new',
      gdpr: 'tag-default',
      dashboard: 'tag-default',
      punishments: 'tag-default'
    };
    return map[key] ?? 'tag-default';
  }

  /** Material-icon name for each section key. */
  iconFor(key: string): string {
    const map: Record<string, string> = {
      allInOne: 'all_inclusive',
      replay: 'movie',
      network: 'hub',
      gdpr: 'verified_user',
      punishments: 'gavel',
      reports: 'flag',
      appeals: 'contact_support',
      permissions: 'key',
      dashboard: 'dashboard',
      integration: 'extension',
      i18n: 'translate'
    };
    return map[key] ?? 'star';
  }

  /** Shopify product URL per tier. Currently placeholder; swap these
   *  to real /products/<handle> URLs at Shopify launch. */
  shopifyUrl(tier: string): string {
    const handles: Record<string, string> = {
      starter: '#pricing',
      pro: '#pricing',
      network: '#pricing'
    };
    return handles[tier] ?? '#pricing';
  }
}
