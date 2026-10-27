import { Capacitor } from '@capacitor/core'
import type {
  AndroidReadOptions,
  AndroidWriteOptions,
  OpenedAndroidDocument,
  SavedAndroidDocument,
} from '../../lib/androidDocuments'
import { callRepository } from '../../lib/localRepositories'
import { buildRepositorySourceUri, parseRepositorySourceUri } from '../../lib/localRepositoryUris'
import {
  prepareLocalImagesForDocument,
  resolveRepositoryRelativePath,
} from '../cloud-documents/githubImages'

/**
 * Working-tree roots by repository id, learned from reads, so the image
 * resolver can map a document's relative images to files on the device.
 */
const workTreeUris = new Map<string, string>()
const repositoryNames = new Map<string, string>()

/** The repository's display name, as remembered when its work tree was. */
export function getRepositoryDisplayName(repositoryId: string) {
  return repositoryNames.get(repositoryId) ?? repositoryId
}

export function rememberRepositoryWorkTree(repositoryId: string, workTreeUri: string, name?: string) {
  workTreeUris.set(repositoryId, workTreeUri.replace(/\/+$/, ''))
  if (name) {
    repositoryNames.set(repositoryId, name)
  }
}

export async function readRepositoryMarkdownDocument(
  sourceUri: string,
  options: AndroidReadOptions = {},
): Promise<OpenedAndroidDocument> {
  const location = requireLocation(sourceUri)
  const document = await callRepository(plugin =>
    plugin.readRepositoryDocument({
      id: location.repositoryId,
      path: location.path,
      defaultEncoding: options.defaultEncoding,
      autoDetectEncoding: options.autoDetectEncoding,
    }),
  )
  rememberRepositoryWorkTree(location.repositoryId, document.workTreeUri, document.repositoryName)
  return {
    canceled: false,
    sourceUri: buildRepositorySourceUri(location.repositoryId, document.path),
    displayName: document.displayName,
    providerName: document.repositoryName,
    pathHint: `${document.repositoryName}/${document.path}`,
    mimeType: 'text/markdown',
    markdown: document.markdown,
    encoding: document.encoding,
    hasEncodingBom: document.hasEncodingBom,
    canWrite: true,
    // The working copy lives in app storage: reopening needs no grant.
    persisted: true,
  }
}

/**
 * Autosaves straight into the working tree. Images inserted on the device
 * are copied next to the document and linked relatively on disk, so they
 * are part of the next commit; the editor keeps its app-local links.
 */
export async function writeRepositoryMarkdownDocument(
  sourceUri: string,
  markdown: string,
  options: AndroidWriteOptions,
): Promise<SavedAndroidDocument> {
  const location = requireLocation(sourceUri)
  const prepared = prepareLocalImagesForDocument(markdown, location.path)
  const written = await callRepository(plugin =>
    plugin.writeRepositoryDocument({
      id: location.repositoryId,
      path: location.path,
      markdown: prepared.markdown,
      encoding: options.encoding,
      writeBom: options.writeBom,
      ...(prepared.images.length ? { localImages: prepared.images } : {}),
    }),
  )
  const repositoryName = repositoryNames.get(location.repositoryId) ?? location.repositoryId
  return {
    sourceUri,
    displayName: written.displayName,
    providerName: repositoryName,
    pathHint: `${repositoryName}/${location.path}`,
    mimeType: 'text/markdown',
    encoding: options.encoding,
    canWrite: true,
    persisted: true,
  }
}

/** The committed and current (on-disk) text of a repository document. */
export async function getRepositoryDocumentChanges(sourceUri: string) {
  const location = requireLocation(sourceUri)
  const diff = await callRepository(plugin =>
    plugin.getRepositoryFileDiff({ id: location.repositoryId, path: location.path }),
  )
  return { oldText: diff.oldText, newText: diff.newText }
}

/** Throws away the document's uncommitted edits (back to its last commit). */
export async function revertRepositoryDocument(sourceUri: string) {
  const location = requireLocation(sourceUri)
  await callRepository(plugin =>
    plugin.discardRepositoryChanges({ id: location.repositoryId, paths: [location.path] }),
  )
}

/** The three sides of a pull-and-merge for one working-tree document. */
export interface RepositoryDocumentMergeSides {
  /** Last commit before the pull. */
  base: string
  /** The working-tree text before the pull (the user's uncommitted edits). */
  ours: string
  /** The file after the pull. */
  theirs: string
}

/** How the document is stored on disk, so a restore writes it back the same way. */
export interface RepositoryDocumentStorage {
  encoding?: string
  writeBom?: boolean
}

/**
 * Pulls the repository while keeping this document's uncommitted edits: the
 * file goes back to its last commit so the pull can update it, and the three
 * texts come back for a three-way merge. Every side is the on-disk text, so
 * image links rewritten for the editor never read as changes. If the pull
 * fails, the edits are written back (same encoding and BOM) before the
 * pull's error surfaces; a failed restore never hides that error.
 */
export async function pullRepositoryDocumentForMerge(
  sourceUri: string,
  storage: RepositoryDocumentStorage = {},
): Promise<RepositoryDocumentMergeSides> {
  const location = requireLocation(sourceUri)
  const before = await callRepository(plugin =>
    plugin.getRepositoryFileDiff({ id: location.repositoryId, path: location.path }),
  )
  const ours = before.newText ?? ''
  // A file the last commit never had cannot be set aside by a checkout.
  const setAside = before.oldText !== null && before.oldText !== before.newText
  let restore: RepositoryDocumentStorage = storage
  if (setAside) {
    if (storage.encoding === undefined) {
      // Learn the on-disk encoding and BOM before the file is reset.
      const current = await callRepository(plugin =>
        plugin.readRepositoryDocument({ id: location.repositoryId, path: location.path, autoDetectEncoding: true }),
      )
      restore = { encoding: current.encoding, writeBom: storage.writeBom ?? current.hasEncodingBom }
    }
    await revertRepositoryDocument(sourceUri)
  }
  try {
    await callRepository(plugin => plugin.pullRepository({ id: location.repositoryId }))
  } catch (error) {
    if (setAside) {
      try {
        await callRepository(plugin =>
          plugin.writeRepositoryDocument({
            id: location.repositoryId,
            path: location.path,
            markdown: ours,
            ...(restore.encoding !== undefined ? { encoding: restore.encoding } : {}),
            ...(restore.writeBom !== undefined ? { writeBom: restore.writeBom } : {}),
          }),
        )
      } catch {
        // The pull's failure is what the user must see; the editor still
        // holds the edits and the next autosave writes them again.
      }
    }
    throw error
  }
  const after = await callRepository(plugin =>
    plugin.getRepositoryFileDiff({ id: location.repositoryId, path: location.path }),
  )
  return { base: before.oldText ?? '', ours, theirs: after.newText ?? '' }
}

/**
 * Image sources of a repository document map to its working-tree files
 * (served to the WebView through Capacitor's file URL bridge).
 */
export function resolveRepositoryImageSource(source: string, sourceUri: string): string | null {
  const location = parseRepositorySourceUri(sourceUri)
  const root = location ? workTreeUris.get(location.repositoryId) : undefined
  if (!location || !root) {
    return null
  }
  const path = resolveRepositoryRelativePath(source, location.path)
  if (path === null) {
    return null
  }
  const encoded = path.split('/').map(segment => encodeURIComponent(segment)).join('/')
  return Capacitor.convertFileSrc(`${root}/${encoded}`)
}

function requireLocation(sourceUri: string) {
  const location = parseRepositorySourceUri(sourceUri)
  if (!location) {
    throw new Error(`Not a repository document: ${sourceUri}`)
  }
  return location
}
