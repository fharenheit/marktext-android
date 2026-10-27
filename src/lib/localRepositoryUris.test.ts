import { describe, expect, it } from 'vitest'
import {
  buildRepositorySourceUri,
  isAppRoutedSourceUri,
  isRepositorySourceUri,
  parseRepositorySourceUri,
} from './localRepositoryUris'

describe('localRepositoryUris', () => {
  it('round-trips a repository document, colons in the path included', () => {
    const uri = buildRepositorySourceUri('octocat-hello', 'docs/a:b.md')

    expect(uri).toBe('repo:octocat-hello:docs/a:b.md')
    expect(isRepositorySourceUri(uri)).toBe(true)
    expect(parseRepositorySourceUri(uri)).toEqual({ repositoryId: 'octocat-hello', path: 'docs/a:b.md' })
  })

  it('rejects malformed and foreign URIs', () => {
    expect(parseRepositorySourceUri('repo:only-id')).toBeNull()
    expect(parseRepositorySourceUri('repo::path.md')).toBeNull()
    expect(parseRepositorySourceUri('repo:id:')).toBeNull()
    expect(parseRepositorySourceUri('content://x/y')).toBeNull()
    expect(parseRepositorySourceUri(null)).toBeNull()
  })

  it('treats repository and cloud documents as app-routed, SAF documents not', () => {
    expect(isAppRoutedSourceUri('repo:a:b.md')).toBe(true)
    expect(isAppRoutedSourceUri('cloud:github:o/r/main/a.md')).toBe(true)
    expect(isAppRoutedSourceUri('content://provider/doc')).toBe(false)
    expect(isAppRoutedSourceUri(null)).toBe(false)
  })
})
