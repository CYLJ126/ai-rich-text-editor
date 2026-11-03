import {useEffect, useRef, useState} from 'react';
import * as api from '@/services/platform/model';
import {AiApiError} from '@/services/arte-ai/request';
import {checkWorkspaceAccess} from '@/services/platform/workspace';
import {type BrowserContext, readValue, storageKey, writeValue} from './storage';
import {modelFailure, readModelCommand, saveModelCommand, uncertainModelFailure} from './modelManagementState';

export interface ModelDetail {
  model: api.ManagedModel;
  draft: api.ModelDraft;
  actions: Set<api.ModelAction>;
  templates: api.ModelTemplate[];
  versions: api.ModelVersion[];
  totalVersions: number;
  versionPage: number;
  options: api.PublicationOption[];
  validation: api.ModelValidation | null;
}

/** 管理读取及命令协调：迟到响应不能跨资源／空间覆盖，未确认写入只按原请求和原键确认。 */
export type ModelChange = { kind: api.ModelCommand['kind'] | 'prepare'; resourceId: string | null };

export function useModelManagement(context: BrowserContext, onUpdated: (change: ModelChange) => void, initialResourceId?: string, initialModel?: import('@/services/platform/ai').ModelSelection) {
  const [restored] = useState(() => readModelCommand(context));
  const [pending, setPending] = useState<api.ModelCommand | null>(restored.command);
  const pendingRef = useRef(pending);
  const [blocked, setBlocked] = useState(restored.blocked);
  const [saving, setSaving] = useState(false);
  const writing = useRef(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [records, setRecords] = useState<api.ManagedModel[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const visitedCursors = useRef(new Set<string>());
  const [loading, setLoading] = useState(false);
  const [canCreate, setCanCreate] = useState(false);
  const [selected, setSelected] = useState(restored.command && 'resourceId' in restored.command.request ? restored.command.request.resourceId : initialResourceId ?? null);
  const [detail, setDetail] = useState<ModelDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [status, setStatus] = useState<api.PublicationStatus | null>(null);
  const [statusError, setStatusError] = useState('');
  const [selectedVersion, setSelectedVersion] = useState<api.ModelVersion | null>(null);
  const [closed, setClosed] = useState(false);
  const live = useRef(true);
  const listRead = useRef<AbortController | null>(null);
  const detailRead = useRef<AbortController | null>(null);
  const statusRead = useRef<AbortController | null>(null);
  const secret = useRef<string | null>(null);
  const [hasTemporarySecret, setHasTemporarySecret] = useState(false);
  const locating = useRef(!!initialModel);
  const update = useRef(onUpdated);
  update.current = onUpdated;

  useEffect(() => {
    live.current = true;
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== context.userId) return;
      live.current = false;
      secret.current = null;
      listRead.current?.abort();
      detailRead.current?.abort();
      statusRead.current?.abort();
      setClosed(true);
      setRecords([]);
      setDetail(null);
      setStatus(null);
      setHasTemporarySecret(false);
    };
    window.addEventListener('arte-account-session-cleared', logout);
    return () => {
      live.current = false;
      secret.current = null;
      listRead.current?.abort();
      detailRead.current?.abort();
      statusRead.current?.abort();
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [context.userId, context.scope.tenantId, context.scope.workspaceId]);

  async function loadList(append = false) {
    if (!live.current || closed) return;
    listRead.current?.abort();
    const controller = new AbortController();
    listRead.current = controller;
    const after = append ? cursor : null;
    if (!append) {
      setRecords([]);
      setCursor(null);
      visitedCursors.current.clear();
    }
    setLoading(true);
    setError('');
    setCanCreate(false);
    const results = await Promise.allSettled([
      api.listManagedModels(context.scope, after, {signal: controller.signal}),
      checkWorkspaceAccess(context.scope, 'workspace', 'model:create', {signal: controller.signal}),
    ]);
    if (!live.current || controller.signal.aborted) return;
    const [list, access] = results;
    if (list.status === 'fulfilled') {
      const page = list.value.body.data;
      if (page.nextCursor !== null && (page.nextCursor === after || visitedCursors.current.has(page.nextCursor))) {
        setError('目录游标未向后推进，请重新读取模型列表。');
        setCursor(null);
      } else {
        if (page.nextCursor) visitedCursors.current.add(page.nextCursor);
        setRecords(previous => append ? [...previous, ...page.records.filter(item => !previous.some(old => old.resourceId === item.resourceId))] : page.records);
        setCursor(page.nextCursor);
      }
      setCanCreate(access.status === 'fulfilled' && access.value.body.data.allowed);
      if (access.status === 'rejected') setError('无法核验创建权限，当前只允许读取。');
    } else {
      setError(modelFailure(list.reason, true));
      setRecords([]);
      setCursor(null);
      setDetail(null);
      setStatus(null);
    }
    setLoading(false);
  }

  async function open(resourceId: string, page = 1) {
    if (!live.current || closed) return;
    detailRead.current?.abort();
    statusRead.current?.abort();
    const controller = new AbortController();
    detailRead.current = controller;
    setSelected(resourceId);
    setDetail(null);
    setStatus(null);
    setSelectedVersion(null);
    setStatusError('');
    setError('');
    setDetailLoading(true);
    try {
      const results = await Promise.allSettled([
        api.getManagedModel(context.scope, resourceId, {signal: controller.signal}),
        api.getModelDraft(context.scope, resourceId, {signal: controller.signal}),
        api.checkModelActions(context.scope, resourceId, {signal: controller.signal}),
        api.listModelTemplates(context.scope, {signal: controller.signal}),
        api.listModelVersions(context.scope, resourceId, page, {signal: controller.signal}),
      ]);
      if (!live.current || controller.signal.aborted) return;
      const [model, draft, actions, templates, versions] = results;
      if (model.status === 'rejected') throw model.reason;
      if (draft.status === 'rejected') throw draft.reason;
      const value: ModelDetail = {
        model: model.value.body.data,
        draft: draft.value.body.data,
        actions: actions.status === 'fulfilled' ? actions.value : new Set(),
        templates: templates.status === 'fulfilled' ? templates.value.body.data : [],
        versions: versions.status === 'fulfilled' ? versions.value.body.data.records : [],
        totalVersions: versions.status === 'fulfilled' ? versions.value.body.data.total : 0,
        versionPage: page,
        options: [],
        validation: null
      };
      if (actions.status === 'rejected' || templates.status === 'rejected' || versions.status === 'rejected') setError('部分管理条件无法核验。相关操作保持关闭，请重新读取。');
      const remembered = readValue<{ validationId: string }>(storageKey(context, 'model-validation', resourceId));
      const reads = await Promise.allSettled([
        value.actions.has('model:edit') ? api.listPublicationOptions(context.scope, resourceId, {signal: controller.signal}) : Promise.resolve(null),
        remembered.value?.validationId && value.actions.has('model:validate') ? api.getModelValidation(context.scope, remembered.value.validationId, {signal: controller.signal}) : Promise.resolve(null),
      ]);
      if (!live.current || controller.signal.aborted) return;
      if (reads[0].status === 'fulfilled' && reads[0].value) value.options = reads[0].value.body.data;
      if (reads[1].status === 'fulfilled' && reads[1].value && reads[1].value.body.data.resourceId === resourceId) value.validation = reads[1].value.body.data;
      if (reads[0].status === 'rejected') setError('当前无法取得发布方案；发布功能可能未开放或资格已变化。可以继续读取草稿与历史。');
      if (locating.current && initialModel?.resourceId === resourceId) {
        let exact = value.versions.find(item => item.binding.id === initialModel.binding.id && item.binding.version === initialModel.binding.version);
        for (let current = 2; !exact && (current - 1) * 10 < value.totalVersions; current++) {
          const response = await api.listModelVersions(context.scope, resourceId, current, {signal: controller.signal});
          if (!live.current || controller.signal.aborted) return;
          exact = response.body.data.records.find(item => item.binding.id === initialModel.binding.id && item.binding.version === initialModel.binding.version);
        }
        if (!exact) throw new Error('定位版本不可读或已失效，请重新选择；不会采用最新版本。');
        locating.current = false;
        void selectVersion(exact);
      }
      setDetail(value);
    } catch (failure) {
      if (live.current && !controller.signal.aborted) {
        setError(modelFailure(failure));
        setDetail(null);
        setStatus(null);
      }
    } finally {
      if (live.current && !controller.signal.aborted) setDetailLoading(false);
    }
  }

  async function selectVersion(value: api.ModelVersion) {
    statusRead.current?.abort();
    const controller = new AbortController();
    statusRead.current = controller;
    setSelectedVersion(value);
    setStatus(null);
    setStatusError('');
    if (value.source !== 'RESOURCE') return;
    try {
      const result = await api.getPublicationStatus(context.scope, value.resourceId, value.binding, {signal: controller.signal});
      if (live.current && !controller.signal.aborted) setStatus(result.body.data);
    } catch (failure) {
      if (live.current && !controller.signal.aborted) setStatusError(modelFailure(failure));
    }
  }

  async function queryValidation() {
    const current = detail?.validation;
    if (!current || !selected) return;
    const controller = detailRead.current;
    try {
      const result = await api.getModelValidation(context.scope, current.validationId, {signal: controller?.signal});
      if (live.current && controller === detailRead.current && !controller?.signal.aborted) {
        if (result.body.data.resourceId !== selected || result.body.data.validationId !== current.validationId) throw new Error('identity');
        setDetail(previous => previous ? {...previous, validation: result.body.data} : previous);
      }
    } catch (failure) {
      if (live.current && !controller?.signal.aborted) setError(modelFailure(failure));
    }
  }

  async function run(command: api.ModelCommand, token?: string) {
    if (!live.current || closed || writing.current || restored.blocked) return false;
    const operation = pendingRef.current ?? api.modelCommandSchema.parse(command);
    pendingRef.current = operation;
    setPending(operation);
    setNotice('');
    setError('');
    if (operation.kind === 'credential' && token) {
      secret.current = token;
      setHasTemporarySecret(true);
    }
    if (!saveModelCommand(context, operation)) {
      setBlocked(true);
      setError('无法保存恢复记录，本次未提交。恢复浏览器存储后，请重试原操作。');
      return false;
    }
    setBlocked(false);
    if (operation.kind === 'credential' && !secret.current) {
      setError('请重新输入原操作的同一密钥，再确认原操作。');
      return false;
    }
    writing.current = true;
    setSaving(true);
    try {
      const result = await api.executeModelCommand(operation, secret.current ?? undefined);
      if (!live.current) return false;
      let resourceId = 'resourceId' in operation.request ? operation.request.resourceId : null;
      if (operation.kind === 'create' && 'value' in result && 'resourceId' in result.value) {
        const model = api.modelResourceSchema.parse(result.value);
        if (model.scope.tenantId !== context.scope.tenantId || model.scope.workspaceId !== context.scope.workspaceId) throw new Error('identity');
        resourceId = model.resourceId;
      }
      if (resourceId && 'value' in result) {
        const value = result.value;
        const actual = 'resourceId' in value ? value.resourceId : 'resource' in value ? value.resource.resourceId : null;
        if (actual !== resourceId) throw new Error('identity');
      }
      if (operation.kind === 'validate' && 'current' in result) {
        if (!resourceId || result.current.resourceId !== resourceId || result.current.draftVersion !== operation.request.draftVersion
          || result.current.credentialVersion !== operation.request.credentialVersion) throw new Error('identity');
        if (!writeValue(storageKey(context, 'model-validation', resourceId), {validationId: result.current.validationId})) {
          setBlocked(true);
          setError('验证已完成，但本地定位未保存。请恢复存储后按原操作确认，不要重新探测。');
          return false;
        }
      }
      if (!saveModelCommand(context, null)) {
        setBlocked(true);
        setError('服务端已确认，但恢复记录暂无法清除。请恢复存储后按原操作确认。');
        return false;
      }
      pendingRef.current = null;
      setPending(null);
      secret.current = null;
      setHasTemporarySecret(false);
      setNotice(operation.kind === 'publish' ? '发布已确认。请检查版本的当前状态；发布不会自动应用到会话或创建预算。' : '原操作已确认，当前内容已重新读取。');
      update.current({kind: operation.kind, resourceId});
      await loadList();
      if (resourceId) await open(resourceId);
      return true;
    } catch (failure) {
      if (!live.current) return false;
      setError(modelFailure(failure));
      if (!uncertainModelFailure(failure)) {
        secret.current = null;
        setHasTemporarySecret(false);
        if (saveModelCommand(context, null)) {
          pendingRef.current = null;
          setPending(null);
        } else setBlocked(true);
        if (failure instanceof AiApiError && [401, 403, 404].includes(failure.httpStatus)) {
          setDetail(null);
          setStatus(null);
          setRecords([]);
          setCanCreate(false);
        }
      }
      return false;
    } finally {
      writing.current = false;
      if (live.current) setSaving(false);
    }
  }

  useEffect(() => {
    void loadList();
    if (selected) void open(selected);
  }, []);
  return {
    records, cursor, loading, canCreate, selected, detail, detailLoading, selectedVersion, status, statusError, pending,
    blocked, saving, error, notice, closed, hasTemporarySecret, loadList, open, selectVersion, queryValidation, run,
    clearSecret: () => {
      secret.current = null;
      setHasTemporarySecret(false);
    },
    retry: (token?: string) => pendingRef.current ? run(pendingRef.current, token) : Promise.resolve(false)
  };
}
