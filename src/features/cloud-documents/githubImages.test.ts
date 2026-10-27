import { describe, expect, it } from 'vitest'
import { prepareLocalImagesForCommit, resolveGitHubImageSource } from './githubImages'

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

describe('prepareLocalImagesForCommit', () => {
  it('rewrites device images to the images folder next to the document', () => {
    const markdown = [
      '![a](marktext-image://local/1-abc-shot.png)',
      '![again](marktext-image://local/1-abc-shot.png)',
      '<img src="marktext-image://local/2-def-photo.jpeg">',
      '![remote](https://example.com/x.png)',
    ].join('\n')

    const prepared = prepareLocalImagesForCommit(markdown, DOCUMENT)

    expect(prepared.markdown).toBe([
      '![a](images/1-abc-shot.png)',
      '![again](images/1-abc-shot.png)',
      '<img src="images/2-def-photo.jpeg">',
      '![remote](https://example.com/x.png)',
    ].join('\n'))
    expect(prepared.images).toEqual([
      { name: '1-abc-shot.png', path: 'docs/guide/images/1-abc-shot.png' },
      { name: '2-def-photo.jpeg', path: 'docs/guide/images/2-def-photo.jpeg' },
    ])
  })

  it('uses the repository root folder for root documents and skips odd names', () => {
    const prepared = prepareLocalImagesForCommit(
      '![a](marktext-image://local/ok.png) ![b](marktext-image://local/..hidden.png)',
      'o/r/main/README.md',
    )

    expect(prepared.images).toEqual([{ name: 'ok.png', path: 'images/ok.png' }])
    expect(prepared.markdown).toBe('![a](images/ok.png) ![b](marktext-image://local/..hidden.png)')
  })

  it('leaves documents without device images untouched', () => {
    expect(prepareLocalImagesForCommit('# Plain', DOCUMENT)).toEqual({ markdown: '# Plain', images: [] })
  })
})
