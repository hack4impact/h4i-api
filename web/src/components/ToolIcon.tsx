import { FileBadgeIcon, PlugIcon } from '@primer/octicons-react'
import { siGithub, siGoogle, siNotion, siVaultwarden } from 'simple-icons'
import { TOOL_NAME } from './Status'

/** Slack's mark, from simple-icons 13.21.0 (CC0); later releases dropped it at Slack's request. */
const SLACK =
  'M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.528 2.528 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.527 2.527 0 0 1 2.521 2.52v6.313A2.528 2.528 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313zM8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.528 2.528 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52H8.834zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.528 2.528 0 0 1-2.521 2.521H2.522A2.528 2.528 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312zM18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.528 2.528 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.527 2.527 0 0 1-2.52-2.521V2.522A2.527 2.527 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312zM15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.528 2.528 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.526 2.526 0 0 1 2.52-2.52h6.313A2.527 2.527 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z'

/** Each tool's mark (simple-icons, CC0), drawn in the text color: monochrome in light and dark mode. */
const PATHS: Record<string, string> = {
  slack: SLACK,
  google: siGoogle.path,
  github: siGithub.path,
  notion: siNotion.path,
  vaultwarden: siVaultwarden.path,
}

export function ToolIcon({ tool, size = 16, label }: { tool: string; size?: number; label?: boolean }) {
  const name = TOOL_NAME[tool] ?? tool
  // Documenso has no published mark: a signed-document icon in the same single color.
  if (tool === 'documenso') return <FileBadgeIcon size={size} className="tool-icon" aria-label={label ? name : undefined} aria-hidden={label ? undefined : true} />
  const d = PATHS[tool]
  // A plugin tool without a mark of its own (wiki: tool plugins): a plug, in the same single color.
  if (!d) return <PlugIcon size={size} className="tool-icon" aria-label={label ? name : undefined} aria-hidden={label ? undefined : true} />
  return (
    <svg className="tool-icon" viewBox="0 0 24 24" width={size} height={size} fill="currentColor"
      role={label ? 'img' : undefined} aria-label={label ? name : undefined} aria-hidden={label ? undefined : true}>
      {label && <title>{name}</title>}
      <path d={d} />
    </svg>
  )
}

/** A tool's icon and name together ("[icon] Slack"). */
export function ToolName({ tool }: { tool: string }) {
  return <span className="tool-name"><ToolIcon tool={tool} />{TOOL_NAME[tool] ?? tool}</span>
}
