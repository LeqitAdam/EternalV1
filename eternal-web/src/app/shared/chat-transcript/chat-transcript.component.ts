import { Component, Input } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { ChatLogEntry, ChatLogKind } from '../../core/models';
import { LegacyTextPipe } from '../legacy-text.pipe';

/**
 * Reusable, on-brand chat transcript. Renders a list of {@link ChatLogEntry}
 * as styled bubbles — player head, &-coloured name/content (LegacyTextPipe),
 * a HH:mm:ss timestamp and a kind chip (CHAT / COMMAND / MSG). MSG bubbles
 * additionally show "sender → target".
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
    <div *ngIf="messages?.length; else empty" class="space-y-1.5">
      <div *ngFor="let m of messages"
           class="flex items-start gap-2.5 rounded-lg px-2.5 py-1.5 transition"
           [class.bg-ink-800]="!isHighlight(m)"
           [class.bg-eternal-900]="isHighlight(m)"
           [class.bg-opacity-40]="!isHighlight(m)"
           [class.ring-2]="isHighlight(m)"
           [class.ring-eternal-500]="isHighlight(m)"
           [class.ring-offset-1]="isHighlight(m)"
           [class.ring-offset-ink-900]="isHighlight(m)">
        <img [src]="head(m.senderUuid)" class="w-8 h-8 rounded shrink-0 mt-0.5" alt="" />
        <div class="flex-1 min-w-0">
          <!-- Header line: name → (target) · chip · server? · time -->
          <div class="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs leading-tight">
            <span class="font-medium text-ink-100" [innerHTML]="(m.senderName || '—') | legacy"></span>
            <ng-container *ngIf="m.kind === 'MSG' && (m.targetName || m.targetUuid)">
              <mat-icon class="!text-sm !w-4 !h-4 !leading-4 text-ink-400 align-middle">arrow_forward</mat-icon>
              <span class="font-medium text-ink-100" [innerHTML]="(m.targetName || '—') | legacy"></span>
            </ng-container>
            <span [class]="chipClass(m.kind)">{{ chipLabel(m.kind) }}</span>
            <span *ngIf="showServer && m.server" class="text-ink-400 font-mono">{{ m.server }}</span>
            <span class="flex-1"></span>
            <span class="text-ink-400 font-mono shrink-0">{{ m.createdAt | date:'HH:mm:ss' }}</span>
          </div>
          <!-- Body: the message / command text, &-coloured. break-words so
               long commands don't blow out the layout. -->
          <div class="text-sm text-ink-100 break-words mt-0.5"
               [class.font-mono]="m.kind === 'COMMAND'"
               [innerHTML]="m.content | legacy"></div>
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
    const base = 'text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded font-medium';
    if (kind === 'COMMAND') return `${base} bg-amber-900/40 text-amber-300`;
    if (kind === 'MSG') return `${base} bg-eternal-900/40 text-eternal-300`;
    return `${base} bg-ink-700 text-ink-300`;
  }
}
