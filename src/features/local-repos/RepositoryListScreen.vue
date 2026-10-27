<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
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
/** Where the clone comes from: the signed-in GitHub account (default) or any HTTPS Git host. */
const cloneSource = ref<'github' | 'git'>('github')
const cloneUrl = ref('')
const cloneBranch = ref('')
const cloneName = ref('')
const gitUsername = ref('')
const gitPassword = ref('')
/** The native store holds a password for this host and username; an empty field reuses it. */
const gitPasswordSaved = ref(false)
const githubConnected = ref(false)
const githubSearch = ref('')
const selectedGitHubRepository = ref<CloudFolderEntry | null>(null)
const cloning = ref(false)
const progress = ref<RepositoryProgressEvent | null>(null)
const githubRepositories = ref<CloudFolderEntry[] | null>(null)
const githubRepositoriesError = ref(false)
const deleting = ref<LocalRepositorySummary | null>(null)
let progressListener: PluginListenerHandle | null = null
let savedUsername: { host: string; username: string } | null = null

const CLONE_FORM_STORAGE_KEY = 'marktext.repositories.cloneForm'

interface RememberedCloneForm {
  source?: 'github' | 'git'
  gitUrl?: string
  username?: string
}

function readRememberedCloneForm(): RememberedCloneForm {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(CLONE_FORM_STORAGE_KEY) ?? '{}')
    return parsed && typeof parsed === 'object' ? (parsed as RememberedCloneForm) : {}
  } catch {
    return {}
  }
}

function rememberCloneForm(form: RememberedCloneForm) {
  try {
    localStorage.setItem(CLONE_FORM_STORAGE_KEY, JSON.stringify({ ...readRememberedCloneForm(), ...form }))
  } catch {
    // Remembering is a convenience; private mode simply starts empty next time.
  }
}

const filteredGitHubRepositories = computed(() => {
  const query = githubSearch.value.trim().toLowerCase()
  const entries = githubRepositories.value ?? []
  return query ? entries.filter(entry => entry.name.toLowerCase().includes(query)) : entries
})

const canClone = computed(() => {
  if (cloning.value) {
    return false
  }
  if (cloneSource.value === 'github' && githubConnected.value) {
    return selectedGitHubRepository.value !== null
  }
  return cloneUrl.value.trim().startsWith('https://')
})

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
  const remembered = readRememberedCloneForm()
  cloneOpen.value = true
  cloneSource.value = remembered.source === 'git' ? 'git' : 'github'
  cloneBranch.value = ''
  cloneName.value = ''
  githubSearch.value = ''
  selectedGitHubRepository.value = null
  gitPassword.value = ''
  notice.value = null
  applySource(remembered)
  if (githubRepositories.value !== null) {
    return
  }
  // The signed-in GitHub account lists its repositories to search and pick.
  try {
    const account = await getCloudAccountState('github')
    githubConnected.value = account.connected
    githubRepositories.value = account.connected ? await listCloudFolder('github') : []
  } catch {
    githubRepositoriesError.value = githubConnected.value
    githubRepositories.value = []
  }
}

function applySource(remembered = readRememberedCloneForm()) {
  if (cloneSource.value === 'git') {
    cloneUrl.value = remembered.gitUrl ?? ''
    gitUsername.value = remembered.username ?? ''
    void refreshSavedCredentials()
  } else {
    cloneUrl.value = ''
  }
}

function selectSource(source: 'github' | 'git') {
  if (cloneSource.value === source || cloning.value) {
    return
  }
  cloneSource.value = source
  cloneName.value = ''
  selectedGitHubRepository.value = null
  applySource()
}

/** Prefills the username remembered for the URL's host and notes a saved password. */
async function refreshSavedCredentials() {
  const url = cloneUrl.value.trim()
  if (!url.startsWith('https://')) {
    savedUsername = null
    gitPasswordSaved.value = false
    return
  }
  try {
    const saved = await callRepository(plugin => plugin.getGitCredentials({ url }))
    if (url !== cloneUrl.value.trim()) {
      return
    }
    savedUsername = saved.host && saved.username ? { host: saved.host, username: saved.username } : null
    if (savedUsername && gitUsername.value.trim() === '') {
      gitUsername.value = savedUsername.username
    }
    gitPasswordSaved.value = saved.hasPassword && savedUsername?.username === gitUsername.value.trim()
  } catch {
    gitPasswordSaved.value = false
  }
}

watch(gitUsername, username => {
  gitPasswordSaved.value = gitPasswordSaved.value && savedUsername?.username === username.trim()
})

function pickGitHubRepository(entry: CloudFolderEntry) {
  selectedGitHubRepository.value = entry
  cloneName.value = entry.name
}

async function forgetSavedPassword() {
  const url = cloneUrl.value.trim()
  try {
    await callRepository(plugin => plugin.forgetGitCredentials({ url }))
  } catch (error) {
    notice.value = getRepositoryUserMessage(error)
    return
  }
  savedUsername = null
  gitPasswordSaved.value = false
}

async function clone() {
  if (!canClone.value) {
    return
  }
  const source = cloneSource.value
  const selected = selectedGitHubRepository.value
  const url = source === 'github' && githubConnected.value && selected
    ? `https://github.com/${selected.id}.git`
    : cloneUrl.value.trim()
  cloning.value = true
  progress.value = null
  notice.value = null
  try {
    const cloned = await callRepository(plugin =>
      plugin.cloneRepository({
        url,
        branch: cloneBranch.value.trim() || undefined,
        name: cloneName.value.trim() || undefined,
        ...(source === 'git'
          ? { username: gitUsername.value.trim() || undefined, password: gitPassword.value || undefined, remember: true }
          : {}),
      }),
    )
    rememberCloneForm(
      source === 'git'
        ? { source, gitUrl: url, username: gitUsername.value.trim() }
        : { source },
    )
    gitPassword.value = ''
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
        <div class="repository-segmented" role="radiogroup" :aria-label="t('clone.source')">
          <button
            v-for="source in (['github', 'git'] as const)"
            :key="source"
            type="button"
            role="radio"
            class="repository-segment"
            :class="{ 'repository-segment-selected': cloneSource === source }"
            :aria-checked="cloneSource === source"
            :disabled="cloning"
            :data-testid="`repository-clone-source-${source}`"
            @click="selectSource(source)"
          >
            {{ source === 'github' ? 'GitHub' : t('clone.sourceGit') }}
          </button>
        </div>

        <template v-if="cloneSource === 'github'">
          <p v-if="githubRepositories === null" class="repository-hint">{{ t('cloudBrowser.loading') }}</p>
          <template v-else-if="githubConnected && !githubRepositoriesError">
            <input
              v-model="githubSearch"
              class="repository-input"
              type="search"
              autocapitalize="off"
              spellcheck="false"
              :placeholder="t('clone.search')"
              :aria-label="t('clone.search')"
              :disabled="cloning"
              data-testid="repository-clone-search"
            >
            <ul class="repository-pick-list" :aria-label="t('clone.fromGitHub')" data-testid="repository-clone-github-list">
              <li v-for="entry in filteredGitHubRepositories" :key="entry.id">
                <button
                  type="button"
                  class="repository-pick"
                  :class="{ 'repository-pick-selected': selectedGitHubRepository?.id === entry.id }"
                  :aria-pressed="selectedGitHubRepository?.id === entry.id"
                  :disabled="cloning"
                  :data-testid="`repository-clone-pick-${entry.id}`"
                  @click="pickGitHubRepository(entry)"
                >
                  <span class="repository-pick-owner">{{ entry.name.split('/')[0] }}/</span>
                  <span class="repository-pick-name">{{ entry.name.split('/').slice(1).join('/') }}</span>
                </button>
              </li>
              <li v-if="filteredGitHubRepositories.length === 0" class="repository-hint">
                {{ githubSearch.trim() ? t('clone.noMatches') : t('cloudBrowser.empty') }}
              </li>
            </ul>
          </template>
          <template v-else>
            <p class="repository-hint">{{ githubRepositoriesError ? t('clone.listFailed') : t('clone.signInHint') }}</p>
            <input
              v-model="cloneUrl"
              class="repository-input"
              type="url"
              inputmode="url"
              autocapitalize="off"
              spellcheck="false"
              placeholder="https://github.com/owner/repo.git"
              :aria-label="t('clone.url')"
              :disabled="cloning"
              data-testid="repository-clone-url"
            >
          </template>
        </template>

        <template v-else>
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
            @change="refreshSavedCredentials"
          >
          <input
            v-model="gitUsername"
            class="repository-input"
            type="text"
            autocomplete="username"
            autocapitalize="off"
            spellcheck="false"
            :placeholder="t('clone.username')"
            :aria-label="t('clone.username')"
            :disabled="cloning"
            data-testid="repository-clone-username"
          >
          <input
            v-model="gitPassword"
            class="repository-input"
            type="password"
            autocomplete="current-password"
            :placeholder="gitPasswordSaved ? t('clone.passwordSaved') : t('clone.password')"
            :aria-label="t('clone.password')"
            :disabled="cloning"
            data-testid="repository-clone-password"
          >
          <p class="repository-hint">
            {{ t('clone.credentialsHint') }}
            <button
              v-if="gitPasswordSaved"
              type="button"
              class="repository-link"
              :disabled="cloning"
              data-testid="repository-clone-forget"
              @click="forgetSavedPassword"
            >
              {{ t('clone.forgetPassword') }}
            </button>
          </p>
        </template>

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
            :disabled="!canClone"
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
