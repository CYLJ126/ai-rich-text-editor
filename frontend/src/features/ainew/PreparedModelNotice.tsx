import React, {useEffect, useState} from 'react';
import {Alert, Button, Flex} from 'antd';
import type {BrowserContext} from './storage';
import {
  clearModelChatHandoff,
  MODEL_CHAT_HANDOFF_EVENT,
  type ModelChatHandoff,
  readModelChatHandoff
} from './modelUsageRecovery';

/** 同标签页和已挂载聊天页共用交接通知；状态始终按当前账号与执行空间重读。 */
export function useModelChatHandoff(context: BrowserContext) {
  const [saved, setSaved] = useState<{
    handoff: ModelChatHandoff | null;
    warning: string | null
  }>(() => readModelChatHandoff(context));
  useEffect(() => {
    const refresh = () => setSaved(readModelChatHandoff(context));
    refresh();
    window.addEventListener(MODEL_CHAT_HANDOFF_EVENT, refresh);
    return () => window.removeEventListener(MODEL_CHAT_HANDOFF_EVENT, refresh);
  }, [context.userId, context.scope.tenantId, context.scope.workspaceId]);
  return {saved, setSaved};
}

/** 账号与完整空间匹配的选型提示；记录不是授权，不触发发送或自动应用配置。 */
export default function PreparedModelNotice({context, disabled, onSelect}: {
  context: BrowserContext; disabled: boolean; onSelect: (handoff: ModelChatHandoff) => void;
}) {
  const {saved, setSaved} = useModelChatHandoff(context);
  if (saved.warning) return <Alert type="warning" title={saved.warning}/>;
  const handoff = saved.handoff;
  if (!handoff) return null;
  return <Alert type="info" title={`待用于聊天：${handoff.request.binding.id}@${handoff.request.binding.version}`}
                description="准备不会改变当前会话配置。请重新发现精确版本、检查预算和参数，再明确应用；已有调用和重新生成继续使用原配置。"
                action={<Flex wrap gap={8}>
                  <Button disabled={disabled} onClick={() => onSelect(handoff)}>重新发现并选择此模型</Button>
                  <Button disabled={disabled} onClick={() => {
                    if (!clearModelChatHandoff(context, handoff.id)) setSaved({
                      ...saved,
                      warning: '待选择记录暂无法清除，请检查浏览器存储。'
                    });
                  }}>暂不使用</Button>
                </Flex>}/>;
}
