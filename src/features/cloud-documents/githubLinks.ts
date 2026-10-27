/**
 * A GitHub file link split into what the plugin needs to resolve it. The
 * ref/path boundary is NOT decided here: branch names may contain slashes,
 * so `segments` carries `<ref>/<path>` whole and the plugin resolves the
 * split against GitHub.
 */
export interface GitHubDocumentLink {
  owner: string
  repo: string
  /** Decoded path segments after `blob/` (ref segments, then path segments). */
  segments: string[]
}

const NAME_REGEXP = /^[A-Za-z0-9._-]+$/
const GITHUB_HOSTS = new Set(['github.com', 'www.github.com', 'm.github.com'])

/**
 * Accepts github.com `/blob/` and `/raw/` file links and
 * raw.githubusercontent.com links; anything else (folders, issues, other
 * hosts) returns null.
 */
export function parseGitHubDocumentLink(value: string): GitHubDocumentLink | null {
  let url: URL
  try {
    url = new URL(value.trim())
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') {
    return null
  }

  const host = url.hostname.toLowerCase()
  const parts = url.pathname.split('/').filter(part => part.length > 0)
  let owner: string | undefined
  let repo: string | undefined
  let tail: string[]
  if (GITHUB_HOSTS.has(host)) {
    if (parts[2] !== 'blob' && parts[2] !== 'raw') {
      return null
    }
    ;[owner, repo] = parts
    tail = parts.slice(3)
  } else if (host === 'raw.githubusercontent.com') {
    ;[owner, repo] = parts
    tail = parts.slice(2)
    // Newer raw links spell the branch as refs/heads/<name>.
    if (tail[0] === 'refs' && tail[1] === 'heads') {
      tail = tail.slice(2)
    }
  } else {
    return null
  }

  if (!owner || !repo || !isValidName(owner) || !isValidName(repo) || tail.length < 2) {
    return null
  }

  const segments: string[] = []
  for (const part of tail) {
    let decoded: string
    try {
      decoded = decodeURIComponent(part)
    } catch {
      return null
    }
    if (decoded === '.' || decoded === '..' || decoded.includes('/')) {
      return null
    }
    segments.push(decoded)
  }
  return { owner, repo, segments }
}

/**
 * Recognizes a share that is just a GitHub file link: the link alone, or a
 * title line followed by it (what browsers and the GitHub app send). Longer
 * text that merely mentions a link stays a text share.
 */
export function findSharedGitHubLink(text: string): GitHubDocumentLink | null {
  const lines = text
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line.length > 0)
  if (lines.length === 0 || lines.length > 2) {
    return null
  }
  const lastLine = lines[lines.length - 1]
  const tokens = lastLine.split(/\s+/)
  return parseGitHubDocumentLink(cleanSharedLinkToken(tokens[tokens.length - 1]))
}

/**
 * Drops what prose wraps around a pasted link: surrounding <…>, and
 * trailing sentence punctuation or closing brackets ("see <url>.", "(url)").
 */
function cleanSharedLinkToken(token: string) {
  return token.replace(/^[<(["']+/, '').replace(/[.,;:!?)\]>}"']+$/, '')
}

function isValidName(value: string) {
  return NAME_REGEXP.test(value) && value !== '.' && value !== '..'
}
