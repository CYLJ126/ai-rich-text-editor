import React from 'react';
import ModelsPage from '@/pages/AI/Models';

/** 保留原地址，直接打开整合后的租户模型与授权页签。 */
export default function ModelSharingPage() {
  return <ModelsPage initialTab="sharing"/>;
}
