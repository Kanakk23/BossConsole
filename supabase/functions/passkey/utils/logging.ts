/**
 * Log-safe renderings of user identifiers.
 *
 * Function logs are persistent and these call sites sit on unauthenticated
 * request paths, so raw emails and user ids - attacker-controlled input -
 * must not reach them. The masked forms keep just enough of the value to
 * correlate two lines from one request without storing the identifier.
 */

/** `victim@example.com` -> `v***@example.com` */
export function maskEmail(email: string): string {
  const at = email.indexOf('@')
  if (at === -1) return '***'
  return `${email.slice(0, 1)}***@${email.slice(at + 1)}`
}

/** `de305d54-75b4-431b-adb2-eb6b9e546014` -> `de30…` */
export function maskUserId(userId: string): string {
  return userId.length <= 4 ? '***' : `${userId.slice(0, 4)}…`
}
