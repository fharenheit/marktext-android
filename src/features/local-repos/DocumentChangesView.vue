<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'
import DiffView from './DiffView.vue'

defineProps<{
  documentName: string
  /** What the diff compares against, e.g. "last commit". */
  baselineLabel: string
  oldText: string | null
  newText: string | null
  canRevert: boolean
}>()

const emit = defineEmits<{
  close: []
  revert: []
}>()

const { t } = useI18n()
const root = ref<HTMLElement | null>(null)
const { onModalKeydown } = useModalFocus({
  root,
  initialFocus: () => root.value,
  onEscape: () => emit('close'),
})
</script>

<template>
  <div
    ref="root"
    class="repository-overlay"
    role="dialog"
    aria-modal="true"
    aria-labelledby="document-changes-title"
    tabindex="-1"
    data-testid="document-changes"
    @keydown="onModalKeydown"
  >
    <header class="repository-header">
      <button type="button" class="repository-back" :aria-label="t('openLocations.back')" @click="emit('close')">
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <h1 id="document-changes-title" class="repository-title">
        {{ t('changes.title') }}
        <span class="repository-subtitle">{{ documentName }} · {{ baselineLabel }}</span>
      </h1>
      <button
        v-if="canRevert"
        type="button"
        class="repository-header-action"
        data-testid="document-changes-revert"
        @click="emit('revert')"
      >
        {{ t('editor.actions.revertChanges') }}
      </button>
    </header>
    <div class="repository-content">
      <DiffView :old-text="oldText" :new-text="newText" />
    </div>
  </div>
</template>

<style src="./repositoryScreens.css"></style>
