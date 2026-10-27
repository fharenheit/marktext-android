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

  // Nothing left to commit: the push sheet only pushes the waiting commit.
  await page.getByTestId('repository-push').click()
  await expect(page.getByTestId('push-sheet')).toContainText('1 local commit(s) will be pushed')
  await expect(page.getByTestId('push-sheet-message')).toHaveCount(0)
  await page.getByTestId('push-sheet-confirm').click()
  await expect(page.getByTestId('repository-notice')).toContainText('Pushed')
  expect((await mockState(page)).ahead).toBe(0)
})

test('push lists the changed files, takes a message, then commits and pushes in one step', async ({ page }) => {
  await openRepository(page)
  await editDocument(page, 'README.md', 'pushed from the phone')
  await page.getByTestId('back-button').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()

  await page.getByTestId('repository-push').click()
  const sheet = page.getByTestId('push-sheet')
  await expect(sheet).toBeVisible()
  await expect(page.getByTestId('push-sheet-files')).toContainText('README.md')
  await expect(page.getByTestId('push-sheet-message')).toHaveValue('Update README.md')
  await snapshot(page, 'repo-13-push-sheet')

  await page.getByTestId('push-sheet-message').fill('')
  await expect(page.getByTestId('push-sheet-confirm')).toBeDisabled()
  await page.getByTestId('push-sheet-message').fill('Phone notes')
  await expect(page.getByTestId('push-sheet-confirm')).toHaveText('Commit & push')
  await page.getByTestId('push-sheet-confirm').click()

  await expect(sheet).toBeHidden()
  await expect(page.getByTestId('repository-notice')).toContainText('Committed and pushed')
  const state = await mockState(page)
  expect(state.ahead).toBe(0)
  expect((state.commits as Array<{ summary: string }>)[0].summary).toBe('Phone notes')
  expect((state.head as Record<string, string>)['README.md']).toContain('pushed from the phone')
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

async function openRepositoryList(page: Page, options: Partial<LocalRepositoriesMockOptions> = {}) {
  await installAndroidAppMock(page)
  await installLocalRepositoriesMock(page, { files: FILES, ...options })
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.reload()
  await page.getByTestId('open-file-button').click()
  await page.getByTestId('open-repositories').click()
  await expect(page.getByRole('heading', { name: 'Repositories' })).toBeVisible()
}

test('clones from the signed-in GitHub account by searching its repositories', async ({ page }) => {
  await openRepositoryList(page, {
    githubRepositories: ['octocat/notes', 'octocat/website', 'acme/handbook'],
  })
  await page.getByTestId('repository-clone-open').click()
  await expect(page.getByTestId('repository-clone-source-github')).toHaveAttribute('aria-checked', 'true')
  const list = page.getByTestId('repository-clone-github-list')
  await expect(list.getByRole('button')).toHaveCount(3)
  await expect(page.getByTestId('repository-clone-confirm')).toBeDisabled()

  await page.getByTestId('repository-clone-search').fill('HAND')
  await expect(list.getByRole('button')).toHaveCount(1)
  await snapshot(page, 'repo-10-clone-github')
  await page.getByTestId('repository-clone-pick-acme/handbook').click()
  await page.getByTestId('repository-clone-confirm').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()
  const clones = (await mockState(page)).clones as Array<Record<string, unknown>>
  expect(clones.at(-1)).toMatchObject({ url: 'https://github.com/acme/handbook.git' })
  expect(clones.at(-1)?.username).toBeUndefined()
})

test('clones any Git host with a username and token that are remembered next time', async ({ page }) => {
  await openRepositoryList(page)
  await page.getByTestId('repository-clone-open').click()
  await page.getByTestId('repository-clone-source-git').click()
  await page.getByTestId('repository-clone-url').fill('https://git.example.org/team/notes.git')
  await page.getByTestId('repository-clone-username').fill('alice')
  await page.getByTestId('repository-clone-password').fill('glpat-secret')
  await snapshot(page, 'repo-11-clone-git')
  await page.getByTestId('repository-clone-confirm').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()
  let clones = (await mockState(page)).clones as Array<Record<string, unknown>>
  expect(clones.at(-1)).toMatchObject({
    url: 'https://git.example.org/team/notes.git',
    username: 'alice',
    password: 'glpat-secret',
    remember: true,
  })

  // The form comes back on the Git tab with the address and username filled
  // in; the password stays native and an empty field reuses it.
  await page.getByRole('button', { name: 'Back' }).first().click()
  await page.getByTestId('repository-clone-open').click()
  await expect(page.getByTestId('repository-clone-source-git')).toHaveAttribute('aria-checked', 'true')
  await expect(page.getByTestId('repository-clone-url')).toHaveValue('https://git.example.org/team/notes.git')
  await expect(page.getByTestId('repository-clone-username')).toHaveValue('alice')
  await expect(page.getByTestId('repository-clone-password')).toHaveValue('')
  await expect(page.getByTestId('repository-clone-password')).toHaveAttribute('placeholder', /Saved password/)
  await page.getByTestId('repository-clone-confirm').click()
  await expect(page.getByTestId('repository-screen')).toBeVisible()
  clones = (await mockState(page)).clones as Array<Record<string, unknown>>
  expect(clones.at(-1)).toMatchObject({ username: 'alice' })
  expect(clones.at(-1)?.password).toBeUndefined()
  expect(((await mockState(page)).credentials as Record<string, { password: string }>)['git.example.org'].password)
    .toBe('glpat-secret')
})

async function pullLatestFromEditor(page: Page) {
  await page.getByTestId('editor-menu-button').click()
  await page.getByTestId('pull-latest-button').click()
}

test('pulls the latest version into an edited document and merges both', async ({ page }) => {
  await openRepository(page, {
    pull: 'fast-forward',
    incoming: { 'README.md': '# Notes (updated)\n\nfirst line\n' },
  })
  await editDocument(page, 'README.md', 'added on the phone')

  await pullLatestFromEditor(page)
  await expect(page.getByTestId('editor-host')).toContainText('Notes (updated)')
  await expect(page.getByTestId('editor-host')).toContainText('added on the phone')
  const state = await mockState(page)
  expect((state.head as Record<string, string>)['README.md']).toBe('# Notes (updated)\n\nfirst line\n')
  expect((state.files as Record<string, string>)['README.md']).toBe(
    '# Notes (updated)\n\nfirst line\n\nadded on the phone\n',
  )
  await snapshot(page, 'repo-10-pull-merged')
})

test('a clashing pull asks, and "keep mine" keeps the edit while merging the rest', async ({ page }) => {
  await openRepository(page, {
    pull: 'fast-forward',
    incoming: { 'docs/guide.md': '# Guide (v2)\n\nstep one, revised\n' },
  })
  await page.getByTestId('repository-entry-docs').click()
  await page.getByTestId('repository-entry-docs/guide.md').click()
  await expectEditorReady(page)
  await page.getByTestId('editor-host').getByText('step one').click()
  await page.keyboard.press('End')
  await page.keyboard.type(' on the phone')
  await expect.poll(async () => ((await mockState(page)).files as Record<string, string>)['docs/guide.md'])
    .toContain('step one on the phone')

  await pullLatestFromEditor(page)
  await expect(page.getByTestId('merge-conflict-sheet')).toContainText('1')
  await snapshot(page, 'repo-11-merge-conflict')
  await page.getByTestId('merge-conflict-ours').click()

  await expect(page.getByTestId('editor-host')).toContainText('Guide (v2)')
  await expect(page.getByTestId('editor-host')).toContainText('step one on the phone')
  expect(((await mockState(page)).files as Record<string, string>)['docs/guide.md']).toBe(
    '# Guide (v2)\n\nstep one on the phone\n',
  )
})

test('conflict markers land in the source pane of the split view', async ({ page }) => {
  await openRepository(page, {
    pull: 'fast-forward',
    incoming: { 'README.md': '# Notes\n\nlaptop line\n' },
  })
  await page.getByTestId('repository-entry-README.md').click()
  await expectEditorReady(page)
  await page.getByTestId('editor-host').getByText('first line').click()
  await page.keyboard.press('End')
  await page.keyboard.type(' phone')
  await expect.poll(async () => ((await mockState(page)).files as Record<string, string>)['README.md'])
    .toContain('first line phone')

  await pullLatestFromEditor(page)
  await page.getByTestId('merge-conflict-markers').click()
  const source = page.getByTestId('source-mode-editor')
  await expect(source).toBeVisible()
  await expect(page.getByTestId('editor-host')).toBeVisible()
  await expect(source).toHaveValue(
    /<<<<<<< my edits\nfirst line phone\n=======\nlaptop line\n>>>>>>> latest version/,
  )
  await snapshot(page, 'repo-12-conflict-markers')
  // The raw markers are what gets saved, not a WYSIWYG re-serialization.
  await expect.poll(async () => ((await mockState(page)).files as Record<string, string>)['README.md'])
    .toContain('=======\nlaptop line\n>>>>>>> latest version')
})

test('the editor file panel jumps to another file without leaving the editor', async ({ page }) => {
  await openRepository(page)
  await editDocument(page, 'README.md', 'edited before switching')

  await page.getByTestId('file-switcher-open-button').click()
  const panel = page.getByTestId('file-switcher')
  await expect(panel).toBeVisible()
  await expect(page.getByTestId('file-switcher-entry-README.md')).toHaveAttribute('aria-current', 'page')
  await page.waitForTimeout(400)
  await snapshot(page, 'repo-14-file-panel')

  // Into a folder and open a file there: the editor switches directly.
  await page.getByTestId('file-switcher-entry-docs').click()
  await expect(page.getByTestId('file-switcher-folder')).toContainText('docs')
  await page.getByTestId('file-switcher-entry-docs/guide.md').click()
  await expect(panel).toBeHidden()
  await expectEditorReady(page)
  await expect(page.getByTestId('editor-host')).toContainText('step one')

  // The previous file's edit was saved before switching.
  expect(((await mockState(page)).files as Record<string, string>)['README.md']).toContain('edited before switching')

  // Back in the panel: it starts in this file's folder; Up and Back work.
  await page.getByTestId('file-switcher-open-button').click()
  await expect(page.getByTestId('file-switcher-entry-docs/guide.md')).toHaveAttribute('aria-current', 'page')
  await page.getByTestId('file-switcher-up').click()
  await expect(page.getByTestId('file-switcher-entry-README.md')).toBeVisible()
  await page.evaluate(() => (window as unknown as { __emitCapacitorAppBackButton: () => void }).__emitCapacitorAppBackButton())
  await expect(panel).toBeHidden()
  await expect(page.getByTestId('editor-host')).toContainText('step one')
})
