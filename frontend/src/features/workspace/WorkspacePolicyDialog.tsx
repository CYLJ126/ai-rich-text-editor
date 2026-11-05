import React, {useCallback, useEffect, useState} from 'react';
import {Modal, Tabs} from 'antd';
import PolicyEditor from './PolicyEditor';
import type {WorkspaceView} from '@/services/platform/workspace';
import type {PolicySnapshot, TenantPolicyView} from '@/services/platform/policy';
import {notifyAiConfiguration} from './aiConfigurationEvents';
import {useConfigurationNavigation} from '@/features/configuration/ConfigurationBoundary';

/** 政策范围固定为配置目标，弹窗内不切换聊天空间。 */
export default function WorkspacePolicyDialog({userId, target, onClose, onUnsafeChange}: {
  userId: string; target: WorkspaceView; onClose: () => void; onUnsafeChange: (unsafe: boolean) => void;
}) {
  const [tenantUnsafe, setTenantUnsafe] = useState(false);
  const [spaceUnsafe, setSpaceUnsafe] = useState(false);
  const [ceiling, setCeiling] = useState<TenantPolicyView | null>(null);
  const [revision, setRevision] = useState(0);
  const unsafe = tenantUnsafe || spaceUnsafe;
  const navigation = useConfigurationNavigation();
  useEffect(() => {
    onUnsafeChange(unsafe);
  }, [unsafe, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange(false), [onUnsafeChange]);
  const snapshot = useCallback((value: PolicySnapshot | null) => setCeiling(value?.kind === 'tenant' ? value.value : null), []);
  const tenantSaved = () => {
    setRevision(value => value + 1);
    notifyAiConfiguration(userId, target.scope, true);
  };
  const spaceSaved = () => notifyAiConfiguration(userId, target.scope);
  return <Modal open title={`租户与空间政策 · ${target.name}`} width={920} footer={null}
                closable={navigation.available || !unsafe} keyboard={navigation.available || !unsafe}
                mask={{closable: navigation.available || !unsafe}} onCancel={() => {
    if (navigation.available) navigation.request(onClose, ['tenant-policy', 'space-policy']);
    else if (!unsafe) onClose();
  }}
                styles={{body: {maxHeight: '75dvh', overflowY: 'auto'}}}>
    <Tabs defaultActiveKey="workspace" destroyOnHidden={false} items={[
      {
        key: 'tenant', label: '租户政策', forceRender: true, children: <PolicyEditor userId={userId}
                                                                                     scope={{tenantId: target.scope.tenantId}}
                                                                                     kind="tenant" onSnapshot={snapshot}
                                                                                     onSaved={tenantSaved}
                                                                                     onUnsafeChange={setTenantUnsafe}/>
      },
      {
        key: 'workspace', label: '空间政策', children: <PolicyEditor userId={userId} scope={target.scope}
                                                                     kind="workspace" workspaceName={target.name}
                                                                     ceiling={ceiling} refreshRevision={revision}
                                                                     onSaved={spaceSaved}
                                                                     onUnsafeChange={setSpaceUnsafe}/>
      },
    ]}/>
  </Modal>;
}
