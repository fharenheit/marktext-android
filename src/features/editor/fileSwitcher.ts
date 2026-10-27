import { parseCloudSourceUri } from '../../lib/cloudDocumentUris'
import { parseRepositorySourceUri } from '../../lib/localRepositoryUris'

/**
 * Where the editor's file panel browses for the open document: its
 * repository working copy, or its GitHub repository branch. Folders are
 * repository-relative paths ('' = the root); the panel never climbs above
 * the root (the branch, on GitHub).
 */
export type FileSwitcherLocation =
  | { kind: 'repository', repositoryId: string, folder: string, currentPath: string }
  | {
      kind: 'github'
      /** `owner/repo/<encoded ref>`: the GitHub id of the branch root. */
      branchId: string
      folder: string
      currentPath: string
    }

export interface FileSwitcherEntry {
  name: string
  /** Repository-relative path. */
  path: string
  isFolder: boolean
  /** Whether the editor can open it (Markdown and plain text). */
  openable: boolean
}

const OPENABLE_FILE = /\.(md|markdown|mdown|mkd|mkdn|mdx|txt)$/i

export function isOpenableFileName(name: string) {
  return OPENABLE_FILE.test(name)
}

export function parentFolder(path: string) {
  const index = path.lastIndexOf('/')
  return index < 0 ? '' : path.slice(0, index)
}

/** The panel's starting point for a document: its own folder. */
export function fileSwitcherLocationFor(sourceUri: string | null | undefined): FileSwitcherLocation | null {
  if (!sourceUri) {
    return null
  }
  const repository = parseRepositorySourceUri(sourceUri)
  if (repository) {
    return {
      kind: 'repository',
      repositoryId: repository.repositoryId,
      folder: parentFolder(repository.path),
      currentPath: repository.path,
    }
  }
  const cloud = parseCloudSourceUri(sourceUri)
  if (cloud?.provider !== 'github') {
    return null
  }
  const parts = cloud.fileId.split('/')
  if (parts.length !== 4) {
    return null
  }
  let path: string
  try {
    path = decodeURIComponent(parts[3])
  } catch {
    return null
  }
  return {
    kind: 'github',
    branchId: parts.slice(0, 3).join('/'),
    folder: parentFolder(path),
    currentPath: path,
  }
}

/** The GitHub id of a folder (or file) path on the location's branch. */
export function githubPathId(branchId: string, path: string) {
  return path ? `${branchId}/${encodeURIComponent(path)}` : branchId
}

/** Repository-relative path of a GitHub entry id on a branch. */
export function githubIdPath(id: string) {
  const parts = id.split('/')
  try {
    return parts.length === 4 ? decodeURIComponent(parts[3]) : ''
  } catch {
    return ''
  }
}

/** Folders first, then files, each alphabetically (case-insensitive). */
export function sortFileSwitcherEntries(entries: FileSwitcherEntry[]) {
  return [...entries].sort((a, b) =>
    a.isFolder === b.isFolder
      ? a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
      : a.isFolder ? -1 : 1,
  )
}
