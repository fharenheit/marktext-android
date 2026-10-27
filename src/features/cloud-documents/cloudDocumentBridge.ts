import {
  AndroidDocumentError,
  readAndroidMarkdownDocument,
  writeAndroidMarkdownDocument,
  type AndroidReadOptions,
  type AndroidWriteOptions,
  type OpenedAndroidDocument,
  type SavedAndroidDocument,
} from '../../lib/androidDocuments'
import {
  createCloudDocument,
  readCloudDocument,
  writeCloudDocument,
  type CloudDocumentContent,
  type CloudProviderId,
} from '../../lib/cloudDocuments'
import { buildCloudSourceUri, parseCloudSourceUri } from '../../lib/cloudDocumentUris'
import { prepareLocalImagesForCommit } from './githubImages'
import { isRepositorySourceUri } from '../../lib/localRepositoryUris'
import {
  readRepositoryMarkdownDocument,
  writeRepositoryMarkdownDocument,
} from '../local-repos/repositoryDocumentBridge'

/**
 * GitHub saves are commits: autosave must never write them, only an
 * explicit commit with a message. Other sources save on every autosave.
 */
export function requiresExplicitCommit(sourceUri: string | null | undefined) {
  return parseCloudSourceUri(sourceUri)?.provider === 'github'
}

/**
 * Last-seen version tag (OneDrive ETag / Drive headRevisionId / GitHub blob sha) per cloud
 * source URI, for conflict-checked writes. Process-lifetime is enough: a
 * document is always read before it can be saved, and a fresh read
 * refreshes the entry.
 */
const cloudDocumentETags = new Map<string, string>()

/**
 * The text each cloud document had when last read or saved, so "show
 * changes" can diff the editor against it. Process-lifetime like the tags.
 */
const cloudDocumentBaselines = new Map<string, string>()

export function getCloudDocumentBaseline(sourceUri: string): string | null {
  return cloudDocumentBaselines.get(sourceUri) ?? null
}

const CLOUD_PROVIDER_LABELS: Record<CloudProviderId, string> = {
  onedrive: 'OneDrive',
  googledrive: 'Google Drive',
  github: 'GitHub',
  webdav: 'WebDAV',
}

export function toOpenedDocumentFromCloud(
  provider: CloudProviderId,
  content: CloudDocumentContent,
): OpenedAndroidDocument {
  const sourceUri = buildCloudSourceUri(provider, content.fileId)
  cloudDocumentETags.set(sourceUri, content.eTag)
  cloudDocumentBaselines.set(sourceUri, content.markdown)
  return {
    canceled: false,
    sourceUri,
    displayName: content.displayName,
    providerName: content.providerName,
    pathHint: content.pathHint ?? content.displayName,
    mimeType: 'text/markdown',
    markdown: content.markdown,
    encoding: content.encoding,
    hasEncodingBom: content.hasEncodingBom,
    canWrite: content.canWrite,
    // Token-backed access survives restarts the way a persisted SAF grant
    // does, which is what downstream `persisted` consumers actually gate on.
    persisted: true,
  }
}

/**
 * Save-as: creates a new provider document and adopts it into the
 * opened-document shape. The returned document's version tag is seeded
 * into this module's map, so the very first autosave writes with the
 * created ETag instead of failing closed.
 */
export async function createRoutedCloudDocument(
  provider: CloudProviderId,
  parentId: string | null,
  name: string,
  markdown: string,
  options: { encoding: OpenedAndroidDocument['encoding']; writeBom: boolean },
): Promise<OpenedAndroidDocument> {
  const created = await createCloudDocument(provider, {
    parentId: parentId ?? undefined,
    name,
    markdown,
    encoding: options.encoding,
    writeBom: options.writeBom,
  })
  const sourceUri = buildCloudSourceUri(provider, created.fileId)
  cloudDocumentETags.set(sourceUri, created.eTag)
  cloudDocumentBaselines.set(sourceUri, markdown)
  return {
    canceled: false,
    sourceUri,
    displayName: created.displayName,
    providerName: CLOUD_PROVIDER_LABELS[provider],
    pathHint: created.displayName,
    mimeType: 'text/markdown',
    markdown,
    encoding: options.encoding,
    hasEncodingBom: options.writeBom,
    canWrite: true,
    persisted: true,
  }
}

/**
 * Drop-in replacements for the SAF read/write functions: `cloud:` source
 * URIs route to the cloud plugin, everything else passes through. Injected
 * where App.vue previously injected the SAF functions, so the entire
 * document pipeline (autosave, recovery, recents) stays unchanged.
 */
export async function readRoutedMarkdownDocument(
  sourceUri: string,
  options: AndroidReadOptions = {},
): Promise<OpenedAndroidDocument> {
  if (isRepositorySourceUri(sourceUri)) {
    return readRepositoryMarkdownDocument(sourceUri, options)
  }
  const cloud = parseCloudSourceUri(sourceUri)
  if (!cloud) {
    return readAndroidMarkdownDocument(sourceUri, options)
  }

  const content = await readCloudDocument(cloud.provider, cloud.fileId, options)
  return toOpenedDocumentFromCloud(cloud.provider, content)
}

export async function writeRoutedMarkdownDocument(
  sourceUri: string,
  markdown: string,
  options: AndroidWriteOptions,
): Promise<SavedAndroidDocument> {
  if (isRepositorySourceUri(sourceUri)) {
    return writeRepositoryMarkdownDocument(sourceUri, markdown, options)
  }
  const cloud = parseCloudSourceUri(sourceUri)
  if (!cloud) {
    return writeAndroidMarkdownDocument(sourceUri, markdown, options)
  }

  // Fail closed without a baseline ETag (for example after a recovery
  // reload dropped this module's state): an ETag-less PUT would silently
  // overwrite whatever changed remotely in the meantime. The save-failed
  // path keeps the content in a recovery draft; reopening re-reads the
  // document and re-establishes the ETag.
  const eTag = cloudDocumentETags.get(sourceUri)
  if (!eTag) {
    throw new AndroidDocumentError(
      'CLOUD_DOCUMENT_CONFLICT',
      'This document must be reopened before it can be saved to the cloud',
    )
  }

  // GitHub commits carry device-inserted images along, as repo files.
  const prepared = cloud.provider === 'github'
    ? prepareLocalImagesForCommit(markdown, cloud.fileId)
    : { markdown, images: [] }
  const written = await writeCloudDocument(cloud.provider, cloud.fileId, prepared.markdown, {
    encoding: options.encoding,
    writeBom: options.writeBom,
    eTag,
    commitMessage: options.commitMessage,
    ...(prepared.images.length ? { localImages: prepared.images } : {}),
  })
  cloudDocumentETags.set(sourceUri, written.eTag)
  cloudDocumentBaselines.set(sourceUri, markdown)
  return {
    sourceUri,
    displayName: written.displayName,
    providerName: CLOUD_PROVIDER_LABELS[cloud.provider],
    pathHint: written.displayName,
    mimeType: 'text/markdown',
    encoding: options.encoding,
    canWrite: true,
    persisted: true,
  }
}

export interface NewBranchCommitResult {
  /** The document on the new branch: the editor continues there. */
  document: OpenedAndroidDocument
  pullRequest: { number: number; url: string } | null
  /** The commit landed but GitHub did not open the pull request. */
  pullRequestError: string | null
}

/**
 * Commits a GitHub document to a new branch (created from its current
 * branch) and optionally opens a pull request back. The result adopts the
 * branch copy into the opened-document shape with its version tag seeded,
 * like a save-as, so the next commit continues on the new branch.
 */
export async function commitRoutedCloudDocumentToNewBranch(
  sourceUri: string,
  markdown: string,
  options: {
    encoding: OpenedAndroidDocument['encoding']
    writeBom: boolean
    commitMessage: string
    branch: string
    openPullRequest: boolean
  },
): Promise<NewBranchCommitResult> {
  const cloud = parseCloudSourceUri(sourceUri)
  if (cloud?.provider !== 'github') {
    throw new AndroidDocumentError(
      'CLOUD_OPERATION_UNSUPPORTED',
      'Only GitHub documents can be committed to a new branch',
    )
  }
  const eTag = cloudDocumentETags.get(sourceUri)
  if (!eTag) {
    throw new AndroidDocumentError(
      'CLOUD_DOCUMENT_CONFLICT',
      'This document must be reopened before it can be saved to the cloud',
    )
  }

  const prepared = prepareLocalImagesForCommit(markdown, cloud.fileId)
  const written = await writeCloudDocument('github', cloud.fileId, prepared.markdown, {
    encoding: options.encoding,
    writeBom: options.writeBom,
    eTag,
    commitMessage: options.commitMessage,
    newBranch: options.branch,
    openPullRequest: options.openPullRequest,
    localImages: prepared.images,
  })

  const branchSourceUri = buildCloudSourceUri('github', written.fileId)
  cloudDocumentETags.set(branchSourceUri, written.eTag)
  cloudDocumentBaselines.set(branchSourceUri, markdown)
  const [owner, repo] = written.fileId.split('/')
  const path = cloud.fileId.split('/')[3]
  return {
    document: {
      canceled: false,
      sourceUri: branchSourceUri,
      displayName: written.displayName,
      providerName: CLOUD_PROVIDER_LABELS.github,
      pathHint: `${owner}/${repo}@${options.branch}/${safeDecode(path)}`,
      mimeType: 'text/markdown',
      markdown,
      encoding: options.encoding,
      hasEncodingBom: options.writeBom,
      canWrite: true,
      persisted: true,
    },
    pullRequest: written.pullRequestUrl
      ? { number: written.pullRequestNumber ?? 0, url: written.pullRequestUrl }
      : null,
    pullRequestError: written.pullRequestError ?? null,
  }
}

function safeDecode(value: string) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}
