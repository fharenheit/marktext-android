<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import { useModalFocus } from '../../lib/modalFocus'
import type { RepositoryChange } from '../../lib/localRepositories'

// Push in one step: the uncommitted changes are listed with a commit
// message box; confirming commits them and pushes. With nothing left to
// commit it only pushes the local commits waiting for the remote, or
// publishes a branch the remote does not have yet.
const props = defineProps<{
  changes: RepositoryChange[]
  /** Local commits not on the remote yet. */
  ahead: number
  /** False for a branch not on the remote yet (ahead then reads 0). */
  hasUpstream: boolean
  branch: string
  busy: boolean
}>()

const emit = defineEmits<{
  push: [message: string | null]
  cancel: []
}>()

const { t } = useI18n()
const modalRoot = ref<HTMLElement | null>(null)
const messageInput = ref<HTMLTextAreaElement | null>(null)
const { onModalKeydown } = useModalFocus({
  root: modalRoot,
  onEscape: () => {
    if (!props.busy) {
      emit('cancel')
    }
  },
})

// The mode is fixed when the sheet opens: the commit inside "Commit & push"
// empties the changes before the push runs, and the sheet must not turn
// into a plain "Push" sheet mid-operation.
const hasChanges = props.changes.length > 0
const listedChanges = [...props.changes]
const publishing = computed(() => !props.hasUpstream)
const message = ref(
  props.changes.length === 1
    ? t('push.defaultMessageOne', { path: props.changes[0].path.split('/').pop() ?? props.changes[0].path })
    : props.changes.length > 1
      ? t('push.defaultMessageMany', { count: props.changes.length })
      : '',
)
const canPush = computed(() =>
  !props.busy && (hasChanges ? message.value.trim().length > 0 : props.ahead > 0 || publishing.value),
)

function confirm() {
  if (canPush.value) {
    emit('push', hasChanges ? message.value.trim() : null)
  }
}

onMounted(() => {
  void nextTick(() => {
    messageInput.value?.focus()
    messageInput.value?.select()
  })
})
</script>

<template>
  <section
    ref="modalRoot"
    class="draft-save-sheet save-flow-modal"
    role="dialog"
    aria-modal="true"
    aria-labelledby="push-sheet-title"
    tabindex="-1"
    data-testid="push-sheet"
    @keydown="onModalKeydown"
  >
    <div class="draft-save-panel push-sheet-panel">
      <h2 id="push-sheet-title">{{ t('push.title', { branch }) }}</h2>

      <template v-if="hasChanges">
        <p class="push-sheet-hint">{{ t('push.changesHint', { count: listedChanges.length }) }}</p>
        <ul class="push-sheet-files" data-testid="push-sheet-files">
          <li v-for="change in listedChanges" :key="change.path">
            <span class="repository-badge" :class="`repository-badge-${change.kind}`">{{ t(`repo.kind.${change.kind}`) }}</span>
            <span class="push-sheet-path">{{ change.path }}</span>
          </li>
        </ul>
        <label class="push-sheet-label" for="push-sheet-message">{{ t('repo.commitMessage') }}</label>
        <textarea
          id="push-sheet-message"
          ref="messageInput"
          v-model="message"
          class="repository-input push-sheet-message"
          rows="3"
          :disabled="busy"
          data-testid="push-sheet-message"
          @keydown.ctrl.enter.prevent="confirm"
          @keydown.meta.enter.prevent="confirm"
        />
      </template>
      <p v-if="publishing" class="push-sheet-hint" data-testid="push-sheet-publish">{{ t('push.publishHint', { branch }) }}</p>
      <p v-else-if="!hasChanges && ahead > 0" class="push-sheet-hint">{{ t('push.aheadOnly', { count: ahead }) }}</p>
      <p v-else-if="!hasChanges" class="push-sheet-hint">{{ t('push.nothing') }}</p>

      <div class="draft-save-actions">
        <button class="primary-action" type="button" :disabled="!canPush" data-testid="push-sheet-confirm" @click="confirm">
          {{
            busy
              ? t('repo.pushing')
              : hasChanges
                ? t('push.commitAndPush')
                : publishing
                  ? t('push.publish')
                  : t('repo.push')
          }}
        </button>
        <button type="button" :disabled="busy" data-testid="push-sheet-cancel" @click="emit('cancel')">
          {{ t('saveAs.cancel') }}
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.push-sheet-panel h2 {
  margin: 0 0 8px;
  font-size: 18px;
}

.push-sheet-hint {
  margin: 0 0 8px;
  color: var(--text-muted);
}

.push-sheet-files {
  max-height: 32vh;
  margin: 0 0 12px;
  padding: 0;
  overflow-y: auto;
  list-style: none;
  border: var(--hairline) solid var(--separator);
  border-radius: var(--radius-sm);
}

.push-sheet-files li {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border-bottom: var(--hairline) solid var(--separator);
}

.push-sheet-files li:last-child {
  border-bottom: none;
}

.push-sheet-path {
  min-width: 0;
  overflow-wrap: anywhere;
  font-family: var(--mt-mono-font);
  font-size: 13px;
}

.push-sheet-label {
  display: block;
  margin-bottom: 4px;
  color: var(--text-muted);
  font-size: 13px;
}

.push-sheet-message {
  resize: vertical;
  font: inherit;
}
</style>
