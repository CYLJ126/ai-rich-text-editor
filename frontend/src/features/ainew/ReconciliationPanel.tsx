import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Pagination,
  Tag,
  Typography
} from 'antd';
import React, {useEffect, useId, useRef, useState} from 'react';
import {
  AiApiError,
  confirmReconciliation,
  type ConfirmReconciliationRequest,
  type PendingReconciliation,
  pendingReconciliations,
  reconciliationReceipt,
  type ReconciliationReceipt,
} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';
import {type BrowserContext, readOperation, saveOperation, STORAGE_WARNING} from './storage';
import '../aiManagement.css';

/** 由后端授权决定能否核对；未知费用只接受管理员核实的实际账单，不填估算默认值。 */
export default function ReconciliationPanel({
                                              config,
                                              t,
                                              onConfirmed,
                                              context,
                                              writable = true,
                                              onUnsafeToLeave,
                                            }: {
  config: ChatTestConfig;
  t: (key: string) => string;
  onConfirmed: (id: string) => Promise<void>;
  context?: BrowserContext;
  writable?: boolean;
  onUnsafeToLeave?: (unsafe: boolean) => void;
}) {
  const formId = useId();
  const [restored] = useState(() => readOperation<ConfirmReconciliationRequest>(context, 'reconcile', config.budgetRef));
  const [storageWarning, setStorageWarning] = useState<string | null>(restored.warning);
  const [open, setOpen] = useState(!!restored.value);
  const [records, setRecords] = useState<PendingReconciliation[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [canReconcile, setCanReconcile] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [selected, setSelected] = useState<PendingReconciliation | null>(null);
  const [amount, setAmount] = useState('');
  const [evidence, setEvidence] = useState('');
  const [note, setNote] = useState('');
  const [ended, setEnded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [receipt, setReceipt] = useState<ReconciliationReceipt | null>(null);
  const [pending, setPending] = useState<{
    key: string;
    request: ConfirmReconciliationRequest;
  } | null>(restored.value);
  const pendingRef = useRef<typeof pending>(restored.value);
  const reads = useRef<AbortController | null>(null);
  const writes = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const currentSettings = useRef(config);
  currentSettings.current = config;
  const unsafeToLeave = !!storageWarning && (!!pending || saving);
  useEffect(() => {
    onUnsafeToLeave?.(unsafeToLeave);
    const warn = (event: BeforeUnloadEvent) => {
      if (unsafeToLeave) {
        event.preventDefault();
        event.returnValue = '';
      }
    };
    window.addEventListener('beforeunload', warn);
    return () => {
      window.removeEventListener('beforeunload', warn);
      onUnsafeToLeave?.(false);
    };
  }, [unsafeToLeave, onUnsafeToLeave]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      reads.current?.abort();
      writes.current?.abort();
    };
  }, []);

  async function load(current = 1) {
    reads.current?.abort();
    const controller = new AbortController();
    reads.current = controller;
    setLoading(true);
    setError(null);
    const settings = currentSettings.current;
    try {
      const {body} = await pendingReconciliations(
        {
          scope: {
            tenantId: settings.tenantId,
            workspaceId: settings.workspaceId,
          },
          budgetRef: settings.budgetRef,
          current,
          size: 10,
        },
        {signal: controller.signal},
      );
      if (controller.signal.aborted || !alive.current) return;
      setRecords(body.data.page.records);
      setPage(body.data.page.current);
      setTotal(body.data.page.total);
      setCanReconcile(body.data.canReconcile);
    } catch (failure) {
      if (!controller.signal.aborted && alive.current) setError(failure);
    } finally {
      if (!controller.signal.aborted && alive.current) setLoading(false);
    }
  }

  function choose(item: PendingReconciliation) {
    if (!writable || pendingRef.current || writes.current) return;
    setSelected(item);
    setAmount('');
    setEvidence('');
    setNote('');
    setEnded(false);
    setReceipt(null);
    setError(null);
  }

  async function findReceipt() {
    const snapshot = pendingRef.current;
    if (!snapshot || writes.current) return;
    const controller = new AbortController();
    writes.current = controller;
    setSaving(true);
    try {
      const {body} = await reconciliationReceipt({
        scope: snapshot.request.scope,
        budgetRef: snapshot.request.budgetRef,
        invocationId: snapshot.request.invocationId,
        key: snapshot.key
      }, {signal: controller.signal});
      if (controller.signal.aborted || !alive.current) return;
      setReceipt(body.data);
      setPending(null);
      pendingRef.current = null;
      if (!saveOperation(context, 'reconcile', config.budgetRef, null)) setStorageWarning(STORAGE_WARNING);
      await Promise.all([load(), onConfirmed(body.data.invocationId)]);
    } catch (failure) {
      if (!controller.signal.aborted && alive.current) setError(failure);
    } finally {
      if (!controller.signal.aborted && alive.current) {
        writes.current = null;
        setSaving(false);
      }
    }
  }

  async function confirm() {
    if (!writable || writes.current || restored.warning) return;
    let snapshot = pendingRef.current;
    if (!snapshot) {
      if (
        !selected ||
        !canReconcile ||
        !ended ||
        !/^[0-9]{1,20}(\.[0-9]{1,18})?$/.test(amount) ||
        !evidence.trim() ||
        !note.trim()
      )
        return;
      const settings = currentSettings.current;
      snapshot = {
        key: crypto.randomUUID(),
        request: {
          scope: {
            tenantId: settings.tenantId,
            workspaceId: settings.workspaceId,
          },
          budgetRef: selected.budgetRef,
          invocationId: selected.invocationId,
          invocationVersion: selected.invocationVersion,
          reservationId: selected.reservationId,
          reservationVersion: selected.reservationVersion,
          actualCharge: amount,
          currency: selected.reserved.currency,
          evidenceRef: evidence.trim(),
          note: note.trim(),
          executionEnded: ended,
        },
      };
    }
    pendingRef.current = snapshot;
    if (!saveOperation(context, 'reconcile', config.budgetRef, snapshot)) setStorageWarning(STORAGE_WARNING);
    setPending(snapshot);
    setSaving(true);
    setError(null);
    const controller = new AbortController();
    writes.current = controller;
    try {
      const {body} = await confirmReconciliation(
        snapshot.request,
        snapshot.key,
        {signal: controller.signal},
      );
      if (controller.signal.aborted || !alive.current) return;
      if (!saveOperation(context, 'reconcile', config.budgetRef, null)) setStorageWarning(STORAGE_WARNING);
      pendingRef.current = null;
      setPending(null);
      setReceipt(body.data);
      setSelected(null);
      // 权威费用已经保存；页面刷新失败时也保留核对成功回执。
      await Promise.all([load(), onConfirmed(body.data.invocationId)]);
    } catch (failure) {
      if (controller.signal.aborted || !alive.current) return;
      setError(failure);
      if (
        failure instanceof AiApiError &&
        failure.httpStatus >= 400 &&
        failure.httpStatus < 500 &&
        failure.httpStatus !== 408 && failure.code !== '205027'
      ) {
        if (!saveOperation(context, 'reconcile', config.budgetRef, null)) setStorageWarning(STORAGE_WARNING);
        pendingRef.current = null;
        setPending(null);
        if (failure.httpStatus === 409) {
          setSelected(null);
          await load();
          if (alive.current) setError(failure);
        }
      }
    } finally {
      if (!controller.signal.aborted && alive.current) {
        writes.current = null;
        setSaving(false);
      }
    }
  }

  const frozen = !!pending || saving || !writable;
  return (
    <>
      <Button
        size="small"
        onClick={() => {
          setOpen(true);
          void load();
        }}
      >
        {t('reconciliation.open')}
      </Button>
      <Drawer
        title={t('reconciliation.title')}
        open={open}
        onClose={() => setOpen(false)}
        size="large"
      >
        <Flex vertical gap={12} className="ai-management-panel">
          <Alert type="info" title={t('reconciliation.help')}/>
          {storageWarning && <Alert type="warning" title={storageWarning}/>}
          {pending && <Flex component="section" vertical gap={8} aria-label="恢复原核对操作">
            <Typography.Paragraph>待确认原核对：{pending.request.actualCharge} {pending.request.currency} · {pending.request.invocationId}</Typography.Paragraph>
            <Flex gap={8} wrap>
            <Button disabled={saving} onClick={() => void findReceipt()}>查询原核对回执</Button>
            {!selected && <Button disabled={!writable || !!restored.warning} loading={saving}
                                  onClick={() => void confirm()}>{t('reconciliation.retry')}</Button>}
            </Flex>
          </Flex>}
          <Button loading={loading} onClick={() => void load(page)}>
            {t('refreshBudget')}
          </Button>
          {error != null && (
            <Alert
              type="error"
              title={
                error instanceof AiApiError
                  ? `${error.message} (HTTP ${error.httpStatus})`
                  : t('error.network')
              }
            />
          )}
          {!loading && !records.length && (
            <Empty description={t('reconciliation.empty')}/>
          )}
          {!canReconcile && records.length > 0 && (
            <Alert type="info" title={t('reconciliation.permission')}/>
          )}
          <Flex component="ol" vertical gap={12} className="ai-management-list">
            {records.map((item) => (
              <Flex component="li" vertical
                key={item.reservationId}
              >
                <Card size="small" styles={{body: {padding: 12}}}>
                  <Flex vertical gap={8}>
                    <Descriptions column={1} size="small" className="ai-management-info" items={[
                      {key: 'invocation', label: t('invocationId'), children: item.invocationId},
                      {key: 'state', label: '执行状态', children: <Tag>{t(`execution.${item.state}`)}</Tag>},
                      {
                        key: 'reserved',
                        label: t('budgetHeld'),
                        children: `${item.reserved.amount} ${item.reserved.currency}`
                      },
                      ...(item.remoteRequestId ? [{
                        key: 'remote',
                        label: t('reconciliation.remoteId'),
                        children: item.remoteRequestId
                      }] : []),
                    ]}/>
                {canReconcile && (
                  <Button style={{alignSelf: 'flex-start'}} disabled={frozen} onClick={() => choose(item)}>
                    {t('reconciliation.review')}
                  </Button>
                )}
                  </Flex>
                </Card>
              </Flex>
            ))}
          </Flex>
          <Pagination
            current={page}
            total={total}
            pageSize={10}
            hideOnSinglePage
            showSizeChanger={false}
            disabled={frozen}
            onChange={(value) => void load(value)}
          />
          {selected && (
            <Flex component="section" vertical gap={8} aria-label={t('reconciliation.review')}>
              <Descriptions column={1} size="small" className="ai-management-info" items={[
                {key: 'invocation', label: t('invocationId'), children: selected.invocationId},
              ]}/>
              <Form component="div" layout="vertical" className="ai-management-form">
                <Form.Item htmlFor={`${formId}-amount`}
                           label={`${t('reconciliation.amount')} (${selected.reserved.currency})`} required>
                <Input
                  id={`${formId}-amount`}
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.amount')}
                  inputMode="decimal"
                  maxLength={39}
                />
                </Form.Item>
                <Form.Item htmlFor={`${formId}-evidence`} label={t('reconciliation.evidence')} required>
                <Input
                  id={`${formId}-evidence`}
                  value={evidence}
                  onChange={(event) => setEvidence(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.evidence')}
                  maxLength={256}
                />
                </Form.Item>
                <Form.Item htmlFor={`${formId}-note`} label={t('reconciliation.note')} required>
                <Input.TextArea
                  id={`${formId}-note`}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.note')}
                  maxLength={2000}
                />
                </Form.Item>
              </Form>
              <Checkbox
                checked={ended}
                disabled={frozen}
                onChange={(event) => setEnded(event.target.checked)}
              >
                {t('reconciliation.ended')}
              </Checkbox>
              {pending && !saving && (
                <Alert type="warning" title={t('reconciliation.retryHint')}/>
              )}
              <Button
                type="primary"
                style={{alignSelf: 'flex-start'}}
                loading={saving}
                onClick={() => void confirm()}
                aria-label={t(
                  pending && !saving
                    ? 'reconciliation.retry'
                    : 'reconciliation.confirm',
                )}
                disabled={
                  !pending &&
                  (!ended ||
                    !/^[0-9]{1,20}(\.[0-9]{1,18})?$/.test(amount) ||
                    !evidence.trim() ||
                    !note.trim())
                }
              >
                {t(
                  pending && !saving
                    ? 'reconciliation.retry'
                    : 'reconciliation.confirm',
                )}
              </Button>
            </Flex>
          )}
          {receipt && (
            <Alert
              type="success"
              title={t('reconciliation.done')}
              description={
                <Descriptions column={1} size="small" className="ai-management-info" items={[
                  {
                    key: 'charge',
                    label: t('reconciliation.amount'),
                    children: `${receipt.charge.amount} ${receipt.charge.currency}`
                  },
                  {key: 'reviewer', label: '审核人', children: receipt.reviewer.subjectName},
                  {key: 'time', label: '确认时间', children: receipt.confirmedAt},
                  {key: 'evidence', label: t('reconciliation.evidence'), children: receipt.evidenceRef},
                  {key: 'note', label: t('reconciliation.note'), children: receipt.note},
                  {key: 'invocation', label: t('invocationId'), children: receipt.invocationId},
                  {key: 'key', label: t('reconciliation.key'), children: receipt.key},
                ]}/>
              }
            />
          )}
        </Flex>
      </Drawer>
    </>
  );
}
