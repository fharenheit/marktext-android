import { expect, test, type Page } from '@playwright/test'
import { openLocalDraft } from './helpers/drafts'

const MARKDOWN = '# Heading\n\nBody text with `inline code` and <kbd>Ctrl</kbd>\n\n```js\nconst f = (a) => a !== b\n```\n'

async function firstFamily(page: Page, selector: string) {
  return page.locator(selector).first().evaluate(element => getComputedStyle(element).fontFamily.split(',')[0].trim())
}

async function openSourceMode(page: Page) {
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('source-mode-editor')).toBeVisible()
}

test('rendered text defaults to the system font; monospace spans and the source editor to D2Coding', async ({ page }) => {
  await openLocalDraft(page, { id: 'fonts', title: 'Heading', markdown: MARKDOWN })

  expect(await firstFamily(page, '.mu-editor p')).not.toContain('D2Coding')
  expect(await firstFamily(page, '.mu-editor code.mu-inline-rule')).toBe('"D2Coding Ligature"')
  expect(await firstFamily(page, '.mu-editor kbd')).toBe('"D2Coding Ligature"')
  expect(await firstFamily(page, '.mu-editor .mu-code-block')).toBe('"D2Coding Ligature"')

  await openSourceMode(page)
  expect(await firstFamily(page, '[data-testid="source-mode-editor"]')).toBe('"D2Coding Ligature"')
})

test('the monospace and source editor fonts follow their own settings', async ({ page }) => {
  await openLocalDraft(page, {
    id: 'fonts',
    title: 'Heading',
    markdown: MARKDOWN,
    settings: { codeFontFamily: 'dejavu-sans-mono', sourceFontFamily: 'system-mono', editorFontFamily: 'serif' },
  })

  expect(await firstFamily(page, '.mu-editor p')).toBe('Georgia')
  expect(await firstFamily(page, '.mu-editor code.mu-inline-rule')).toBe('"DejaVu Sans Mono"')
  expect(await firstFamily(page, '.mu-editor .mu-code-block')).toBe('"DejaVu Sans Mono"')

  await openSourceMode(page)
  expect(await firstFamily(page, '[data-testid="source-mode-editor"]')).toBe('ui-monospace')
})

test('monospace text is sized relative to the editor text', async ({ page }) => {
  await openLocalDraft(page, {
    id: 'fonts',
    title: 'Heading',
    markdown: MARKDOWN,
    settings: { fontSize: 20, codeFontScale: 80 },
  })
  const size = (selector: string) =>
    page.locator(selector).first().evaluate(element => getComputedStyle(element).fontSize)

  expect(await size('.mu-editor p')).toBe('20px')
  expect(await size('.mu-editor code.mu-inline-rule')).toBe('16px')
  expect(await size('.mu-editor .mu-code-block')).toBe('16px')

  await openSourceMode(page)
  expect(await size('[data-testid="source-mode-editor"]')).toBe('16px')
})
