<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../../lib/i18n'
import { useModalFocus } from '../../../lib/modalFocus'
import type { FileSwitcherEntry } from '../fileSwitcher'

// The open document's folder (working copy or GitHub branch), to jump to
// another file without leaving the editor.
defineProps<{
  /** Repository-relative folder shown ('' = the root). */
  folder: string
  rootLabel: string
  currentPath: string
  /** null while loading. */
  entries: FileSwitcherEntry[] | null
  error: string | null
}>()

const emit = defineEmits<{
  close: []
  up: []
  open: [entry: FileSwitcherEntry]
}>()

const { t } = useI18n()
const panel = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({
  root: panel,
  initialFocus: () => panel.value,
  onEscape: () => emit('close'),
  restoreFocus: false,
})
</script>

<template>
  <section
    class="editor-action-sheet"
    role="presentation"
    data-testid="file-switcher-scrim"
    @click="emit('close')"
  >
    <div
      ref="panel"
      class="editor-action-panel outline-panel"
      role="dialog"
      aria-modal="true"
      :aria-label="t('editor.files.title')"
      tabindex="-1"
      data-testid="file-switcher"
      @click.stop
      @keydown="onModalKeydown"
    >
      <header class="outline-header">
        <div class="editor-action-grabber" aria-hidden="true" />
        <div class="outline-header-row">
          <button
            v-if="folder"
            class="icon-button"
            type="button"
            :aria-label="t('editor.files.up')"
            data-testid="file-switcher-up"
            @click="emit('up')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M15 5l-7 7 7 7" />
            </svg>
          </button>
          <h2 class="editor-action-title outline-title file-switcher-title" data-testid="file-switcher-folder">
            {{ folder ? `${rootLabel}/${folder}` : rootLabel }}
          </h2>
          <button
            class="icon-button"
            type="button"
            :aria-label="t('editor.outline.close')"
            data-testid="file-switcher-close"
            @click="emit('close')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path d="M7 7l10 10M17 7L7 17" />
            </svg>
          </button>
        </div>
      </header>

      <p v-if="error" class="outline-empty" role="alert">{{ error }}</p>
      <p v-else-if="entries === null" class="outline-empty">{{ t('cloudBrowser.loading') }}</p>
      <p v-else-if="entries.length === 0" class="outline-empty">{{ t('cloudBrowser.empty') }}</p>
      <ul v-else class="outline-list" data-testid="file-switcher-list">
        <li v-for="entry in entries" :key="entry.path">
          <button
            class="outline-row file-switcher-row"
            :class="{ 'is-current': entry.path === currentPath }"
            type="button"
            :disabled="!entry.isFolder && !entry.openable"
            :aria-current="entry.path === currentPath ? 'page' : undefined"
            :data-testid="`file-switcher-entry-${entry.path}`"
            @click="emit('open', entry)"
          >
            <svg class="file-switcher-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
              <path v-if="entry.isFolder" d="M3.5 6.5a1.5 1.5 0 0 1 1.5-1.5h4.2l2 2.2H19a1.5 1.5 0 0 1 1.5 1.5v8.8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z" />
              <path v-else d="M7 3.5h7l4 4V20a.5.5 0 0 1-.5.5h-10A.5.5 0 0 1 7 20zM14 3.5V8h4" />
            </svg>
            <span class="outline-row-text" dir="auto">{{ entry.name }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.file-switcher-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-switcher-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.file-switcher-row:disabled {
  opacity: 0.45;
}

.file-switcher-row.is-current {
  color: var(--accent);
  font-weight: 600;
}

.file-switcher-icon {
  flex: none;
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linejoin: round;
}
</style>
