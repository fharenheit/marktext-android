import { threeWayMerge } from '../../lib/threeWayMerge'

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
 * runs, the bullet / ordered-list marker style, and trailing spaces. Lines
 * the user did not change therefore never count as changed.
 */
export function markdownLineKey(line: string) {
  const trimmed = line.replace(/\s+$/, '')
  if (TABLE_ROW.test(trimmed)) {
    const cells = trimmed.trim().slice(1, -1).split(/(?<!\\)\|/).map(cell => cell.trim())
    return `|${cells.map(cell => (TABLE_DELIMITER_CELL.test(cell) ? cell.replace(/-+/, '-') : cell)).join('|')}|`
  }
  return trimmed
    .replace(BULLET_MARKER, '$1-$2')
    .replace(ORDERED_MARKER, '$1$2.$3')
    .replace(/^(\s*[-\d.]+)\s+/, '$1 ')
}

export interface SourceFormattingBaseline {
  /** The document text as opened (or as last handed over from source mode). */
  source: string
  /** Muya's serialization of `source`. */
  canonical: string
}

/** The text to save for Muya's current serialization, in the source's own formatting. */
export function preserveSourceFormatting(baseline: SourceFormattingBaseline | null, canonical: string) {
  if (!baseline) {
    return canonical
  }
  if (canonical === baseline.canonical) {
    return baseline.source
  }
  // Where an edited block also differs in formatting, the edit wins
  // ('ours'): that block takes Muya's style, the rest keeps the source's.
  const merged = threeWayMerge(baseline.canonical, canonical, baseline.source, {
    strategy: 'ours',
    lineKey: markdownLineKey,
  }).text
  return matchCompactTables(merged, new Set(baseline.source.split('\n')))
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
          lines[index] = indent + markdownLineKey(lines[index])
        }
      }
    }
    start = end
  }
  return lines.join('\n')
}
