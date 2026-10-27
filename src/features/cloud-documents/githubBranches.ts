/**
 * Mirrors the plugin's conservative ref-name rules (GitHubClient
 * .isValidBranchName) so the commit sheet can explain a bad name before
 * anything is sent.
 */
export function isValidBranchName(name: string) {
  if (name.length === 0 || name.length > 100 || !/^[A-Za-z0-9._/-]+$/.test(name)) {
    return false
  }
  if (name.includes('..') || name.startsWith('-') || name.endsWith('.')) {
    return false
  }
  return name
    .split('/')
    .every(segment => segment.length > 0 && !segment.startsWith('.') && !segment.endsWith('.lock'))
}

/** `marktext/<document-slug>-<yyyymmdd-hhmm>`, valid by construction. */
export function suggestBranchName(displayName: string, now = new Date()) {
  const slug = displayName
    .replace(/\.[^.]+$/, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
  const pad = (value: number) => String(value).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`
  return `marktext/${slug || 'edit'}-${stamp}`
}
