import { beforeEach, describe, expect, it, vi } from 'vitest'

const plugin = {
  readRepositoryDocument: vi.fn(),
  writeRepositoryDocument: vi.fn(),
  getRepositoryFileDiff: vi.fn(),
  discardRepositoryChanges: vi.fn(),
  pullRepository: vi.fn(),
}

vi.mock('../../lib/localRepositories', () => ({
  callRepository: (operation: (value: typeof plugin) => unknown) => operation(plugin),
}))

vi.mock('@capacitor/core', () => ({
  Capacitor: { convertFileSrc: (uri: string) => uri.replace('file://', 'https://localhost/_capacitor_file_') },
}))

import {
  getRepositoryDocumentChanges,
  pullRepositoryDocumentForMerge,
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

  describe('pullRepositoryDocumentForMerge', () => {
    function diffs(...sides: Array<{ oldText: string | null; newText: string | null }>) {
      for (const side of sides) {
        plugin.getRepositoryFileDiff.mockResolvedValueOnce({ ...side, binary: false, tooLarge: false })
      }
    }

    it('sets the edits aside, pulls and returns base, ours and theirs', async () => {
      diffs({ oldText: 'base', newText: 'mine' }, { oldText: 'theirs', newText: 'theirs' })
      plugin.pullRepository.mockResolvedValueOnce({ result: 'fast-forward', conflicts: [], status: {} })

      const sides = await pullRepositoryDocumentForMerge(SOURCE, { encoding: 'utf16le', writeBom: true })

      expect(sides).toEqual({ base: 'base', ours: 'mine', theirs: 'theirs' })
      expect(plugin.discardRepositoryChanges).toHaveBeenCalledWith({ id: 'octocat-notes', paths: ['docs/guide.md'] })
      expect(plugin.pullRepository).toHaveBeenCalledWith({ id: 'octocat-notes' })
      expect(plugin.readRepositoryDocument).not.toHaveBeenCalled()
      expect(plugin.writeRepositoryDocument).not.toHaveBeenCalled()
    })

    it('writes the edits back with their encoding and BOM when the pull fails', async () => {
      diffs({ oldText: 'base', newText: 'mine' })
      const failure = new Error('network down')
      plugin.pullRepository.mockRejectedValueOnce(failure)

      await expect(pullRepositoryDocumentForMerge(SOURCE, { encoding: 'utf16le', writeBom: true })).rejects.toBe(failure)

      expect(plugin.writeRepositoryDocument).toHaveBeenCalledWith({
        id: 'octocat-notes',
        path: 'docs/guide.md',
        markdown: 'mine',
        encoding: 'utf16le',
        writeBom: true,
      })
    })

    it('learns the encoding from the file when the caller does not pass it', async () => {
      diffs({ oldText: 'base', newText: 'mine' })
      plugin.readRepositoryDocument.mockResolvedValueOnce({
        path: 'docs/guide.md',
        displayName: 'guide.md',
        repositoryName: 'octocat/notes',
        workTreeUri: 'file:///data/files/repositories/octocat-notes',
        markdown: 'mine',
        encoding: 'utf8',
        hasEncodingBom: true,
      })
      plugin.pullRepository.mockRejectedValueOnce(new Error('network down'))

      await expect(pullRepositoryDocumentForMerge(SOURCE)).rejects.toThrow('network down')

      expect(plugin.writeRepositoryDocument).toHaveBeenCalledWith(
        expect.objectContaining({ markdown: 'mine', encoding: 'utf8', writeBom: true }),
      )
    })

    it('keeps the pull error when the restore fails too', async () => {
      diffs({ oldText: 'base', newText: 'mine' })
      const failure = new Error('pull refused')
      plugin.pullRepository.mockRejectedValueOnce(failure)
      plugin.writeRepositoryDocument.mockRejectedValueOnce(new Error('disk full'))

      await expect(pullRepositoryDocumentForMerge(SOURCE, { encoding: 'utf8', writeBom: false })).rejects.toBe(failure)
      expect(plugin.writeRepositoryDocument).toHaveBeenCalledTimes(1)
    })

    it('neither sets aside nor restores an unedited document', async () => {
      diffs({ oldText: 'same', newText: 'same' })
      const failure = new Error('pull refused')
      plugin.pullRepository.mockRejectedValueOnce(failure)

      await expect(pullRepositoryDocumentForMerge(SOURCE)).rejects.toBe(failure)
      expect(plugin.discardRepositoryChanges).not.toHaveBeenCalled()
      expect(plugin.writeRepositoryDocument).not.toHaveBeenCalled()
    })
  })
})
