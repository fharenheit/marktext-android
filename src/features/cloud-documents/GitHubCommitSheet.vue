<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'
import { isValidBranchName } from './githubBranches'

const props = withDefaults(
  defineProps<{
    suggestedMessage: string
    /** Prefilled name for a commit to a new branch with a pull request. */
    suggestedBranch: string
    committing: boolean
    /** Images inserted on this device that the commit adds to the repository. */
    imageCount?: number
  }>(),
  { imageCount: 0 },
)

const emit = defineEmits<{
  confirm: [commit: { message: string; newBranch: string | null }]
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
const useNewBranch = ref(false)
const branch = ref(props.suggestedBranch)
const branchValid = computed(() => isValidBranchName(branch.value.trim()))
const canConfirm = computed(
  () =>
    !props.committing
    && message.value.trim().length > 0
    && (!useNewBranch.value || branchValid.value),
)

onMounted(() => {
  messageInput.value?.focus()
  messageInput.value?.select()
})

function confirm() {
  if (!canConfirm.value) {
    return
  }
  emit('confirm', {
    message: message.value.trim(),
    newBranch: useNewBranch.value ? branch.value.trim() : null,
  })
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
      <p v-if="!useNewBranch">{{ t('commit.body') }}</p>
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
      <p v-if="imageCount > 0" class="github-commit-note" data-testid="github-commit-images">
        {{ t('commit.imagesNote', { count: imageCount }) }}
      </p>
      <label class="github-commit-option">
        <input
          v-model="useNewBranch"
          type="checkbox"
          data-testid="github-commit-new-branch"
          :disabled="committing"
        >
        <span>{{ t('commit.newBranch') }}</span>
      </label>
      <template v-if="useNewBranch">
        <input
          v-model="branch"
          class="github-commit-input"
          type="text"
          autocapitalize="off"
          autocomplete="off"
          spellcheck="false"
          :aria-label="t('commit.branchName')"
          :aria-invalid="!branchValid"
          data-testid="github-commit-branch"
          :disabled="committing"
          @keydown.enter.prevent="confirm"
        >
        <p v-if="!branchValid" class="github-commit-note" role="alert">
          {{ t('commit.invalidBranch') }}
        </p>
      </template>
      <div class="draft-save-actions">
        <button
          class="primary-action"
          type="button"
          data-testid="github-commit-confirm"
          :disabled="!canConfirm"
          @click="confirm"
        >
          {{
            committing
              ? t('app.status.committing')
              : useNewBranch
                ? t('commit.confirmPullRequest')
                : t('commit.confirm')
          }}
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

.github-commit-option {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--text);
}

.github-commit-option input {
  width: 20px;
  height: 20px;
  margin: 0;
  accent-color: var(--accent);
}

.github-commit-note {
  margin: -4px 0 12px;
  font-size: 13px;
  color: var(--text-muted);
}
</style>
