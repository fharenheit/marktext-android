/**
 * Line diff for showing changes: Myers' O((N+M)·D) shortest edit script
 * over lines, grouped into unified-diff hunks with context. Pure, so the
 * same engine serves working-tree changes, commit history and the editor's
 * "show changes" for every document source.
 */

export interface DiffLine {
  kind: 'context' | 'add' | 'remove'
  text: string
  /** 1-based line number in the old text; null for added lines. */
  oldNumber: number | null
  /** 1-based line number in the new text; null for removed lines. */
  newNumber: number | null
}

export interface DiffHunk {
  oldStart: number
  oldLines: number
  newStart: number
  newLines: number
  lines: DiffLine[]
}

/**
 * Edit distance beyond which the full Myers search is abandoned and the
 * change is reported as a wholesale replacement: the trace grows with D²,
 * and a document rewritten that thoroughly reads the same either way.
 */
const MAX_EDIT_DISTANCE = 4000

export function splitLines(text: string | null): string[] {
  if (text === null || text.length === 0) {
    return []
  }
  const lines = text.split('\n')
  if (lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines.map(line => (line.endsWith('\r') ? line.slice(0, -1) : line))
}

export function diffLines(oldText: string | null, newText: string | null): DiffLine[] {
  const oldLines = splitLines(oldText)
  const newLines = splitLines(newText)

  // Common prefix and suffix need no search.
  let prefix = 0
  while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix] === newLines[prefix]) {
    prefix += 1
  }
  let suffix = 0
  while (
    suffix < oldLines.length - prefix
    && suffix < newLines.length - prefix
    && oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  ) {
    suffix += 1
  }

  const result: DiffLine[] = []
  for (let index = 0; index < prefix; index += 1) {
    result.push({ kind: 'context', text: oldLines[index], oldNumber: index + 1, newNumber: index + 1 })
  }
  const middle = diffMiddle(
    oldLines.slice(prefix, oldLines.length - suffix),
    newLines.slice(prefix, newLines.length - suffix),
  )
  for (const line of middle) {
    result.push({
      ...line,
      oldNumber: line.oldNumber === null ? null : line.oldNumber + prefix,
      newNumber: line.newNumber === null ? null : line.newNumber + prefix,
    })
  }
  for (let index = suffix; index > 0; index -= 1) {
    const oldIndex = oldLines.length - index
    const newIndex = newLines.length - index
    result.push({ kind: 'context', text: oldLines[oldIndex], oldNumber: oldIndex + 1, newNumber: newIndex + 1 })
  }
  return result
}

function diffMiddle(oldLines: string[], newLines: string[]): DiffLine[] {
  const n = oldLines.length
  const m = newLines.length
  if (n === 0 || m === 0) {
    return replaceAll(oldLines, newLines)
  }

  const max = n + m
  const offset = max
  let v: Int32Array = new Int32Array(2 * max + 2)
  const trace: Int32Array[] = []
  let found = false
  for (let d = 0; d <= Math.min(max, MAX_EDIT_DISTANCE); d += 1) {
    trace.push(v.slice())
    for (let k = -d; k <= d; k += 2) {
      let x = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])
        ? v[offset + k + 1]
        : v[offset + k - 1] + 1
      let y = x - k
      while (x < n && y < m && oldLines[x] === newLines[y]) {
        x += 1
        y += 1
      }
      v[offset + k] = x
      if (x >= n && y >= m) {
        found = true
        break
      }
    }
    if (found) {
      trace.push(v)
      break
    }
  }
  if (!found) {
    return replaceAll(oldLines, newLines)
  }

  // Backtrack through the saved frontiers to recover the edit script.
  const reversed: DiffLine[] = []
  let x = n
  let y = m
  for (let d = trace.length - 2; d >= 0; d -= 1) {
    v = trace[d]
    const k = x - y
    const previousK = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1]) ? k + 1 : k - 1
    const previousX = d === 0 ? 0 : v[offset + previousK]
    const previousY = previousX - previousK
    while (x > previousX && y > previousY) {
      x -= 1
      y -= 1
      reversed.push({ kind: 'context', text: oldLines[x], oldNumber: x + 1, newNumber: y + 1 })
    }
    if (d === 0) {
      break
    }
    if (x === previousX) {
      y -= 1
      reversed.push({ kind: 'add', text: newLines[y], oldNumber: null, newNumber: y + 1 })
    } else {
      x -= 1
      reversed.push({ kind: 'remove', text: oldLines[x], oldNumber: x + 1, newNumber: null })
    }
  }
  while (x > 0 && y > 0) {
    x -= 1
    y -= 1
    reversed.push({ kind: 'context', text: oldLines[x], oldNumber: x + 1, newNumber: y + 1 })
  }
  return reversed.reverse()
}

function replaceAll(oldLines: string[], newLines: string[]): DiffLine[] {
  return [
    ...oldLines.map((text, index): DiffLine => ({ kind: 'remove', text, oldNumber: index + 1, newNumber: null })),
    ...newLines.map((text, index): DiffLine => ({ kind: 'add', text, oldNumber: null, newNumber: index + 1 })),
  ]
}

/** Groups changes into hunks with `context` unchanged lines around them. */
export function buildHunks(lines: DiffLine[], context = 3): DiffHunk[] {
  const changeIndexes = lines.flatMap((line, index) => (line.kind === 'context' ? [] : [index]))
  if (changeIndexes.length === 0) {
    return []
  }

  const ranges: [number, number][] = []
  for (const index of changeIndexes) {
    const start = Math.max(0, index - context)
    const end = Math.min(lines.length - 1, index + context)
    const last = ranges[ranges.length - 1]
    if (last && start <= last[1] + 1) {
      last[1] = Math.max(last[1], end)
    } else {
      ranges.push([start, end])
    }
  }

  return ranges.map(([start, end]) => {
    const hunkLines = lines.slice(start, end + 1)
    const firstOld = hunkLines.find(line => line.oldNumber !== null)?.oldNumber
    const firstNew = hunkLines.find(line => line.newNumber !== null)?.newNumber
    const oldCount = hunkLines.filter(line => line.kind !== 'add').length
    const newCount = hunkLines.filter(line => line.kind !== 'remove').length
    return {
      oldStart: firstOld ?? previousNumber(lines, start, 'oldNumber'),
      oldLines: oldCount,
      newStart: firstNew ?? previousNumber(lines, start, 'newNumber'),
      newLines: newCount,
      lines: hunkLines,
    }
  })
}

/** Unified-diff convention: an empty side starts after the preceding line. */
function previousNumber(lines: DiffLine[], index: number, key: 'oldNumber' | 'newNumber') {
  for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
    const value = lines[cursor][key]
    if (value !== null) {
      return value
    }
  }
  return 0
}

export function countChanges(lines: DiffLine[]) {
  let added = 0
  let removed = 0
  for (const line of lines) {
    if (line.kind === 'add') {
      added += 1
    } else if (line.kind === 'remove') {
      removed += 1
    }
  }
  return { added, removed }
}
