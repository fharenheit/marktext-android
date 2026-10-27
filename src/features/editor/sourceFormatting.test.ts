import { describe, expect, it } from 'vitest'
import { markdownLineKey, preserveSourceFormatting } from './sourceFormatting'

// The source as a user wrote it, and how Muya serializes it (padded table,
// '-' bullets): the two differ only in formatting.
const SOURCE = [
  '# Notes',
  '',
  '* first item',
  '* second item',
  '',
  '|Name|Value|',
  '|-|:-:|',
  '|alpha|1|',
  '|beta|2|',
  '',
  'Closing paragraph.',
  '',
].join('\n')

const CANONICAL = [
  '# Notes',
  '',
  '- first item',
  '- second item',
  '',
  '| Name  | Value |',
  '| ----- |:-----:|',
  '| alpha | 1     |',
  '| beta  | 2     |',
  '',
  'Closing paragraph.',
  '',
].join('\n')

const baseline = { source: SOURCE, canonical: CANONICAL }

describe('markdownLineKey', () => {
  it('ignores table padding, delimiter length and list markers', () => {
    expect(markdownLineKey('|alpha|1|')).toBe(markdownLineKey('| alpha | 1     |'))
    expect(markdownLineKey('|-|:-:|')).toBe(markdownLineKey('| ----- |:-----:|'))
    expect(markdownLineKey('* item')).toBe(markdownLineKey('- item'))
    expect(markdownLineKey('3) step')).toBe(markdownLineKey('3. step'))
    expect(markdownLineKey('| a \\| b | c |')).toBe('|a \\| b|c|')
  })

  it('still tells different content apart', () => {
    expect(markdownLineKey('| alpha | 1 |')).not.toBe(markdownLineKey('| alpha | 2 |'))
    expect(markdownLineKey('- item')).not.toBe(markdownLineKey('- items'))
  })
})

describe('preserveSourceFormatting', () => {
  it('returns the source untouched while nothing was edited', () => {
    expect(preserveSourceFormatting(baseline, CANONICAL)).toBe(SOURCE)
  })

  it('changes only the edited table row, in the table\'s own compact style', () => {
    const edited = CANONICAL.replace('| beta  | 2     |', '| beta  | 20    |')
    expect(preserveSourceFormatting(baseline, edited)).toBe(SOURCE.replace('|beta|2|', '|beta|20|'))
  })

  it('leaves an edited row padded in a table written padded', () => {
    const source = '| a  | b  |\n| -- | -- |\n| 1  | 2  |\n'
    const canonical = '| a     | b     |\n| ----- | ----- |\n| 1     | 2     |\n'
    const edited = canonical.replace('| 1     | 2     |', '| 10    | 2     |')
    expect(preserveSourceFormatting({ source, canonical }, edited)).toBe(
      '| a  | b  |\n| -- | -- |\n| 10    | 2     |\n',
    )
  })

  it('keeps list markers and tables when a paragraph is edited', () => {
    const edited = CANONICAL.replace('Closing paragraph.', 'Closing paragraph, edited.')
    expect(preserveSourceFormatting(baseline, edited)).toBe(
      SOURCE.replace('Closing paragraph.', 'Closing paragraph, edited.'),
    )
  })

  it('keeps added blocks and removed lines', () => {
    const edited = CANONICAL.replace('- second item\n', '').replace('Closing paragraph.\n', 'Closing paragraph.\n\nNew block.\n')
    expect(preserveSourceFormatting(baseline, edited)).toBe(
      SOURCE.replace('* second item\n', '').replace('Closing paragraph.\n', 'Closing paragraph.\n\nNew block.\n'),
    )
  })

  it('a row the user added goes in among the existing rows', () => {
    const edited = CANONICAL.replace('| beta  | 2     |\n', '| beta  | 2     |\n| gamma | 3     |\n')
    expect(preserveSourceFormatting(baseline, edited)).toBe(
      SOURCE.replace('|beta|2|\n', '|beta|2|\n|gamma|3|\n'),
    )
  })

  it('never compacts table-like lines in fenced code', () => {
    const source = '|a|b|\n|-|-|\n|1|2|\n\n```\n| keep  | this |\n```\n'
    const canonical = '| a     | b     |\n| ----- | ----- |\n| 1     | 2     |\n\n```\n| keep  | this |\n```\n'
    const edited = canonical.replace('| keep  | this |', '| keep  | this too |')
    expect(preserveSourceFormatting({ source, canonical }, edited)).toBe(
      source.replace('| keep  | this |', '| keep  | this too |'),
    )
  })

  it('keeps edits that only look like formatting inside fenced code', () => {
    const source = '* item\n\n```diff\n- foo\n  aligned\n```\n'
    const canonical = '- item\n\n```diff\n- foo\n  aligned\n```\n'
    const edited = canonical.replace('- foo', '+ foo').replace('  aligned', '    aligned')
    expect(preserveSourceFormatting({ source, canonical }, edited)).toBe(
      '* item\n\n```diff\n+ foo\n    aligned\n```\n',
    )
  })

  it('keeps a removed or added hard break', () => {
    const source = '* item\n\nline one  \nline two\n'
    const canonical = '- item\n\nline one  \nline two\n'
    expect(preserveSourceFormatting({ source, canonical }, canonical.replace('one  ', 'one'))).toBe(
      '* item\n\nline one\nline two\n',
    )
    const plain = { source: '* item\n\nline one\nline two\n', canonical: '- item\n\nline one\nline two\n' }
    expect(preserveSourceFormatting(plain, plain.canonical.replace('one', 'one  '))).toBe(
      '* item\n\nline one  \nline two\n',
    )
    expect(markdownLineKey('text  ')).not.toBe(markdownLineKey('text'))
    expect(markdownLineKey('text ')).toBe(markdownLineKey('text'))
  })

  it('keeps a list marker change the user made', () => {
    const source = '* a\n* b\n\npara\n'
    const canonical = '- a\n- b\n\npara\n'
    // Equal by key, but base and ours are both Muya's serializations: any
    // difference between them is the user's edit and must survive.
    expect(preserveSourceFormatting({ source, canonical }, canonical.replace('- b', '-  b'))).toBe(
      '* a\n-  b\n\npara\n',
    )
  })

  it('writes merged edits with the source\'s CRLF line endings', () => {
    const source = SOURCE.replace(/\n/g, '\r\n')
    const edited = CANONICAL.replace('Closing paragraph.', 'Closing paragraph, edited.')
    expect(preserveSourceFormatting({ source, canonical: CANONICAL }, edited)).toBe(
      source.replace('Closing paragraph.', 'Closing paragraph, edited.'),
    )
    const row = CANONICAL.replace('| beta  | 2     |', '| beta  | 20    |')
    expect(preserveSourceFormatting({ source, canonical: CANONICAL }, row)).toBe(
      source.replace('|beta|2|', '|beta|20|'),
    )
  })

  it('passes the serialization through without a baseline', () => {
    expect(preserveSourceFormatting(null, CANONICAL)).toBe(CANONICAL)
  })
})
