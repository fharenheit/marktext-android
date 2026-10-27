import { describe, expect, it } from 'vitest'
import { buildCloudSourceUri } from '../../lib/cloudDocumentUris'
import { buildRepositorySourceUri } from '../../lib/localRepositoryUris'
import {
  fileSwitcherLocationFor,
  githubIdPath,
  githubPathId,
  isOpenableFileName,
  parentFolder,
  sortFileSwitcherEntries,
} from './fileSwitcher'

describe('fileSwitcher', () => {
  it('starts a working-copy document in its own folder', () => {
    expect(fileSwitcherLocationFor(buildRepositorySourceUri('notes', 'docs/guide/a.md'))).toEqual({
      kind: 'repository',
      repositoryId: 'notes',
      folder: 'docs/guide',
      currentPath: 'docs/guide/a.md',
    })
    expect(fileSwitcherLocationFor(buildRepositorySourceUri('notes', 'README.md'))).toMatchObject({ folder: '' })
  })

  it('starts a GitHub document in its folder on the same branch', () => {
    const fileId = `octo/notes/${encodeURIComponent('feature/x')}/${encodeURIComponent('docs/My File.md')}`
    expect(fileSwitcherLocationFor(buildCloudSourceUri('github', fileId))).toEqual({
      kind: 'github',
      branchId: `octo/notes/${encodeURIComponent('feature/x')}`,
      folder: 'docs',
      currentPath: 'docs/My File.md',
    })
  })

  it('offers nothing for documents outside a repository', () => {
    expect(fileSwitcherLocationFor(null)).toBeNull()
    expect(fileSwitcherLocationFor('content://com.android/doc/1')).toBeNull()
    expect(fileSwitcherLocationFor(buildCloudSourceUri('onedrive', 'abc'))).toBeNull()
  })

  it('round-trips GitHub ids and folder paths', () => {
    const branch = 'octo/notes/main'
    expect(githubPathId(branch, '')).toBe(branch)
    expect(githubIdPath(githubPathId(branch, 'docs/a b/c.md'))).toBe('docs/a b/c.md')
    expect(githubIdPath(branch)).toBe('')
    expect(parentFolder('docs/a/c.md')).toBe('docs/a')
    expect(parentFolder('c.md')).toBe('')
  })

  it('opens Markdown and text files and lists folders first', () => {
    expect(isOpenableFileName('Guide.MD')).toBe(true)
    expect(isOpenableFileName('notes.txt')).toBe(true)
    expect(isOpenableFileName('logo.png')).toBe(false)
    const entry = (name: string, isFolder: boolean) => ({ name, path: name, isFolder, openable: !isFolder })
    expect(sortFileSwitcherEntries([entry('b.md', false), entry('Zeta', true), entry('a.md', false), entry('alpha', true)])
      .map(item => item.name)).toEqual(['alpha', 'Zeta', 'a.md', 'b.md'])
  })
})
