import { isCloudSourceUri } from './cloudDocumentUris'

/**
 * Documents inside a Git working copy on the device ride the same
 * android-document pipeline as SAF and cloud documents, keyed by a
 * synthetic `repo:<repository id>:<path>` source URI. Repository ids never
 * contain ':'; the path is everything after the second ':'.
 */
const REPOSITORY_URI_PREFIX = 'repo:'

export function buildRepositorySourceUri(repositoryId: string, path: string) {
  return `${REPOSITORY_URI_PREFIX}${repositoryId}:${path}`
}

export function isRepositorySourceUri(sourceUri: string | null | undefined): sourceUri is string {
  return typeof sourceUri === 'string' && sourceUri.startsWith(REPOSITORY_URI_PREFIX)
}

export function parseRepositorySourceUri(
  sourceUri: string | null | undefined,
): { repositoryId: string; path: string } | null {
  if (!isRepositorySourceUri(sourceUri)) {
    return null
  }
  const rest = sourceUri.slice(REPOSITORY_URI_PREFIX.length)
  const separator = rest.indexOf(':')
  if (separator <= 0 || separator === rest.length - 1) {
    return null
  }
  return { repositoryId: rest.slice(0, separator), path: rest.slice(separator + 1) }
}

/**
 * Source URIs served by an app plugin rather than an Android document
 * provider: they hold no SAF grant to maintain or release.
 */
export function isAppRoutedSourceUri(sourceUri: string | null | undefined): sourceUri is string {
  return isCloudSourceUri(sourceUri) || isRepositorySourceUri(sourceUri)
}
