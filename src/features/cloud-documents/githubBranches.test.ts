import { describe, expect, it } from 'vitest'
import { isValidBranchName, suggestBranchName } from './githubBranches'

describe('githubBranches', () => {
  it('accepts plain names and rejects unsafe ref names', () => {
    expect(isValidBranchName('marktext/update-readme-20261027-0130')).toBe(true)
    for (const name of ['', 'has space', 'a..b', '/lead', 'trail/', 'a//b', '-dash', 'x.lock', 'a/.hidden', 'end.', 'a~1', '한글']) {
      expect(isValidBranchName(name), name).toBe(false)
    }
  })

  it('suggests a valid, dated branch from the document name', () => {
    const name = suggestBranchName('Release Notes (v2).md', new Date(2026, 9, 27, 1, 5))
    expect(name).toBe('marktext/release-notes-v2-20261027-0105')
    expect(isValidBranchName(name)).toBe(true)
    expect(suggestBranchName('한글 문서.md', new Date(2026, 0, 2, 3, 4))).toBe('marktext/edit-20260102-0304')
  })
})
