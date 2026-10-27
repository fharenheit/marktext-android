<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'

defineProps<{
  message: string
  confirmLabel: string
  /** Destructive actions use the danger styling. */
  danger?: boolean
}>()

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

const { t } = useI18n()
const modalRoot = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({ root: modalRoot, onEscape: () => emit('cancel') })
</script>

<template>
  <section
    ref="modalRoot"
    class="draft-save-sheet save-flow-modal"
    role="alertdialog"
    aria-modal="true"
    aria-labelledby="repository-confirm-message"
    tabindex="-1"
    data-testid="repository-confirm-sheet"
    @keydown="onModalKeydown"
  >
    <div class="draft-save-panel">
      <p id="repository-confirm-message">{{ message }}</p>
      <div class="draft-save-actions">
        <button
          :class="danger ? 'danger-action' : 'primary-action'"
          type="button"
          data-testid="repository-confirm"
          @click="emit('confirm')"
        >
          {{ confirmLabel }}
        </button>
        <button type="button" data-testid="repository-confirm-cancel" @click="emit('cancel')">
          {{ t('saveAs.cancel') }}
        </button>
      </div>
    </div>
  </section>
</template>
