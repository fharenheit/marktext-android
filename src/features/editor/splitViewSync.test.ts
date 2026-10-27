import { describe, expect, it, vi } from 'vitest'
import { createSplitViewSync, hasConflictMarkers } from './splitViewSync'

/**
 * A fake Muya: `doc` is its document; replaceContent fires the change event
 * synchronously, like the real json-change dispatch does.
 */
function setup(initial = 'alpha\n\nbeta\n', format = (doc: string) => doc) {
  const timers: { callback: () => void, delay: number }[] = []
  const state = { source: initial, active: true }
  const editor = {
    doc: initial,
    replaceContent: vi.fn((content: string) => {
      if (content === editor.doc) {
        return false
      }
      editor.doc = content
      sync.editorChanged()
      return true
    }),
  }
  const mirrorIntoSource = vi.fn((text: string) => {
    state.source = text
  })
  const rebaseFormatting = vi.fn()
  const sync = createSplitViewSync({
    getEditor: () => editor,
    isActive: () => state.active,
    getSourceText: () => state.source,
    mirrorIntoSource,
    readEditorText: () => format(editor.doc),
    rebaseFormatting,
    setTimer: (callback, delay) => {
      const timer = { callback, delay }
      timers.push(timer)
      return timer
    },
    clearTimer: handle => {
      timers.splice(timers.indexOf(handle as (typeof timers)[number]), 1)
    },
  })
  /** The user types in the textarea. */
  function typeSource(text: string) {
    state.source = text
    sync.sourceEdited()
  }
  /** The user (or a toolbar button) edits Muya. */
  function editWysiwyg(text: string) {
    editor.doc = text
    sync.editorChanged()
  }
  /** The split opens; Muya already shows the source. */
  function open() {
    sync.start()
    editor.replaceContent.mockClear()
  }
  function runTimers() {
    for (const timer of timers.splice(0)) {
      timer.callback()
    }
  }
  return { state, editor, sync, open, timers, mirrorIntoSource, rebaseFormatting, typeSource, editWysiwyg, runTimers }
}

describe('splitViewSync', () => {
  it('hands source edits to Muya after the debounce and rebases the formatting', () => {
    const { open, editor, timers, rebaseFormatting, typeSource, runTimers, mirrorIntoSource } = setup()
    open()
    typeSource('alpha!\n\nbeta\n')
    expect(editor.replaceContent).not.toHaveBeenCalled()
    expect(timers).toHaveLength(1)
    runTimers()
    expect(editor.doc).toBe('alpha!\n\nbeta\n')
    expect(rebaseFormatting).toHaveBeenLastCalledWith('alpha!\n\nbeta\n')
    // The replace's own change event is not mirrored back.
    expect(mirrorIntoSource).not.toHaveBeenCalled()
  })

  it('flushes at once when the WYSIWYG pane is engaged, so a quick switch loses nothing (1)', () => {
    const { state, editor, sync, typeSource, editWysiwyg, timers } = setup()
    sync.start()
    typeSource('alpha typed\n\nbeta\n')
    // Tap the WYSIWYG pane within the debounce, then type there.
    sync.flush()
    expect(timers).toHaveLength(0)
    expect(editor.doc).toBe('alpha typed\n\nbeta\n')
    editWysiwyg('alpha typed\n\nbeta wysiwyg\n')
    expect(state.source).toBe('alpha typed\n\nbeta wysiwyg\n')
  })

  it('mirrors Muya changes in the source formatting, not Muya\'s raw text (2)', () => {
    // Stand-in for preserveSourceFormatting: Muya writes '-', the source '*'.
    const { state, sync, editWysiwyg } = setup('* a\n', doc => doc.replace(/^- /gm, '* '))
    sync.start()
    editWysiwyg('- a\n- b\n')
    expect(state.source).toBe('* a\n* b\n')
  })

  it('mirrors a Muya change whatever has focus, e.g. a toolbar command (3)', () => {
    const { state, sync, editWysiwyg } = setup()
    sync.start()
    editWysiwyg('# alpha\n\nbeta\n')
    expect(state.source).toBe('# alpha\n\nbeta\n')
  })

  it('merges a Muya change made before a pending source edit reached Muya (3)', () => {
    const { state, editor, sync, typeSource, editWysiwyg, runTimers } = setup()
    sync.start()
    typeSource('alpha typed\n\nbeta\n')
    // A toolbar command acts on Muya's (stale) document within the debounce.
    editWysiwyg('alpha\n\n**beta**\n')
    expect(state.source).toBe('alpha typed\n\n**beta**\n')
    runTimers()
    expect(editor.doc).toBe('alpha typed\n\n**beta**\n')
  })

  it('keeps conflict markers out of Muya and Muya out of the markers (4)', () => {
    const marked = 'alpha\n<<<<<<< mine\nb1\n=======\nb2\n>>>>>>> latest\n'
    const { open, state, editor, sync, typeSource, editWysiwyg, runTimers } = setup()
    open()
    typeSource(marked)
    runTimers()
    sync.flush()
    expect(editor.replaceContent).not.toHaveBeenCalled()
    editWysiwyg('stale wysiwyg edit\n')
    expect(state.source).toBe(marked)

    // Resolved: the next source edit reaches Muya again.
    typeSource('alpha\nb1\n')
    runTimers()
    expect(editor.doc).toBe('alpha\nb1\n')
  })

  it('opening the split brings Muya up to source edits made before it', () => {
    const { state, editor, sync } = setup()
    state.source = 'typed in plain source mode\n'
    sync.start()
    expect(editor.doc).toBe('typed in plain source mode\n')
  })

  it('does nothing while the split is closed', () => {
    const { state, editor, typeSource, editWysiwyg, timers, sync } = setup()
    state.active = false
    sync.start()
    typeSource('changed\n')
    editWysiwyg('other\n')
    expect(timers).toHaveLength(0)
    expect(editor.replaceContent).not.toHaveBeenCalled()
    expect(state.source).toBe('changed\n')
  })

  it('stop drops a pending hand-over', () => {
    const { open, editor, sync, typeSource, timers } = setup()
    open()
    typeSource('changed\n')
    sync.stop()
    expect(timers).toHaveLength(0)
    expect(editor.replaceContent).not.toHaveBeenCalled()
  })

  it('detects conflict markers only at a line start', () => {
    expect(hasConflictMarkers('a\n<<<<<<< mine\n')).toBe(true)
    expect(hasConflictMarkers('a <<<<<<< mine\n')).toBe(false)
  })
})
