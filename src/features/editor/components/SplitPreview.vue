<script setup lang="ts">
import { onBeforeUnmount, ref, watch, type CSSProperties } from 'vue'
import { useI18n } from '../../../lib/i18n'
import { createLogger } from '../../../lib/logger'
import { resolveDisplayImageSource } from '../../../lib/androidImages'
import { rewriteMarkdownImageSourcesForExport } from '../pdfExportHtml'
import type { MuyaEditor } from '../editorRuntime'

// Read-only rendering of the source text beside the source editor. It goes
// through Muya's export renderer (sanitized, with highlighting, math and
// diagrams) instead of a second live editor, so the textarea stays the one
// document and the WYSIWYG undo history is untouched.

const props = defineProps<{
  markdown: string
  dir: 'ltr' | 'rtl'
  /** The rendered-text font, size and line height from the appearance settings. */
  textStyle: CSSProperties
  /** The live editor, for its feature options (footnotes, math, diagram themes). */
  getEditor: () => MuyaEditor | null
}>()

const { t } = useI18n()
const log = createLogger('split-preview')
const RENDER_DELAY_MS = 250

const root = ref<HTMLElement | null>(null)
const content = ref<HTMLElement | null>(null)
const failed = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let generation = 0

const URL_SCHEME_PATTERN = /^[a-z][a-z0-9+.-]*:/i

/** Repository-relative and MarkText image sources, mapped the way the editor shows them. */
function resolveImages(container: DocumentFragment) {
  for (const image of container.querySelectorAll('img')) {
    const source = image.getAttribute('src')
    if (!source || source.startsWith('#') || (URL_SCHEME_PATTERN.test(source) && !source.startsWith('marktext-image:'))) {
      continue
    }
    const resolved = resolveDisplayImageSource(source)
    if (resolved) {
      image.setAttribute('src', resolved)
    }
  }
}

async function render(markdown: string) {
  const current = ++generation
  try {
    const { MarkdownToHtml } = await import('@muyajs/core')
    const prepared = rewriteMarkdownImageSourcesForExport(markdown, resolveDisplayImageSource)
    const html = await new MarkdownToHtml(prepared, props.getEditor() ?? undefined).renderHtml()
    if (current !== generation || !content.value) {
      return
    }
    // An inert template keeps relative image URLs from loading against the
    // app origin before they are resolved.
    const template = document.createElement('template')
    template.innerHTML = html
    resolveImages(template.content)
    content.value.replaceChildren(template.content)
    failed.value = false
  } catch (error) {
    if (current === generation) {
      failed.value = true
      log.warn('Preview render failed', { error })
    }
  }
}

watch(
  () => props.markdown,
  markdown => {
    if (timer !== null) {
      clearTimeout(timer)
    }
    timer = setTimeout(() => {
      timer = null
      void render(markdown)
    }, generation === 0 ? 0 : RENDER_DELAY_MS)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  generation++
  if (timer !== null) {
    clearTimeout(timer)
  }
})

/** Follows the source editor's scroll position proportionally. */
function syncScroll(ratio: number) {
  const element = root.value
  if (element) {
    element.scrollTop = ratio * Math.max(0, element.scrollHeight - element.clientHeight)
  }
}

defineExpose({ syncScroll })
</script>

<template>
  <section
    ref="root"
    class="split-preview"
    :dir="dir"
    :style="textStyle"
    :aria-label="t('editor.splitView.preview')"
    data-testid="split-preview"
  >
    <p v-if="failed" class="split-preview-error" role="status">{{ t('editor.splitView.failed') }}</p>
    <div ref="content" class="split-preview-content" />
  </section>
</template>

<style src="./splitPreview.css"></style>
