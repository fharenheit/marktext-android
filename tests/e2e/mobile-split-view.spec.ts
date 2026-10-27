import { expect, test, type Page } from '@playwright/test'
import { openLocalDraft } from './helpers/drafts'
import { expectEditorReady } from './helpers/editor'

const MARKDOWN = '# Split title\n\nSome **bold** text.\n\n```js\nconst answer = 42\n```\n\n- [x] done\n- [ ] todo\n'

/** Optional screenshots for manual review: SCREENSHOT_DIR=/some/dir. */
async function snapshot(page: Page, name: string) {
  if (process.env.SCREENSHOT_DIR) {
    await page.screenshot({ path: `${process.env.SCREENSHOT_DIR}/${name}.png` })
  }
}

async function toggleSplitView(page: Page) {
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('split-view-toggle-button').click()
}

test('shows the source and its rendered preview together and keeps edits', async ({ page }) => {
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })

  await toggleSplitView(page)
  const source = page.getByTestId('source-mode-editor')
  const preview = page.getByTestId('split-preview')
  await expect(source).toBeVisible()
  await expect(preview).toBeVisible()
  await expect(preview.locator('h1')).toHaveText('Split title')
  await expect(preview.locator('strong')).toHaveText('bold')
  await expect(preview.locator('pre code')).toContainText('const answer = 42')
  await expect(preview.locator('input[type="checkbox"]')).toHaveCount(2)
  await snapshot(page, 'split-01')

  // The preview stacks under the source on a phone.
  const sourceBox = (await source.boundingBox())!
  const previewBox = (await preview.boundingBox())!
  expect(previewBox.y).toBeGreaterThanOrEqual(sourceBox.y + sourceBox.height - 1)

  await source.click()
  await page.keyboard.press('Control+End')
  await page.keyboard.type('\n## Added *later*\n')
  await expect(preview.locator('h2')).toHaveText('Added later')
  await expect(preview.locator('h2 em')).toHaveText('later')
  await snapshot(page, 'split-02-edited')

  // The menu entry now reads as checked; leaving returns to the WYSIWYG
  // editor with the edit applied.
  await page.getByTestId('editor-menu-button').click()
  await expect(page.getByTestId('split-view-toggle-button')).toHaveAttribute('aria-checked', 'true')
  await page.getByTestId('split-view-toggle-button').click()
  await expect(preview).toBeHidden()
  await expect(source).toBeHidden()
  await expectEditorReady(page)
  // Muya shows the caret's block with its raw syntax, so match the words only.
  await expect(page.getByTestId('editor-host').locator('h2')).toContainText('Added')
})

test('leaving source mode also closes the preview', async ({ page }) => {
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  await expect(page.getByTestId('split-preview')).toBeVisible()

  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('split-preview')).toBeHidden()
  await expectEditorReady(page)

  // Plain source mode shows no preview.
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('source-mode-editor')).toBeVisible()
  await expect(page.getByTestId('split-preview')).toHaveCount(0)
})

test('wide screens show the source and the preview side by side', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 })
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  const sourceBox = (await page.getByTestId('source-mode-editor').boundingBox())!
  const previewBox = (await page.getByTestId('split-preview').boundingBox())!
  expect(previewBox.x).toBeGreaterThanOrEqual(sourceBox.x + sourceBox.width - 1)
  expect(Math.abs(previewBox.y - sourceBox.y)).toBeLessThan(2)
  await snapshot(page, 'split-03-wide')
})
