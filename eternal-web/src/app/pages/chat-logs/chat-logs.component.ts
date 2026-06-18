import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of, catchError } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { ChatLogEntry, ChatLogKind, ChatLogPage } from '../../core/models';
import { ChatTranscriptComponent } from '../../shared/chat-transcript/chat-transcript.component';

const PAGE_SIZE = 100;
const ALL_KINDS: ChatLogKind[] = ['CHAT', 'COMMAND', 'MSG'];

/**
 * Chat-log search + browse — two-pane layout mirroring admin-users. LEFT is a
 * filter panel (server select, debounced text search, kind toggles, optional
 * date range, sensitive-mode switch); RIGHT is a styled transcript of the
 * matching lines with "load more" pagination.
 *
 * The sensitive toggle is best-effort: we attempt the gated endpoint and, if
 * the server answers 403, we permanently hide the switch — non-privileged
 * staff never even see it after the first denial.
 */
@Component({
  selector: 'et-chat-logs',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonToggleModule,
    MatCheckboxModule, MatSlideToggleModule, MatProgressSpinnerModule,
    ChatTranscriptComponent
  ],
  template: `
    <h1 class="text-3xl font-bold mb-2">Chat-Logs</h1>
    <p class="text-ink-300 mb-6">
      Durchsuche den netzwerkweiten Chat-Verlauf — Chat, Befehle und private
      Nachrichten. Filter links, Treffer rechts.
    </p>

    <div class="grid lg:grid-cols-[340px_1fr] gap-5">
      <!-- ===== Filter panel ===== -->
      <mat-card class="p-4 h-fit space-y-4">
        <!-- Server -->
        <mat-form-field appearance="outline" class="w-full !mb-0">
          <mat-label>Server</mat-label>
          <mat-select [(ngModel)]="server" (ngModelChange)="resetAndLoad()">
            <mat-option [value]="''">Alle Server</mat-option>
            <mat-option *ngFor="let s of servers()" [value]="s">{{ s }}</mat-option>
          </mat-select>
        </mat-form-field>

        <!-- Text search -->
        <mat-form-field appearance="outline" class="w-full !mb-0">
          <mat-label>Suche</mat-label>
          <mat-icon matPrefix class="!mr-1 text-ink-400">search</mat-icon>
          <input matInput [(ngModel)]="query" (ngModelChange)="onSearch($event)"
                 placeholder="Text, Name …" />
        </mat-form-field>

        <!-- Kind toggles (hidden in sensitive mode — that store is commands only) -->
        <div *ngIf="!sensitive()">
          <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">Typ</div>
          <mat-button-toggle-group multiple [value]="kinds()" (change)="onKinds($event.value)"
                                   class="et-kind-toggle w-full" hideSingleSelectionIndicator>
            <mat-button-toggle value="CHAT">Chat</mat-button-toggle>
            <mat-button-toggle value="COMMAND">Befehle</mat-button-toggle>
            <mat-button-toggle value="MSG">Privat</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <!-- Date range -->
        <div>
          <div class="text-xs uppercase tracking-wide text-eternal-300 mb-2">Zeitraum</div>
          <mat-form-field appearance="outline" class="w-full !mb-2">
            <mat-label>Von</mat-label>
            <input matInput type="datetime-local" [(ngModel)]="fromStr" (ngModelChange)="resetAndLoad()" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="w-full !mb-0">
            <mat-label>Bis</mat-label>
            <input matInput type="datetime-local" [(ngModel)]="toStr" (ngModelChange)="resetAndLoad()" />
          </mat-form-field>
        </div>

        <!-- Sensitive mode (only shown while permitted) -->
        <div *ngIf="sensitiveAllowed()" class="pt-1 border-t border-ink-700/50">
          <mat-slide-toggle color="warn" [checked]="sensitive()" (change)="toggleSensitive($event.checked)">
            <span class="text-sm">Sensible Befehle</span>
          </mat-slide-toggle>
          <p class="text-xs text-ink-400 mt-1">
            Login/Register/Passwort — getrennt gespeichert, erfordert höhere Rechte.
          </p>
        </div>
      </mat-card>

      <!-- ===== Results ===== -->
      <mat-card class="p-5">
        <div class="flex items-center gap-3 mb-4">
          <h2 class="text-lg font-semibold">
            {{ sensitive() ? 'Sensible Befehle' : 'Verlauf' }}
          </h2>
          <span *ngIf="total() > 0" class="text-sm text-ink-300">
            {{ items().length }} von {{ total() }}
          </span>
          <span class="flex-1"></span>
          <button mat-icon-button (click)="resetAndLoad()" matTooltip="Aktualisieren" [disabled]="loading()">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>

        <div *ngIf="error()" class="p-3 mb-3 bg-red-900/30 border border-red-700/40 rounded text-red-300 text-sm">
          {{ error() }}
        </div>

        <div *ngIf="loading() && items().length === 0" class="flex justify-center py-12">
          <mat-spinner diameter="32" />
        </div>

        <div *ngIf="!loading() && items().length === 0 && !error()"
             class="text-center py-12 text-ink-300">
          <mat-icon class="!text-5xl !w-12 !h-12 mb-2 text-ink-500">forum</mat-icon>
          <div>Keine Treffer.</div>
        </div>

        <et-chat-transcript *ngIf="items().length > 0"
                            [messages]="items()" [showServer]="server() === ''" />

        <div *ngIf="hasMore()" class="flex justify-center pt-4">
          <button mat-stroked-button (click)="loadMore()" [disabled]="loading()">
            <mat-icon>expand_more</mat-icon>
            {{ loading() ? 'Lädt …' : 'Mehr laden' }}
          </button>
        </div>
      </mat-card>
    </div>
  `,
  styles: [`
    ::ng-deep .et-kind-toggle { display: flex; }
    ::ng-deep .et-kind-toggle .mat-button-toggle { flex: 1; background: #13131a; color: #9b9bb0; }
    ::ng-deep .et-kind-toggle .mat-button-toggle-button { font-size: .8rem; }
    ::ng-deep .et-kind-toggle .mat-button-toggle-checked { background: #8f0a6d; color: #fff; }
  `]
})
export class ChatLogsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly search$ = new Subject<string>();

  /* --- bound filter state (ngModel) --- */
  query = '';
  fromStr = '';
  toStr = '';

  /* --- signal state --- */
  readonly server = signal('');
  readonly kinds = signal<ChatLogKind[]>([...ALL_KINDS]);
  readonly sensitive = signal(false);
  /** Hidden once the sensitive endpoint 403s for this user. */
  readonly sensitiveAllowed = signal(true);

  readonly servers = signal<string[]>([]);
  readonly items = signal<ChatLogEntry[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  private offset = 0;

  readonly hasMore = computed(() => this.items().length < this.total());

  ngOnInit() {
    this.api.chatLogServers().subscribe({
      next: s => this.servers.set(s ?? []),
      error: () => this.servers.set([])
    });
    this.search$.pipe(
      debounceTime(300),
      distinctUntilChanged()
    ).subscribe(() => this.resetAndLoad());
    this.resetAndLoad();
  }

  onSearch(q: string) { this.search$.next(q); }

  onKinds(values: ChatLogKind[]) {
    this.kinds.set(values ?? []);
    this.resetAndLoad();
  }

  toggleSensitive(on: boolean) {
    this.sensitive.set(on);
    this.resetAndLoad();
  }

  /** Reload from page 0 with the current filters. */
  resetAndLoad() {
    this.offset = 0;
    this.items.set([]);
    this.total.set(0);
    this.load();
  }

  loadMore() {
    this.offset += PAGE_SIZE;
    this.load();
  }

  private load() {
    this.loading.set(true);
    this.error.set(null);
    const from = this.toEpoch(this.fromStr);
    const to = this.toEpoch(this.toStr);

    const req$ = this.sensitive()
      ? this.api.sensitiveChatLogs({ server: this.server(), q: this.query, from, to, limit: PAGE_SIZE, offset: this.offset }).pipe(
          catchError(err => {
            // 403 => not allowed to view sensitive logs. Hide the switch and
            // fall back to the normal store so the user isn't stranded.
            if (err?.status === 403) {
              this.sensitiveAllowed.set(false);
              this.sensitive.set(false);
              this.error.set('Keine Berechtigung für sensible Befehle.');
              return of<ChatLogPage>({ total: 0, items: [] });
            }
            throw err;
          })
        )
      : this.api.chatLogs({ server: this.server(), q: this.query, kinds: this.kindsCsv(), from, to, limit: PAGE_SIZE, offset: this.offset });

    req$.subscribe({
      next: page => {
        this.items.set(this.offset === 0 ? page.items : [...this.items(), ...page.items]);
        this.total.set(page.total);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(e.error?.error ?? e.message ?? 'Laden fehlgeschlagen.');
        this.loading.set(false);
      }
    });
  }

  /** CSV of the selected kinds, or empty (= all) when none/everything chosen. */
  private kindsCsv(): string {
    const k = this.kinds();
    if (k.length === 0 || k.length === ALL_KINDS.length) return '';
    return k.join(',');
  }

  /** datetime-local string -> epoch ms, 0 when blank/invalid. */
  private toEpoch(s: string): number {
    if (!s) return 0;
    const ms = new Date(s).getTime();
    return Number.isFinite(ms) ? ms : 0;
  }
}
