import React, {useEffect, useRef, useState} from 'react';
import {Alert, Button, Flex, Input, Typography} from 'antd';
import type {BudgetAccountRef} from '@/services/arte-ai';
import {AiApiError} from '@/services/arte-ai';
import {
  accountResponsibility,
  assignAccountReconciler,
  revokeAccountReconciler
} from '@/services/platform/budgetAccount';
import type {ChatTestConfig} from './config';
import {type BrowserContext, readOperation, readValue, saveOperation, storageKey, writeValue} from './storage';
import ReconciliationPanel from './ReconciliationPanel';
import {recoveryRead} from './recoveryRead';

type Governance = {
  scope: BrowserContext['scope']; accountRef: BudgetAccountRef; kind: 'assign' | 'revoke';
  expectedAclVersion: number; subjectId?: string; grantId?: string; grantVersion?: number
};
/** 只定位获授权账户；财务读取与责任指派分别检查账户动作，不借空间角色或目标 ID 获权。 */
export default function BudgetReconciliationManager({context, config, t, onUnsafeToLeave, onChanged}: {
  context: BrowserContext; config: ChatTestConfig; t: (key: string) => string;
  onUnsafeToLeave: (unsafe: boolean) => void; onChanged: () => void;
}) {
  const [restored] = useState(() => readOperation<Governance>(context, 'budget-responsibility', ''));
  const [savedTarget] = useState(() => readValue<string>(storageKey(context, 'managed-reconciliation-target')));
  const [pending, setPending] = useState(restored.value);
  const [target, setTarget] = useState(restored.value?.request.accountRef.resourceId ?? (typeof savedTarget.value === 'string' ? savedTarget.value : config.budgetRef));
  const [selected, setSelected] = useState(restored.value?.request.accountRef.resourceId ?? (typeof savedTarget.value === 'string' ? savedTarget.value : ''));
  const [subject, setSubject] = useState('');
  const [facts, setFacts] = useState<Awaited<ReturnType<typeof accountResponsibility>> | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [storageFailed, setStorageFailed] = useState(false);
  const [financeUnsafe, setFinanceUnsafe] = useState(false);
  const read = useRef<AbortController | null>(null);
  const write = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const accountRef: BudgetAccountRef = {tenantId: context.scope.tenantId, type: 'budget-account', resourceId: selected};
  const unsafe = financeUnsafe || (!!pending && storageFailed);
  useEffect(() => {
    onUnsafeToLeave(unsafe);
    return () => onUnsafeToLeave(false);
  }, [unsafe, onUnsafeToLeave]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      read.current?.abort();
      write.current?.abort();
    };
  }, []);

  async function governance() {
    read.current?.abort();
    const controller = new AbortController();
    read.current = controller;
    setBusy(true);
    setError(null);
    setFacts(null);
    try {
      const value = await recoveryRead(signal => accountResponsibility(context.scope, accountRef, {signal}), controller.signal);
      if (!controller.signal.aborted && alive.current) setFacts(value);
    } catch (failure) {
      if (!controller.signal.aborted && alive.current) setError(failure);
    } finally {
      if (!controller.signal.aborted && alive.current) setBusy(false);
    }
  }

  async function execute(request?: Governance) {
    if (write.current || restored.warning || !alive.current) return;
    const operation = pending ?? (request ? {key: crypto.randomUUID(), request} : null);
    if (!operation) return;
    if (!saveOperation(context, 'budget-responsibility', '', operation)) setStorageFailed(true);
    setPending(operation);
    setBusy(true);
    setError(null);
    const controller = new AbortController();
    write.current = controller;
    try {
      const value = operation.request;
      if (value.kind === 'assign') await assignAccountReconciler(value.scope, value.accountRef, value.subjectId!, value.expectedAclVersion, operation.key, {signal: controller.signal});
      else await revokeAccountReconciler(value.scope, value.accountRef, {
        id: value.grantId!,
        version: value.grantVersion!
      }, value.expectedAclVersion, operation.key, {signal: controller.signal});
      if (!alive.current || controller.signal.aborted) return;
      if (!saveOperation(context, 'budget-responsibility', '', null)) setStorageFailed(true);
      setPending(null);
      setFacts(null);
      onChanged();
    } catch (failure) {
      if (!alive.current || controller.signal.aborted) return;
      setError(failure);
      if (failure instanceof AiApiError && failure.httpStatus >= 400 && failure.httpStatus < 500 && ![408, 429].includes(failure.httpStatus)) {
        if (!saveOperation(context, 'budget-responsibility', '', null)) setStorageFailed(true);
        setPending(null);
        setFacts(null);
      }
    } finally {
      if (alive.current && !controller.signal.aborted) {
        write.current = null;
        setBusy(false);
      }
    }
  }

  return <Flex vertical gap={8} aria-label={t('reconciliation.management')}>
    <Typography.Title level={5}>{t('reconciliation.management')}</Typography.Title>
    <Typography.Paragraph>{t('reconciliation.managementHelp')}</Typography.Paragraph>
    <Input aria-label={t('reconciliation.accountRef')} value={target} maxLength={256} disabled={busy || !!pending}
           onChange={event => setTarget(event.target.value)}/>
    <Button disabled={!target.trim() || busy || !!pending} onClick={() => {
      read.current?.abort();
      setSelected(target.trim());
      setFacts(null);
      setError(null);
      if (!writeValue(storageKey(context, 'managed-reconciliation-target'), target.trim())) setStorageFailed(true);
    }}>{t('reconciliation.openAccount')}</Button>
    {error != null && <Alert type="error" title={error instanceof Error ? error.message : t('error.network')}/>}
    {(storageFailed || restored.warning) && <Alert type="warning" title={t('reconciliation.storageWarning')}/>}
    {pending && <Alert type="warning" title={t('reconciliation.assignmentPending')}
                       action={<Button aria-label={t('reconciliation.confirmAssignment')} loading={busy}
                                       disabled={!!restored.warning}
                                       onClick={() => void execute()}>{t('reconciliation.confirmAssignment')}</Button>}/>}
    {selected && <>
      <Typography.Text>{t('reconciliation.managedAccount')}：{selected}</Typography.Text>
      <ReconciliationPanel key={selected} config={{...config, budgetRef: selected}} context={context}
                           accountRef={accountRef}
                           t={t} onUnsafeToLeave={setFinanceUnsafe} onConfirmed={async () => {
        onChanged();
      }}/>
      <Button disabled={busy || !!pending}
              onClick={() => void governance()}>{t('reconciliation.inspectAssignment')}</Button>
      {facts && <>
        <Input aria-label={t('reconciliation.responsibleSubject')} value={subject} maxLength={256}
               disabled={busy || !!pending} onChange={event => setSubject(event.target.value)}/>
        <Button disabled={!subject.trim() || busy || !!pending} onClick={() => void execute({
          scope: context.scope, accountRef,
          kind: 'assign', expectedAclVersion: facts.aclVersion, subjectId: subject.trim()
        })}>{t('reconciliation.assign')}</Button>
        {facts.grants.filter(g => g.action === 'budget-account:reconcile' && g.state === 'ACTIVE').map(g =>
          <Flex gap={8} key={g.id}><Typography.Text>{g.recipient.subjectId}</Typography.Text>
            <Button disabled={busy || !!pending} onClick={() => void execute({
              scope: context.scope, accountRef, kind: 'revoke', expectedAclVersion: facts.aclVersion,
              grantId: g.id, grantVersion: g.version
            })}>{t('reconciliation.revokeAssignment')}</Button></Flex>)}
      </>}
    </>}
  </Flex>;
}
