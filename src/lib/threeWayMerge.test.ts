import { describe, expect, it } from 'vitest'
import { createThreeWayMerger, threeWayMerge } from './threeWayMerge'

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

  it('keeps CRLF line endings, taken from theirs then base', () => {
    const base = BASE.replace(/\n/g, '\r\n')
    const ours = base.replace('alpha', 'alpha mine')
    const theirs = base.replace('gamma', 'gamma theirs')
    expect(threeWayMerge(base, ours, theirs).text).toBe(
      '# Title\r\n\r\nalpha mine\r\n\r\nbeta\r\n\r\ngamma theirs\r\n',
    )
    // An LF-only ours (an editor serialization) still merges into CRLF.
    expect(threeWayMerge(base, BASE.replace('alpha', 'alpha mine'), theirs).text).toBe(
      '# Title\r\n\r\nalpha mine\r\n\r\nbeta\r\n\r\ngamma theirs\r\n',
    )
  })

  it('handles empty base and empty sides', () => {
    expect(threeWayMerge('', 'mine\n', 'theirs\n', { strategy: 'ours' })).toEqual({ text: 'mine\n', conflicts: 1 })
    expect(threeWayMerge('', 'same\n', 'same\n')).toEqual({ text: 'same\n', conflicts: 0 })
    expect(threeWayMerge(BASE, '', BASE)).toEqual({ text: '', conflicts: 0 })
    expect(threeWayMerge(BASE, '', BASE.replace('gamma', 'gamma 2'), { strategy: 'theirs' }).text).toBe(
      BASE.replace('gamma', 'gamma 2'),
    )
  })

  it('takes the final newline from the side that changed it', () => {
    const ours = BASE.replace('alpha', 'alpha mine')
    const theirsWithout = BASE.replace('gamma', 'gamma 2').replace(/\n$/, '')
    expect(threeWayMerge(BASE, ours, theirsWithout).text).toBe(
      '# Title\n\nalpha mine\n\nbeta\n\ngamma 2',
    )
    const base = BASE.replace(/\n$/, '')
    expect(threeWayMerge(base, base.replace('alpha', 'alpha mine'), `${base.replace('gamma', 'gamma 2')}\n`).text).toBe(
      '# Title\n\nalpha mine\n\nbeta\n\ngamma 2\n',
    )
  })

  it('merges an identical change made on both sides without a conflict', () => {
    const both = BASE.replace('beta', 'beta both')
    expect(threeWayMerge(BASE, both.replace('alpha', 'alpha mine'), both)).toEqual({
      text: BASE.replace('beta', 'beta both').replace('alpha', 'alpha mine'),
      conflicts: 0,
    })
  })

  it('compares by lineKey and keeps theirs text for lines equal by key', () => {
    const lineKey = (line: string) => line.toLowerCase()
    const base = 'one\ntwo\nthree\n'
    const theirs = 'ONE\nTWO\nTHREE\n'
    expect(threeWayMerge(base, 'one\ntwo 2\nthree\n', theirs, { lineKey })).toEqual({
      text: 'ONE\ntwo 2\nTHREE\n',
      conflicts: 0,
    })
  })

  it('never folds a change on our side when oursLineKey is exact', () => {
    const lineKey = (line: string) => line.toLowerCase()
    const merge = createThreeWayMerger('one\ntwo\n', 'One\nTwo\n', { lineKey, oursLineKey: line => line })
    // 'two' -> 'TWO' is equal by key, but it is our edit and must survive.
    expect(merge('one\nTWO\n').text).toBe('One\nTWO\n')
    expect(merge('one\ntwo\n').text).toBe('One\nTwo\n')
  })
})
