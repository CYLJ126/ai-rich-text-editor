import {useConfigurationDraft} from '@/features/configuration/ConfigurationBoundary';
import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Divider, Flex, Form, InputNumber, Select, Switch, Typography} from 'antd';
import {AiApiError} from '@/services/arte-ai';
import {
  type CatalogView,
  getAiCatalog,
  initializeAiCatalog,
  refKey,
  updateDefaultAiPolicy,
} from '@/services/platform/ai';
import {type BrowserContext, readOperation, saveOperation} from './storage';
import '../aiManagement.css';

type Props = { context: BrowserContext; onUpdated: () => void; onUnsafeChange?: (unsafe: boolean) => void };
export default function DefaultAiTemplateEditor({context, onUpdated, onUnsafeChange}: Props) {
  const [restored] = useState(() => readOperation<{
    kind: 'initialize' | 'policy';
    confirmed?: boolean;
    request: unknown
  }>(context, 'ai-configuration', ''));
  const live = useRef(true);
  useEffect(() => {
    live.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail === context.userId) live.current = false;
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId]);
  const [unsafe, setUnsafe] = useState(!!restored.warning);
  const [catalog, setCatalog] = useState<CatalogView>();
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const pending = useRef<{
    kind: 'initialize' | 'policy';
    confirmed?: boolean;
    key: string;
    request: unknown
  } | null>(restored.value ? {...restored.value.request, key: restored.value.key} : null);
  const writing = useRef(false);
  const [uncertain, setUncertain] = useState(!!restored.value);
  const [catalogEditable, setCatalogEditable] = useState(false);
  const [policyDirty, setPolicyDirty] = useState(false);
  const protectedState = unsafe || uncertain || saving || policyDirty;
  useConfigurationDraft({
    group: 'template', label: '平台默认模板修改',
    status: unsafe ? 'blocked' : saving ? 'submitting' : uncertain ? 'pending' : policyDirty ? 'dirty' : 'clean',
    discard: async () => {
      const ok = await readCatalog();
      if (ok) setPolicyDirty(false);
      return ok;
    }
  });

  useEffect(() => {
    onUnsafeChange?.(protectedState);
  }, [protectedState, onUnsafeChange]);
  useEffect(() => () => onUnsafeChange?.(false), [onUnsafeChange]);
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (protectedState) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [protectedState]);
  useEffect(() => {
    void readCatalog();
  }, []);

  async function run(kind: 'initialize' | 'policy', request: unknown) {
    if (!live.current || writing.current || (pending.current && pending.current.kind !== kind)) return;
    const operation = pending.current ?? {kind, key: crypto.randomUUID(), request};
    pending.current = operation;
    const persist = () => saveOperation(context, 'ai-configuration', '', {
      key: operation.key,
      request: {
        scope: context.scope, kind: operation.kind, request: operation.request,
        ...(operation.kind === 'policy' ? {confirmed: operation.confirmed ?? false} : {})
      }
    });
    if (!persist()) {
      setUnsafe(true);
      setUncertain(true);
      setError('无法保存原请求和操作键，本次未提交。恢复浏览器存储后确认原操作。');
      return;
    }
    writing.current = true;
    setUnsafe(false);
    setSaving(true);
    setUncertain(true);
    setError('');
    try {
      if (kind === 'policy') {
        if (!operation.confirmed) {
          await updateDefaultAiPolicy(operation.request as Parameters<typeof updateDefaultAiPolicy>[0], operation.key);
          if (!live.current) return;
          operation.confirmed = true;
          if (!persist()) {
            setUnsafe(true);
            setError('默认模板已确认，但本地确认状态未保存。请恢复存储后重新读取。');
            return;
          }
        }
        if (!await readCatalog()) {
          setError('默认模板已确认，但当前目录读取失败。确认原操作只会重新读取，不再次发布。');
          return;
        }
      } else {
        const {body} = await initializeAiCatalog(operation.key);
        if (!live.current) return;
        setCatalog(body.data.value);
        setCatalogEditable(true);
      }
      if (!live.current) return;
      if (!saveOperation(context, 'ai-configuration', '', null)) {
        setUnsafe(true);
        setError('配置已确认，但恢复记录无法清除。请恢复存储后确认原操作。');
        return;
      }
      pending.current = null;
      setUncertain(false);
      setUnsafe(false);
      setPolicyDirty(false);
      onUpdated();
    } catch (failure) {
      if (!live.current) return;
      const unknown = !(failure instanceof AiApiError) || failure.httpStatus >= 500 || (failure.httpStatus >= 200 && failure.httpStatus < 300);
      setUncertain(unknown);
      if (!unknown) {
        pending.current = null;
        setUnsafe(!saveOperation(context, 'ai-configuration', '', null));
      }
      setError(unknown ? '保存结果待确认，请保留本页并按原操作重试。'
        : failure instanceof AiApiError && failure.httpStatus === 403 ? '当前账号没有此项配置管理权限，请联系平台管理员。'
          : failure instanceof AiApiError && failure.httpStatus === 409 ? '配置已发生变化，请重新读取最新配置后修改。'
            : '配置未能保存，请检查模型、费率、预算额度和版本。');
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  async function readCatalog() {
    try {
      const {body} = await getAiCatalog();
      if (!live.current) return false;
      setCatalog(body.data);
      setCatalogEditable(true);
      return true;
    } catch {
      if (!live.current) return false;
      setError('只有平台配置管理员可以维护审核目录和新空间默认模板。');
      return false;
    }
  }

  const publishedCatalog = catalog?.catalog;
  return <Flex component="section" vertical gap={12} className="ai-management-panel" aria-label="平台默认 AI 模板">
    <Typography.Paragraph>维护首次空间准备采用的默认开关与费率上限。已有空间保持自己的配置。</Typography.Paragraph>
    {unsafe && <Alert type="warning" title="配置操作无法保存到当前浏览器，请保留本页并确认原操作后再切换。"/>}
    {error && <Alert type="warning" showIcon title={error}/>}
    {uncertain && <Button loading={saving}
                          onClick={() => pending.current && void run(pending.current.kind, pending.current.request)}>重试确认原配置操作</Button>}
    {(catalogEditable && catalog?.version === 0) &&
      <Alert type="info" title="数据库尚未初始化 AI 目录"
             description="平台配置管理员可初始化空目录，然后在连接管理页面创建审核连接。不会读取配置文件中的模型定义。"
             action={<Button loading={saving} disabled={uncertain || unsafe}
                             onClick={() => void run('initialize', null)}>初始化空目录</Button>}/>}

    <Divider className="ai-management-divider"/>
    <Button disabled={saving || uncertain || unsafe || policyDirty}
            onClick={() => void readCatalog()}>维护默认 AI 模板</Button>
    {catalogEditable && catalog && publishedCatalog && <>
      <Typography.Paragraph>目录修订：{catalog.version}。这里只维护首次空间准备的默认开关与费率上限；不创建实际模型，不启用审核方案，不初始化账户。</Typography.Paragraph>
      <Form component="div" layout="vertical" className="ai-management-form">
        <Form.Item label="首次准备时启用 AI"><Switch aria-label="首次准备时启用 AI"
                                                     disabled={saving || uncertain || unsafe}
                                                     checked={publishedCatalog.defaults.enabled}
                                                     onChange={enabled => {
                                                       setPolicyDirty(true);
                                                       setCatalog({
                                                         ...catalog,
                                                         catalog: {
                                                           ...publishedCatalog,
                                                           defaults: {...publishedCatalog.defaults, enabled}
                                                         }
                                                       });
                                                     }}/></Form.Item>
        <Form.Item label="默认费率批准上限"><Select aria-label="默认费率批准上限" mode="multiple"
                                                    disabled={saving || uncertain || unsafe}
                                                    value={publishedCatalog.defaults.rateCaps.map(item => refKey(item.rate))}
                                                    options={publishedCatalog.rates.map(rate => ({
                                                      value: refKey(rate.definition),
                                                      label: `${rate.definition.id}@${rate.definition.version} · ${(rate.inputPerMillion ?? rate.toolPerCall)?.currency}`
                                                    }))}
                                                    onChange={keys => {
                                                      setPolicyDirty(true);
                                                      const data = publishedCatalog;
                                                      setCatalog({
                                                        ...catalog, catalog: {
                                                          ...data, defaults: {
                                                            ...data.defaults,
                                                            rateCaps: data.rates.filter(rate => keys.includes(refKey(rate.definition))).flatMap(rate => {
                                                              const unit = rate.inputPerMillion ?? rate.toolPerCall;
                                                              return unit ? [data.defaults.rateCaps.find(item => refKey(item.rate) === refKey(rate.definition))
                                                              ?? {
                                                                rate: rate.definition,
                                                                limit: {amount: '0', currency: unit.currency}
                                                              }] : [];
                                                            })
                                                          }
                                                        }
                                                      });
                                                    }}/></Form.Item>
        {publishedCatalog.defaults.rateCaps.map((item, index) => <Form.Item key={refKey(item.rate)}
                                                                            label={`默认费率上限 · ${item.limit.currency} · ${item.rate.id}@${item.rate.version}`}>
          <InputNumber aria-label={`默认预算额度 ${item.rate.id}`} stringMode min="0" max="1000000000"
                       disabled={saving || uncertain || unsafe} value={String(item.limit.amount)} onChange={amount => {
            setPolicyDirty(true);
            const data = publishedCatalog;
            setCatalog({
              ...catalog, catalog: {
                ...data, defaults: {
                  ...data.defaults,
                  rateCaps: data.defaults.rateCaps.map((value, i) => i === index ? {
                    ...value,
                    limit: {...value.limit, amount: amount ?? '0'}
                  } : value)
                }
              }
            });
          }}/>
        </Form.Item>)}
      </Form>
      <Typography.Paragraph>修改默认模板不会覆盖已有空间。实际模型请到模型管理创建、验证并发布；再到空间配置显式准备并启用，预算规则独立维护。</Typography.Paragraph>
      <Button loading={saving} disabled={uncertain || unsafe} onClick={() => void run('policy', {
        expectedCatalogVersion: catalog.version, enabled: publishedCatalog.defaults.enabled,
        rateCaps: publishedCatalog.defaults.rateCaps.map(item => ({
          ...item,
          limit: {...item.limit, amount: String(item.limit.amount)}
        }))
      })}>发布默认 AI 模板</Button>
      <Button disabled={saving || uncertain || unsafe} onClick={() => void readCatalog().then(ok => {
        if (ok) setPolicyDirty(false);
      })}>放弃默认模板修改并重新读取</Button>
    </>}
  </Flex>;
}
