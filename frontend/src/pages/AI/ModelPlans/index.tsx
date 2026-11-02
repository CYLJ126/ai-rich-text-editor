import React from 'react';
import ModelsPage from '@/pages/AI/Models';

/** 保留原地址，直接打开整合后的审核模型与费率页签。 */
export default function ModelPlansPage() {
  return <ModelsPage initialTab="plans"/>;
}
