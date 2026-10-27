import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import { AndroidDocumentError, isAndroidDocumentAccessAvailable } from './androidDocuments'
import { useI18n } from './i18n'
import type { MarkdownEncoding } from '../features/settings/advancedSettings'

/** A Git working copy on the device. */
export interface LocalRepositorySummary {
  id: string
  name: string
  remoteUrl: string | null
  branch: string
  changeCount: number
  ahead: number
  behind: number
  state: RepositoryState
  /** file:// root of the working tree, for showing the repository's images. */
  workTreeUri: string
}

/** SAFE, or a merge/revert in progress (MERGING/REVERTING, "_RESOLVED" once nothing conflicts). */
export type RepositoryState = 'SAFE' | 'MERGING' | 'MERGING_RESOLVED' | 'REVERTING' | 'REVERTING_RESOLVED' | string

export interface RepositoryChange {
  path: string
  kind: 'added' | 'modified' | 'deleted' | 'conflicting'
}

export interface RepositoryStatus {
  branch: string
  state: RepositoryState
  ahead: number
  behind: number
  hasUpstream: boolean
  changes: RepositoryChange[]
}

export interface RepositoryEntry {
  name: string
  path: string
  isFolder: boolean
  size: number
}

export interface RepositoryCommit {
  sha: string
  shortSha: string
  summary: string
  message: string
  author: string
  date: string
  parentCount: number
}

export interface RepositoryBranch {
  name: string
  current: boolean
  local: boolean
  remote: boolean
}

/** Both text sides of a change; null = the file is absent on that side. */
export interface RepositoryFileDiff {
  path?: string
  changeType?: 'add' | 'modify' | 'delete' | 'rename' | 'copy'
  oldPath?: string | null
  newPath?: string | null
  oldText: string | null
  newText: string | null
  binary: boolean
  tooLarge: boolean
}

export interface RepositoryOutcome {
  result: 'up-to-date' | 'fast-forward' | 'merged' | 'conflicts'
  conflicts: string[]
  status: RepositoryStatus
}

export interface RepositoryDocument {
  path: string
  displayName: string
  repositoryName: string
  /** file:// root of the working tree. */
  workTreeUri: string
  markdown: string
  encoding: MarkdownEncoding
  hasEncodingBom: boolean
}

export interface RepositoryProgressEvent {
  id: string
  task: string
  completed: number
  total: number
}

/** A repository failure; `paths` names the files it concerns (conflicts, blocked files). */
export class RepositoryError extends AndroidDocumentError {
  paths: string[]

  constructor(code: string, message: string, paths: string[] = []) {
    super(code, message)
    this.name = 'RepositoryError'
    this.paths = paths
  }
}

interface LocalRepositoriesPlugin {
  addListener(
    eventName: 'repositoryProgress',
    listener: (event: RepositoryProgressEvent) => void,
  ): Promise<PluginListenerHandle>
  listRepositories(): Promise<{ repositories: LocalRepositorySummary[] }>
  cloneRepository(options: {
    url: string
    branch?: string
    name?: string
    /** Non-GitHub hosts: an empty password reuses the one remembered for this host and username. */
    username?: string
    password?: string
    remember?: boolean
  }): Promise<LocalRepositorySummary>
  /** The remembered sign-in for a host; the password itself never leaves native storage. */
  getGitCredentials(options: { url: string }): Promise<{ host: string | null; username: string | null; hasPassword: boolean }>
  forgetGitCredentials(options: { url: string }): Promise<void>
  deleteRepository(options: { id: string }): Promise<void>
  getRepositoryStatus(options: { id: string }): Promise<RepositoryStatus>
  listRepositoryFolder(options: { id: string; path: string }): Promise<{ entries: RepositoryEntry[] }>
  readRepositoryDocument(options: {
    id: string
    path: string
    defaultEncoding?: string
    autoDetectEncoding?: boolean
  }): Promise<RepositoryDocument>
  writeRepositoryDocument(options: {
    id: string
    path: string
    markdown: string
    encoding?: string
    writeBom?: boolean
    localImages?: { name: string; path: string }[]
  }): Promise<{ path: string; displayName: string }>
  getRepositoryFileDiff(options: { id: string; path: string }): Promise<RepositoryFileDiff>
  commitRepository(options: { id: string; message: string }): Promise<RepositoryCommit>
  pushRepository(options: { id: string }): Promise<RepositoryStatus>
  pullRepository(options: { id: string }): Promise<RepositoryOutcome>
  resolveRepositoryConflict(options: {
    id: string
    path: string
    choice: 'ours' | 'theirs' | 'edited'
  }): Promise<RepositoryStatus>
  completeRepositoryMerge(options: { id: string; message?: string }): Promise<RepositoryCommit>
  abortRepositoryMerge(options: { id: string }): Promise<RepositoryStatus>
  discardRepositoryChanges(options: { id: string; paths: string[] }): Promise<RepositoryStatus>
  listRepositoryBranches(options: { id: string }): Promise<{ branches: RepositoryBranch[] }>
  checkoutRepositoryBranch(options: { id: string; name: string; create?: boolean }): Promise<RepositoryStatus>
  getRepositoryHistory(options: { id: string; maxCount?: number; skip?: number }): Promise<{ commits: RepositoryCommit[] }>
  getRepositoryCommitDiff(options: { id: string; sha: string }): Promise<{ files: RepositoryFileDiff[] }>
  revertRepositoryCommit(options: { id: string; sha: string }): Promise<RepositoryOutcome>
}

const LocalRepositories = registerPlugin<LocalRepositoriesPlugin>('LocalRepositories')

export function isLocalRepositoryAccessAvailable() {
  return isAndroidDocumentAccessAvailable()
}

/** Plugin rejections become RepositoryErrors carrying the code and affected paths. */
export async function callRepository<T>(operation: (plugin: LocalRepositoriesPlugin) => Promise<T>): Promise<T> {
  try {
    return await operation(LocalRepositories)
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error) {
      const { code, message, data } = error as { code?: unknown; message?: unknown; data?: unknown }
      const paths = data && typeof data === 'object' && Array.isArray((data as { paths?: unknown }).paths)
        ? ((data as { paths: unknown[] }).paths.filter(path => typeof path === 'string') as string[])
        : []
      throw new RepositoryError(
        typeof code === 'string' ? code : 'UNKNOWN',
        typeof message === 'string' ? message : '',
        paths,
      )
    }
    throw error
  }
}

export function addRepositoryProgressListener(listener: (event: RepositoryProgressEvent) => void) {
  return LocalRepositories.addListener('repositoryProgress', listener)
}

/**
 * User-facing, localized text for repository failures. `remoteUrl` (the
 * repository's or the clone's address) tailors sign-in failures to GitHub
 * or any other Git host; without it the message covers both.
 */
export function getRepositoryUserMessage(error: unknown, remoteUrl?: string | null) {
  const { t } = useI18n()
  const code = error instanceof AndroidDocumentError ? error.code : 'UNKNOWN'
  const detail = error instanceof Error ? error.message.trim() : ''
  const paths = error instanceof RepositoryError && error.paths.length > 0
    ? ` ${t('repoError.files', { paths: `${error.paths.slice(0, 3).join(', ')}${error.paths.length > 3 ? ', …' : ''}` })}`
    : ''
  switch (code) {
    case 'UNAVAILABLE':
      return t('repoError.unavailable')
    case 'INVALID_REMOTE_URL':
      return t('repoError.invalidRemoteUrl')
    case 'REMOTE_NOT_FOUND':
      return t('repoError.remoteNotFound')
    case 'AUTH_REQUIRED':
      return t('repoError.authRequired')
    case 'AUTH_FAILED': {
      const github = isGitHubRemote(remoteUrl)
      return github === null
        ? t('repoError.authFailed')
        : github ? t('repoError.authFailedGitHub') : t('repoError.authFailedGit')
    }
    case 'REMOTE_ACCESS_DENIED':
      return t('repoError.remoteAccessDenied')
    case 'NETWORK_FAILED':
      return t('repoError.network')
    case 'TRANSPORT_FAILED':
      return detail ? t('repoError.transportFailed', { detail }) : t('repoError.transportFailedPlain')
    case 'NOTHING_TO_COMMIT':
      return t('repoError.nothingToCommit')
    case 'NOTHING_TO_PUSH':
      return t('repoError.nothingToPush')
    case 'PUSH_REJECTED':
      return t('repoError.pushRejected')
    case 'PUSH_REJECTED_OTHER':
      return detail ? t('repoError.pushRefused', { detail }) : t('repoError.pushRejected')
    case 'NO_UPSTREAM':
      return t('repoError.noUpstream')
    case 'LOCAL_CHANGES_BLOCK_PULL':
      return `${t('repoError.localChangesBlockPull')}${paths}`
    case 'LOCAL_CHANGES_BLOCK_CHECKOUT':
      return `${t('repoError.localChangesBlockCheckout')}${paths}`
    case 'LOCAL_CHANGES_BLOCK_REVERT':
      return `${t('repoError.localChangesBlockRevert')}${paths}`
    case 'CONFLICTS_UNRESOLVED':
      return t('repoError.conflictsUnresolved')
    case 'MERGE_IN_PROGRESS':
      return t('repoError.mergeInProgress')
    case 'BRANCH_EXISTS':
      return t('repoError.branchExists')
    case 'INVALID_BRANCH_NAME':
      return t('repoError.invalidBranchName')
    case 'REPOSITORY_NOT_FOUND':
      return t('repoError.repositoryNotFound')
    default:
      // The native detail is English; the localized lead says what failed.
      return detail ? t('repoError.failedDetail', { detail }) : t('repoError.failed')
  }
}

/** True for github.com remotes, false for other hosts, null when unknown. */
function isGitHubRemote(remoteUrl: string | null | undefined) {
  if (!remoteUrl) {
    return null
  }
  try {
    const host = new URL(remoteUrl).hostname.toLowerCase()
    return host === 'github.com' || host === 'www.github.com'
  } catch {
    return null
  }
}
