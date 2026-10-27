import { expect, test, type Page } from '@playwright/test'
import { installAndroidAppMock } from './helpers/androidAppMock'

const GOOD_TOKEN = 'github_pat_11ABCDEFG0123456789_goodtoken'

/**
 * CloudDocuments stand-in for a build without a GitHub OAuth client id:
 * the device flow is off, token sign-in is the only way in.
 */
async function installGitHubTokenMock(page: Page) {
  await page.addInitScript((goodToken: string) => {
    interface Win {
      Capacitor: {
        PluginHeaders: Array<{ name: string; methods: Array<{ name: string; rtype: string }> }>
        nativePromise: (plugin: string, method: string, options?: Record<string, unknown>) => Promise<unknown>
        nativeCallback: (
          plugin: string,
          method: string,
          options: Record<string, unknown>,
          callback?: (data: unknown) => void,
        ) => Promise<unknown>
      }
    }
    const win = window as unknown as Win
    let connected = false
    const methods = [
      'removeListener', 'getCloudAccountState', 'connectCloudAccount', 'connectGitHubWithToken',
      'cancelCloudAuth', 'disconnectCloudAccount', 'listCloudFolder', 'setCloudImageContext',
    ]
    win.Capacitor.PluginHeaders.push({
      name: 'CloudDocuments',
      methods: [{ name: 'addListener', rtype: 'callback' }, ...methods.map(name => ({ name, rtype: 'promise' }))],
    })
    const basePromise = win.Capacitor.nativePromise.bind(win.Capacitor)
    const baseCallback = win.Capacitor.nativeCallback.bind(win.Capacitor)
    win.Capacitor.nativePromise = (plugin, method, args = {}) => {
      if (plugin !== 'CloudDocuments') {
        return basePromise(plugin, method, args)
      }
      if (method === 'getCloudAccountState') {
        return args.provider === 'github'
          ? Promise.resolve({ connected, available: true, deviceFlowAvailable: false, accountName: connected ? 'octocat' : null })
          : Promise.resolve({ connected: false, available: false, accountName: null })
      }
      if (method === 'connectGitHubWithToken') {
        if (args.token !== goodToken) {
          return Promise.reject(Object.assign(new Error('GitHub did not accept this token'), { code: 'CLOUD_TOKEN_INVALID' }))
        }
        connected = true
        return Promise.resolve({ connected: true, accountName: 'octocat' })
      }
      if (method === 'listCloudFolder') {
        return Promise.resolve({
          entries: [{ id: 'octocat/notes', name: 'octocat/notes', isFolder: true, size: 0, lastModified: '' }],
        })
      }
      return Promise.resolve({})
    }
    win.Capacitor.nativeCallback = (plugin, method, args, callback) => {
      if (plugin === 'CloudDocuments' && method === 'addListener') {
        return Promise.resolve(`cloud-listener-${String(args.eventName)}`)
      }
      return baseCallback(plugin, method, args, callback)
    }
  }, GOOD_TOKEN)
}

test('signs in to GitHub with a personal access token when the build has no client id', async ({ page }) => {
  await installAndroidAppMock(page)
  await installGitHubTokenMock(page)
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.reload()

  await page.getByTestId('open-file-button').click()
  const githubRow = page.getByRole('button', { name: /GitHub/ }).first()
  await expect(githubRow).toBeEnabled()
  await expect(githubRow).not.toContainText('Not configured')
  await githubRow.click()

  await expect(page.getByTestId('github-token-form')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign in to GitHub' })).toHaveCount(0)
  await expect(page.getByTestId('github-token-connect')).toBeDisabled()

  await page.getByTestId('github-token-input').fill('ghp_this_one_is_wrong_000000000')
  await page.getByTestId('github-token-connect').click()
  await expect(page.getByRole('alert')).toContainText('did not accept this token')

  await page.getByTestId('github-token-input').fill(GOOD_TOKEN)
  await page.getByTestId('github-token-connect').click()
  await expect(page.getByText('octocat/notes')).toBeVisible()
  await expect(page.getByText('octocat', { exact: true })).toBeVisible()
})
