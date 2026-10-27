import type { Page } from '@playwright/test'

export interface MockRepositoryFiles {
  [path: string]: string
}

export interface LocalRepositoriesMockOptions {
  /** HEAD (committed) contents; the working tree starts identical. */
  files: MockRepositoryFiles
  /**
   * What the next pull does. 'fast-forward' brings `incoming` in as new
   * commits, refusing (like JGit) when one of those files has uncommitted
   * edits.
   */
  pull?: 'up-to-date' | 'conflict' | 'fast-forward'
  /** The other side of a conflicting pull, per path. */
  incoming?: MockRepositoryFiles
  /** Signs in a GitHub account (CloudDocuments mock) listing these `owner/repo` names. */
  githubRepositories?: string[]
}

/**
 * In-page stand-in for the native LocalRepositories plugin: a single
 * working copy with HEAD, working tree, commits, branches and merges kept
 * in memory, wired into the Capacitor bridge installed by
 * installAndroidAppMock (call that first). `window.__repositoryMock`
 * exposes the state to assertions.
 */
export async function installLocalRepositoriesMock(page: Page, options: LocalRepositoriesMockOptions) {
  await page.addInitScript((mockOptions: LocalRepositoriesMockOptions) => {
    type Files = Record<string, string>
    interface Commit {
      sha: string
      shortSha: string
      summary: string
      message: string
      author: string
      date: string
      parentCount: number
      before: Files
      after: Files
    }
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
      __repositoryMock: unknown
    }

    const win = window as unknown as Win
    let counter = 1
    const nextSha = () => {
      counter += 1
      return String(counter).padStart(7, '0') + 'f'.repeat(33)
    }
    const initial: Commit = {
      sha: nextSha(),
      shortSha: '',
      summary: 'Initial commit',
      message: 'Initial commit',
      author: 'octocat',
      date: '2026-10-01T09:00:00Z',
      parentCount: 0,
      before: {},
      after: { ...mockOptions.files },
    }
    initial.shortSha = initial.sha.slice(0, 7)
    const state = {
      branch: 'main',
      head: { ...mockOptions.files } as Files,
      files: { ...mockOptions.files } as Files,
      commits: [initial] as Commit[],
      branches: ['main'],
      remoteBranches: ['main', 'feature/outline'],
      ahead: 0,
      merging: false,
      conflicts: [] as string[],
      mine: {} as Files,
      pull: mockOptions.pull ?? 'up-to-date',
      calls: [] as string[],
      clones: [] as Array<Record<string, unknown>>,
      /** Native per-host credential store: host -> { username, password }. */
      credentials: {} as Record<string, { username: string; password: string }>,
    }
    win.__repositoryMock = state
    const hostOf = (url: unknown) => {
      try {
        return new URL(String(url)).host.toLowerCase()
      } catch {
        return null
      }
    }

    const reject = (code: string, message: string, paths: string[] = []) =>
      Promise.reject({ code, message, data: { paths } })

    function changes() {
      const paths = new Set([...Object.keys(state.head), ...Object.keys(state.files)])
      const list: Array<{ path: string; kind: string }> = []
      for (const path of [...paths].sort()) {
        if (state.conflicts.includes(path)) {
          list.push({ path, kind: 'conflicting' })
        } else if (!(path in state.head)) {
          list.push({ path, kind: 'added' })
        } else if (!(path in state.files)) {
          list.push({ path, kind: 'deleted' })
        } else if (state.head[path] !== state.files[path]) {
          list.push({ path, kind: 'modified' })
        }
      }
      return list
    }

    function status() {
      return {
        branch: state.branch,
        state: state.merging ? (state.conflicts.length > 0 ? 'MERGING' : 'MERGING_RESOLVED') : 'SAFE',
        ahead: state.ahead,
        behind: 0,
        hasUpstream: true,
        changes: changes(),
      }
    }

    function summary() {
      return {
        id: 'octocat-notes',
        name: 'octocat/notes',
        remoteUrl: 'https://github.com/octocat/notes.git',
        branch: state.branch,
        changeCount: changes().length,
        ahead: state.ahead,
        behind: 0,
        state: status().state,
        workTreeUri: 'file:///data/repositories/octocat-notes',
      }
    }

    function commit(message: string, parentCount = 1) {
      const created: Commit = {
        sha: nextSha(),
        shortSha: '',
        summary: message.split('\n')[0],
        message,
        author: 'octocat',
        date: '2026-10-27T10:00:00Z',
        parentCount,
        before: { ...state.head },
        after: { ...state.files },
      }
      created.shortSha = created.sha.slice(0, 7)
      state.commits.unshift(created)
      state.head = { ...state.files }
      state.ahead += 1
      return created
    }

    function describe(created: Commit) {
      return {
        sha: created.sha,
        shortSha: created.shortSha,
        summary: created.summary,
        message: created.message,
        author: created.author,
        date: created.date,
        parentCount: created.parentCount,
      }
    }

    function commitFiles(created: Commit) {
      const paths = new Set([...Object.keys(created.before), ...Object.keys(created.after)])
      return [...paths].sort().flatMap(path => {
        const oldText = created.before[path] ?? null
        const newText = created.after[path] ?? null
        if (oldText === newText) {
          return []
        }
        return [{
          changeType: oldText === null ? 'add' : newText === null ? 'delete' : 'modify',
          oldPath: oldText === null ? null : path,
          newPath: newText === null ? null : path,
          oldText,
          newText,
          binary: false,
          tooLarge: false,
        }]
      })
    }

    function handle(method: string, args: Record<string, unknown>): Promise<unknown> {
      state.calls.push(method)
      const path = typeof args.path === 'string' ? args.path : ''
      switch (method) {
        case 'listRepositories':
          return Promise.resolve({ repositories: [summary()] })
        case 'cloneRepository': {
          state.clones.push({ ...args })
          const host = hostOf(args.url)
          if (host && host !== 'github.com' && typeof args.username === 'string' && args.username) {
            const saved = state.credentials[host]
            const password = typeof args.password === 'string' && args.password
              ? args.password
              : saved?.username === args.username ? saved.password : ''
            if (args.remember !== false) {
              state.credentials[host] = { username: args.username, password }
            }
          }
          return Promise.resolve(summary())
        }
        case 'getGitCredentials': {
          const host = hostOf(args.url)
          const saved = host ? state.credentials[host] : undefined
          return Promise.resolve({ host, username: saved?.username ?? null, hasPassword: Boolean(saved?.password) })
        }
        case 'forgetGitCredentials': {
          const host = hostOf(args.url)
          if (host) {
            delete state.credentials[host]
          }
          return Promise.resolve({})
        }
        case 'deleteRepository':
          return Promise.resolve({})
        case 'getRepositoryStatus':
          return Promise.resolve(status())
        case 'listRepositoryFolder': {
          const prefix = path ? `${path}/` : ''
          const seen = new Map<string, { name: string; path: string; isFolder: boolean; size: number }>()
          for (const file of Object.keys(state.files)) {
            if (!file.startsWith(prefix)) {
              continue
            }
            const rest = file.slice(prefix.length)
            const [first, ...more] = rest.split('/')
            seen.set(first, { name: first, path: prefix + first, isFolder: more.length > 0, size: 1 })
          }
          const entries = [...seen.values()].sort((a, b) =>
            a.isFolder !== b.isFolder ? (a.isFolder ? -1 : 1) : a.name.localeCompare(b.name))
          return Promise.resolve({ entries })
        }
        case 'readRepositoryDocument':
          if (!(path in state.files)) {
            return reject('DOCUMENT_NOT_FOUND', 'This file is not in the repository anymore')
          }
          return Promise.resolve({
            path,
            displayName: path.split('/').pop(),
            repositoryName: 'octocat/notes',
            workTreeUri: 'file:///data/repositories/octocat-notes',
            markdown: state.files[path],
            encoding: 'utf8',
            hasEncodingBom: false,
          })
        case 'writeRepositoryDocument':
          state.files[path] = String(args.markdown)
          return Promise.resolve({ path, displayName: path.split('/').pop() })
        case 'getRepositoryFileDiff':
          return Promise.resolve({
            path,
            oldText: state.head[path] ?? null,
            newText: state.files[path] ?? null,
            binary: false,
            tooLarge: false,
          })
        case 'commitRepository':
          if (changes().length === 0) {
            return reject('NOTHING_TO_COMMIT', 'There are no changes to commit')
          }
          return Promise.resolve(describe(commit(String(args.message))))
        case 'pushRepository':
          state.ahead = 0
          return Promise.resolve(status())
        case 'pullRepository': {
          if (state.pull === 'fast-forward') {
            const incoming = Object.entries(mockOptions.incoming ?? {})
            const blocked = incoming.map(([file]) => file).filter(file => state.files[file] !== state.head[file])
            if (blocked.length) {
              return reject('LOCAL_CHANGES_BLOCK_PULL', 'Local changes would be overwritten', blocked)
            }
            state.pull = 'up-to-date'
            for (const [file, theirs] of incoming) {
              state.head[file] = theirs
              state.files[file] = theirs
            }
            return Promise.resolve({ result: 'fast-forward', conflicts: [], status: status() })
          }
          if (state.pull !== 'conflict') {
            return Promise.resolve({ result: 'up-to-date', conflicts: [], status: status() })
          }
          state.pull = 'up-to-date'
          state.merging = true
          for (const [file, theirs] of Object.entries(mockOptions.incoming ?? {})) {
            state.mine[file] = state.files[file]
            state.files[file] = `<<<<<<< HEAD\n${state.files[file]}=======\n${theirs}>>>>>>> origin/main\n`
            state.conflicts.push(file)
          }
          return Promise.resolve({ result: 'conflicts', conflicts: [...state.conflicts], status: status() })
        }
        case 'resolveRepositoryConflict': {
          const choice = String(args.choice)
          if (choice === 'ours') {
            state.files[path] = state.mine[path]
          } else if (choice === 'theirs') {
            state.files[path] = (mockOptions.incoming ?? {})[path]
          }
          state.conflicts = state.conflicts.filter(conflict => conflict !== path)
          return Promise.resolve(status())
        }
        case 'completeRepositoryMerge':
          if (state.conflicts.length > 0) {
            return reject('CONFLICTS_UNRESOLVED', 'Resolve every conflict before committing')
          }
          state.merging = false
          return Promise.resolve(describe(commit("Merge branch 'main' of origin", 2)))
        case 'abortRepositoryMerge':
          state.merging = false
          state.conflicts = []
          state.files = { ...state.head }
          return Promise.resolve(status())
        case 'discardRepositoryChanges': {
          const paths = Array.isArray(args.paths) ? (args.paths as string[]) : []
          for (const file of paths.length ? paths : Object.keys({ ...state.files, ...state.head })) {
            if (file in state.head) {
              state.files[file] = state.head[file]
            } else {
              delete state.files[file]
            }
          }
          return Promise.resolve(status())
        }
        case 'listRepositoryBranches':
          return Promise.resolve({
            branches: [...new Set([...state.branches, ...state.remoteBranches])].map(name => ({
              name,
              current: name === state.branch,
              local: state.branches.includes(name),
              remote: state.remoteBranches.includes(name),
            })).sort((a, b) => (a.current ? -1 : b.current ? 1 : a.name.localeCompare(b.name))),
          })
        case 'checkoutRepositoryBranch': {
          const name = String(args.name)
          if (args.create && state.branches.includes(name)) {
            return reject('BRANCH_EXISTS', 'A branch with this name already exists')
          }
          if (!state.branches.includes(name)) {
            state.branches.push(name)
          }
          state.branch = name
          return Promise.resolve(status())
        }
        case 'getRepositoryHistory': {
          const skip = Number(args.skip ?? 0)
          const max = Number(args.maxCount ?? 50)
          return Promise.resolve({ commits: state.commits.slice(skip, skip + max).map(describe) })
        }
        case 'getRepositoryCommitDiff': {
          const found = state.commits.find(item => item.sha === args.sha)
          return found
            ? Promise.resolve({ files: commitFiles(found) })
            : reject('COMMIT_NOT_FOUND', 'This commit does not exist')
        }
        case 'revertRepositoryCommit': {
          const found = state.commits.find(item => item.sha === args.sha)
          if (!found) {
            return reject('COMMIT_NOT_FOUND', 'This commit does not exist')
          }
          for (const file of new Set([...Object.keys(found.before), ...Object.keys(found.after)])) {
            if (file in found.before) {
              state.files[file] = found.before[file]
            } else {
              delete state.files[file]
            }
          }
          commit(`Revert "${found.summary}"`)
          return Promise.resolve({ result: 'merged', conflicts: [], status: status() })
        }
        default:
          return reject('UNIMPLEMENTED', `LocalRepositories.${method} is not mocked`)
      }
    }

    const methods = [
      'listRepositories', 'cloneRepository', 'deleteRepository', 'getRepositoryStatus', 'listRepositoryFolder',
      'readRepositoryDocument', 'writeRepositoryDocument', 'getRepositoryFileDiff', 'commitRepository',
      'pushRepository', 'pullRepository', 'resolveRepositoryConflict', 'completeRepositoryMerge',
      'abortRepositoryMerge', 'discardRepositoryChanges', 'listRepositoryBranches', 'checkoutRepositoryBranch',
      'getRepositoryHistory', 'getRepositoryCommitDiff', 'revertRepositoryCommit', 'removeListener',
      'getGitCredentials', 'forgetGitCredentials',
    ]
    win.Capacitor.PluginHeaders.push({
      name: 'LocalRepositories',
      methods: [
        { name: 'addListener', rtype: 'callback' },
        ...methods.map(name => ({ name, rtype: 'promise' })),
      ],
    })
    const githubRepositories = mockOptions.githubRepositories
    if (githubRepositories) {
      win.Capacitor.PluginHeaders.push({
        name: 'CloudDocuments',
        methods: [
          { name: 'addListener', rtype: 'callback' },
          ...['removeListener', 'getCloudAccountState', 'listCloudFolder'].map(name => ({ name, rtype: 'promise' })),
        ],
      })
    }
    const basePromise = win.Capacitor.nativePromise.bind(win.Capacitor)
    const baseCallback = win.Capacitor.nativeCallback.bind(win.Capacitor)
    win.Capacitor.nativePromise = (plugin, method, args = {}) => {
      if (plugin === 'CloudDocuments' && githubRepositories) {
        if (method === 'getCloudAccountState') {
          return Promise.resolve(args.provider === 'github'
            ? { connected: true, available: true, deviceFlowAvailable: false, accountName: 'octocat' }
            : { connected: false, available: false, accountName: null })
        }
        if (method === 'listCloudFolder') {
          return Promise.resolve({
            entries: githubRepositories.map(name => ({ id: name, name, isFolder: true, size: 0, lastModified: '' })),
          })
        }
        return Promise.resolve({})
      }
      if (plugin !== 'LocalRepositories') {
        return basePromise(plugin, method, args)
      }
      if (method === 'removeListener') {
        return Promise.resolve()
      }
      return handle(method, args)
    }
    win.Capacitor.nativeCallback = (plugin, method, args, callback) => {
      if ((plugin === 'LocalRepositories' || (plugin === 'CloudDocuments' && githubRepositories)) && method === 'addListener') {
        return Promise.resolve(`local-repositories-listener-${String(args.eventName)}`)
      }
      return baseCallback(plugin, method, args, callback)
    }
  }, options)
}
