/**
 * @see https://umijs.org/docs/max/access#access
 * */
export default function access(
  initialState: { currentUser?: API.CurrentUser } | undefined,
) {
  const { currentUser } = initialState ?? {};
  return {
    canAdmin: hasAdminRole(currentUser),
  };
}

export const ADMIN_ROLE_CODE = 'admin';

function includesRoleCode(
  values: string[] | string | undefined,
  roleCode: string,
) {
  if (Array.isArray(values)) {
    return values.includes(roleCode);
  }
  return values === roleCode;
}

export function hasAdminRole(
  currentUser?: Pick<API.CurrentUser, 'access' | 'roles'>,
) {
  return (
    includesRoleCode(currentUser?.roles, ADMIN_ROLE_CODE) ||
    includesRoleCode(currentUser?.access, ADMIN_ROLE_CODE)
  );
}
