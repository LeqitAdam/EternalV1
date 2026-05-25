import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { StaffStat } from '../../core/models';

@Component({
  selector: 'et-dashboard',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <h1 class="text-3xl font-bold mb-2">Übersicht</h1>
    <p class="text-ink-300 mb-8">Willkommen zurück, {{ auth.me()?.name }}.</p>

    <div *ngIf="loading()" class="flex justify-center py-12">
      <mat-spinner />
    </div>

    <div *ngIf="!loading()" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
      <mat-card class="p-5">
        <div class="text-sm text-ink-300">Bans gesamt</div>
        <div class="text-3xl font-bold text-eternal-300 mt-1">{{ totalBans() }}</div>
      </mat-card>
      <mat-card class="p-5">
        <div class="text-sm text-ink-300">Mutes gesamt</div>
        <div class="text-3xl font-bold text-eternal-300 mt-1">{{ totalMutes() }}</div>
      </mat-card>
      <mat-card class="p-5">
        <div class="text-sm text-ink-300">Reports bearbeitet</div>
        <div class="text-3xl font-bold text-eternal-300 mt-1">{{ totalReports() }}</div>
      </mat-card>
      <mat-card class="p-5">
        <div class="text-sm text-ink-300">Mods aktiv</div>
        <div class="text-3xl font-bold text-eternal-300 mt-1">{{ stats().length }}</div>
      </mat-card>
    </div>

    <mat-card class="p-6" *ngIf="!loading()">
      <h2 class="text-xl font-semibold mb-4">Mod-Statistiken</h2>
      <div *ngIf="stats().length === 0" class="text-ink-300 text-sm">Noch keine Aktivitaeten.</div>
      <table *ngIf="stats().length > 0" class="w-full text-sm">
        <thead class="text-left text-ink-300 border-b border-ink-700">
          <tr>
            <th class="py-2">Name</th>
            <th class="py-2 text-right">Bans</th>
            <th class="py-2 text-right">Mutes</th>
            <th class="py-2 text-right">Reports</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let s of stats()" class="border-b border-ink-700/40">
            <td class="py-2 font-medium">{{ s.staffName }}</td>
            <td class="py-2 text-right tabular-nums">{{ s.banCount }}</td>
            <td class="py-2 text-right tabular-nums">{{ s.muteCount }}</td>
            <td class="py-2 text-right tabular-nums">{{ s.reportsHandled }}</td>
          </tr>
        </tbody>
      </table>
    </mat-card>
  `
})
export class DashboardComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);

  readonly stats = signal<StaffStat[]>([]);
  readonly loading = signal(true);

  constructor() {
    this.api.stats().subscribe({
      next: s => { this.stats.set(s); this.loading.set(false); },
      error: () => this.loading.set(false)
    });
  }

  totalBans() { return this.stats().reduce((a, s) => a + s.banCount, 0); }
  totalMutes() { return this.stats().reduce((a, s) => a + s.muteCount, 0); }
  totalReports() { return this.stats().reduce((a, s) => a + s.reportsHandled, 0); }
}
