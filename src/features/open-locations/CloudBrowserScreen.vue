<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from '../../lib/i18n'
import type { CloudAccountState, CloudFolderEntry } from '../../lib/cloudDocuments'

withDefaults(
  defineProps<{
    /** Which in-app browsable provider this screen shows. */
    provider?: 'onedrive' | 'github'
    accountState: CloudAccountState | null
    /** 'browser' = waiting for the sign-in browser; 'completing' = redirect landed, exchanging tokens. */
    connecting: 'browser' | 'completing' | null
    loading: boolean
    errorMessage: string | null
    entries: CloudFolderEntry[]
    folderName: string | null
    openingFileId: string | null
    /** Save-as destination mode: folders navigate, files are inert, a save bar appears. */
    saveMode?: boolean
    /** GitHub device-flow code the user enters on github.com while the app polls. */
    deviceCode?: { userCode: string; verificationUri: string } | null
  }>(),
  { provider: 'onedrive', saveMode: false, deviceCode: null },
)

const emit = defineEmits<{
  back: []
  connect: []
  disconnect: []
  openEntry: [entry: CloudFolderEntry]
  retry: []
  saveHere: []
  openVerification: []
  cancelConnect: []
  connectToken: [token: string]
  openTokenPage: []
}>()

const { t } = useI18n()
const githubToken = ref('')

function submitGitHubToken() {
  const token = githubToken.value.trim()
  if (token.length > 0) {
    emit('connectToken', token)
  }
}
</script>

<template>
  <div class="cloud-browser-screen">
    <header class="cloud-browser-header">
      <button
        type="button"
        class="cloud-browser-back"
        :aria-label="t('openLocations.back')"
        @click="emit('back')"
      >
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path
            d="M15 5l-7 7 7 7"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
      <h1 id="cloud-browser-title" class="cloud-browser-title">
        {{ folderName ?? (provider === 'github' ? 'GitHub' : 'OneDrive') }}
      </h1>
      <button
        v-if="accountState?.connected && !saveMode"
        type="button"
        class="cloud-browser-disconnect"
        @click="emit('disconnect')"
      >
        {{ t('cloudBrowser.disconnect') }}
      </button>
    </header>

    <p v-if="accountState?.connected && accountState.accountName" class="cloud-browser-account">
      {{ accountState.accountName }}
    </p>

    <div class="cloud-browser-content">
      <template v-if="accountState && !accountState.connected && deviceCode">
        <p class="cloud-browser-hint">{{ t('cloudBrowser.github.deviceHint') }}</p>
        <p class="cloud-browser-device-code" aria-live="polite">{{ deviceCode.userCode }}</p>
        <button type="button" class="cloud-browser-primary" @click="emit('openVerification')">
          {{ t('cloudBrowser.github.openVerification') }}
        </button>
        <p class="cloud-browser-hint">{{ t('cloudBrowser.github.waiting') }}</p>
        <button type="button" class="cloud-browser-secondary" @click="emit('cancelConnect')">
          {{ t('cloudBrowser.github.cancel') }}
        </button>
      </template>

      <template v-else-if="accountState && !accountState.connected && provider === 'github'">
        <p class="cloud-browser-hint">{{ t('cloudBrowser.github.signInHint') }}</p>
        <button
          v-if="accountState.deviceFlowAvailable"
          type="button"
          class="cloud-browser-primary"
          :disabled="connecting !== null"
          @click="emit('connect')"
        >
          {{ connecting !== null ? t('cloudBrowser.loading') : t('cloudBrowser.github.connect') }}
        </button>
        <form
          v-if="accountState.available"
          class="cloud-browser-token"
          data-testid="github-token-form"
          @submit.prevent="submitGitHubToken"
        >
          <p class="cloud-browser-hint">{{ t('cloudBrowser.github.tokenHint') }}</p>
          <label class="cloud-browser-token-label" for="github-token-input">
            {{ t('cloudBrowser.github.tokenLabel') }}
          </label>
          <input
            id="github-token-input"
            v-model="githubToken"
            class="cloud-browser-token-input"
            type="password"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            placeholder="github_pat_…"
            data-testid="github-token-input"
          >
          <button
            type="submit"
            :class="accountState.deviceFlowAvailable ? 'cloud-browser-secondary' : 'cloud-browser-primary'"
            :disabled="connecting !== null || githubToken.trim().length === 0"
            data-testid="github-token-connect"
          >
            {{ connecting !== null ? t('cloudBrowser.loading') : t('cloudBrowser.github.tokenConnect') }}
          </button>
          <button type="button" class="cloud-browser-link" @click="emit('openTokenPage')">
            {{ t('cloudBrowser.github.createToken') }}
          </button>
        </form>
        <p v-if="!accountState.available" class="cloud-browser-hint">
          {{ t('openLocations.cloudUnavailable') }}
        </p>
        <p v-else-if="errorMessage !== null" class="cloud-browser-hint" role="alert">
          {{ errorMessage }}
        </p>
      </template>

      <template v-else-if="accountState && !accountState.connected">
        <p class="cloud-browser-hint">{{ t('cloudBrowser.signInHint') }}</p>
        <button
          type="button"
          class="cloud-browser-primary"
          :disabled="connecting !== null || !accountState.available"
          @click="emit('connect')"
        >
          {{
            connecting === 'completing'
              ? t('cloudBrowser.completing')
              : connecting === 'browser'
                ? t('cloudBrowser.connecting')
                : t('cloudBrowser.connect')
          }}
        </button>
        <p v-if="!accountState.available" class="cloud-browser-hint">
          {{ t('openLocations.cloudUnavailable') }}
        </p>
      </template>

      <template v-else-if="errorMessage !== null">
        <p class="cloud-browser-hint" role="alert">{{ errorMessage }}</p>
        <button type="button" class="cloud-browser-primary" @click="emit('retry')">
          {{ t('cloudBrowser.retry') }}
        </button>
      </template>

      <p v-else-if="loading" class="cloud-browser-hint">{{ t('cloudBrowser.loading') }}</p>

      <p v-else-if="entries.length === 0" class="cloud-browser-hint">
        {{ t('cloudBrowser.empty') }}
      </p>

      <ul v-else class="cloud-browser-list">
        <li v-for="entry in entries" :key="entry.id">
          <button
            type="button"
            class="cloud-browser-entry"
            :disabled="openingFileId !== null || (saveMode && !entry.isFolder)"
            @click="emit('openEntry', entry)"
          >
            <span class="cloud-browser-entry-icon" aria-hidden="true">
              <svg v-if="entry.isFolder" viewBox="0 0 24 24" width="22" height="22">
                <path
                  d="M3.5 7.5v-2a1 1 0 0 1 1-1h4l2 2h9a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-11Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linejoin="round"
                />
              </svg>
              <svg v-else viewBox="0 0 24 24" width="22" height="22">
                <path
                  d="M6 2.5h8l4 4V20a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 20V4A1.5 1.5 0 0 1 6.5 2.5Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linejoin="round"
                />
              </svg>
            </span>
            <span class="cloud-browser-entry-name">
              {{ entry.name }}
              <template v-if="openingFileId === entry.id"> …</template>
            </span>
          </button>
        </li>
      </ul>
    </div>

    <div v-if="saveMode && accountState?.connected" class="cloud-browser-save-bar">
      <button type="button" class="cloud-browser-primary" @click="emit('saveHere')">
        {{ t('cloudBrowser.saveHere') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.cloud-browser-screen {
  display: flex;
  flex-direction: column;
  height: 100%;
  color: var(--text);
}

.cloud-browser-header {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: max(12px, env(safe-area-inset-top)) 12px 8px 4px;
}

.cloud-browser-back {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: none;
  background: none;
  color: var(--text);
  border-radius: var(--radius-sm);
}

.cloud-browser-back:active {
  background: var(--press);
}

.cloud-browser-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cloud-browser-disconnect {
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 13px;
  padding: 8px;
  border-radius: var(--radius-sm);
}

.cloud-browser-disconnect:active {
  background: var(--press);
}

.cloud-browser-account {
  margin: 0;
  padding: 0 16px 8px;
  font-size: 13px;
  color: var(--text-muted);
}

.cloud-browser-content {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 24px;
}

.cloud-browser-save-bar {
  padding: 8px 16px calc(12px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--border, rgba(128, 128, 128, 0.25));
}

.cloud-browser-hint {
  margin: 16px 8px;
  font-size: 14px;
  color: var(--text-muted);
}

.cloud-browser-primary {
  display: block;
  margin: 8px;
  padding: 12px 20px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--accent);
  color: var(--on-accent);
  font-size: 15px;
  font-weight: 600;
}

.cloud-browser-primary:disabled {
  opacity: 0.6;
}

.cloud-browser-secondary {
  display: block;
  margin: 8px;
  padding: 10px 16px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-muted);
  font-size: 14px;
}

.cloud-browser-secondary:active {
  background: var(--press);
}

.cloud-browser-secondary:disabled {
  opacity: 0.6;
}

.cloud-browser-token {
  margin-top: 16px;
}

.cloud-browser-token-label {
  display: block;
  margin: 0 8px 4px;
  color: var(--text-muted);
  font-size: 13px;
}

.cloud-browser-token-input {
  box-sizing: border-box;
  width: calc(100% - 16px);
  margin: 0 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font-family: var(--mt-mono-font);
  font-size: 14px;
}

.cloud-browser-link {
  display: block;
  margin: 4px 8px;
  padding: 8px 0;
  border: none;
  background: none;
  color: var(--accent);
  font-size: 14px;
}

.cloud-browser-device-code {
  margin: 8px;
  font-family: var(--mt-mono-font);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.12em;
  user-select: all;
}

.cloud-browser-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.cloud-browser-entry {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 13px 10px;
  border: none;
  background: none;
  border-radius: var(--radius-sm);
  color: var(--text);
  text-align: start;
}

.cloud-browser-entry:active {
  background: var(--press);
}

.cloud-browser-entry-icon {
  display: grid;
  place-items: center;
  color: var(--accent-strong);
  flex-shrink: 0;
}

.cloud-browser-entry-name {
  font-size: 15px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
