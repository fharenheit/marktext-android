<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'

defineProps<{
  branch: string
  /** Null when the commit landed but GitHub did not open the pull request. */
  pullRequest: { number: number; url: string } | null
  error: string | null
}>()

const emit = defineEmits<{
  open: []
  close: []
}>()

const { t } = useI18n()
const modalRoot = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({ root: modalRoot, onEscape: () => emit('close') })
</script>

<template>
  <section
    ref="modalRoot"
    class="draft-save-sheet save-flow-modal"
    role="dialog"
    aria-modal="true"
    aria-labelledby="github-pull-request-title"
    tabindex="-1"
    data-testid="github-pull-request-sheet"
    @keydown="onModalKeydown"
  >
    <div class="draft-save-panel">
      <h2 id="github-pull-request-title">
        {{
          pullRequest
            ? t('commit.pullRequestTitle', { number: pullRequest.number })
            : t('commit.branchCommittedTitle')
        }}
      </h2>
      <p v-if="pullRequest">{{ t('commit.pullRequestBody', { branch }) }}</p>
      <p v-else role="alert">{{ t('commit.pullRequestFailed', { branch, error: error ?? '' }) }}</p>
      <div class="draft-save-actions">
        <button
          v-if="pullRequest"
          class="primary-action"
          type="button"
          data-testid="github-pull-request-open"
          @click="emit('open')"
        >
          {{ t('commit.openOnGitHub') }}
        </button>
        <button type="button" data-testid="github-pull-request-close" @click="emit('close')">
          {{ t('commit.close') }}
        </button>
      </div>
    </div>
  </section>
</template>
