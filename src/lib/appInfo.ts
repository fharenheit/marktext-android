const REPOSITORY = 'fharenheit/marktext-android'

const BUILD_HASH = typeof __MARKTEXT_BUILD_HASH__ === 'string' ? __MARKTEXT_BUILD_HASH__ : 'unknown'
const BUILD_TIME = typeof __MARKTEXT_BUILD_TIME__ === 'string' ? __MARKTEXT_BUILD_TIME__ : ''

export const APP_INFO = Object.freeze({
  name: 'MarkText for Data Dynamics Inc',
  /** Release version; update checks compare against this. */
  version: '0.2.1',
  /** Short commit hash of the build. */
  buildHash: BUILD_HASH,
  /** Build time as yyyyMMddHHmmss. */
  buildTime: BUILD_TIME,
  /** What Settings > About shows: version, commit and build time. */
  displayVersion: [ '0.2.1', BUILD_HASH, BUILD_TIME ].filter(Boolean).join(' · '),
  repositoryUrl: `https://github.com/${REPOSITORY}`,
  releasesUrl: `https://github.com/${REPOSITORY}/releases`,
  latestReleaseApiUrl: `https://api.github.com/repos/${REPOSITORY}/releases/latest`,
} as const)
