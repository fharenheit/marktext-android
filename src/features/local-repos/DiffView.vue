<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from '../../lib/i18n'
import { buildHunks, countChanges, diffLines } from '../../lib/lineDiff'

const props = withDefaults(
  defineProps<{
    /** null = the file did not exist on that side. */
    oldText: string | null
    newText: string | null
    binary?: boolean
    tooLarge?: boolean
  }>(),
  { binary: false, tooLarge: false },
)

const { t } = useI18n()

const lines = computed(() =>
  props.binary || props.tooLarge ? [] : diffLines(props.oldText, props.newText),
)
const hunks = computed(() => buildHunks(lines.value, 3))
const summary = computed(() => countChanges(lines.value))
</script>

<template>
  <div class="diff-view" data-testid="diff-view">
    <p v-if="binary" class="diff-note">{{ t('diff.binary') }}</p>
    <p v-else-if="tooLarge" class="diff-note">{{ t('diff.tooLarge') }}</p>
    <p v-else-if="hunks.length === 0" class="diff-note">{{ t('diff.noChanges') }}</p>
    <template v-else>
      <p class="diff-summary">
        <span class="diff-summary-added">+{{ summary.added }}</span>
        <span class="diff-summary-removed">−{{ summary.removed }}</span>
      </p>
      <div v-for="(hunk, hunkIndex) in hunks" :key="hunkIndex" class="diff-hunk">
        <div class="diff-hunk-header" aria-hidden="true">
          @@ −{{ hunk.oldStart }},{{ hunk.oldLines }} +{{ hunk.newStart }},{{ hunk.newLines }} @@
        </div>
        <div
          v-for="(line, lineIndex) in hunk.lines"
          :key="lineIndex"
          class="diff-line"
          :class="`diff-line-${line.kind}`"
        >
          <span class="diff-number" aria-hidden="true">{{ line.oldNumber ?? '' }}</span>
          <span class="diff-number" aria-hidden="true">{{ line.newNumber ?? '' }}</span>
          <span class="diff-sign" aria-hidden="true">{{
            line.kind === 'add' ? '+' : line.kind === 'remove' ? '−' : ' '
          }}</span>
          <span class="diff-text">
            <span class="diff-sr-only">{{
              line.kind === 'add' ? t('diff.addedLine') : line.kind === 'remove' ? t('diff.removedLine') : ''
            }}</span>{{ line.text || ' ' }}
          </span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.diff-view {
  font-family: var(--mt-mono-font);
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--text);
}

.diff-note {
  margin: 12px 4px;
  font-family: inherit;
  color: var(--text-muted);
}

.diff-summary {
  display: flex;
  gap: 12px;
  margin: 4px 4px 10px;
  font-weight: 600;
}

.diff-summary-added {
  color: #2da44e;
}

.diff-summary-removed {
  color: var(--danger);
}

.diff-hunk {
  margin-bottom: 12px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.diff-hunk-header {
  padding: 2px 8px;
  color: var(--text-muted);
  background: var(--surface-sunken);
}

.diff-line {
  display: grid;
  grid-template-columns: 2.6em 2.6em 1.2em minmax(0, 1fr);
}

.diff-line-add {
  background: rgba(45, 164, 78, 0.16);
}

.diff-line-remove {
  background: rgba(207, 34, 46, 0.14);
}

.diff-number {
  padding-right: 4px;
  color: var(--text-faint);
  text-align: right;
  user-select: none;
}

.diff-sign {
  text-align: center;
  user-select: none;
}

.diff-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.diff-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
