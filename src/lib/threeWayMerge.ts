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

function changesFrom(base: string, side: string): Change[] {
  const changes: Change[] = []
  let baseIndex = 0
  let current: Change | null = null
  for (const line of diffLines(base, side)) {
    if (line.kind === 'context') {
      if (current) {
        changes.push(current)
        current = null
      }
      baseIndex += 1
      continue
    }
    current ??= { start: baseIndex, end: baseIndex, lines: [] }
    if (line.kind === 'remove') {
      baseIndex += 1
      current.end = baseIndex
    } else {
      current.lines.push(line.text)
    }
  }
  if (current) {
    changes.push(current)
  }
  return changes
}

/** One side's version of base[start, end), applying its changes inside the range. */
function applyWithin(baseLines: string[], start: number, end: number, changes: Change[]): string[] {
  const result: string[] = []
  let index = start
  for (const change of changes) {
    result.push(...baseLines.slice(index, change.start), ...change.lines)
    index = change.end
  }
  result.push(...baseLines.slice(index, end))
  return result
}

function sameLines(a: string[], b: string[]) {
  return a.length === b.length && a.every((line, index) => line === b[index])
}

export function threeWayMerge(
  base: string,
  ours: string,
  theirs: string,
  options: { strategy?: MergeConflictStrategy, labels?: MergeLabels } = {},
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
  const baseLines = splitLines(base)
  const oursChanges = changesFrom(base, ours)
  const theirsChanges = changesFrom(base, theirs)
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
    if (regionTheirs.length === 0 || sameLines(oursVersion, theirsVersion)) {
      output.push(...oursVersion)
    } else if (regionOurs.length === 0) {
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
