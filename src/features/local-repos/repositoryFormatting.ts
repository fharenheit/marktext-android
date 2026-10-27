import type { RepositoryProgressEvent } from '../../lib/localRepositories'

/** "Receiving objects 42%" — JGit task names are English, like git's. */
export function formatProgress(event: RepositoryProgressEvent) {
  if (event.total > 0) {
    const percent = Math.min(100, Math.round((event.completed / event.total) * 100))
    return `${event.task} ${percent}%`
  }
  return event.completed > 0 ? `${event.task} ${event.completed}` : event.task
}

/** Short local date-time for commit lists; the raw value when unparseable. */
export function formatCommitDate(isoDate: string, locale: string) {
  const date = new Date(isoDate)
  if (Number.isNaN(date.getTime())) {
    return isoDate
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

/** The path a diff file is known by: the new path, or the old one for deletions. */
export function diffFilePath(file: { newPath?: string | null; oldPath?: string | null; path?: string }) {
  return file.newPath ?? file.oldPath ?? file.path ?? ''
}
