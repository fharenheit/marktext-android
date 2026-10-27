import { expect, test } from '@playwright/test'
import { getStoredDraftMarkdown, openLocalDraft } from './helpers/drafts'

// Written the way a user (or another editor) formats Markdown, deliberately
// unlike Muya's own style: unpadded tables, '*' bullets, '1)' numbering,
// '__strong__' and '_emphasis_'.
const TITLE = 'Formatting kept'
const SOURCE = [
  `# ${TITLE}`,
  '',
  '* first item',
  '* second item',
  '',
  '1) one',
  '2) two',
  '',
  'Some __strong__ and _emphasis_ text.',
  '',
  '|Name|Value|',
  '|-|-:|',
  '|alpha|1|',
  '|beta|2|',
  '',
  'Last line.',
  '',
].join('\n')

test('editing one table cell changes only that row on save', async ({ page }) => {
  await openLocalDraft(page, { id: 'formatting', title: TITLE, markdown: SOURCE })
  await page.locator('figure.mu-table').getByText('beta').click()
  await page.keyboard.press('End')
  await page.keyboard.type('X')

  await expect.poll(() => getStoredDraftMarkdown(page)).toContain('betaX')
  const saved = await getStoredDraftMarkdown(page)
  const changed = saved.split('\n').filter((line, index) => line !== SOURCE.split('\n')[index])
  expect(changed).toHaveLength(1)
  // The table is written compact, so the edited row is too.
  expect(changed[0]).toBe('|betaX|2|')
})

test('editing a paragraph leaves lists, tables and emphasis as written', async ({ page }) => {
  await openLocalDraft(page, { id: 'formatting', title: TITLE, markdown: SOURCE })
  await page.locator('.mu-editor').getByText('Last line.').click()
  await page.keyboard.press('End')
  await page.keyboard.type(' Edited.')

  await expect.poll(() => getStoredDraftMarkdown(page)).toContain('Last line. Edited.')
  expect(await getStoredDraftMarkdown(page)).toBe(SOURCE.replace('Last line.', 'Last line. Edited.'))
})

test('source mode shows the document as written, not re-serialized', async ({ page }) => {
  await openLocalDraft(page, { id: 'formatting', title: TITLE, markdown: SOURCE })
  await page.locator('.mu-editor').getByText('Last line.').click()
  await page.keyboard.press('End')
  await page.keyboard.type('!')
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('source-mode-toggle-button').click()
  await expect(page.getByTestId('source-mode-editor')).toHaveValue(SOURCE.replace('Last line.', 'Last line.!'))
})
