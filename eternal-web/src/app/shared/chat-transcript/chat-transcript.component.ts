import { Component, Input } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { ChatLogEntry, ChatLogKind } from '../../core/models';
import { LegacyTextPipe } from '../legacy-text.pipe';

/**
 * Reusable, on-brand chat transcript. Renders a list of {@link ChatLogEntry}
 * as compact single-line rows — a leading dd.MM.yy HH:mm:ss timestamp, player
 * head, &-coloured name/content (LegacyTextPipe) and a kind chip (CHAT /
 * COMMAND / MSG), all on one wrapping line so the time sits right next to the
 * message instead of floating off to the right. MSG rows show "sender → target".
 *
 * Drop it anywhere a chat snippet is needed (chat-log search, report context,
 * per-player sessions). Pass {@code highlightAt} to ring the one message whose
 * createdAt matches (e.g. the reported line) in Eternal pink.
 */
@Component({
  selector: 'et-chat-transcript',
  standalone: true,
  imports: [CommonModule, DatePipe, MatIconModule, LegacyTextPipe],
  template: `
    <div *ngIf="messages?.length; else empty" class="space-y-px font-mono text-sm">
      <div *ngFor="let m of messages"
           class="flex items-baseline gap-2 rounded px-2 py-1 transition leading-snug"
           [class.bg-ink-800]="!isHighlight(m)"
           [class.bg-eternal-900]="isHighlight(m)"
           [class.bg-opacity-40]="!isHighlight(m)"
           [class.ring-2]="isHighlight(m)"
           [class.ring-eternal-500]="isHighlight(m)"
           [class.ring-offset-1]="isHighlight(m)"
           [class.ring-offset-ink-900]="isHighlight(m)">
        <!-- Leading timestamp: date + time, fixed-width, right next to the text -->
        <span class="text-ink-400 text-xs tabular-nums shrink-0 select-none">{{ m.createdAt | date:'dd.MM.yy HH:mm:ss' }}</span>
        <img [src]="head(m.senderUuid)" class="w-5 h-5 rounded shrink-0 self-center" alt="" />
        <!-- Everything else flows inline so it wraps under the name, not the time -->
        <div class="min-w-0 flex-1 break-words">
          <span class="font-semibold text-ink-100" [innerHTML]="(m.senderName || '—') | legacy"></span>
          <ng-container *ngIf="m.kind === 'MSG' && (m.targetName || m.targetUuid)">
            <mat-icon class="!text-sm !w-4 !h-4 !leading-4 text-ink-400 align-middle mx-0.5">arrow_forward</mat-icon>
            <span class="font-semibold text-ink-100" [innerHTML]="(m.targetName || '—') | legacy"></span>
          </ng-container>
          <span [class]="chipClass(m.kind)" class="mx-1 align-middle">{{ chipLabel(m.kind) }}</span>
          <span *ngIf="showServer && m.server" class="text-ink-500 text-xs mr-1">[{{ m.server }}]</span>
          <span class="text-ink-100"
                [class.text-amber-200]="m.kind === 'COMMAND'"
                [innerHTML]="m.content | legacy"></span>
        </div>
      </div>
    </div>

    <ng-template #empty>
      <div class="text-sm text-ink-300 italic py-3">Keine Nachrichten.</div>
    </ng-template>
  `
})
export class ChatTranscriptComponent {
  @Input() messages: ChatLogEntry[] = [];
  /** Epoch ms of the anchored/reported message to ring in Eternal pink. */
  @Input() highlightAt?: number | null;
  /** Show the originating server name in each bubble header. */
  @Input() showServer = false;

  head(uuid: string): string {
    return `https://mc-heads.net/avatar/${(uuid ?? '').replace(/-/g, '')}/32`;
  }

  isHighlight(m: ChatLogEntry): boolean {
    return this.highlightAt != null && m.createdAt === this.highlightAt;
  }

  chipLabel(kind: ChatLogKind): string {
    return kind === 'MSG' ? 'MSG' : kind === 'COMMAND' ? 'CMD' : 'CHAT';
  }

  chipClass(kind: ChatLogKind): string {
    const base = 'inline-block text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded font-medium';
    if (kind === 'COMMAND') return `${base} bg-amber-900/40 text-amber-300`;
    if (kind === 'MSG') return `${base} bg-eternal-900/40 text-eternal-300`;
    return `${base} bg-ink-700 text-ink-300`;
  }
}
