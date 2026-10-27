import { expect, test } from '@playwright/test'
import { getStoredDraftMarkdown, openLocalDraft } from './helpers/drafts'

test.describe.configure({ timeout: 60000 })

test('renders loaded CommonMark and GFM blocks as Muya editor structures', async ({ page }) => {
  await openLocalDraft(
    page,
    {
      id: 'markdown-rendering-draft',
      markdown: `# Rendering Matrix

> Quoted **strong** and *emphasized* text with \`inline code\`.

---

- Bullet item
- [x] Checked item
- [ ] Unchecked item

1. First item
2. Second item

[MarkText](https://github.com/marktext/marktext)

| Feature | State |
| --- | --- |
| Table | Ready |
`,
      title: /Rendering Matrix/,
      now: '2026-07-01T10:00:00.000Z',
    },
  )

  const editor = page.getByTestId('editor-host')
  await expect(editor.locator('h1.mu-atx-heading')).toContainText('Rendering Matrix')
  await expect(editor.locator('.mu-block-quote')).toContainText('Quoted')
  // The inline marks render as their own Muya structures inside the quote.
  await expect(editor.locator('.mu-block-quote strong.mu-inline-rule')).toContainText('strong')
  await expect(editor.locator('.mu-block-quote em.mu-inline-rule')).toContainText('emphasized')
  await expect(editor.locator('.mu-thematic-break')).toHaveCount(1)
  await expect(editor.locator('ul').filter({ hasText: 'Bullet item' })).toHaveCount(1)
  await expect(editor.locator('ol').filter({ hasText: 'Second item' })).toHaveCount(1)
  await expect(editor.locator('.mu-task-list-checkbox')).toHaveCount(2)
  await expect(editor.locator('.mu-task-list-checkbox.mu-checkbox-checked')).toHaveCount(1)
  await expect(editor.locator('.mu-link').filter({ hasText: 'MarkText' })).toBeVisible()
  await expect(editor.locator('figure.mu-table')).toHaveCount(1)
  await expect(editor.locator('figure.mu-table')).toContainText('Ready')
})

test('renders loaded math, fenced code, and Mermaid previews', async ({ page }) => {
  await openLocalDraft(
    page,
    {
      id: 'markdown-rendering-draft',
      markdown: `# Preview Matrix

Inline math $x^2 + y^2$.

$$
E = mc^2
$$

\`\`\`ts
const ready: boolean = true
\`\`\`

\`\`\`mermaid
graph TD
  A[Start] --> B[Done]
\`\`\`
`,
      title: /Preview Matrix/,
      now: '2026-07-01T10:00:00.000Z',
    },
  )

  const editor = page.getByTestId('editor-host')
  await expect.poll(() => editor.locator('.katex').count()).toBeGreaterThan(0)
  await expect(editor.locator('.mu-math-block')).toHaveCount(1)
  await expect(editor.locator('.mu-math-error')).toHaveCount(0)
  await expect(editor.locator('.mu-code-block')).toHaveCount(1)
  await expect(editor.locator('.mu-code-block .mu-code-copy')).toBeVisible()
  await expect(editor.locator('.mu-code-block .token.keyword').first()).toBeVisible()
  await expect(editor.locator('.mu-diagram-block')).toHaveCount(1)
  await expect(editor.locator('.mu-diagram-error')).toHaveCount(0)
  await expect
    .poll(() => editor.locator('.mu-diagram-preview svg').count(), { timeout: 30000 })
    .toBeGreaterThan(0)
})

test('renders all supported diagram previews without rewriting the Markdown source', async ({ page }) => {
  await page.route('https://www.plantuml.com/plantuml/svg/**', route =>
    route.fulfill({
      contentType: 'image/svg+xml',
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="40"><text x="4" y="24">PlantUML</text></svg>',
    }),
  )

  const markdown = `# Diagram Matrix

\`\`\`mermaid
graph TD
  A[Start] --> B[Done]
\`\`\`

\`\`\`plantuml
@startuml
Alice -> Bob: Hello
@enduml
\`\`\`

\`\`\`vega-lite
{
  "data": { "values": [{ "category": "A", "value": 1 }, { "category": "B", "value": 2 }] },
  "mark": "bar",
  "encoding": {
    "x": { "field": "category", "type": "nominal" },
    "y": { "field": "value", "type": "quantitative" }
  }
}
\`\`\`

\`\`\`flowchart
st=>start: Start
op=>operation: Process
e=>end: End
st->op->e
\`\`\`

\`\`\`sequence
Alice->Bob: Hello Bob
Bob-->Alice: Hello Alice
\`\`\`
`

  await openLocalDraft(page, {
    id: 'markdown-rendering-draft',
    markdown,
    title: /Diagram Matrix/,
    now: '2026-07-01T10:00:00.000Z',
  })

  const editor = page.getByTestId('editor-host')
  await expect(editor.locator('.mu-diagram-block')).toHaveCount(5)
  await expect(editor.locator('.mu-code-block')).toHaveCount(0)
  await expect(editor.locator('.mu-diagram-error')).toHaveCount(0)
  await expect(editor.locator('.mu-diagram-preview img[src^="https://www.plantuml.com/plantuml/svg/"]')).toHaveCount(1)
  await expect
    .poll(() => editor.locator('.mu-diagram-preview svg').count(), { timeout: 45000 })
    .toBeGreaterThanOrEqual(4)
  await expect.poll(() => getStoredDraftMarkdown(page)).toBe(markdown)
})

test('applies Editing runtime settings on editor startup without rewriting source', async ({
  page,
}) => {
  const markdown = `# Settings Matrix

\`\`\`js
const one = 1
const two = 2
\`\`\`

Footnote marker.[^note]

<div class="html-probe"><strong>Hidden HTML</strong></div>

[^note]: Footnote body.
`

  await openLocalDraft(page, {
    id: 'markdown-rendering-draft',
    markdown,
    title: /Settings Matrix/,
    now: '2026-07-01T10:00:00.000Z',
    settings: {
      codeBlockLineNumbers: true,
      footnote: true,
      isHtmlEnabled: false,
      spellcheckerEnabled: true,
      spellcheckerLanguage: 'de-DE',
    },
  })

  const editor = page.getByTestId('editor-host')
  await expect(editor.locator('.mu-code-block.mu-line-numbers')).toHaveCount(1)
  await expect(editor.locator('.mu-line-numbers-rows')).toHaveCount(1)
  await expect(editor.locator('.mu-footnote')).toHaveCount(1)
  await expect(editor.locator('.mu-html-block.mu-disable-html-render')).toHaveCount(1)
  await expect(editor.locator('[lang="de-DE"]')).toHaveCount(1)
  await expect.poll(() => getStoredDraftMarkdown(page)).toBe(markdown)
})

test('renders loaded front matter, footnotes, HTML blocks, and inline images', async ({ page }) => {
  await page.goto('/')
  const appIconUrl = await page.evaluate(() => new URL('/favicon.svg', location.href).href)

  await openLocalDraft(
    page,
    {
      id: 'markdown-rendering-draft',
      markdown: `---
title: Mobile Render Probe
---

# Extended Matrix

Reference with footnote.[^note]

Inline image: ![App icon](${appIconUrl})

<div class="html-probe"><strong>HTML Rendered</strong></div>

[^note]: Footnote body text.
`,
      title: /Extended Matrix/,
      now: '2026-07-01T10:00:00.000Z',
      settings: { footnote: true },
    },
  )

  const editor = page.getByTestId('editor-host')
  await expect(editor.locator('pre.mu-frontmatter')).toHaveCount(1)
  await expect(editor.locator('pre.mu-frontmatter')).toContainText('Mobile Render Probe')
  await expect(editor.locator('.mu-footnote')).toHaveCount(1)
  await expect(editor.locator('.mu-footnote')).toContainText('Footnote body text')
  await expect(editor.locator('.mu-html-block')).toHaveCount(1)
  await expect(editor.locator('.mu-html-preview')).toContainText('HTML Rendered')
  await expect(editor.locator('.mu-inline-image img[alt="App icon"]')).toHaveCount(1)
  await expect.poll(() => editor.locator('.mu-inline-image.mu-image-success').count()).toBeGreaterThan(0)
})

test('renders SVG in every Markdown form and keeps the source', async ({ page }) => {
  const svgDataUrl = `data:image/svg+xml;base64,${Buffer.from(
    '<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40"><rect width="40" height="40" fill="#393"/></svg>',
  ).toString('base64')}`
  const markdown = `# SVG Matrix

Inline: <svg xmlns="http://www.w3.org/2000/svg" width="80" height="40"><circle class="inline-probe" cx="20" cy="20" r="18" fill="#c98a4b"/></svg>

<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40">
  <circle class="block-probe" cx="20" cy="20" r="18" fill="#4c8492"/>
</svg>

Image: ![svg](${svgDataUrl})
`

  await openLocalDraft(page, {
    id: 'markdown-rendering-draft',
    markdown,
    title: /SVG Matrix/,
    now: '2026-07-01T10:00:00.000Z',
  })

  const editor = page.getByTestId('editor-host')
  // A single-line <svg> inside a paragraph draws its child shapes too, not
  // just an empty <svg> element.
  const inlineCircle = editor.locator('.mu-inline-svg-render circle.inline-probe')
  await expect(inlineCircle).toHaveCount(1)
  await expect.poll(async () => (await inlineCircle.boundingBox())?.width ?? 0).toBeGreaterThan(30)
  await expect(editor.locator('.mu-html-preview circle.block-probe')).toHaveCount(1)
  await expect.poll(() => editor.locator('.mu-inline-image.mu-image-success').count()).toBe(1)
  await expect.poll(() => getStoredDraftMarkdown(page)).toBe(markdown)
})

test('renders the common Mermaid diagram types', async ({ page }) => {
  const diagrams = [
    'graph TD\n  A[Start] --> B{Ok?}\n  B -->|yes| C[Done]',
    'sequenceDiagram\n  Alice->>Bob: Hello',
    'classDiagram\n  Animal <|-- Duck',
    'stateDiagram-v2\n  [*] --> Still\n  Still --> Moving',
    'gantt\n  dateFormat YYYY-MM-DD\n  section A\n  Task :a1, 2026-01-01, 3d',
    'pie title Pets\n  "Dogs" : 40\n  "Cats" : 60',
    'erDiagram\n  CUSTOMER ||--o{ ORDER : places',
    'mindmap\n  root((MarkText))\n    Android',
  ]
  const markdown = `# Mermaid Matrix\n\n${diagrams.map(body => `\`\`\`mermaid\n${body}\n\`\`\`\n`).join('\n')}`

  await openLocalDraft(page, {
    id: 'markdown-rendering-draft',
    markdown,
    title: /Mermaid Matrix/,
    now: '2026-07-01T10:00:00.000Z',
  })

  const editor = page.getByTestId('editor-host')
  await expect(editor.locator('.mu-diagram-block')).toHaveCount(diagrams.length)
  await expect
    .poll(() => editor.locator('.mu-diagram-preview svg').count(), { timeout: 45000 })
    .toBe(diagrams.length)
  await expect(editor.locator('.mu-diagram-error')).toHaveCount(0)
})
