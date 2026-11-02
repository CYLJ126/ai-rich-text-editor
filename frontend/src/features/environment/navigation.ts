/** 环境配置入口只组织已有能力，业务操作继续使用原页面的授权检查。 */
export const environmentEntries = [
  {
    menuCode: 'EnvironmentTenants',
    path: '/Environment/Tenants',
    title: '租户配置',
    description: '查看和维护已有租户的政策。',
  },
  {
    menuCode: 'EnvironmentWorkspaces',
    path: '/Environment/Workspaces',
    title: '工作空间配置',
    description: '选择、新建工作空间，维护空间信息和个人偏好。',
  },
  {
    menuCode: 'EnvironmentModels',
    path: '/Environment/Models',
    title: '模型配置',
    description: '管理模型草稿、凭据、验证、发布及已有授权配置。',
  },
  {
    menuCode: 'EnvironmentTools',
    path: '/Environment/Tools',
    title: '工具配置',
    description: '查看工具目录，维护当前空间的个人工具配置。',
  },
] as const;
