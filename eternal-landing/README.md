# eternal-landing

Public sales / docs landing page for **EternalSystem**. Standalone Angular
17 app — independent build, independent deploy, independent dependency
tree. Lives next to the dashboard (`eternal-web`) so the two can run on
different hostnames (e.g. `eternalsystem.com` for the landing page,
`dashboard.eternalsystem.com` for the moderator UI) without sharing
bundle weight or routing concerns.

## Tech

- Angular 17 (standalone components)
- Material + Tailwind (same palette as the dashboard for visual continuity)
- `@ngx-translate/core` for runtime DE↔EN switching
- No backend / no API dependency — pure static

## Develop

```bash
cd eternal-landing
npm install
npm start          # ng serve on http://localhost:4300
```

## Build

```bash
npm run build      # → dist/eternal-landing/browser/
```

Copy `dist/eternal-landing/browser/` to whatever static host serves the
main domain (nginx, Cloudflare Pages, GitHub Pages, …). No backend
container needed.

## Customisation

| Want to change          | File                                                     |
| ----------------------- | -------------------------------------------------------- |
| Headline / body text    | `src/assets/i18n/de.json` + `en.json`                    |
| Add a translation       | Drop `<lang>.json`, add the code to the language switch  |
| Replace screenshots     | Drop PNGs at `src/assets/landing/<key>.png`, add the key to `imagedHighlights` in `landing.component.ts` |
| Shopify URLs            | `shopifyUrl()` in `landing.component.ts`                 |
| Dashboard CTA target    | `dashboardUrl` in `landing.component.ts`                 |
| Brand colors            | `tailwind.config.js` (`eternal` and `ink` palettes)      |
