import { diffLines, splitLines } from './lineDiff'

/**
 * Line-based three-way merge (diff3): the changes "ours" and "theirs" each
 * made to a common base are combined. Changes touching separate lines merge
 * cleanly; overlapping changes that differ are conflicts, resolved per the
 * strategy: git-style markers, or one side wins in that region only.
 */

export type MergeConflictStrategy = 'markers' | 'ours' | 'theirs'

export interface MergeLabels {
  ours: string
  theirs: string
}

export interface ThreeWayMergeResult {
  text: string
  conflicts: number
}

/** One side's change: base lines [start, end) replaced by `lines`. */
interface Change {
  start: number
  end: number
  lines: string[]
}

/**
 * Lines are compared by `lineKey` (identity by default) so formatting-only
 * differences can count as equal; replacements keep the side's real lines.
 */
function changesFrom(
  baseLines: string[],
  sideLines: string[],
  lineKey: (line: string) => string,
  /** Filled with the side's own text of each base line it left unchanged. */
  unchangedText?: string[],
): Change[] {
  const changes: Change[] = []
  let baseIndex = 0
  let current: Change | null = null
  const keyed = (lines: string[]) => lines.length ? `${lines.map(lineKey).join('\n')}\n` : ''
  for (const line of diffLines(keyed(baseLines), keyed(sideLines))) {
    if (line.kind === 'context') {
      if (current) {
        changes.push(current)
        current = null
      }
      if (unchangedText) {
        unchangedText[baseIndex] = sideLines[(line.newNumber ?? 1) - 1]
      }
      baseIndex += 1
      continue
    }
    current ??= { start: baseIndex, end: baseIndex, lines: [] }
    if (line.kind === 'remove') {
      baseIndex += 1
      current.end = baseIndex
    } else {
      current.lines.push(sideLines[(line.newNumber ?? 1) - 1])
    }
  }
  if (current) {
    changes.push(current)
  }
  return changes
}

/** One side's version of base[start, end), applying its changes inside the range. */
function applyWithin(baseLines: readonly string[], start: number, end: number, changes: Change[]): string[] {
  const result: string[] = []
  let index = start
  for (const change of changes) {
    result.push(...baseLines.slice(index, change.start), ...change.lines)
    index = change.end
  }
  result.push(...baseLines.slice(index, end))
  return result
}

function sameLines(a: string[], b: string[], lineKey: (line: string) => string) {
  return a.length === b.length && a.every((line, index) => lineKey(line) === lineKey(b[index]))
}

export interface ThreeWayMergeOptions {
  strategy?: MergeConflictStrategy
  labels?: MergeLabels
  /**
   * Comparison key per line. Lines with equal keys count as unchanged, and
   * where both sides agree by key the THEIRS text is kept.
   */
  lineKey?: (line: string) => string
}

export function threeWayMerge(
  base: string,
  ours: string,
  theirs: string,
  options: ThreeWayMergeOptions = {},
): ThreeWayMergeResult {
  if (ours === theirs) {
    return { text: ours, conflicts: 0 }
  }
  if (ours === base) {
    return { text: theirs, conflicts: 0 }
  }
  if (theirs === base) {
    return { text: ours, conflicts: 0 }
  }

  const strategy = options.strategy ?? 'markers'
  const labels = options.labels ?? { ours: 'mine', theirs: 'latest' }
  const lineKey = options.lineKey ?? ((line: string) => line)
  const rawBaseLines = splitLines(base)
  // Lines theirs left unchanged (by key) are written in theirs' own text, so
  // with a formatting-blind key the untouched parts keep theirs' formatting.
  const theirsText: string[] = []
  const oursChanges = changesFrom(rawBaseLines, splitLines(ours), lineKey)
  const theirsChanges = changesFrom(rawBaseLines, splitLines(theirs), lineKey, theirsText)
  const baseLines = rawBaseLines.map((line, index) => theirsText[index] ?? line)
  const output: string[] = []
  let conflicts = 0
  let baseIndex = 0
  let o = 0
  let t = 0

  while (o < oursChanges.length || t < theirsChanges.length) {
    // Start a region at whichever change comes first, then absorb every
    // change from either side that overlaps or touches it.
    const first = t >= theirsChanges.length
      || (o < oursChanges.length && oursChanges[o].start <= theirsChanges[t].start)
      ? oursChanges[o]
      : theirsChanges[t]
    let start = first.start
    let end = first.end
    const regionOurs: Change[] = []
    const regionTheirs: Change[] = []
    let grew = true
    while (grew) {
      grew = false
      while (o < oursChanges.length && oursChanges[o].start <= end && oursChanges[o].end >= start) {
        regionOurs.push(oursChanges[o])
        start = Math.min(start, oursChanges[o].start)
        end = Math.max(end, oursChanges[o].end)
        o += 1
        grew = true
      }
      while (t < theirsChanges.length && theirsChanges[t].start <= end && theirsChanges[t].end >= start) {
        regionTheirs.push(theirsChanges[t])
        start = Math.min(start, theirsChanges[t].start)
        end = Math.max(end, theirsChanges[t].end)
        t += 1
        grew = true
      }
    }

    output.push(...baseLines.slice(baseIndex, start))
    const oursVersion = applyWithin(baseLines, start, end, regionOurs)
    const theirsVersion = applyWithin(baseLines, start, end, regionTheirs)
    if (regionTheirs.length === 0) {
      output.push(...oursVersion)
    } else if (regionOurs.length === 0 || sameLines(oursVersion, theirsVersion, lineKey)) {
      output.push(...theirsVersion)
    } else {
      conflicts += 1
      if (strategy === 'ours') {
        output.push(...oursVersion)
      } else if (strategy === 'theirs') {
        output.push(...theirsVersion)
      } else {
        output.push(
          `<<<<<<< ${labels.ours}`,
          ...oursVersion,
          '=======',
          ...theirsVersion,
          `>>>>>>> ${labels.theirs}`,
        )
      }
    }
    baseIndex = end
  }
  output.push(...baseLines.slice(baseIndex))

  const endsWithNewline = [ours, theirs].some(text => text.endsWith('\n'))
  const text = output.join('\n') + (endsWithNewline && output.length > 0 ? '\n' : '')
  return { text, conflicts }
}
