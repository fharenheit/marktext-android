import { describe, expect, it } from 'vitest'
import { diffFilePath, formatCommitDate, formatProgress } from './repositoryFormatting'

describe('repositoryFormatting', () => {
  it('formats progress with or without a known total', () => {
    expect(formatProgress({ id: 'r', task: 'Receiving objects', completed: 21, total: 50 })).toBe('Receiving objects 42%')
    expect(formatProgress({ id: 'r', task: 'Counting objects', completed: 7, total: 0 })).toBe('Counting objects 7')
    expect(formatProgress({ id: 'r', task: 'Updating references', completed: 0, total: 0 })).toBe('Updating references')
  })

  it('formats commit dates and keeps unparseable values', () => {
    expect(formatCommitDate('2026-10-27T02:00:00Z', 'en')).toMatch(/2026/)
    expect(formatCommitDate('not a date', 'en')).toBe('not a date')
  })

  it('names diff files by their new path, else the old one', () => {
    expect(diffFilePath({ newPath: 'b.md', oldPath: 'a.md' })).toBe('b.md')
    expect(diffFilePath({ newPath: null, oldPath: 'gone.md' })).toBe('gone.md')
    expect(diffFilePath({ path: 'x.md' })).toBe('x.md')
  })
})
