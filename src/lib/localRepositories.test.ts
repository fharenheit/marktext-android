import { beforeEach, describe, expect, it } from 'vitest'
import { useI18n } from './i18n'
import { getRepositoryUserMessage, RepositoryError } from './localRepositories'

const { setLocale } = useI18n()

beforeEach(() => {
  setLocale('en')
})

describe('getRepositoryUserMessage', () => {
  it('names the files that block a pull', () => {
    expect(getRepositoryUserMessage(new RepositoryError('LOCAL_CHANGES_BLOCK_PULL', '', ['a.md', 'b.md'])))
      .toBe('Pulling would overwrite uncommitted edits. Commit or revert your edits to these files first. Files: a.md, b.md')
  })

  it('tailors a refused sign-in to GitHub or another Git host', () => {
    const error = new RepositoryError('AUTH_FAILED', 'not authorized')
    expect(getRepositoryUserMessage(error, 'https://github.com/o/r.git')).toContain('Reconnect the GitHub account')
    expect(getRepositoryUserMessage(error, 'https://git.example.org/o/r.git'))
      .toBe('The sign-in was refused. Check the username and password or token for this Git host.')
    expect(getRepositoryUserMessage(error)).toContain('GitHub account, or the username')
  })

  it('carries the native detail of a refused request', () => {
    expect(getRepositoryUserMessage(new RepositoryError('TRANSPORT_FAILED', 'pre-receive hook declined')))
      .toBe('The remote refused the request: pre-receive hook declined')
    expect(getRepositoryUserMessage(new RepositoryError('TRANSPORT_FAILED', ''))).toBe('The remote refused the request.')
    expect(getRepositoryUserMessage(new RepositoryError('SOMETHING_NEW', 'odd failure')))
      .toBe('The repository operation failed: odd failure')
  })

  it('follows the app language', () => {
    setLocale('ko')
    expect(getRepositoryUserMessage(new RepositoryError('REMOTE_ACCESS_DENIED', '')))
      .toBe('로그인되어 있지만 이 계정은 저장소에 접근하거나 푸시할 권한이 없습니다.')
  })
})
