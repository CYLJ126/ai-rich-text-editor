import React from 'react';
import ModelsPage from '@/pages/AI/Models';

/** 保留原地址，直接打开整合后的审核连接页签。 */
export default function ConnectionsPage() {
  return <ModelsPage initialTab="connections"/>;
}
