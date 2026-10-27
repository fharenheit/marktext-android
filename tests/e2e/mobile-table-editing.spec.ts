import { expect, test, type CDPSession, type Page } from '@playwright/test'
import { getStoredDraftMarkdown, openLocalDraft } from './helpers/drafts'

const TITLE = 'Table editing doc'
const MARKDOWN = `# ${TITLE}\n\npara one\n\nline a<br/>line b\n\n| A | B |\n| --- | --- |\n| one<br/>two |  |\n| cell3 | cell4 |\n\nafter\n`

async function selectToolbarPanel(page: Page, panelId: string) {
  if (await page.getByTestId('mobile-editor-toolbar-body').isHidden()) {
    await page.getByTestId('toolbar-expand-button').click()
  }
  await page.getByTestId('toolbar-group-switcher').click()
  await page.getByTestId(`toolbar-section-option-${panelId}`).click()
}

/** Android keyboards commit text without a usable keydown (keyCode 229). */
async function androidType(client: CDPSession, text: string) {
  for (const character of text) {
    await client.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Unidentified', windowsVirtualKeyCode: 229 })
    await client.send('Input.insertText', { text: character })
    await client.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Unidentified', windowsVirtualKeyCode: 229 })
  }
}

async function storedMarkdown(page: Page, expected: string) {
  await expect.poll(() => getStoredDraftMarkdown(page), { timeout: 10000 }).toContain(expected)
  return getStoredDraftMarkdown(page)
}

test('a <br> shows as a line break and hides its raw tag away from the caret', async ({ page }) => {
  await openLocalDraft(page, { id: 'table-br', title: TITLE, markdown: MARKDOWN })
  await page.locator('.mu-editor').getByText('after').click()

  const markers = page.locator('.mu-editor .mu-html-tag .mu-html-tag')
  await expect(markers).toHaveCount(2)
  for (const marker of await markers.all()) {
    await expect(marker).toHaveClass(/mu-hide/)
  }
  await expect(page.locator('.mu-editor br')).toHaveCount(2)
})

test('block commands are unavailable in a table cell and leave the table intact', async ({ page }) => {
  await openLocalDraft(page, { id: 'table-blocks', title: TITLE, markdown: MARKDOWN })
  await page.locator('figure.mu-table').getByText('cell4').click()
  await page.keyboard.press('End')

  await selectToolbarPanel(page, 'paragraph')
  await expect(page.getByTestId('toolbar-command-paragraph.bullet-list')).toBeDisabled()
  await expect(page.getByTestId('toolbar-command-paragraph.quote-block')).toBeDisabled()
  await selectToolbarPanel(page, 'format')
  await expect(page.getByTestId('toolbar-command-format.emphasis')).toBeEnabled()

  await page.keyboard.type('X')
  const markdown = await storedMarkdown(page, 'cell4X')
  expect(markdown).toMatch(/\| cell3 +\| cell4X \|/)
  expect(markdown).not.toMatch(/^[-*] /m)
  await expect(page.locator('figure.mu-table')).toHaveCount(1)
})

test('typing * from an Android keyboard stays in the tapped cell, including an empty one', async ({ page }) => {
  await openLocalDraft(page, { id: 'table-star', title: TITLE, markdown: MARKDOWN })
  const client = await page.context().newCDPSession(page)
  // Header cells are rendered as td too: index 3 is the empty body cell.
  await page.locator('figure.mu-table td').nth(3).click()
  await androidType(client, '*b*')
  await page.locator('figure.mu-table').getByText('cell3').click()
  await page.keyboard.press('End')
  await androidType(client, ' *c*')

  const markdown = await storedMarkdown(page, 'cell3 *c*')
  expect(markdown).toMatch(/\| one<br\/>two \| \*b\* +\|/)
  expect(markdown).toMatch(/\| cell3 \*c\* +\| cell4 +\|/)
})

test('Enter while an IME is composing splits the paragraph instead of the DOM', async ({ page }) => {
  await openLocalDraft(page, { id: 'table-ime', title: TITLE, markdown: MARKDOWN })
  const client = await page.context().newCDPSession(page)
  await page.locator('.mu-editor').getByText('para one').click()
  await page.keyboard.press('End')

  // Compose a syllable, press Enter, the keyboard commits it, then the next one.
  await client.send('Input.imeSetComposition', { text: '한', selectionStart: 1, selectionEnd: 1 })
  await client.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 })
  await client.send('Input.insertText', { text: '한' })
  await client.send('Input.dispatchKeyEvent', { type: 'char', key: 'Enter', text: '\r', unmodifiedText: '\r' })
  await client.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 })
  await client.send('Input.imeSetComposition', { text: '글', selectionStart: 1, selectionEnd: 1 })
  await client.send('Input.insertText', { text: '글' })

  const markdown = await storedMarkdown(page, '글')
  expect(markdown).toContain('para one한\n\n글\n')
  await expect(page.locator('.mu-editor .mu-paragraph-content br')).toHaveCount(1) // only "line a<br/>line b"
})
