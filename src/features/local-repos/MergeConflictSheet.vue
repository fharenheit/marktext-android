<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'
import type { MergeConflictStrategy } from '../../lib/threeWayMerge'

// Asked when pulling the latest version clashes with local edits in the same
// places. Everything that does not clash is merged whatever the choice; the
// choice only decides those places. Not dismissable: by now the latest
// version is in and the edits wait on this answer.
defineProps<{
  conflicts: number
}>()

const emit = defineEmits<{
  choose: [strategy: MergeConflictStrategy]
}>()

const { t } = useI18n()
const modalRoot = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({ root: modalRoot })
</script>

<template>
  <section
    ref="modalRoot"
    class="draft-save-sheet save-flow-modal"
    role="alertdialog"
    aria-modal="true"
    aria-labelledby="merge-conflict-title"
    aria-describedby="merge-conflict-message"
    tabindex="-1"
    data-testid="merge-conflict-sheet"
    @keydown="onModalKeydown"
  >
    <div class="draft-save-panel">
      <h2 id="merge-conflict-title">{{ t('pullMerge.conflictTitle') }}</h2>
      <p id="merge-conflict-message">{{ t('pullMerge.conflictMessage', { count: conflicts }) }}</p>
      <div class="draft-save-actions">
        <button class="primary-action" type="button" data-testid="merge-conflict-markers" @click="emit('choose', 'markers')">
          {{ t('pullMerge.markConflicts') }}
        </button>
        <button type="button" data-testid="merge-conflict-ours" @click="emit('choose', 'ours')">
          {{ t('pullMerge.keepMine') }}
        </button>
        <button type="button" data-testid="merge-conflict-theirs" @click="emit('choose', 'theirs')">
          {{ t('pullMerge.takeLatest') }}
        </button>
      </div>
    </div>
  </section>
</template>
