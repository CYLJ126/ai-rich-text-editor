/** 环境配置入口只组织已有能力，业务操作继续使用原页面的授权检查。 */
export const environmentEntries = [
  {
    menuCode: 'EnvironmentTenants',
    path: '/Environment/Tenants',
    title: '租户配置',
    description: '查看所属租户的概览、可访问工作空间和租户政策。',
  },
  {
    menuCode: 'EnvironmentWorkspaces',
    path: '/Environment/Workspaces',
    title: '工作空间配置',
    description: '维护空间信息和个人偏好，独立配置目标空间 AI 模型与预算规则。',
  },
  {
    menuCode: 'EnvironmentModels',
    path: '/Environment/Models',
    title: '模型配置',
    description: '按审核连接、审核方案、实际模型三个页签维护个人／租户模型与授权。',
  },
  {
    menuCode: 'EnvironmentTools',
    path: '/Environment/Tools',
    title: '工具配置',
    description: '查看工具目录，维护当前空间的个人工具配置。',
  },
] as const;
