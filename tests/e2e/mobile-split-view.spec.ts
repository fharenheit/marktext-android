import { expect, test, type Page } from '@playwright/test'
import { getStoredDraftMarkdown, openLocalDraft } from './helpers/drafts'
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

test('source edits flow into the WYSIWYG pane without taking the keyboard', async ({ page }) => {
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  const source = page.getByTestId('source-mode-editor')
  const wysiwyg = page.getByTestId('editor-host')
  await expect(source).toBeVisible()
  await expect(wysiwyg).toBeVisible()
  await expect(wysiwyg.locator('h1')).toContainText('Split title')

  // The source pane comes first and the WYSIWYG pane stacks under it on a phone.
  const sourceBox = (await source.boundingBox())!
  const wysiwygBox = (await wysiwyg.boundingBox())!
  expect(wysiwygBox.y).toBeGreaterThanOrEqual(sourceBox.y + sourceBox.height - 1)

  await source.click()
  await page.keyboard.press('Control+End')
  await page.keyboard.type('\n## Added *later*\n')
  await expect(wysiwyg.locator('h2')).toContainText('Added')
  await expect(wysiwyg.locator('h2 em')).toHaveText('later')
  await expect(source).toBeFocused()
  await snapshot(page, 'split-01-source-edit')
})

test('WYSIWYG edits flow back into the source pane and both survive leaving', async ({ page }) => {
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  const source = page.getByTestId('source-mode-editor')
  const wysiwyg = page.getByTestId('editor-host')

  await wysiwyg.getByText('Some').click()
  await page.keyboard.press('End')
  await page.keyboard.type(' Typed in WYSIWYG.')
  await expect(source).toHaveValue(/Some \*\*bold\*\* text\. Typed in WYSIWYG\./)

  await source.click()
  await page.keyboard.press('Control+End')
  await page.keyboard.type('\nFrom the source.\n')
  await expect(wysiwyg).toContainText('From the source.')
  await expect.poll(() => getStoredDraftMarkdown(page)).toContain('From the source.')
  await snapshot(page, 'split-02-both-edited')

  await page.getByTestId('editor-menu-button').click()
  await expect(page.getByTestId('split-view-toggle-button')).toHaveAttribute('aria-checked', 'true')
  await page.getByTestId('split-view-toggle-button').click()
  await expect(source).toBeHidden()
  await expectEditorReady(page)
  await expect(wysiwyg).toContainText('Typed in WYSIWYG.')
  await expect(wysiwyg).toContainText('From the source.')
})

test('leaving source mode also closes the split', async ({ page }) => {
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  await expect(page.getByTestId('editor-host')).toBeVisible()

  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('source-mode-editor')).toBeHidden()
  await expectEditorReady(page)

  // Plain source mode hides the WYSIWYG pane.
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('source-mode-editor')).toBeVisible()
  await expect(page.getByTestId('editor-host')).toBeHidden()
})

test('wide screens show the source and the WYSIWYG editor side by side', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 })
  await openLocalDraft(page, { id: 'split', title: 'Split title', markdown: MARKDOWN })
  await toggleSplitView(page)
  const sourceBox = (await page.getByTestId('source-mode-editor').boundingBox())!
  const wysiwygBox = (await page.getByTestId('editor-host').boundingBox())!
  expect(wysiwygBox.x).toBeGreaterThanOrEqual(sourceBox.x + sourceBox.width - 1)
  expect(Math.abs(wysiwygBox.y - sourceBox.y)).toBeLessThan(2)
  expect(Math.abs(wysiwygBox.width - sourceBox.width)).toBeLessThan(2)
  await snapshot(page, 'split-03-wide')
})
