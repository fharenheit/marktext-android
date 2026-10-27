<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'

const props = defineProps<{
  suggestedMessage: string
  committing: boolean
}>()

const emit = defineEmits<{
  confirm: [message: string]
  cancel: []
}>()

const { t } = useI18n()
const modalRoot = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({
  root: modalRoot,
  onEscape: () => {
    if (!props.committing) {
      emit('cancel')
    }
  },
})
const message = ref(props.suggestedMessage)
const messageInput = ref<HTMLInputElement | null>(null)

onMounted(() => {
  messageInput.value?.focus()
  messageInput.value?.select()
})

function confirm() {
  const trimmed = message.value.trim()
  if (trimmed.length === 0 || props.committing) {
    return
  }
  emit('confirm', trimmed)
}
</script>

<template>
  <section
    ref="modalRoot"
    class="draft-save-sheet save-flow-modal"
    role="dialog"
    aria-modal="true"
    aria-labelledby="github-commit-title"
    tabindex="-1"
    data-testid="github-commit-sheet"
    @keydown="onModalKeydown"
  >
    <div class="draft-save-panel">
      <h2 id="github-commit-title">{{ t('commit.title') }}</h2>
      <p>{{ t('commit.body') }}</p>
      <input
        ref="messageInput"
        v-model="message"
        class="github-commit-input"
        type="text"
        enterkeyhint="send"
        aria-labelledby="github-commit-title"
        data-testid="github-commit-message"
        :disabled="committing"
        @keydown.enter.prevent="confirm"
      >
      <div class="draft-save-actions">
        <button
          class="primary-action"
          type="button"
          data-testid="github-commit-confirm"
          :disabled="committing || message.trim().length === 0"
          @click="confirm"
        >
          {{ committing ? t('app.status.committing') : t('commit.confirm') }}
        </button>
        <button
          type="button"
          data-testid="github-commit-cancel"
          :disabled="committing"
          @click="emit('cancel')"
        >
          {{ t('saveAs.cancel') }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.github-commit-input {
  width: 100%;
  box-sizing: border-box;
  margin: 4px 0 12px;
  padding: 10px 12px;
  font-size: 15px;
  color: var(--text);
  background: var(--surface, transparent);
  border: 1px solid var(--border, currentColor);
  border-radius: var(--radius-sm);
}
</style>
