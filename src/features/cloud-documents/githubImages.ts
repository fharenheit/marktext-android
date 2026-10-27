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

/** Images inserted on the device live in app storage under these names. */
const LOCAL_IMAGE_REFERENCE_REGEXP = /marktext-image:\/\/local\/([^/?#\s"'<>()[\]]+)/gi
const LOCAL_IMAGE_NAME_REGEXP = /^[A-Za-z0-9_-][A-Za-z0-9._-]*$/
/** Folder, next to the document, that committed device images go into. */
export const COMMITTED_IMAGE_FOLDER = 'images'

export interface CommittedLocalImage {
  /** File name in the app's imported-image storage. */
  name: string
  /** Repository path the image is committed to. */
  path: string
}

/**
 * Prepares a GitHub commit of a document that references images inserted
 * on this device: each `marktext-image://local/<name>` becomes the relative
 * `images/<name>` the repository will hold, and the images to add in the
 * same commit are listed. The editor keeps its app-local references, so
 * committing again re-adds identical blobs, which leaves the tree unchanged.
 */
export function prepareLocalImagesForCommit(
  markdown: string,
  documentFileId: string,
): { markdown: string; images: CommittedLocalImage[] } {
  const idParts = documentFileId.split('/')
  if (idParts.length !== 4) {
    return { markdown, images: [] }
  }
  let documentPath: string
  try {
    documentPath = decodeURIComponent(idParts[3])
  } catch {
    return { markdown, images: [] }
  }
  const folder = documentPath.split('/').slice(0, -1)

  const images = new Map<string, CommittedLocalImage>()
  const rewritten = markdown.replace(LOCAL_IMAGE_REFERENCE_REGEXP, (reference, encodedName: string) => {
    let name: string
    try {
      name = decodeURIComponent(encodedName)
    } catch {
      return reference
    }
    if (!LOCAL_IMAGE_NAME_REGEXP.test(name)) {
      return reference
    }
    images.set(name, { name, path: [...folder, COMMITTED_IMAGE_FOLDER, name].join('/') })
    return `${COMMITTED_IMAGE_FOLDER}/${name}`
  })
  return { markdown: rewritten, images: [...images.values()] }
}
