<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { useI18n } from '../../lib/i18n'
import {
  addRepositoryProgressListener,
  callRepository,
  getRepositoryUserMessage,
  type LocalRepositorySummary,
  type RepositoryBranch,
  type RepositoryChange,
  type RepositoryCommit,
  type RepositoryEntry,
  type RepositoryFileDiff,
  type RepositoryOutcome,
  type RepositoryProgressEvent,
  type RepositoryStatus,
} from '../../lib/localRepositories'
import { isValidBranchName } from '../cloud-documents/githubBranches'
import ConfirmSheet from './ConfirmSheet.vue'
import PushSheet from './PushSheet.vue'
import DiffView from './DiffView.vue'
import { diffFilePath, formatCommitDate, formatProgress } from './repositoryFormatting'

type Tab = 'files' | 'changes' | 'branches' | 'history'

const props = defineProps<{
  repository: LocalRepositorySummary
}>()

const emit = defineEmits<{
  back: []
  openDocument: [path: string]
}>()

const { t, locale } = useI18n()
const id = computed(() => props.repository.id)

const tab = ref<Tab>('files')
const status = ref<RepositoryStatus | null>(null)
const busy = ref<string | null>(null)
const notice = ref<string | null>(null)
const progress = ref<RepositoryProgressEvent | null>(null)

const folder = ref<string[]>([])
const entries = ref<RepositoryEntry[]>([])
const commitMessage = ref('')
const branches = ref<RepositoryBranch[]>([])
const newBranchName = ref('')
const commits = ref<RepositoryCommit[]>([])
const historyComplete = ref(false)

/** Full-screen views stacked over the tabs; Back closes them first. */
const fileDiff = ref<{ title: string; diff: RepositoryFileDiff } | null>(null)
const commitDetail = ref<{ commit: RepositoryCommit; files: RepositoryFileDiff[] } | null>(null)
const confirmation = ref<{ message: string; label: string; danger: boolean; action: () => Promise<void> } | null>(null)

let progressListener: PluginListenerHandle | null = null
const HISTORY_PAGE = 50

const changes = computed(() => status.value?.changes ?? [])
const conflicting = computed(() => changes.value.filter(change => change.kind === 'conflicting'))
const resolvedChanges = computed(() => changes.value.filter(change => change.kind !== 'conflicting'))
const mergeInProgress = computed(() => {
  const state = status.value?.state ?? 'SAFE'
  return state.startsWith('MERGING') || state.startsWith('REVERTING')
})
const isReverting = computed(() => (status.value?.state ?? '').startsWith('REVERTING'))

/** Runs one repository operation with the busy lock and error notice. */
async function operate<T>(name: string, operation: () => Promise<T>): Promise<T | null> {
  if (busy.value !== null) {
    return null
  }
  busy.value = name
  notice.value = null
  progress.value = null
  try {
    return await operation()
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
    return null
  } finally {
    busy.value = null
    progress.value = null
  }
}

async function refreshStatus() {
  try {
    status.value = await callRepository(plugin => plugin.getRepositoryStatus({ id: id.value }))
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  }
}

async function loadFolder() {
  try {
    entries.value = (
      await callRepository(plugin => plugin.listRepositoryFolder({ id: id.value, path: folder.value.join('/') }))
    ).entries
  } catch (error) {
    entries.value = []
    notice.value = getRepositoryUserMessage(error)
  }
}

async function loadBranches() {
  try {
    branches.value = (await callRepository(plugin => plugin.listRepositoryBranches({ id: id.value }))).branches
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  }
}

async function loadHistory(reset: boolean) {
  try {
    const page = (
      await callRepository(plugin =>
        plugin.getRepositoryHistory({
          id: id.value,
          maxCount: HISTORY_PAGE,
          skip: reset ? 0 : commits.value.length,
        }),
      )
    ).commits
    commits.value = reset ? page : [...commits.value, ...page]
    historyComplete.value = page.length < HISTORY_PAGE
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  }
}

async function selectTab(next: Tab) {
  tab.value = next
  if (next === 'files') {
    await loadFolder()
  } else if (next === 'changes') {
    await refreshStatus()
  } else if (next === 'branches') {
    await loadBranches()
  } else {
    await loadHistory(true)
  }
}

/** After anything that can change the tree: status plus the visible tab. */
async function refreshAll() {
  await refreshStatus()
  if (tab.value === 'files') {
    await loadFolder()
  } else if (tab.value === 'branches') {
    await loadBranches()
  } else if (tab.value === 'history') {
    await loadHistory(true)
  }
}

function openEntry(entry: RepositoryEntry) {
  if (entry.isFolder) {
    folder.value = [...folder.value, entry.name]
    void loadFolder()
    return
  }
  emit('openDocument', entry.path)
}

function folderUp() {
  folder.value = folder.value.slice(0, -1)
  void loadFolder()
}

// --- remote ------------------------------------------------------------

function describeOutcome(outcome: RepositoryOutcome) {
  if (outcome.result === 'conflicts') {
    return t('repo.conflictsFound', { count: outcome.conflicts.length })
  }
  return outcome.result === 'up-to-date' ? t('repo.upToDate') : t('repo.pulled')
}

async function pull() {
  const outcome = await operate('pull', () => callRepository(plugin => plugin.pullRepository({ id: id.value })))
  if (outcome) {
    status.value = outcome.status
    notice.value = describeOutcome(outcome)
    if (outcome.result === 'conflicts') {
      tab.value = 'changes'
    }
    await refreshAll()
  }
}

const pushSheetOpen = ref(false)

/** Push opens the sheet with what is going out: changes to commit and/or local commits. */
async function openPushSheet() {
  if (busy.value !== null) {
    return
  }
  await refreshStatus()
  pushSheetOpen.value = true
}

/**
 * Commit (when there are changes) and push as one step. A push that fails
 * after the commit keeps the commit locally; the header shows it waiting.
 */
async function commitAndPush(message: string | null) {
  if (message !== null) {
    const created = await operate('push', () =>
      callRepository(plugin => plugin.commitRepository({ id: id.value, message })),
    )
    if (!created) {
      return
    }
    commitMessage.value = ''
    await refreshStatus()
  }
  const pushed = await operate('push', () => callRepository(plugin => plugin.pushRepository({ id: id.value })))
  pushSheetOpen.value = false
  if (pushed) {
    status.value = pushed
    notice.value = message !== null ? t('push.committedAndPushed') : t('repo.pushed')
  } else if (message !== null) {
    const pushError = notice.value
    notice.value = `${t('push.committedNotPushed')} ${pushError ?? ''}`.trim()
  }
  await refreshAll()
}

// --- changes -------------------------------------------------------------

async function showChange(change: RepositoryChange) {
  const diff = await operate('diff', () =>
    callRepository(plugin => plugin.getRepositoryFileDiff({ id: id.value, path: change.path })),
  )
  if (diff) {
    fileDiff.value = { title: change.path, diff }
  }
}

async function commit() {
  const message = commitMessage.value.trim()
  if (!message) {
    return
  }
  const created = await operate('commit', () =>
    callRepository(plugin => plugin.commitRepository({ id: id.value, message })),
  )
  if (created) {
    commitMessage.value = ''
    notice.value = t('repo.committed', { sha: created.shortSha })
    await refreshAll()
  }
}

function confirmRevert(paths: string[]) {
  confirmation.value = {
    message: paths.length === 1
      ? t('repo.revertConfirm', { path: paths[0] })
      : t('repo.revertAllConfirm'),
    label: paths.length === 1 ? t('repo.revertFile') : t('repo.revertAll'),
    danger: true,
    action: async () => {
      const result = await operate('revert', () =>
        callRepository(plugin => plugin.discardRepositoryChanges({ id: id.value, paths })),
      )
      if (result) {
        status.value = result
        await refreshAll()
      }
    },
  }
}

async function resolve(path: string, choice: 'ours' | 'theirs' | 'edited') {
  const result = await operate('resolve', () =>
    callRepository(plugin => plugin.resolveRepositoryConflict({ id: id.value, path, choice })),
  )
  if (result) {
    status.value = result
  }
}

async function completeMerge() {
  const created = await operate('merge', () =>
    callRepository(plugin => plugin.completeRepositoryMerge({ id: id.value })),
  )
  if (created) {
    notice.value = t('repo.committed', { sha: created.shortSha })
    await refreshAll()
  }
}

function confirmAbortMerge() {
  confirmation.value = {
    message: t('repo.abortConfirm'),
    label: t('repo.abortMerge'),
    danger: true,
    action: async () => {
      const result = await operate('abort', () =>
        callRepository(plugin => plugin.abortRepositoryMerge({ id: id.value })),
      )
      if (result) {
        status.value = result
        await refreshAll()
      }
    },
  }
}

// --- branches ------------------------------------------------------------

function confirmSwitch(branch: RepositoryBranch) {
  if (branch.current) {
    return
  }
  confirmation.value = {
    message: t('repo.switchConfirm', { name: branch.name }),
    label: t('repo.switch'),
    danger: false,
    action: async () => {
      const result = await operate('checkout', () =>
        callRepository(plugin => plugin.checkoutRepositoryBranch({ id: id.value, name: branch.name })),
      )
      if (result) {
        status.value = result
        folder.value = []
        await loadBranches()
      }
    },
  }
}

const newBranchValid = computed(() => isValidBranchName(newBranchName.value.trim()))

async function createBranch() {
  const name = newBranchName.value.trim()
  if (!isValidBranchName(name)) {
    return
  }
  const result = await operate('branch', () =>
    callRepository(plugin => plugin.checkoutRepositoryBranch({ id: id.value, name, create: true })),
  )
  if (result) {
    status.value = result
    newBranchName.value = ''
    await loadBranches()
  }
}

// --- history -------------------------------------------------------------

async function showCommit(commit: RepositoryCommit) {
  const result = await operate('diff', () =>
    callRepository(plugin => plugin.getRepositoryCommitDiff({ id: id.value, sha: commit.sha })),
  )
  if (result) {
    commitDetail.value = { commit, files: result.files }
  }
}

function confirmRevertCommit(commit: RepositoryCommit) {
  confirmation.value = {
    message: t('repo.revertCommitConfirm', { sha: commit.shortSha }),
    label: t('repo.revertCommit'),
    danger: false,
    action: async () => {
      const outcome = await operate('revert-commit', () =>
        callRepository(plugin => plugin.revertRepositoryCommit({ id: id.value, sha: commit.sha })),
      )
      if (outcome) {
        commitDetail.value = null
        status.value = outcome.status
        if (outcome.result === 'conflicts') {
          notice.value = t('repo.conflictsFound', { count: outcome.conflicts.length })
          tab.value = 'changes'
        } else {
          notice.value = t('repo.reverted', { sha: commit.shortSha })
          await loadHistory(true)
        }
      }
    },
  }
}

async function runConfirmation() {
  const pending = confirmation.value
  confirmation.value = null
  await pending?.action()
}

/** Back unwinds overlays, then folders; false hands Back to the app. */
function handleBack() {
  if (confirmation.value) {
    confirmation.value = null
    return true
  }
  if (fileDiff.value) {
    fileDiff.value = null
    return true
  }
  if (commitDetail.value) {
    commitDetail.value = null
    return true
  }
  if (tab.value === 'files' && folder.value.length > 0) {
    folderUp()
    return true
  }
  return false
}

function showNotice(message: string) {
  notice.value = message
}

defineExpose({ handleBack, showNotice, refreshAll })

function changeLabel(change: RepositoryChange) {
  return t(`repo.kind.${change.kind}`)
}

onMounted(async () => {
  progressListener = await addRepositoryProgressListener(event => {
    if (event.id === id.value && busy.value !== null) {
      progress.value = event
    }
  })
  await refreshStatus()
  if (mergeInProgress.value) {
    tab.value = 'changes'
  } else {
    await loadFolder()
  }
})

onBeforeUnmount(() => {
  void progressListener?.remove()
})
</script>

<template>
  <div class="repository-screen" data-testid="repository-screen">
    <header class="repository-header">
      <button type="button" class="repository-back" :aria-label="t('openLocations.back')" @click="handleBack() || emit('back')">
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <h1 id="repository-title" class="repository-title">
        {{ repository.name }}
        <span class="repository-subtitle">
          {{ status?.branch ?? repository.branch }}
          <template v-if="status && status.ahead > 0"> · {{ t('repos.ahead', { count: status.ahead }) }}</template>
          <template v-if="status && status.behind > 0"> · {{ t('repos.behind', { count: status.behind }) }}</template>
        </span>
      </h1>
      <button
        type="button"
        class="repository-header-action"
        data-testid="repository-pull"
        :disabled="busy !== null || mergeInProgress"
        @click="pull"
      >
        {{ busy === 'pull' ? t('repo.pulling') : t('repo.pull') }}
      </button>
      <button
        type="button"
        class="repository-header-action"
        data-testid="repository-push"
        :disabled="busy !== null || mergeInProgress"
        @click="openPushSheet"
      >
        {{ busy === 'push' ? t('repo.pushing') : t('repo.push') }}
      </button>
    </header>

    <div class="repository-tabs" role="tablist" :aria-label="repository.name">
      <button
        v-for="name in (['files', 'changes', 'branches', 'history'] as Tab[])"
        :key="name"
        type="button"
        role="tab"
        class="repository-tab"
        :aria-selected="tab === name"
        :data-testid="`repository-tab-${name}`"
        @click="selectTab(name)"
      >
        {{ t(`repo.${name}`) }}<template v-if="name === 'changes' && changes.length > 0"> ({{ changes.length }})</template>
      </button>
    </div>

    <div class="repository-content">
      <p v-if="busy && progress" class="repository-hint" aria-live="polite">{{ formatProgress(progress) }}</p>
      <p v-if="notice" class="repository-notice" role="status" data-testid="repository-notice">{{ notice }}</p>

      <!-- Files -->
      <template v-if="tab === 'files'">
        <p v-if="folder.length > 0" class="repository-breadcrumb">/{{ folder.join('/') }}</p>
        <ul class="repository-list">
          <li v-if="folder.length > 0" class="repository-list-item">
            <button type="button" class="repository-entry" @click="folderUp">
              <span class="repository-entry-icon" aria-hidden="true">↑</span>
              <span class="repository-entry-name">..</span>
            </button>
          </li>
          <li v-for="entry in entries" :key="entry.path" class="repository-list-item">
            <button type="button" class="repository-entry" :data-testid="`repository-entry-${entry.path}`" @click="openEntry(entry)">
              <span class="repository-entry-icon" aria-hidden="true">
                <svg v-if="entry.isFolder" viewBox="0 0 24 24" width="22" height="22">
                  <path d="M3.5 7.5v-2a1 1 0 0 1 1-1h4l2 2h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-11Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="22" height="22">
                  <path d="M6 2.5h8l4 4V20a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 20V4A1.5 1.5 0 0 1 6.5 2.5Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                </svg>
              </span>
              <span class="repository-entry-name">{{ entry.name }}</span>
            </button>
          </li>
        </ul>
        <p v-if="entries.length === 0" class="repository-hint">{{ t('cloudBrowser.empty') }}</p>
      </template>

      <!-- Changes -->
      <template v-else-if="tab === 'changes'">
        <section v-if="mergeInProgress" class="repository-banner" data-testid="repository-merge-banner">
          <h2 class="repository-section-title">{{ isReverting ? t('repo.revertTitle') : t('repo.mergeTitle') }}</h2>
          <p>{{ conflicting.length > 0 ? t('repo.mergeHint') : t('repo.mergeReady') }}</p>
          <div v-for="change in conflicting" :key="change.path" class="repository-conflict">
            <span class="repository-conflict-path">{{ change.path }}</span>
            <div class="repository-row-actions">
              <button type="button" class="repository-secondary repository-small" :disabled="busy !== null" @click="resolve(change.path, 'ours')">
                {{ t('repo.keepMine') }}
              </button>
              <button type="button" class="repository-secondary repository-small" :disabled="busy !== null" @click="resolve(change.path, 'theirs')">
                {{ t('repo.keepTheirs') }}
              </button>
              <button type="button" class="repository-secondary repository-small" @click="emit('openDocument', change.path)">
                {{ t('repo.open') }}
              </button>
              <button type="button" class="repository-secondary repository-small" :disabled="busy !== null" @click="resolve(change.path, 'edited')">
                {{ t('repo.markResolved') }}
              </button>
            </div>
          </div>
          <div class="repository-row-actions">
            <button
              type="button"
              class="repository-primary"
              data-testid="repository-complete-merge"
              :disabled="busy !== null || conflicting.length > 0"
              @click="completeMerge"
            >
              {{ t('repo.completeMerge') }}
            </button>
            <button type="button" class="repository-danger" :disabled="busy !== null" @click="confirmAbortMerge">
              {{ t('repo.abortMerge') }}
            </button>
          </div>
        </section>

        <p v-if="changes.length === 0" class="repository-hint">{{ t('repo.noChanges') }}</p>
        <ul class="repository-list">
          <li v-for="change in (mergeInProgress ? resolvedChanges : changes)" :key="change.path" class="repository-list-item">
            <button type="button" class="repository-entry" :data-testid="`repository-change-${change.path}`" @click="showChange(change)">
              <span class="repository-badge" :class="`repository-badge-${change.kind}`">{{ changeLabel(change) }}</span>
              <span class="repository-entry-name">{{ change.path }}</span>
            </button>
            <button
              v-if="!mergeInProgress"
              type="button"
              class="repository-icon-button"
              :aria-label="t('repo.revertFile')"
              :disabled="busy !== null"
              @click="confirmRevert([change.path])"
            >
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                <path d="M9 14 4 9l5-5M4 9h10.5a5.5 5.5 0 0 1 0 11H11" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </li>
        </ul>

        <section v-if="!mergeInProgress && changes.length > 0" class="repository-card">
          <input
            v-model="commitMessage"
            class="repository-input"
            type="text"
            enterkeyhint="send"
            :placeholder="t('repo.commitMessage')"
            :aria-label="t('repo.commitMessage')"
            data-testid="repository-commit-message"
            @keydown.enter.prevent="commit"
          >
          <div class="repository-row-actions">
            <button
              type="button"
              class="repository-primary"
              data-testid="repository-commit"
              :disabled="busy !== null || commitMessage.trim().length === 0"
              @click="commit"
            >
              {{ t('repo.commit') }}
            </button>
            <button type="button" class="repository-danger" :disabled="busy !== null" @click="confirmRevert([])">
              {{ t('repo.revertAll') }}
            </button>
          </div>
        </section>
      </template>

      <!-- Branches -->
      <template v-else-if="tab === 'branches'">
        <ul class="repository-list">
          <li v-for="branch in branches" :key="branch.name" class="repository-list-item">
            <button
              type="button"
              class="repository-entry"
              :aria-current="branch.current ? 'true' : undefined"
              :disabled="busy !== null"
              :data-testid="`repository-branch-${branch.name}`"
              @click="confirmSwitch(branch)"
            >
              <span class="repository-entry-text">
                <span class="repository-entry-name">{{ branch.name }}</span>
                <span class="repository-entry-meta">
                  <template v-if="branch.current">{{ t('repo.currentBranch') }}</template>
                  <template v-else-if="!branch.local">{{ t('repo.remoteOnly') }}</template>
                </span>
              </span>
            </button>
          </li>
        </ul>
        <section class="repository-card">
          <h2 class="repository-section-title">{{ t('repo.newBranch') }}</h2>
          <input
            v-model="newBranchName"
            class="repository-input"
            type="text"
            autocapitalize="off"
            spellcheck="false"
            :aria-label="t('commit.branchName')"
            :placeholder="t('commit.branchName')"
            :aria-invalid="newBranchName.length > 0 && !newBranchValid"
          >
          <p v-if="newBranchName.length > 0 && !newBranchValid" class="repository-hint" role="alert">
            {{ t('commit.invalidBranch') }}
          </p>
          <button
            type="button"
            class="repository-primary"
            :disabled="busy !== null || !newBranchValid"
            @click="createBranch"
          >
            {{ t('repo.createBranch') }}
          </button>
        </section>
      </template>

      <!-- History -->
      <template v-else>
        <p v-if="commits.length === 0" class="repository-hint">{{ t('repo.noHistory') }}</p>
        <ul class="repository-list">
          <li v-for="item in commits" :key="item.sha" class="repository-list-item">
            <button type="button" class="repository-entry" :data-testid="`repository-commit-${item.shortSha}`" @click="showCommit(item)">
              <span class="repository-entry-text">
                <span class="repository-entry-name">{{ item.summary }}</span>
                <span class="repository-entry-meta">
                  {{ item.shortSha }} · {{ item.author }} · {{ formatCommitDate(item.date, locale) }}
                </span>
              </span>
            </button>
          </li>
        </ul>
        <button v-if="!historyComplete" type="button" class="repository-secondary" @click="loadHistory(false)">
          {{ t('repo.loadMore') }}
        </button>
      </template>
    </div>

    <!-- One file's uncommitted changes -->
    <div
      v-if="fileDiff"
      class="repository-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="repository-diff-title"
      data-testid="repository-file-diff"
    >
      <header class="repository-header">
        <button type="button" class="repository-back" :aria-label="t('openLocations.back')" @click="fileDiff = null">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <h1 id="repository-diff-title" class="repository-title">{{ fileDiff.title }}</h1>
      </header>
      <div class="repository-content">
        <DiffView
          :old-text="fileDiff.diff.oldText"
          :new-text="fileDiff.diff.newText"
          :binary="fileDiff.diff.binary"
          :too-large="fileDiff.diff.tooLarge"
        />
      </div>
    </div>

    <!-- One commit -->
    <div
      v-if="commitDetail"
      class="repository-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="repository-commit-title"
      data-testid="repository-commit-detail"
    >
      <header class="repository-header">
        <button type="button" class="repository-back" :aria-label="t('openLocations.back')" @click="commitDetail = null">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <h1 id="repository-commit-title" class="repository-title">
          {{ commitDetail.commit.summary }}
          <span class="repository-subtitle">
            {{ commitDetail.commit.shortSha }} · {{ commitDetail.commit.author }} · {{ formatCommitDate(commitDetail.commit.date, locale) }}
          </span>
        </h1>
      </header>
      <div class="repository-content">
        <button
          type="button"
          class="repository-secondary"
          data-testid="repository-revert-commit"
          :disabled="busy !== null || mergeInProgress || commitDetail.commit.parentCount > 1"
          @click="confirmRevertCommit(commitDetail.commit)"
        >
          {{ t('repo.revertCommit') }}
        </button>
        <section v-for="file in commitDetail.files" :key="diffFilePath(file)" class="repository-card">
          <h2 class="repository-section-title">
            <span class="repository-badge" :class="`repository-badge-${file.changeType === 'add' ? 'added' : file.changeType === 'delete' ? 'deleted' : 'modified'}`">
              {{ t(`repo.change.${file.changeType ?? 'modify'}`) }}
            </span>
            {{ diffFilePath(file) }}
          </h2>
          <DiffView :old-text="file.oldText" :new-text="file.newText" :binary="file.binary" :too-large="file.tooLarge" />
        </section>
      </div>
    </div>

    <PushSheet
      v-if="pushSheetOpen && status"
      :changes="resolvedChanges"
      :ahead="status.ahead"
      :branch="status.branch"
      :busy="busy === 'push'"
      @push="commitAndPush"
      @cancel="pushSheetOpen = false"
    />

    <ConfirmSheet
      v-if="confirmation"
      :message="confirmation.message"
      :confirm-label="confirmation.label"
      :danger="confirmation.danger"
      @confirm="runConfirmation"
      @cancel="confirmation = null"
    />
  </div>
</template>

<style src="./repositoryScreens.css"></style>
