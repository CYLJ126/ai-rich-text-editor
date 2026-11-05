/** A confirmed command advanced the shared workspace version by one. */
export type WorkspaceVersionChange = { from: number; to: number };

export function confirmedWorkspaceVersion(from: unknown, to?: number): WorkspaceVersionChange | undefined {
  if (typeof from !== 'number' || !Number.isSafeInteger(from) || from < 0 || !Number.isSafeInteger(from + 1)) return;
  return to === undefined || to === from + 1 ? {from, to: from + 1} : undefined;
}
