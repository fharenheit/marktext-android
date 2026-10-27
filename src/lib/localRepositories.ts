import { registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import { AndroidDocumentError, isAndroidDocumentAccessAvailable } from './androidDocuments'
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
  cloneRepository(options: { url: string; branch?: string; name?: string }): Promise<LocalRepositorySummary>
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
        typeof message === 'string' ? message : 'Repository operation failed',
        paths,
      )
    }
    throw error
  }
}

export function addRepositoryProgressListener(listener: (event: RepositoryProgressEvent) => void) {
  return LocalRepositories.addListener('repositoryProgress', listener)
}

/** User-facing text for repository failures (English, like the other document messages). */
export function getRepositoryUserMessage(error: unknown) {
  const code = error instanceof AndroidDocumentError ? error.code : 'UNKNOWN'
  const paths = error instanceof RepositoryError && error.paths.length > 0
    ? ` (${error.paths.slice(0, 3).join(', ')}${error.paths.length > 3 ? ', …' : ''})`
    : ''
  switch (code) {
    case 'UNAVAILABLE':
      return 'Repositories are available in the Android app build.'
    case 'INVALID_REMOTE_URL':
      return 'Enter an https:// repository address.'
    case 'REMOTE_NOT_FOUND':
      return 'The repository was not found, or this account cannot access it.'
    case 'AUTH_FAILED':
      return 'GitHub refused the sign-in. Connect the GitHub account again.'
    case 'NETWORK_FAILED':
      return 'Could not reach the remote. Check the connection and try again.'
    case 'NOTHING_TO_COMMIT':
      return 'There are no changes to commit.'
    case 'NOTHING_TO_PUSH':
      return 'Commit something before pushing.'
    case 'PUSH_REJECTED':
      return 'The remote has new commits. Pull them first, then push again.'
    case 'NO_UPSTREAM':
      return 'This branch is not on the remote yet. Push it first.'
    case 'LOCAL_CHANGES_BLOCK_PULL':
    case 'LOCAL_CHANGES_BLOCK_CHECKOUT':
    case 'LOCAL_CHANGES_BLOCK_REVERT':
      return `Commit or revert your edits to these files first${paths}.`
    case 'CONFLICTS_UNRESOLVED':
      return 'Resolve every conflict before committing.'
    case 'MERGE_IN_PROGRESS':
      return 'Finish or abort the current merge first.'
    case 'BRANCH_EXISTS':
      return 'A branch with this name already exists.'
    case 'INVALID_BRANCH_NAME':
      return 'This branch name is not valid.'
    case 'REPOSITORY_NOT_FOUND':
      return 'This repository is not on the device anymore.'
    default:
      return error instanceof Error && error.message ? error.message : 'The repository operation failed.'
  }
}
