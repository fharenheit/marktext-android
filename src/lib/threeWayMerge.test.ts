import { describe, expect, it } from 'vitest'
import { threeWayMerge } from './threeWayMerge'

const BASE = '# Title\n\nalpha\n\nbeta\n\ngamma\n'

describe('threeWayMerge', () => {
  it('takes the only side that changed', () => {
    expect(threeWayMerge(BASE, BASE, 'x\n')).toEqual({ text: 'x\n', conflicts: 0 })
    expect(threeWayMerge(BASE, 'y\n', BASE)).toEqual({ text: 'y\n', conflicts: 0 })
    expect(threeWayMerge(BASE, 'same\n', 'same\n')).toEqual({ text: 'same\n', conflicts: 0 })
  })

  it('combines changes to separate parts of the document', () => {
    const ours = BASE.replace('alpha', 'alpha (mine)')
    const theirs = BASE.replace('gamma', 'gamma (theirs)') + '\nappended upstream\n'
    expect(threeWayMerge(BASE, ours, theirs)).toEqual({
      text: '# Title\n\nalpha (mine)\n\nbeta\n\ngamma (theirs)\n\nappended upstream\n',
      conflicts: 0,
    })
  })

  it('keeps identical changes made on both sides once', () => {
    const both = BASE.replace('beta', 'BETA')
    expect(threeWayMerge(BASE, both, both.replace('gamma', 'GAMMA'))).toEqual({
      text: BASE.replace('beta', 'BETA').replace('gamma', 'GAMMA'),
      conflicts: 0,
    })
  })

  it('marks overlapping different changes as a conflict', () => {
    const ours = BASE.replace('beta', 'beta mine')
    const theirs = BASE.replace('beta', 'beta theirs')
    const result = threeWayMerge(BASE, ours, theirs, { labels: { ours: 'mine', theirs: 'latest' } })
    expect(result.conflicts).toBe(1)
    expect(result.text).toBe(
      '# Title\n\nalpha\n\n<<<<<<< mine\nbeta mine\n=======\nbeta theirs\n>>>>>>> latest\n\ngamma\n',
    )
  })

  it('resolves conflicts for one side while still merging the rest', () => {
    const ours = BASE.replace('beta', 'beta mine').replace('alpha', 'alpha mine')
    const theirs = BASE.replace('beta', 'beta theirs').replace('gamma', 'gamma theirs')
    expect(threeWayMerge(BASE, ours, theirs, { strategy: 'ours' })).toEqual({
      text: '# Title\n\nalpha mine\n\nbeta mine\n\ngamma theirs\n',
      conflicts: 1,
    })
    expect(threeWayMerge(BASE, ours, theirs, { strategy: 'theirs' })).toEqual({
      text: '# Title\n\nalpha mine\n\nbeta theirs\n\ngamma theirs\n',
      conflicts: 1,
    })
  })

  it('treats insertions at the same spot with different text as a conflict', () => {
    const ours = BASE.replace('alpha\n', 'alpha\nmine added\n')
    const theirs = BASE.replace('alpha\n', 'alpha\ntheirs added\n')
    expect(threeWayMerge(BASE, ours, theirs, { strategy: 'theirs' }).conflicts).toBe(1)
  })

  it('merges a local deletion with an unrelated remote edit', () => {
    const ours = BASE.replace('alpha\n\n', '')
    const theirs = BASE.replace('gamma', 'gamma 2')
    expect(threeWayMerge(BASE, ours, theirs)).toEqual({
      text: '# Title\n\nbeta\n\ngamma 2\n',
      conflicts: 0,
    })
  })
})
