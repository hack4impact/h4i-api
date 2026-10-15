/** Status as a colored dot plus words (design system: status is text first; color never alone). */
export function Status({ tone, children }: { tone: 'ok' | 'warn' | 'off' | 'danger'; children: React.ReactNode }) {
  return <span className={`status ${tone}`}>{children}</span>
}

export const STATUS_LABEL: Record<string, string> = {
  applicant: 'Applicant',
  active: 'Active',
  alumni: 'Alumni',
  removal_requested: 'Removal requested',
  removed: 'Removed',
}

export const STATUS_TONE: Record<string, 'ok' | 'warn' | 'off' | 'danger'> = {
  applicant: 'off',
  active: 'ok',
  alumni: 'off',
  removal_requested: 'danger',
  removed: 'off',
}

export const ROLE_LABEL: Record<string, string> = {
  lead: 'Chapter lead',
  co_lead: 'Co-lead',
  viewer: 'Viewer',
  national: 'National admin',
}

/** Tool names come from the server (`api/tools.ts`). */
export { TOOL_NAME } from '../api/tools'
