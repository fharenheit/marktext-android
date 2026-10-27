const REPOSITORY = 'fharenheit/marktext-android'

const BUILD_HASH = typeof __MARKTEXT_BUILD_HASH__ === 'string' ? __MARKTEXT_BUILD_HASH__ : 'unknown'
/** Release version; update checks compare against this. The release workflow checks it against package.json. */
const VERSION = '0.3.1'
const BUILD_TIME = typeof __MARKTEXT_BUILD_TIME__ === 'string' ? __MARKTEXT_BUILD_TIME__ : ''

export const APP_INFO = Object.freeze({
  name: 'MarkText for Data Dynamics Inc',
  version: VERSION,
  /** Short commit hash of the build. */
  buildHash: BUILD_HASH,
  /** Build time as yyyyMMddHHmmss. */
  buildTime: BUILD_TIME,
  /** What Settings > About shows: version, commit and build time. */
  displayVersion: [ VERSION, BUILD_HASH, BUILD_TIME ].filter(Boolean).join(' · '),
  repositoryUrl: `https://github.com/${REPOSITORY}`,
  releasesUrl: `https://github.com/${REPOSITORY}/releases`,
  latestReleaseApiUrl: `https://api.github.com/repos/${REPOSITORY}/releases/latest`,
} as const)
