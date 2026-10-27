<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import { useI18n } from '../../lib/i18n'
import {
  addRepositoryProgressListener,
  callRepository,
  getRepositoryUserMessage,
  isLocalRepositoryAccessAvailable,
  RepositoryError,
  type LocalRepositorySummary,
  type RepositoryProgressEvent,
} from '../../lib/localRepositories'
import { getCloudAccountState, listCloudFolder } from '../../lib/cloudDocuments'
import type { CloudFolderEntry } from '../../lib/cloudDocuments'
import ConfirmSheet from './ConfirmSheet.vue'
import { formatProgress } from './repositoryFormatting'

const emit = defineEmits<{
  back: []
  openRepository: [repository: LocalRepositorySummary]
}>()

const { t } = useI18n()
const repositories = ref<LocalRepositorySummary[]>([])
const loading = ref(true)
const notice = ref<string | null>(null)
const cloneOpen = ref(false)
const cloneUrl = ref('')
const cloneBranch = ref('')
const cloneName = ref('')
const cloning = ref(false)
const progress = ref<RepositoryProgressEvent | null>(null)
const githubRepositories = ref<CloudFolderEntry[] | null>(null)
const deleting = ref<LocalRepositorySummary | null>(null)
let progressListener: PluginListenerHandle | null = null

async function load() {
  loading.value = true
  try {
    repositories.value = (await callRepository(plugin => plugin.listRepositories())).repositories
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  } finally {
    loading.value = false
  }
}

async function openClone() {
  cloneOpen.value = true
  cloneUrl.value = ''
  cloneBranch.value = ''
  cloneName.value = ''
  notice.value = null
  if (githubRepositories.value !== null) {
    return
  }
  // The signed-in GitHub account offers its repositories as quick picks.
  try {
    const account = await getCloudAccountState('github')
    githubRepositories.value = account.connected ? await listCloudFolder('github') : []
  } catch {
    githubRepositories.value = []
  }
}

function pickGitHubRepository(entry: CloudFolderEntry) {
  cloneUrl.value = `https://github.com/${entry.id}.git`
  cloneName.value = entry.name
}

async function clone() {
  const url = cloneUrl.value.trim()
  if (!url || cloning.value) {
    return
  }
  cloning.value = true
  progress.value = null
  notice.value = null
  try {
    const cloned = await callRepository(plugin =>
      plugin.cloneRepository({
        url,
        branch: cloneBranch.value.trim() || undefined,
        name: cloneName.value.trim() || undefined,
      }),
    )
    cloneOpen.value = false
    await load()
    emit('openRepository', cloned)
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  } finally {
    cloning.value = false
    progress.value = null
  }
}

async function confirmDelete() {
  const repository = deleting.value
  deleting.value = null
  if (!repository) {
    return
  }
  try {
    await callRepository(plugin => plugin.deleteRepository({ id: repository.id }))
    await load()
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
  }
}

onMounted(async () => {
  if (!isLocalRepositoryAccessAvailable()) {
    loading.value = false
    notice.value = getRepositoryUserMessage(new RepositoryError('UNAVAILABLE', ''))
    return
  }
  progressListener = await addRepositoryProgressListener(event => {
    if (cloning.value) {
      progress.value = event
    }
  })
  await load()
})

onBeforeUnmount(() => {
  void progressListener?.remove()
})
</script>

<template>
  <div class="repository-screen" data-testid="repository-list-screen">
    <header class="repository-header">
      <button type="button" class="repository-back" :aria-label="t('openLocations.back')" @click="emit('back')">
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path d="M15 5l-7 7 7 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <h1 id="repository-list-title" class="repository-title">{{ t('repos.title') }}</h1>
      <button type="button" class="repository-header-action" data-testid="repository-clone-open" @click="openClone">
        {{ t('repos.clone') }}
      </button>
    </header>

    <div class="repository-content">
      <p v-if="notice" class="repository-notice" role="alert">{{ notice }}</p>

      <section v-if="cloneOpen" class="repository-card" data-testid="repository-clone-form">
        <h2 class="repository-section-title">{{ t('clone.title') }}</h2>
        <template v-if="githubRepositories && githubRepositories.length > 0">
          <p class="repository-hint">{{ t('clone.fromGitHub') }}</p>
          <div class="repository-chips">
            <button
              v-for="entry in githubRepositories"
              :key="entry.id"
              type="button"
              class="repository-chip"
              :class="{ 'repository-chip-selected': cloneName === entry.name }"
              :disabled="cloning"
              @click="pickGitHubRepository(entry)"
            >
              {{ entry.name }}
            </button>
          </div>
        </template>
        <p v-else-if="githubRepositories" class="repository-hint">{{ t('clone.signInHint') }}</p>
        <input
          v-model="cloneUrl"
          class="repository-input"
          type="url"
          inputmode="url"
          autocapitalize="off"
          spellcheck="false"
          :placeholder="t('clone.url')"
          :aria-label="t('clone.url')"
          :disabled="cloning"
          data-testid="repository-clone-url"
        >
        <input
          v-model="cloneBranch"
          class="repository-input"
          type="text"
          autocapitalize="off"
          spellcheck="false"
          :placeholder="t('clone.branch')"
          :aria-label="t('clone.branch')"
          :disabled="cloning"
        >
        <p v-if="cloning" class="repository-hint" aria-live="polite">
          {{ progress ? formatProgress(progress) : t('clone.cloning') }}
        </p>
        <div class="repository-row-actions">
          <button
            type="button"
            class="repository-primary"
            :disabled="cloning || !cloneUrl.trim().startsWith('https://')"
            data-testid="repository-clone-confirm"
            @click="clone"
          >
            {{ cloning ? t('clone.cloning') : t('clone.confirm') }}
          </button>
          <button type="button" class="repository-secondary" :disabled="cloning" @click="cloneOpen = false">
            {{ t('saveAs.cancel') }}
          </button>
        </div>
      </section>

      <p v-if="loading" class="repository-hint">{{ t('cloudBrowser.loading') }}</p>
      <p v-else-if="repositories.length === 0 && !cloneOpen" class="repository-hint">{{ t('repos.empty') }}</p>

      <ul class="repository-list">
        <li v-for="repository in repositories" :key="repository.id" class="repository-list-item">
          <button
            type="button"
            class="repository-entry"
            :data-testid="`repository-${repository.id}`"
            @click="emit('openRepository', repository)"
          >
            <span class="repository-entry-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="22" height="22">
                <path d="M6.5 4.5v15M6.5 8.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm0 11a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm11-11a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm0 0v1.5a4 4 0 0 1-4 4h-3a4 4 0 0 0-4 4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <span class="repository-entry-text">
              <span class="repository-entry-name">{{ repository.name }}</span>
              <span class="repository-entry-meta">
                {{ repository.branch }}
                <template v-if="repository.changeCount > 0"> · {{ t('repos.changes', { count: repository.changeCount }) }}</template>
                <template v-if="repository.ahead > 0"> · {{ t('repos.ahead', { count: repository.ahead }) }}</template>
                <template v-if="repository.behind > 0"> · {{ t('repos.behind', { count: repository.behind }) }}</template>
              </span>
            </span>
          </button>
          <button
            type="button"
            class="repository-icon-button"
            :aria-label="t('repos.delete')"
            @click="deleting = repository"
          >
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
              <path d="M5 7h14M10 11v6M14 11v6M6.5 7l1 12.5h9L17.5 7M9.5 7V4.5h5V7" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
        </li>
      </ul>
    </div>

    <ConfirmSheet
      v-if="deleting"
      :message="t('repos.deleteConfirm', { name: deleting.name })"
      :confirm-label="t('repos.delete')"
      danger
      @confirm="confirmDelete"
      @cancel="deleting = null"
    />
  </div>
</template>

<style src="./repositoryScreens.css"></style>
