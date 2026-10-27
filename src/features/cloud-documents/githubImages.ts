/** Path prefix the native GitHub image endpoint answers on the app origin. */
export const GITHUB_IMAGE_PATH_PREFIX = '/_marktext_github_/'

const URL_SCHEME_REGEXP = /^[a-z][a-z0-9+.-]*:/i

/**
 * Maps an image source of a GitHub document to the native image endpoint:
 * relative paths resolve against the document's folder, `/`-rooted paths
 * against the repository root (as GitHub renders them). Sources with a
 * scheme (https:, data:, marktext-image:) and paths escaping the
 * repository return null and keep their default handling.
 *
 * @param documentFileId the document's GitHub id: `owner/repo/<ref>/<path>`
 *   with ref and path each percent-encoded as one segment
 */
export function resolveGitHubImageSource(
  source: string,
  documentFileId: string,
  origin: string,
): string | null {
  const idParts = documentFileId.split('/')
  if (idParts.length !== 4) {
    return null
  }
  const [owner, repo, encodedRef, encodedPath] = idParts

  const trimmed = source.trim()
  if (
    trimmed.length === 0
    || URL_SCHEME_REGEXP.test(trimmed)
    || trimmed.startsWith('//')
    || trimmed.startsWith('#')
  ) {
    return null
  }

  const rawPath = trimmed.split(/[?#]/)[0]
  let imagePath: string
  let documentPath: string
  try {
    imagePath = decodeURIComponent(rawPath)
    documentPath = decodeURIComponent(encodedPath)
  } catch {
    return null
  }

  const segments = imagePath.startsWith('/') ? [] : documentPath.split('/').slice(0, -1)
  for (const segment of imagePath.split('/')) {
    if (segment === '' || segment === '.') {
      continue
    }
    if (segment === '..') {
      if (segments.length === 0) {
        return null
      }
      segments.pop()
      continue
    }
    segments.push(segment)
  }
  if (segments.length === 0) {
    return null
  }

  return `${origin}${GITHUB_IMAGE_PATH_PREFIX}${owner}/${repo}/${encodedRef}/${encodeURIComponent(segments.join('/'))}`
}
