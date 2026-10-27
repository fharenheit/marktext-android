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
