import { describe, expect, it } from 'vitest'
import { findSharedGitHubLink, parseGitHubDocumentLink } from './githubLinks'

describe('parseGitHubDocumentLink', () => {
  it('splits blob links into owner, repo and the undecided ref/path tail', () => {
    expect(
      parseGitHubDocumentLink('https://github.com/octocat/hello/blob/feature/x/docs/my%20notes.md?plain=1#L3'),
    ).toEqual({
      owner: 'octocat',
      repo: 'hello',
      segments: ['feature', 'x', 'docs', 'my notes.md'],
    })
  })

  it('accepts raw links on github.com and raw.githubusercontent.com', () => {
    expect(parseGitHubDocumentLink('https://github.com/o/r/raw/main/a.md')?.segments).toEqual([
      'main',
      'a.md',
    ])
    expect(parseGitHubDocumentLink('https://raw.githubusercontent.com/o/r/main/a.md')).toEqual({
      owner: 'o',
      repo: 'r',
      segments: ['main', 'a.md'],
    })
    expect(
      parseGitHubDocumentLink('https://raw.githubusercontent.com/o/r/refs/heads/dev/a.md')?.segments,
    ).toEqual(['dev', 'a.md'])
  })

  it('rejects non-file links, other hosts and unsafe segments', () => {
    expect(parseGitHubDocumentLink('https://github.com/o/r')).toBeNull()
    expect(parseGitHubDocumentLink('https://github.com/o/r/tree/main/docs')).toBeNull()
    expect(parseGitHubDocumentLink('https://github.com/o/r/blob/main')).toBeNull()
    expect(parseGitHubDocumentLink('https://gitlab.com/o/r/blob/main/a.md')).toBeNull()
    expect(parseGitHubDocumentLink('https://github.com/o/r/blob/main/%2E%2E/a.md')).toBeNull()
    expect(parseGitHubDocumentLink('https://github.com/o/r/blob/main/a%2Fb.md')).toBeNull()
    expect(parseGitHubDocumentLink('https://github.com/o/r/blob/main/%zz.md')).toBeNull()
    expect(parseGitHubDocumentLink('ftp://github.com/o/r/blob/main/a.md')).toBeNull()
    expect(parseGitHubDocumentLink('not a url')).toBeNull()
  })
})

describe('findSharedGitHubLink', () => {
  const link = 'https://github.com/o/r/blob/main/README.md'

  it('recognizes a bare link and a title line plus link', () => {
    expect(findSharedGitHubLink(`  ${link}\n`)?.repo).toBe('r')
    expect(findSharedGitHubLink(`r/README.md at main · o/r\n${link}`)?.segments).toEqual([
      'main',
      'README.md',
    ])
    expect(findSharedGitHubLink(`Check this ${link}`)?.owner).toBe('o')
  })

  it('strips surrounding <> and trailing punctuation from the link', () => {
    expect(findSharedGitHubLink(`<${link}>`)?.segments).toEqual(['main', 'README.md'])
    expect(findSharedGitHubLink(`Read this: ${link}.`)?.segments).toEqual(['main', 'README.md'])
    expect(findSharedGitHubLink(`(see ${link})`)?.segments).toEqual(['main', 'README.md'])
    expect(findSharedGitHubLink(`Look at <${link}>!?`)?.segments).toEqual(['main', 'README.md'])
    expect(findSharedGitHubLink(`${link};`)?.repo).toBe('r')
  })

  it('leaves longer text and non-file links as text shares', () => {
    expect(findSharedGitHubLink(`# Notes\n\nSee ${link}\nfor details`)).toBeNull()
    expect(findSharedGitHubLink(`${link} is the spec`)).toBeNull()
    expect(findSharedGitHubLink('https://github.com/o/r')).toBeNull()
    expect(findSharedGitHubLink('')).toBeNull()
  })
})
