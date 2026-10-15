import { api, type ToolInfo } from './client'

/**
 * The tools people have accounts in, from `GET /api/tools`, loaded once after sign-in and before any page renders:
 * their names and list order.
 */
let tools: ToolInfo[] = []

/** Tool key → name ("slack" → "Slack"); filled by [setTools]. */
export const TOOL_NAME: Record<string, string> = {}

export function setTools(list: ToolInfo[]) {
  tools = [...list].sort((a, b) => a.order - b.order)
  for (const key of Object.keys(TOOL_NAME)) delete TOOL_NAME[key]
  for (const t of tools) TOOL_NAME[t.key] = t.name
}

/** Loads the tools; false when they couldn't be loaded. */
export async function loadTools(): Promise<boolean> {
  try {
    const { data } = await api.GET('/api/tools')
    if (!data) return false
    setTools(data)
    return true
  } catch {
    return false
  }
}

/** Every tool, in list order. */
export const allTools = () => tools
