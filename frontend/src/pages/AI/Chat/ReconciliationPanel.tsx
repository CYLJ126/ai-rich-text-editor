import {Alert, Button, Checkbox, Drawer, Empty, Input, Pagination, Space, Tag,} from 'antd';
import React, {useEffect, useId, useRef, useState} from 'react';
import {
  AiApiError,
  confirmReconciliation,
  type ConfirmReconciliationRequest,
  type PendingReconciliation,
  pendingReconciliations,
  type ReconciliationReceipt,
} from '@/services/arte-ai';
import type {ChatTestConfig} from './config';

/** 由后端授权决定能否核对；未知费用只接受管理员核实的实际账单，不填估算默认值。 */
export default function ReconciliationPanel({
                                              config,
                                              t,
                                              onConfirmed,
                                            }: {
  config: ChatTestConfig;
  t: (key: string) => string;
  onConfirmed: (id: string) => Promise<void>;
}) {
  const formId = useId();
  const [open, setOpen] = useState(false);
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
  } | null>(null);
  const pendingRef = useRef<typeof pending>(null);
  const reads = useRef<AbortController | null>(null);
  const writes = useRef<AbortController | null>(null);
  const alive = useRef(true);
  const currentSettings = useRef(config);
  currentSettings.current = config;
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
    if (pendingRef.current || writes.current) return;
    setSelected(item);
    setAmount('');
    setEvidence('');
    setNote('');
    setEnded(false);
    setReceipt(null);
    setError(null);
  }

  async function confirm() {
    if (writes.current) return;
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
        failure.httpStatus !== 408
      ) {
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

  const frozen = !!pending || saving;
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
        <Space orientation="vertical" style={{width: '100%'}}>
          <Alert type="info" title={t('reconciliation.help')}/>
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
          <ol className="m-0 list-none space-y-3 p-0">
            {records.map((item) => (
              <li
                key={item.reservationId}
                className="rounded border border-solid border-[var(--ant-color-border)] p-3"
              >
                <p className="break-all">
                  {t('invocationId')}：{item.invocationId}
                </p>
                <p>
                  <Tag>{t(`execution.${item.state}`)}</Tag>
                  {t('budgetHeld')}：{item.reserved.amount}{' '}
                  {item.reserved.currency}
                </p>
                {item.remoteRequestId && (
                  <p className="break-all">
                    {t('reconciliation.remoteId')}：{item.remoteRequestId}
                  </p>
                )}
                {canReconcile && (
                  <Button disabled={frozen} onClick={() => choose(item)}>
                    {t('reconciliation.review')}
                  </Button>
                )}
              </li>
            ))}
          </ol>
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
            <section aria-label={t('reconciliation.review')}>
              <p className="break-all">
                {t('invocationId')}：{selected.invocationId}
              </p>
              <label htmlFor={`${formId}-amount`}>
                {t('reconciliation.amount')} ({selected.reserved.currency})
                <Input
                  id={`${formId}-amount`}
                  value={amount}
                  onChange={(event) => setAmount(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.amount')}
                  inputMode="decimal"
                  maxLength={39}
                />
              </label>
              <label htmlFor={`${formId}-evidence`}>
                {t('reconciliation.evidence')}
                <Input
                  id={`${formId}-evidence`}
                  value={evidence}
                  onChange={(event) => setEvidence(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.evidence')}
                  maxLength={256}
                />
              </label>
              <label htmlFor={`${formId}-note`}>
                {t('reconciliation.note')}
                <Input.TextArea
                  id={`${formId}-note`}
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  disabled={frozen}
                  aria-label={t('reconciliation.note')}
                  maxLength={2000}
                />
              </label>
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
            </section>
          )}
          {receipt && (
            <Alert
              type="success"
              title={t('reconciliation.done')}
              description={
                <>
                  <p>
                    {receipt.charge.amount} {receipt.charge.currency} ·{' '}
                    {receipt.reviewer.subjectName} · {receipt.confirmedAt}
                  </p>
                  <p>
                    {t('reconciliation.evidence')}：{receipt.evidenceRef}
                  </p>
                  <p>{receipt.note}</p>
                  <p className="break-all">
                    {t('invocationId')}：{receipt.invocationId} ·{' '}
                    {t('reconciliation.key')}：{receipt.key}
                  </p>
                </>
              }
            />
          )}
        </Space>
      </Drawer>
    </>
  );
}
