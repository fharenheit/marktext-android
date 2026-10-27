import { createThreeWayMerger, type ThreeWayMergeResult } from '../../lib/threeWayMerge'

/**
 * Keeps a document's own Markdown formatting through WYSIWYG edits.
 *
 * Muya serializes the WHOLE document in its canonical style (padded tables,
 * its list markers, …), so saving after a one-word edit would also rewrite
 * every block the user never touched. Instead the save text is a three-way
 * merge: base = Muya's serialization of the source as opened, ours = Muya's
 * serialization now, theirs = the source as opened. The user's edits are
 * exactly base→ours; everything else keeps the source's own text.
 */

const TABLE_ROW = /^\s*\|.*\|\s*$/
const TABLE_DELIMITER_CELL = /^:?-+:?$/
const BULLET_MARKER = /^(\s*)[-*+](\s+)/
const ORDERED_MARKER = /^(\s*)(\d+)[.)](\s+)/

/**
 * Comparison key that ignores formatting-only differences between the
 * source and Muya's serialization: table cell padding and delimiter dash
 * runs, the bullet / ordered-list marker style, and stray trailing spaces
 * (a hard break's two or more stay significant). Lines the user did not
 * change therefore never count as changed. Only the source-vs-Muya side of
 * the merge is compared this way: the user's own edits are compared exactly.
 */
export function markdownLineKey(line: string) {
  const hardBreak = /\S {2,}\r?$/.test(line)
  const trimmed = line.replace(/\s+$/, '')
  if (TABLE_ROW.test(trimmed)) {
    const cells = trimmed.trim().slice(1, -1).split(/(?<!\\)\|/).map(cell => cell.trim())
    return `|${cells.map(cell => (TABLE_DELIMITER_CELL.test(cell) ? cell.replace(/-+/, '-') : cell)).join('|')}|`
  }
  const key = trimmed
    .replace(BULLET_MARKER, '$1-$2')
    .replace(ORDERED_MARKER, '$1$2.$3')
    .replace(/^(\s*[-\d.]+)\s+/, '$1 ')
  return hardBreak ? `${key}  ` : key
}

export interface SourceFormattingBaseline {
  /** The document text as opened (or as last handed over from source mode). */
  source: string
  /** Muya's serialization of `source`. */
  canonical: string
}

interface BaselineMerge {
  merge: (ours: string) => ThreeWayMergeResult
  sourceLines: Set<string>
  last: { canonical: string, text: string } | null
}

// Per baseline: the source-vs-Muya diff is computed once, and the latest
// result is remembered, so repeated snapshots of one state cost nothing and
// each new state diffs only Muya's base→now.
const baselineMerges = new WeakMap<SourceFormattingBaseline, BaselineMerge>()

function mergeFor(baseline: SourceFormattingBaseline): BaselineMerge {
  let entry = baselineMerges.get(baseline)
  if (!entry) {
    entry = {
      // Where an edited block also differs in formatting, the edit wins
      // ('ours'): that block takes Muya's style, the rest keeps the source's.
      // base→ours are both Muya serializations, so any difference there is a
      // real edit and is compared exactly — never folded away by the key.
      merge: createThreeWayMerger(baseline.canonical, baseline.source, {
        strategy: 'ours',
        lineKey: markdownLineKey,
        oursLineKey: line => line,
      }),
      sourceLines: new Set(baseline.source.split('\n')),
      last: null,
    }
    baselineMerges.set(baseline, entry)
  }
  return entry
}

/** The text to save for Muya's current serialization, in the source's own formatting. */
export function preserveSourceFormatting(baseline: SourceFormattingBaseline | null, canonical: string) {
  if (!baseline) {
    return canonical
  }
  if (canonical === baseline.canonical) {
    return baseline.source
  }
  const entry = mergeFor(baseline)
  if (entry.last?.canonical !== canonical) {
    entry.last = { canonical, text: matchCompactTables(entry.merge(canonical).text, entry.sourceLines) }
  }
  return entry.last.text
}

const COMPACT_TABLE_ROW = /^\s*\|[^ |]/

const FENCE = /^\s*(`{3,}|~{3,})/

/**
 * An edited table row comes out of Muya padded (`| a   | b |`); in a table
 * whose other rows are written compact (`|a|b|`), write it compact too.
 * Only rows that are not in the source (the edited ones) are touched, and
 * never inside fenced code.
 */
function matchCompactTables(text: string, sourceLines: Set<string>) {
  const lines = text.split('\n')
  let fence: string | null = null
  let start = 0
  while (start < lines.length) {
    const opener = FENCE.exec(lines[start])?.[1]
    if (opener && (fence === null || opener.startsWith(fence))) {
      fence = fence === null ? opener : null
      start += 1
      continue
    }
    if (fence !== null || !TABLE_ROW.test(lines[start])) {
      start += 1
      continue
    }
    let end = start
    while (end < lines.length && TABLE_ROW.test(lines[end])) {
      end += 1
    }
    const rows = lines.slice(start, end)
    const compact = rows.filter(row => COMPACT_TABLE_ROW.test(row)).length
    if (compact > 0 && compact * 2 >= rows.length) {
      for (let index = start; index < end; index += 1) {
        if (!COMPACT_TABLE_ROW.test(lines[index]) && !sourceLines.has(lines[index])) {
          const indent = /^\s*/.exec(lines[index])?.[0] ?? ''
          const cr = lines[index].endsWith('\r') ? '\r' : ''
          lines[index] = indent + markdownLineKey(lines[index]) + cr
        }
      }
    }
    start = end
  }
  return lines.join('\n')
}
