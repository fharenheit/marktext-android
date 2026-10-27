import { expect, test, type Page } from '@playwright/test'
import { installAndroidAppMock } from './helpers/androidAppMock'
import { expectEditorReady } from './helpers/editor'
import { installLocalRepositoriesMock, type LocalRepositoriesMockOptions } from './helpers/localRepositoriesMock'

test.describe.configure({ timeout: 90000 })

const FILES = {
  'README.md': '# Notes\n\nfirst line\n',
  'docs/guide.md': '# Guide\n\nstep one\n',
}

/** Optional screenshots for manual review: SCREENSHOT_DIR=/some/dir. */
async function snapshot(page: Page, name: string) {
  if (process.env.SCREENSHOT_DIR) {
    await page.screenshot({ path: `${process.env.SCREENSHOT_DIR}/${name}.png` })
  }
}

async function openRepository(page: Page, options: Partial<LocalRepositoriesMockOptions> = {}) {
  await installAndroidAppMock(page)
  await installLocalRepositoriesMock(page, { files: FILES, ...options })
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.reload()

  await page.getByTestId('open-file-button').click()
  await page.getByTestId('open-repositories').click()
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible()
  await snapshot(page, 'repo-01-list')
  await page.getByTestId('repository-octocat-notes').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()
}

async function editDocument(page: Page, path: string, line: string) {
  await page.getByTestId(`repository-entry-${path}`).click()
  await expectEditorReady(page)
  await page.getByTestId('editor-host').click()
  await page.keyboard.press('Control+End')
  await page.keyboard.press('Enter')
  await page.keyboard.type(line)
  await expect(page.getByTestId('editor-host')).toContainText(line)
}

async function mockState(page: Page) {
  return page.evaluate(() => (window as unknown as { __repositoryMock: Record<string, unknown> }).__repositoryMock)
}

test('edits a working-copy file, reviews the diff and commits it', async ({ page }) => {
  await openRepository(page)
  await expect(page.getByTestId('repository-entry-docs')).toBeVisible()
  await snapshot(page, 'repo-02-files')

  await editDocument(page, 'README.md', 'added on the phone')

  // Leaving the editor autosaves into the working tree and returns here.
  await page.getByTestId('back-button').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()
  await expect.poll(async () => ((await mockState(page)).files as Record<string, string>)['README.md'])
    .toContain('added on the phone')

  await page.getByTestId('repository-tab-changes').click()
  await expect(page.getByTestId('repository-tab-changes')).toContainText('(1)')
  await snapshot(page, 'repo-03-changes')

  await page.getByTestId('repository-change-README.md').click()
  const diff = page.getByTestId('repository-file-diff')
  await expect(diff).toBeVisible()
  await expect(diff.locator('.diff-line-add').filter({ hasText: 'added on the phone' })).toHaveCount(1)
  await expect(diff.locator('.diff-summary')).toContainText('+')
  await snapshot(page, 'repo-04-diff')
  await page.keyboard.press('Escape')
  await diff.getByRole('button', { name: 'Back' }).click()

  await page.getByTestId('repository-commit-message').fill('Add a phone note')
  await page.getByTestId('repository-commit').click()
  await expect(page.getByTestId('repository-notice')).toContainText('Committed')
  await expect(page.getByText('No uncommitted changes.')).toBeVisible()
  await expect(page.getByTestId('repository-screen')).toContainText('1 to push')

  await page.getByTestId('repository-push').click()
  await expect(page.getByTestId('repository-notice')).toContainText('Pushed')
  expect((await mockState(page)).ahead).toBe(0)
})

test('shows and reverts a working-copy document from the editor', async ({ page }) => {
  await openRepository(page)
  await page.getByTestId('repository-entry-docs').click()
  await editDocument(page, 'docs/guide.md', 'a mistake')

  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('show-changes-button').click()
  const changes = page.getByTestId('document-changes')
  await expect(changes).toBeVisible()
  await expect(changes.locator('.diff-line-add').filter({ hasText: 'a mistake' })).toHaveCount(1)
  await expect(changes).toContainText('since the last commit')
  await snapshot(page, 'repo-05-editor-changes')

  await page.getByTestId('document-changes-revert').click()
  await page.getByTestId('repository-confirm').click()
  await expectEditorReady(page)
  await expect(page.getByTestId('editor-host')).not.toContainText('a mistake')
  await expect(page.getByTestId('editor-host')).toContainText('step one')
  expect(((await mockState(page)).files as Record<string, string>)['docs/guide.md']).toBe(FILES['docs/guide.md'])
})

test('pulls with a conflict, keeps one side and completes the merge', async ({ page }) => {
  await openRepository(page, {
    pull: 'conflict',
    incoming: { 'README.md': '# Notes\n\nfrom the laptop\n' },
  })

  await page.getByTestId('repository-pull').click()
  await expect(page.getByTestId('repository-notice')).toContainText('1 file(s) have conflicts')
  const banner = page.getByTestId('repository-merge-banner')
  await expect(banner).toBeVisible()
  await expect(banner).toContainText('README.md')
  await expect(page.getByTestId('repository-complete-merge')).toBeDisabled()
  await expect(page.getByTestId('repository-pull')).toBeDisabled()
  await snapshot(page, 'repo-06-conflict')

  await banner.getByRole('button', { name: 'Keep theirs' }).click()
  await expect(page.getByTestId('repository-complete-merge')).toBeEnabled()
  await page.getByTestId('repository-complete-merge').click()
  await expect(page.getByTestId('repository-notice')).toContainText('Committed')
  await expect(banner).toBeHidden()
  expect(((await mockState(page)).head as Record<string, string>)['README.md']).toContain('from the laptop')
})

test('reverts uncommitted edits per file from the Changes tab', async ({ page }) => {
  await openRepository(page)
  await editDocument(page, 'README.md', 'throw this away')
  await page.getByTestId('back-button').click()
  await page.getByTestId('repository-tab-changes').click()

  await page.getByRole('button', { name: 'Revert', exact: true }).click()
  await expect(page.getByTestId('repository-confirm-sheet')).toContainText('README.md')
  await page.getByTestId('repository-confirm').click()
  await expect(page.getByText('No uncommitted changes.')).toBeVisible()
  expect(((await mockState(page)).files as Record<string, string>)['README.md']).toBe(FILES['README.md'])
})

test('switches and creates branches', async ({ page }) => {
  await openRepository(page)
  await page.getByTestId('repository-tab-branches').click()
  await expect(page.getByTestId('repository-branch-main')).toContainText('Current branch')
  await expect(page.getByTestId('repository-branch-feature/outline')).toContainText('On the remote only')
  await snapshot(page, 'repo-07-branches')

  await page.getByTestId('repository-branch-feature/outline').click()
  await page.getByTestId('repository-confirm').click()
  await expect(page.locator('#repository-title')).toContainText('feature/outline')

  await page.getByPlaceholder('Branch name').fill('bad..name')
  await expect(page.getByRole('button', { name: 'Create and switch' })).toBeDisabled()
  await page.getByPlaceholder('Branch name').fill('notes/today')
  await page.getByRole('button', { name: 'Create and switch' }).click()
  await expect(page.getByTestId('repository-branch-notes/today')).toContainText('Current branch')
})

test('browses history, shows a commit diff and reverts the commit', async ({ page }) => {
  await openRepository(page)
  await editDocument(page, 'README.md', 'regrettable line')
  await page.getByTestId('back-button').click()
  await page.getByTestId('repository-tab-changes').click()
  await page.getByTestId('repository-commit-message').fill('Regrettable change')
  await page.getByTestId('repository-commit').click()
  await expect(page.getByTestId('repository-notice')).toContainText('Committed')

  await page.getByTestId('repository-tab-history').click()
  await expect(page.getByText('Regrettable change')).toBeVisible()
  await expect(page.getByText('Initial commit')).toBeVisible()
  await snapshot(page, 'repo-08-history')

  await page.getByText('Regrettable change').click()
  const detail = page.getByTestId('repository-commit-detail')
  await expect(detail).toBeVisible()
  await expect(detail.locator('.diff-line-add').filter({ hasText: 'regrettable line' })).toHaveCount(1)
  await snapshot(page, 'repo-09-commit')

  await page.getByTestId('repository-revert-commit').click()
  await page.getByTestId('repository-confirm').click()
  await expect(page.getByTestId('repository-notice')).toContainText('with a new commit')
  await expect(page.getByText('Revert "Regrettable change"')).toBeVisible()
  expect(((await mockState(page)).files as Record<string, string>)['README.md']).toBe(FILES['README.md'])
})

test('Android Back unwinds the repository views before leaving', async ({ page }) => {
  await openRepository(page)
  await page.getByTestId('repository-entry-docs').click()
  await expect(page.getByTestId('repository-entry-docs/guide.md')).toBeVisible()

  const back = () => page.evaluate(() =>
    (window as unknown as { __emitCapacitorAppBackButton: () => void }).__emitCapacitorAppBackButton())
  await back()
  await expect(page.getByTestId('repository-entry-README.md')).toBeVisible()
  await back()
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible()
  await back()
  await expect(page.getByRole('heading', { name: 'Open', exact: true })).toBeVisible()
})
