import { threeWayMerge } from '../../lib/threeWayMerge'

// Split view = source mode with the WYSIWYG editor beside it, both editable.
// The textarea stays the document of record. Edits flow by ORIGIN, never by
// focus (toolbar buttons keep the textarea focused while acting on Muya):
//
//   source → Muya: textarea input, debounced through replaceContent, and
//                  flushed at once when the WYSIWYG pane is engaged;
//   Muya → source: every Muya content change that this module did not cause
//                  itself, mirrored in the source's own formatting.
//
// While the source holds merge conflict markers neither direction runs: the
// markers are not Markdown-safe, so Muya would mangle them, and Muya's stale
// document must not overwrite them. The WYSIWYG pane is read-only meanwhile.

export const SPLIT_SOURCE_TO_EDITOR_DELAY_MS = 250

const CONFLICT_MARKER = /^<<<<<<< /m

/** Whether `text` holds an unresolved merge conflict. */
export function hasConflictMarkers(text: string) {
  return CONFLICT_MARKER.test(text)
}

export interface SplitViewSyncEditor {
  replaceContent(content: string, recordSelection?: unknown): boolean
}

export interface SplitViewSyncOptions {
  getEditor(): SplitViewSyncEditor | null
  /** The split view is open (source mode active with the WYSIWYG pane). */
  isActive(): boolean
  getSourceText(): string
  /** Show `text` in the source pane without re-running the edit pipeline. */
  mirrorIntoSource(text: string): void
  /** Muya's document in the source's formatting (empty doc normalized to ''). */
  readEditorText(): string
  /** `text` was just handed to Muya wholesale: it becomes the formatting baseline. */
  rebaseFormatting(text: string): void
  delayMs?: number
  setTimer?: (callback: () => void, delay: number) => unknown
  clearTimer?: (handle: unknown) => void
}

export interface SplitViewSync {
  /** The split opened: bring the WYSIWYG pane up to the source text. */
  start(): void
  /** The split closed: drop any pending hand-over. */
  stop(): void
  /** Textarea input: hand the text to Muya after the debounce. */
  sourceEdited(): void
  /** Hand pending source edits to Muya now (the WYSIWYG pane is engaged). */
  flush(): void
  /** A Muya content change: mirror it unless this module caused it. */
  editorChanged(): void
}

export function createSplitViewSync(options: SplitViewSyncOptions): SplitViewSync {
  const delayMs = options.delayMs ?? SPLIT_SOURCE_TO_EDITOR_DELAY_MS
  const setTimer = options.setTimer ?? ((callback, delay) => setTimeout(callback, delay))
  const clearTimer = options.clearTimer ?? (handle => clearTimeout(handle as ReturnType<typeof setTimeout>))

  let timer: unknown = null
  // Set while this module itself replaces Muya's content, so the change
  // events that replace fires are not mirrored back.
  let applyingFromSource = false
  // The text both panes last agreed on; the source differing from it means
  // source edits not yet handed to Muya.
  let synced: string | null = null

  function cancelTimer() {
    if (timer !== null) {
      clearTimer(timer)
      timer = null
    }
  }

  function flush() {
    cancelTimer()
    const editor = options.getEditor()
    if (!editor || !options.isActive()) {
      return
    }
    const text = options.getSourceText()
    if (text === synced || hasConflictMarkers(text)) {
      return
    }
    applyingFromSource = true
    try {
      editor.replaceContent(text, null)
    } finally {
      applyingFromSource = false
    }
    options.rebaseFormatting(text)
    synced = text
  }

  function sourceEdited() {
    if (!options.isActive()) {
      return
    }
    cancelTimer()
    timer = setTimer(flush, delayMs)
  }

  function editorChanged() {
    if (applyingFromSource || !options.isActive()) {
      return
    }
    const source = options.getSourceText()
    if (hasConflictMarkers(source)) {
      return
    }
    const editorText = options.readEditorText()
    if (editorText === source) {
      synced = source
      return
    }
    if (synced !== null && source !== synced) {
      // Both panes changed since they last agreed (a Muya command ran before
      // the debounced source edit reached it): merge instead of letting
      // either side overwrite the other, and hand the result to Muya next.
      const merged = threeWayMerge(synced, source, editorText, { strategy: 'ours' }).text
      synced = editorText
      options.mirrorIntoSource(merged)
      if (merged !== editorText) {
        // Not from inside Muya's own change event: through the debounce.
        sourceEdited()
      }
      return
    }
    synced = editorText
    options.mirrorIntoSource(editorText)
  }

  function start() {
    synced = null
    flush()
  }

  function stop() {
    cancelTimer()
    synced = null
  }

  return { start, stop, sourceEdited, flush, editorChanged }
}
