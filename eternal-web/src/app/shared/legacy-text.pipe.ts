import { Pipe, PipeTransform } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

/**
 * Renders Minecraft legacy &-coded text as styled HTML spans.
 *
 * <p>Used to display the {@code lastDisplayName} field (cached form of
 * CloudNet-Chat / nametag-plugin output) as actual coloured text in the
 * dashboard, instead of showing the plain string with raw {@code &} codes.</p>
 *
 * <p>Only converts the colour and basic style codes — we do NOT render the
 * obfuscated/magic {@code &k} animation, since it requires JS and is just
 * visual noise in a web UI. Bold/italic/underline ARE rendered.</p>
 *
 * <p>The result is marked as trusted HTML — input MUST come from controlled
 * sources (DB-stored last_display_name set by our own ConnectionListener),
 * not from free-form user input, to avoid XSS.</p>
 */
@Pipe({ name: 'legacy', standalone: true })
export class LegacyTextPipe implements PipeTransform {

  /** Vanilla Minecraft chat colour palette mapped to web-safe hex. */
  private static readonly COLOR: Record<string, string> = {
    '0': '#000000', '1': '#0000aa', '2': '#00aa00', '3': '#00aaaa',
    '4': '#aa0000', '5': '#aa00aa', '6': '#ffaa00', '7': '#aaaaaa',
    '8': '#555555', '9': '#5555ff', 'a': '#55ff55', 'b': '#55ffff',
    'c': '#ff5555', 'd': '#ff55ff', 'e': '#ffff55', 'f': '#ffffff'
  };

  constructor(private readonly sanitizer: DomSanitizer) {}

  transform(value: string | null | undefined): SafeHtml {
    if (!value) return '';
    return this.sanitizer.bypassSecurityTrustHtml(this.render(value));
  }

  private render(input: string): string {
    // Accept both `&` and `§` (Minecraft's internal section sign) as marker.
    const tokens = input.split(/[&§]/);
    if (tokens.length === 0) return '';

    let out = '';
    let color: string | null = null;
    let bold = false, italic = false, underline = false, strike = false;
    let openSpans = 0;

    const open = () => {
      const styles: string[] = [];
      if (color) styles.push(`color:${color}`);
      if (bold) styles.push('font-weight:600');
      if (italic) styles.push('font-style:italic');
      if (underline) styles.push('text-decoration:underline');
      if (strike) styles.push('text-decoration:line-through');
      out += `<span style="${styles.join(';')}">`;
      openSpans++;
    };
    const close = () => {
      while (openSpans > 0) { out += '</span>'; openSpans--; }
    };
    const escape = (s: string) => s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');

    // First chunk has no preceding marker — emit it raw.
    out += escape(tokens[0]);

    for (let i = 1; i < tokens.length; i++) {
      const chunk = tokens[i];
      if (chunk.length === 0) continue;
      const code = chunk[0].toLowerCase();
      const rest = chunk.slice(1);

      if (LegacyTextPipe.COLOR[code]) {
        close();
        color = LegacyTextPipe.COLOR[code];
        // Colour codes reset style — that's the Minecraft convention.
        bold = italic = underline = strike = false;
      } else if (code === 'l') { close(); bold = true; }
      else if (code === 'o') { close(); italic = true; }
      else if (code === 'n') { close(); underline = true; }
      else if (code === 'm') { close(); strike = true; }
      else if (code === 'r') { close(); color = null; bold = italic = underline = strike = false; }
      else { /* unknown code — drop the marker, treat rest as literal */ }

      if (rest.length > 0) {
        if (openSpans === 0 && (color || bold || italic || underline || strike)) open();
        out += escape(rest);
      }
    }
    close();
    return out;
  }
}
