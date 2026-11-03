import React, {useEffect, useRef, useState} from 'react';
import {history, useModel} from '@umijs/max';
import {Alert, Button, Flex, Select, Typography} from 'antd';
import {type AiModel, getWorkspaceAi, type ModelSelection, refKey} from '@/services/platform/ai';
import {type BrowserContext, sameScope} from './storage';
import {aiLocation} from './aiNavigation';
import {subscribeAiConfiguration} from './aiConfigurationEvents';
import ModelUsagePreparationPanel from './ModelUsagePreparationPanel';

/** 聊天仅检查空间条件和准备本人账户；治理编辑统一跳转工作空间配置。 */
export default function WorkspaceAiUsagePanel({context, disabled, onUnsafeChange, onPrepared, onNavigate}: {
  context: BrowserContext; disabled: boolean; onUnsafeChange: (unsafe: boolean) => void;
  onPrepared: () => void; onNavigate: (proceed: () => void) => void
}) {
  const {initialState} = useModel('@@initialState');
  const hasMenu = initialState?.currentUser?.menus?.includes('EnvironmentWorkspaces') === true;
  const [models, setModels] = useState<AiModel[]>([]);
  const [selected, setSelected] = useState<string>();
  const [canManage, setCanManage] = useState(false);
  const [unsafe, setUnsafe] = useState(false);
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const closed = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    closed.current = false;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === context.userId) {
        closed.current = true;
        controller.abort();
        setModels([]);
        setCanManage(false);
      }
    };
    window.addEventListener('arte-account-session-cleared', logout);
    getWorkspaceAi(context.scope, {signal: controller.signal}).then(({body}) => {
      if (controller.signal.aborted || closed.current) return;
      if (body.data.configuration && !sameScope(body.data.configuration.scope, context.scope)) throw new Error('空间响应不一致。');
      setModels(body.data.models);
      setCanManage(body.data.canManage);
      setError('');
    }).catch(() => {
      if (!controller.signal.aborted) {
        setModels([]);
        setCanManage(false);
        setError('空间使用条件暂不可读，可稍后重新检查。');
      }
    });
    return () => {
      controller.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context, revision]);
  useEffect(() => subscribeAiConfiguration(context.userId, context.scope, () => setRevision(value => value + 1)), [context]);

  function configure(model?: ModelSelection) {
    if (!hasMenu || disabled || unsafe || closed.current) return;
    onNavigate(() => history.push(aiLocation('workspaces', context.userId, {
      scope: context.scope,
      chat: context.scope,
      returnTo: 'chat',
      model
    })));
  }

  const target = models.find(model => JSON.stringify([model.resourceId, refKey(model.binding)]) === selected);
  const usageChanged = React.useCallback((value: boolean) => {
    setUnsafe(value);
    onUnsafeChange(value);
  }, [onUnsafeChange]);
  return <Flex vertical gap={8} aria-label="本人模型使用条件">
    {error && <Alert type="info" title={error}/>}
    <Typography.Text type="secondary">检查已发布模型在此空间的使用条件；本人账户准备无需进入空间治理页。</Typography.Text>
    <Select aria-label="检查模型使用条件" placeholder="选择精确模型版本检查本人预算" value={selected}
            disabled={disabled || unsafe} options={models.map(model => ({
      value: JSON.stringify([model.resourceId, refKey(model.binding)]),
      label: `${model.name} · ${model.kind === 'PERSONAL' ? '个人' : '租户'} · ${model.binding.id}@${model.binding.version}`
    }))} onChange={setSelected}/>
    <ModelUsagePreparationPanel context={context} target={target ? {
      resourceId: target.resourceId,
      binding: {...target.binding, type: 'binding'}
    } : null}
                                refreshRevision={revision} disabled={disabled} navigationDisabled
                                onPrepared={onPrepared} onUnsafeChange={usageChanged}/>
    {canManage ? hasMenu ? <Button disabled={disabled || unsafe} onClick={() => configure()}>配置此空间 AI</Button>
        : <Typography.Text
          type="secondary">您有空间治理资格，但尚未分配工作空间配置菜单，请联系菜单管理员。现有模型和本人账户准备仍可使用。</Typography.Text>
      : <Typography.Text
        type="secondary">此处不提供空间治理编辑；模型未启用或规则未批准时请联系空间负责人。</Typography.Text>}
  </Flex>;
}
