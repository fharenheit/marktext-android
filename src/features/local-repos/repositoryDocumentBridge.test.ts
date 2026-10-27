import { beforeEach, describe, expect, it, vi } from 'vitest'

const plugin = {
  readRepositoryDocument: vi.fn(),
  writeRepositoryDocument: vi.fn(),
  getRepositoryFileDiff: vi.fn(),
  discardRepositoryChanges: vi.fn(),
}

vi.mock('../../lib/localRepositories', () => ({
  callRepository: (operation: (value: typeof plugin) => unknown) => operation(plugin),
}))

vi.mock('@capacitor/core', () => ({
  Capacitor: { convertFileSrc: (uri: string) => uri.replace('file://', 'https://localhost/_capacitor_file_') },
}))

import {
  getRepositoryDocumentChanges,
  readRepositoryMarkdownDocument,
  resolveRepositoryImageSource,
  revertRepositoryDocument,
  writeRepositoryMarkdownDocument,
} from './repositoryDocumentBridge'

const SOURCE = 'repo:octocat-notes:docs/guide.md'

beforeEach(() => {
  vi.clearAllMocks()
  plugin.readRepositoryDocument.mockResolvedValue({
    path: 'docs/guide.md',
    displayName: 'guide.md',
    repositoryName: 'octocat/notes',
    workTreeUri: 'file:///data/files/repositories/octocat-notes',
    markdown: '# Guide',
    encoding: 'utf8',
    hasEncodingBom: false,
  })
})

describe('repositoryDocumentBridge', () => {
  it('opens working-tree documents as writable, persisted documents', async () => {
    const opened = await readRepositoryMarkdownDocument(SOURCE, { defaultEncoding: 'utf8' })

    expect(plugin.readRepositoryDocument).toHaveBeenCalledWith({
      id: 'octocat-notes',
      path: 'docs/guide.md',
      defaultEncoding: 'utf8',
      autoDetectEncoding: undefined,
    })
    expect(opened).toMatchObject({
      sourceUri: SOURCE,
      displayName: 'guide.md',
      providerName: 'octocat/notes',
      pathHint: 'octocat/notes/docs/guide.md',
      markdown: '# Guide',
      canWrite: true,
      persisted: true,
    })
  })

  it('autosaves into the working tree, copying device images next to the document', async () => {
    plugin.writeRepositoryDocument.mockResolvedValue({ path: 'docs/guide.md', displayName: 'guide.md' })

    const saved = await writeRepositoryMarkdownDocument(
      SOURCE,
      '![shot](marktext-image://local/1-abc-shot.png)',
      { encoding: 'utf8', writeBom: false },
    )

    expect(plugin.writeRepositoryDocument).toHaveBeenCalledWith({
      id: 'octocat-notes',
      path: 'docs/guide.md',
      markdown: '![shot](images/1-abc-shot.png)',
      encoding: 'utf8',
      writeBom: false,
      localImages: [{ name: '1-abc-shot.png', path: 'docs/images/1-abc-shot.png' }],
    })
    expect(saved).toMatchObject({ sourceUri: SOURCE, canWrite: true })
  })

  it('reads the committed and current sides and reverts one document', async () => {
    plugin.getRepositoryFileDiff.mockResolvedValue({ oldText: 'old', newText: 'new', binary: false, tooLarge: false })

    expect(await getRepositoryDocumentChanges(SOURCE)).toEqual({ oldText: 'old', newText: 'new' })
    await revertRepositoryDocument(SOURCE)
    expect(plugin.discardRepositoryChanges).toHaveBeenCalledWith({ id: 'octocat-notes', paths: ['docs/guide.md'] })
  })

  it('maps relative images to working-tree files once the root is known', async () => {
    expect(resolveRepositoryImageSource('img/a b.png', 'repo:unknown-repo:a.md')).toBeNull()
    await readRepositoryMarkdownDocument(SOURCE)

    expect(resolveRepositoryImageSource('img/a b.png', SOURCE)).toBe(
      'https://localhost/_capacitor_file_/data/files/repositories/octocat-notes/docs/img/a%20b.png',
    )
    expect(resolveRepositoryImageSource('/logo.svg', SOURCE)).toBe(
      'https://localhost/_capacitor_file_/data/files/repositories/octocat-notes/logo.svg',
    )
    expect(resolveRepositoryImageSource('../../escape.png', SOURCE)).toBeNull()
    expect(resolveRepositoryImageSource('https://example.com/a.png', SOURCE)).toBeNull()
  })
})
