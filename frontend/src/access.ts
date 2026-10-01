/**
 * @see https://umijs.org/docs/max/access#access
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  const isAdmin = hasAdminRole(currentUser);
  return {
    canAdmin: isAdmin,
    canViewAiTools:
      isAdmin ||
      includesValue(currentUser?.menus, AI_TOOL_MENU_CODE) ||
      includesValue(currentUser?.menuOperations, AI_TOOL_OPERATION_CODES.view),
    canManageAiTools:
      isAdmin ||
      includesValue(
        currentUser?.menuOperations,
        AI_TOOL_OPERATION_CODES.manage,
      ),
    canConfigureAiTools:
      isAdmin ||
      includesValue(currentUser?.menuOperations, AI_TOOL_OPERATION_CODES.invoke) ||
      includesValue(currentUser?.menuOperations, AI_TOOL_OPERATION_CODES.manage),
    canInvokeAiTools:
      isAdmin ||
      includesValue(
        currentUser?.menuOperations,
        AI_TOOL_OPERATION_CODES.invoke,
      ),
    canApproveAiTools:
      isAdmin ||
      includesValue(
        currentUser?.menuOperations,
        AI_TOOL_OPERATION_CODES.approve,
      ),
    canManageAiWorkflows:
      isAdmin ||
      includesValue(
        currentUser?.menuOperations,
        AI_TOOL_OPERATION_CODES.workflow,
      ),
  };
}

export const ADMIN_ROLE_CODE = 'admin';
export const AI_TOOL_MENU_CODE = 'AITool';
export const AI_TOOL_OPERATION_CODES = {
  view: 'aiTool:list',
  manage: 'aiTool:manage',
  invoke: 'aiTool:invoke',
  approve: 'aiTool:approve',
  workflow: 'aiTool:workflow',
} as const;

function includesRoleCode(
  values: string[] | string | undefined,
  roleCode: string,
) {
  if (Array.isArray(values)) {
    return values.includes(roleCode);
  }
  return values === roleCode;
}

function includesValue(
  values: string[] | string | undefined,
  expected: string,
) {
  return Array.isArray(values)
    ? values.includes(expected)
    : values === expected;
}

export function hasAdminRole(
  currentUser?: Pick<API.CurrentUser, 'access' | 'roles'>,
) {
  return (
    includesRoleCode(currentUser?.roles, ADMIN_ROLE_CODE) ||
    includesRoleCode(currentUser?.access, ADMIN_ROLE_CODE)
  );
}
