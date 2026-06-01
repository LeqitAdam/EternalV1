import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormControl } from '@angular/forms';
import { Router } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { LegacyTextPipe } from '../../shared/legacy-text.pipe';

/** Whether {@code v} looks like a Minecraft UUID (with or without dashes). */
function isUuidish(v: string): boolean {
  const t = v.trim();
  return /^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$/.test(t);
}

type Suggestion = {
  uuid: string;
  name: string;
  lastDisplayName: string;
  lastGroupName: string;
  lastSeen: number;
};

/**
 * Suchformular mit mat-autocomplete: tippt der Mod 2+ Zeichen, ruft der
 * Server /players/search auf und liefert max 10 Vorschläge — gematcht
 * werden Name- UND UUID-Präfix. Die Vorschlagsliste rendert den
 * rang-coloured DisplayName + UUID-Snippet. Klick → navigiert zur Detail-
 * Seite mit dem Spielername als Slug.
 */
@Component({
  selector: 'et-players',
  standalone: true,
  imports: [
    CommonModule, FormsModule, ReactiveFormsModule,
    MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule,
    MatIconModule, MatAutocompleteModule, LegacyTextPipe
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Spieler-Suche</h1>
    <p class="text-ink-300 mb-6">Tippe Namen oder UUID — Auto-Complete ab 2 Zeichen.</p>

    <mat-card class="p-6">
      <form (submit)="goExact(); $event.preventDefault();" class="flex gap-3 items-start">
        <mat-form-field appearance="outline" class="flex-1">
          <mat-label>Spielername oder UUID</mat-label>
          <input matInput [formControl]="query" name="q" autocomplete="off"
                 [matAutocomplete]="auto" />
          <mat-autocomplete #auto="matAutocomplete"
                            (optionSelected)="pick($event.option.value)"
                            [displayWith]="displayWith">
            <mat-option *ngFor="let s of suggestions()" [value]="s">
              <div class="flex items-center gap-3 py-1">
                <img [src]="head(s.uuid)" class="w-8 h-8 rounded" alt="" />
                <div class="flex-1 min-w-0">
                  <div [innerHTML]="(s.lastDisplayName || s.name) | legacy"></div>
                  <div class="text-xs text-ink-400 font-mono truncate">{{ s.uuid }}</div>
                </div>
              </div>
            </mat-option>
          </mat-autocomplete>
        </mat-form-field>
        <button mat-flat-button color="primary" type="submit" class="!mt-1">
          <mat-icon>search</mat-icon> Öffnen
        </button>
      </form>
    </mat-card>
  `
})
export class PlayersComponent {
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);

  readonly query = new FormControl<string | Suggestion>('', { nonNullable: false });
  readonly suggestions = signal<Suggestion[]>([]);

  constructor() {
    this.query.valueChanges.pipe(
      debounceTime(200),
      distinctUntilChanged(),
      switchMap(v => {
        const term = typeof v === 'string' ? v : v?.name ?? '';
        if (!term || term.trim().length < 2) return of([] as Suggestion[]);
        return this.api.searchPlayers(term.trim());
      })
    ).subscribe(list => this.suggestions.set(list));
  }

  head(uuid: string) {
    return `https://mc-heads.net/avatar/${uuid.replace(/-/g, '')}/32`;
  }

  /** Used by mat-autocomplete to render the selected option in the field. */
  displayWith(v: Suggestion | string | null): string {
    if (!v) return '';
    return typeof v === 'string' ? v : v.name;
  }

  /** Suggestion picked via dropdown — go to that profile. */
  pick(s: Suggestion) {
    this.router.navigate(['/dashboard/players',s.name]);
  }

  /** Submit pressed without dropdown selection. Routes by raw input, with
   *  UUID-style strings routed through /players/{uuid} (the detail page
   *  resolves either form). */
  goExact() {
    const raw = this.query.value;
    const term = (typeof raw === 'string' ? raw : raw?.name ?? '').trim();
    if (!term) return;
    if (isUuidish(term)) {
      // Detail page expects a name slug; if it's a UUID, hit the search
      // API once to resolve, then navigate by the resulting name.
      this.api.searchPlayers(term).subscribe(list => {
        if (list.length > 0) this.router.navigate(['/dashboard/players',list[0].name]);
        else this.router.navigate(['/dashboard/players',term]);
      });
    } else {
      this.router.navigate(['/dashboard/players',term]);
    }
  }
}
