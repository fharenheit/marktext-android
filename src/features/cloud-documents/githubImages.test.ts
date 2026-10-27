import { describe, expect, it } from 'vitest'
import { resolveGitHubImageSource } from './githubImages'

const ORIGIN = 'https://localhost'
const DOCUMENT = 'octocat/hello/feature%2Fx/docs%2Fguide%2FREADME.md'
const PREFIX = `${ORIGIN}/_marktext_github_/octocat/hello/feature%2Fx/`

describe('resolveGitHubImageSource', () => {
  it('resolves relative paths against the document folder', () => {
    expect(resolveGitHubImageSource('img/a.png', DOCUMENT, ORIGIN)).toBe(
      `${PREFIX}docs%2Fguide%2Fimg%2Fa.png`,
    )
    expect(resolveGitHubImageSource('./a.png', DOCUMENT, ORIGIN)).toBe(`${PREFIX}docs%2Fguide%2Fa.png`)
    expect(resolveGitHubImageSource('../assets/b.svg', DOCUMENT, ORIGIN)).toBe(
      `${PREFIX}docs%2Fassets%2Fb.svg`,
    )
  })

  it('resolves rooted paths against the repository root', () => {
    expect(resolveGitHubImageSource('/assets/logo.png', DOCUMENT, ORIGIN)).toBe(
      `${PREFIX}assets%2Flogo.png`,
    )
  })

  it('decodes encoded names and drops queries and fragments', () => {
    expect(resolveGitHubImageSource('my%20shot.png?raw=true#x', DOCUMENT, ORIGIN)).toBe(
      `${PREFIX}docs%2Fguide%2Fmy%20shot.png`,
    )
  })

  it('leaves absolute, data, app-local and escaping sources alone', () => {
    expect(resolveGitHubImageSource('https://example.com/a.png', DOCUMENT, ORIGIN)).toBeNull()
    expect(resolveGitHubImageSource('data:image/png;base64,AAAA', DOCUMENT, ORIGIN)).toBeNull()
    expect(resolveGitHubImageSource('marktext-image://local/a.png', DOCUMENT, ORIGIN)).toBeNull()
    expect(resolveGitHubImageSource('//cdn.example.com/a.png', DOCUMENT, ORIGIN)).toBeNull()
    expect(resolveGitHubImageSource('../../../a.png', DOCUMENT, ORIGIN)).toBeNull()
    expect(resolveGitHubImageSource('', DOCUMENT, ORIGIN)).toBeNull()
  })

  it('needs a file-level document id', () => {
    expect(resolveGitHubImageSource('a.png', 'octocat/hello/main', ORIGIN)).toBeNull()
  })
})
