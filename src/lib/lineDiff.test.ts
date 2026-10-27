import { describe, expect, it } from 'vitest'
import { buildHunks, countChanges, diffLines, splitLines, type DiffLine } from './lineDiff'

function render(lines: DiffLine[]) {
  return lines.map(line => `${line.kind === 'add' ? '+' : line.kind === 'remove' ? '-' : ' '}${line.text}`)
}

/** Applying the script to the old text must yield the new text. */
function apply(lines: DiffLine[]) {
  return lines.filter(line => line.kind !== 'remove').map(line => line.text)
}

describe('lineDiff', () => {
  it('splits lines without a phantom last line and drops CR', () => {
    expect(splitLines('a\nb\n')).toEqual(['a', 'b'])
    expect(splitLines('a\r\nb')).toEqual(['a', 'b'])
    expect(splitLines('')).toEqual([])
    expect(splitLines(null)).toEqual([])
  })

  it('finds a minimal edit between two versions', () => {
    const lines = diffLines('a\nb\nc\nd\n', 'a\nc\nd\ne\n')

    expect(render(lines)).toEqual([' a', '-b', ' c', ' d', '+e'])
    expect(countChanges(lines)).toEqual({ added: 1, removed: 1 })
    expect(lines[2]).toMatchObject({ oldNumber: 3, newNumber: 2 })
  })

  it('reports modifications as remove + add', () => {
    expect(render(diffLines('# Title\nold line\nend', '# Title\nnew line\nend'))).toEqual([
      ' # Title',
      '-old line',
      '+new line',
      ' end',
    ])
  })

  it('handles added and deleted files', () => {
    expect(render(diffLines(null, 'x\ny'))).toEqual(['+x', '+y'])
    expect(render(diffLines('x\ny', null))).toEqual(['-x', '-y'])
    expect(diffLines('same', 'same').every(line => line.kind === 'context')).toBe(true)
  })

  it('always produces a script that rebuilds the new text', () => {
    const random = mulberry32(7)
    for (let round = 0; round < 200; round += 1) {
      const oldLines = Array.from({ length: Math.floor(random() * 30) }, () => String(Math.floor(random() * 6)))
      const newLines = Array.from({ length: Math.floor(random() * 30) }, () => String(Math.floor(random() * 6)))
      const lines = diffLines(oldLines.join('\n'), newLines.join('\n'))
      expect(apply(lines)).toEqual(newLines)
      expect(lines.filter(line => line.kind !== 'add').map(line => line.text)).toEqual(oldLines)
    }
  })

  it('groups changes into hunks with context and unified-diff headers', () => {
    const oldText = Array.from({ length: 20 }, (_, index) => `line ${index + 1}`).join('\n')
    const newText = oldText.replace('line 2\n', 'line two\n').replace('line 18', 'line eighteen')
    const hunks = buildHunks(diffLines(oldText, newText), 2)

    expect(hunks).toHaveLength(2)
    expect(hunks[0]).toMatchObject({ oldStart: 1, oldLines: 4, newStart: 1, newLines: 4 })
    expect(hunks[1]).toMatchObject({ oldStart: 16, oldLines: 5, newStart: 16, newLines: 5 })
    expect(buildHunks(diffLines('a', 'a'))).toEqual([])
  })

  it('merges nearby changes into one hunk', () => {
    const hunks = buildHunks(diffLines('a\nb\nc\nd\ne', 'A\nb\nc\nd\nE'), 3)
    expect(hunks).toHaveLength(1)
  })

  it('falls back to a wholesale replacement for huge rewrites', () => {
    const oldText = Array.from({ length: 6000 }, (_, index) => `old ${index}`).join('\n')
    const newText = Array.from({ length: 6000 }, (_, index) => `new ${index}`).join('\n')
    const lines = diffLines(oldText, newText)
    expect(countChanges(lines)).toEqual({ added: 6000, removed: 6000 })
    expect(apply(lines)).toEqual(splitLines(newText))
  })
})

function mulberry32(seed: number) {
  let state = seed
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
