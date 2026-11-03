import React from 'react';
import ModelsPage from '@/pages/AI/Models';

/** 保留原地址，直接打开统一模型管理的授权详情。 */
export default function ModelSharingPage() {
  return <ModelsPage initialTab="sharing"/>;
}
