import React from 'react';
import type {WorkspaceScope} from '@/services/platform/workspace';
import type {BrowserContext} from './storage';
import DefaultAiTemplateEditor from './DefaultAiTemplateEditor';
import WorkspaceAiGovernanceEditor from './WorkspaceAiGovernanceEditor';

/** 兼容旧组件调用；生产完整空间编辑器仅在工作空间配置页挂载。 */
export default function WorkspaceAiSettings(props: {
  scope: WorkspaceScope; context: BrowserContext; onUpdated: () => void;
  onUnsafeChange?: (unsafe: boolean) => void; adminOnly?: boolean
}) {
  return props.adminOnly ? <DefaultAiTemplateEditor {...props}/> :
    <WorkspaceAiGovernanceEditor key={JSON.stringify([props.context.userId, props.scope])} {...props}/>;
}
