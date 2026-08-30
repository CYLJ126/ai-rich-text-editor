import {i18nText} from '@/utils/i18n';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Conversation, ConversationUpsertDto} from "@/types/ai.type";
import {Input, InputNumber, Select, Slider, Switch, Tooltip} from "antd";
import {DownOutlined, UpOutlined} from '@ant-design/icons';
import {
  fullUpdateConversation,
  listAssistants,
  listContextStrategies,
  listKnowledgeBaseTypes,
  listModelConfigs,
  listReasoningEfforts,
  listTextTypes,
} from "@/services/ant-design-pro/ai.rbac";
import {JsonEditor} from "@/components";
import {copySameKeysImmutable} from "@/utils/reflectUtil";

export type ConversationEditSiderProps = {
  conversation?: Conversation;
  onUpdate?: (upsert: ConversationUpsertDto) => void;
}

// 会话侧边栏：用于会话设置，优先级高于关联的助手配置
const ConversationEditSider: React.FC<ConversationEditSiderProps> = ({conversation, onUpdate}) => {
  const [conversationUpsert, setConversationUpsert] = useState<ConversationUpsertDto>({} as ConversationUpsertDto);
  const [infoCollapsed, setInfoCollapsed] = useState(true);
  // 下拉选项
  const [assistantOptions, setAssistantOptions] = useState<any[]>([]);
  const [modelOptions, setModelOptions] = useState<any[]>([]);
  const [knowledgeBaseOptions, setKnowledgeBaseOptions] = useState<any[]>([]);
  const [contextStrategyOptions, setContextStrategyOptions] = useState<any[]>([]);
  const [textTypeOptions, setTextTypeOptions] = useState<any[]>([]);
  const [reasoningEffortOptions, setReasoningEffortOptions] = useState<any[]>([]);
  const [contextWindowMarks, setContextWindowMarks] = useState<any>();

  // 始终持有 conversationUpsert 最新引用，避免 handleUpdate 闭包陈旧值问题
  const conversationUpsertRef = useRef<ConversationUpsertDto>({} as ConversationUpsertDto);

  const handleUpdate = useCallback((field: string, value: any) => {
    const tempUpsert = {...conversationUpsertRef.current, [field]: value};
    setConversationUpsert(tempUpsert);
    conversationUpsertRef.current = tempUpsert;
    if (tempUpsert?.convId) {
      // 全量更新，允许更新为空值
      fullUpdateConversation?.(tempUpsert).then(() => onUpdate?.(tempUpsert));
    }
  }, [onUpdate]);

  // 切换会话时，填充会话数据
  useEffect(() => {
    const tempUpsert = copySameKeysImmutable(
      conversation || {},
      {
        convId: conversation?.convId ?? '',
        extraParam: {},
      } as ConversationUpsertDto,
      [
        'title',
        'modelId',
        'extraParam',
        'knowledgeBaseId',
        'systemPrompt',
        'contextStrategy',
        'contextWindow',
        'assistantId',
        'reasoningEffort',
        'textType',
        'temperature',
        'maxTokens',
        'topP',
        'topK',
        'presencePenalty',
        'frequencyPenalty',
        'globalMemoryFlag',
        'queryRewriteFlag',
      ] as const,
    );
    !tempUpsert.extraParam && (tempUpsert.extraParam = {});
    setConversationUpsert(tempUpsert);
    conversationUpsertRef.current = tempUpsert;
  }, [conversation, setConversationUpsert]);

  // 初始元数据加载
  useEffect(() => {
    // 加载助手下拉
    listAssistants({
      current: 1,
      size: 100,
      orders: [{column: 'sort_order', asc: true}, {column: 'update_time', asc: false}]
    }).then((res) => {
      if (res?.records && res.records?.length > 0) {
        setAssistantOptions(res.records.map((item: any) => ({value: item.id, label: item.name})));
      }
    });
    // 加载模型下拉
    listModelConfigs({
      current: 1,
      size: 100,
      orders: [{column: 'sort_order', asc: true}, {column: 'update_time', asc: false}]
    }).then((res) => {
      if (res?.records && res.records?.length > 0) {
        setModelOptions(res.records.map((item: any) => ({
          value: item.id,
          label: item.provider + ' - ' + item.modelName
        })));
      }
    });
    // 加载知识库下拉
    listKnowledgeBaseTypes().then((res) => {
      res && setKnowledgeBaseOptions(res);
    });

    // 加载上下文策略下拉
    listContextStrategies().then((res) => {
      res && setContextStrategyOptions(res);
    });

    // 加载文本类型下拉
    listTextTypes().then((res) => {
      res && setTextTypeOptions(res);
    });
    // 加载推理力度下拉
    listReasoningEfforts().then((res) => {
      res && setReasoningEffortOptions(res);
    });
  }, []);

  useEffect(() => {
    if (conversationUpsert.contextStrategy === 'window') {
      setContextWindowMarks({0: '0', 5: '5', 10: '10', 15: '15', 20: '20'});
    } else {
      setContextWindowMarks({0: '0', 2048: '2048', 4096: '4096', 6144: '6144', 8192: '8192'})
    }

  }, [conversationUpsert.contextStrategy]);

  return (
    <div className="flex flex-col h-full w-full overflow-hidden">

      {/* 主区域：可编辑字段，支持滚动，隐藏滚动条 */}
      <div
        className="flex-1 overflow-y-auto px-2 py-3 space-y-4"
        style={{scrollbarWidth: 'none', msOverflowStyle: 'none'}}
      >
        {/* 隐藏 WebKit 滚动条 */}
        <style>{`.hide-scrollbar::-webkit-scrollbar { display: none; }`}</style>

        {/* 会话名称 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.9510816f")}</label>
          <Input
            placeholder={i18nText("app.ai.conversationeditsider.9f4ed39f")}
            value={conversationUpsert?.title || ''}
            onChange={(e) => setConversationUpsert({...conversationUpsert, title: e.target.value})}
            onBlur={() => handleUpdate('title', conversationUpsert.title)}
          />
        </div>

        {/* 会话助手 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.813d269c")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.0687c731")}
            allowClear
            value={conversationUpsert?.assistantId || ''}
            options={assistantOptions}
            onSelect={(value) => handleUpdate('assistantId', value)}
            onClear={() => handleUpdate('assistantId', null)}
          />
        </div>

        {/* 会话模型 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.58427c4b")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.da22cddd")}
            allowClear
            value={conversationUpsert.modelId || ''}
            options={modelOptions}
            onSelect={(value) => handleUpdate('modelId', value)}
            onClear={() => handleUpdate('modelId', null)}
          />
        </div>

        {/* 知识库 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.58867126")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.07ec8e9f")}
            allowClear
            options={knowledgeBaseOptions}
            value={conversationUpsert.knowledgeBaseId || ''}
            onSelect={(value) => handleUpdate('knowledgeBaseId', value)}
            onClear={() => handleUpdate('knowledgeBaseId', null)}
          />
        </div>

        {/* 上下文策略 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.bfe6a5c4")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.231cea64")}
            value={conversationUpsert.contextStrategy || ''}
            options={contextStrategyOptions}
            onSelect={(value) => handleUpdate('contextStrategy', value)}
          />
        </div>

        {/* 上下文窗口大小 */}
        {
          (conversationUpsert.contextStrategy === 'window' || conversationUpsert.contextStrategy === 'token') &&
          <div className="flex flex-col gap-1">
            <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.b487910f")}</label>
            <div className="px-2 pb-2">
              <Tooltip title={i18nText("app.ai.conversationeditsider.b487910f")} placement="topRight">
                <Slider
                  value={conversationUpsert.contextWindow || 0}
                  min={0}
                  max={conversationUpsert.contextStrategy === 'window' ? 20 : 8192}
                  step={1}
                  marks={contextWindowMarks}
                  onChange={(value) => setConversationUpsert({...conversationUpsert, contextWindow: value})}
                  onChangeComplete={(value) => handleUpdate('contextWindow', value)}
                />
              </Tooltip>
            </div>
          </div>
        }

        {/* 系统提示词 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.97c91e97")}</label>
          <Input.TextArea
            placeholder={i18nText("app.ai.conversationeditsider.cd1cfaa9")}
            className="scrollbar-thin"
            value={conversationUpsert.systemPrompt || ''}
            autoSize={{minRows: 3, maxRows: 15}}
            onChange={(e) => setConversationUpsert({...conversationUpsert, systemPrompt: e.target.value})}
            onBlur={(e) => handleUpdate('systemPrompt', e.target.value)}
          />
        </div>

        {/* 推理力度 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.41891402")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.62ff5ba2")}
            value={conversationUpsert.reasoningEffort || undefined}
            options={reasoningEffortOptions}
            onChange={(value) => handleUpdate('reasoningEffort', value ?? '')}
          />
        </div>

        {/* 文本类型 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.4d85a313")}</label>
          <Select
            className="w-full"
            placeholder={i18nText("app.ai.conversationeditsider.41e6fd93")}
            value={conversationUpsert.textType || undefined}
            options={textTypeOptions}
            onChange={(value) => handleUpdate('textType', value ?? '')}
          />
        </div>

        {/* 温度 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>{i18nText("app.ai.conversationeditsider.6f5745a2")}</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.temperature != null ? conversationUpsert.temperature : i18nText("app.ai.conversationeditsider.0ab0f18f")}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title={i18nText("app.ai.conversationeditsider.6f24e3da")} placement="topRight">
              <Slider
                value={conversationUpsert.temperature ?? undefined}
                min={0}
                max={2}
                step={0.1}
                marks={{0: '0', 0.5: '0.5', 1: '1', 1.5: '1.5', 2: '2'}}
                onChange={(value) => setConversationUpsert({...conversationUpsert, temperature: value})}
                onChangeComplete={(value) => handleUpdate('temperature', value)}
              />
            </Tooltip>
          </div>
        </div>

        {/* 最大生成 Token 数 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.6c6afe30")}</label>
          <InputNumber
            className="w-full!"
            placeholder={i18nText("app.ai.conversationeditsider.825004cb")}
            value={conversationUpsert.maxTokens ?? undefined}
            min={1}
            max={128000}
            step={256}
            precision={0}
            onChange={(value) =>
              setConversationUpsert({...conversationUpsert, maxTokens: value ?? undefined})
            }
            onBlur={() => handleUpdate('maxTokens', conversationUpsert.maxTokens ?? null)}
          />
        </div>

        {/* Top P */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>Top P</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.topP != null ? conversationUpsert.topP : i18nText("app.ai.conversationeditsider.0ab0f18f")}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title={i18nText("app.ai.conversationeditsider.a195d9c8")} placement="topRight">
              <Slider
                value={conversationUpsert.topP ?? undefined}
                min={0}
                max={1}
                step={0.05}
                marks={{0: '0', 0.25: '0.25', 0.5: '0.5', 0.75: '0.75', 1: '1'}}
                onChange={(value) => setConversationUpsert({...conversationUpsert, topP: value})}
                onChangeComplete={(value) => handleUpdate('topP', value)}
              />
            </Tooltip>
          </div>
        </div>

        {/* Top K */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>Top K</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.topK != null ? conversationUpsert.topK : i18nText("app.ai.conversationeditsider.0ab0f18f")}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title={i18nText("app.ai.conversationeditsider.99776c3b")} placement="topRight">
              <Slider
                value={conversationUpsert.topK ?? undefined}
                min={0}
                max={100}
                step={5}
                marks={{0: '0', 25: '25', 50: '50', 75: '75', 100: '100'}}
                onChange={(value) => setConversationUpsert({...conversationUpsert, topK: value})}
                onChangeComplete={(value) => handleUpdate('topK', value)}
              />
            </Tooltip>
          </div>
        </div>

        {/* 存在惩罚 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>{i18nText("app.ai.conversationeditsider.c1e2ab7f")}</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.presencePenalty != null ? conversationUpsert.presencePenalty : i18nText("app.ai.conversationeditsider.0ab0f18f")}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title={i18nText("app.ai.conversationeditsider.882e3e5c")} placement="topRight">
              <Slider
                value={conversationUpsert.presencePenalty ?? undefined}
                min={-2}
                max={2}
                step={0.1}
                marks={{'-2': '-2', 0: '0', 2: '2'}}
                onChange={(value) => setConversationUpsert({...conversationUpsert, presencePenalty: value})}
                onChangeComplete={(value) => handleUpdate('presencePenalty', value)}
              />
            </Tooltip>
          </div>
        </div>

        {/* 频率惩罚 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>{i18nText("app.ai.conversationeditsider.12a65d45")}</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.frequencyPenalty != null ? conversationUpsert.frequencyPenalty : i18nText("app.ai.conversationeditsider.0ab0f18f")}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title={i18nText("app.ai.conversationeditsider.8b16a937")} placement="topRight">
              <Slider
                value={conversationUpsert.frequencyPenalty ?? undefined}
                min={-2}
                max={2}
                step={0.1}
                marks={{'-2': '-2', 0: '0', 2: '2'}}
                onChange={(value) => setConversationUpsert({...conversationUpsert, frequencyPenalty: value})}
                onChangeComplete={(value) => handleUpdate('frequencyPenalty', value)}
              />
            </Tooltip>
          </div>
        </div>

        {/* 全局记忆 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.f8e418d5")}</label>
          <div
            className="flex items-center justify-between rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-2">
            <span className="text-xs text-gray-500">{i18nText("app.ai.conversationeditsider.42d80d37")}</span>
            <Switch
              size="medium"
              checked={conversationUpsert.globalMemoryFlag ?? false}
              onChange={(checked) => handleUpdate('globalMemoryFlag', checked)}
            />
          </div>
        </div>

        {/* 查询重写 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.e71aa4e5")}</label>
          <div
            className="flex items-center justify-between rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-2">
            <span className="text-xs text-gray-500">{i18nText("app.ai.conversationeditsider.9711270d")}</span>
            <Switch
              size="medium"
              checked={conversationUpsert.queryRewriteFlag ?? false}
              onChange={(checked) => handleUpdate('queryRewriteFlag', checked)}
            />
          </div>
        </div>

        {/* 额外模型参数 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">{i18nText("app.ai.conversationeditsider.a0ecc096")}</label>
          <JsonEditor
            value={conversationUpsert.extraParam}
            onBlur={(parsed) => {
              // onBlur 时直接调用 handleUpdate，由 ref 保证读取最新 conversationUpsert
              if (parsed) handleUpdate('extraParam', parsed as Record<string, unknown>);
            }}
            height={150}
            placeholder={{key: 'value'}}
          />
        </div>
      </div>

      {/* 底部不可变信息 */}
      <div className="shrink-0 border-t border-slate-300 dark:border-slate-600 px-3 py-2">
        {infoCollapsed ? (
          /* 折叠状态：只显示总消息条数 + 展开按钮 */
          <div className="flex items-center justify-between text-gray-400">
            <span className="text-xs">
              {i18nText("app.ai.conversationeditsider.ff28b9a5")}<span className="font-medium">{conversation?.messageCount ?? '-'}</span>
            </span>
            <button
              className="flex items-center gap-0.5 text-xs hover:text-gray-600 transition-colors cursor-pointer"
              onClick={() => setInfoCollapsed(false)}
            >
              <span>{i18nText("app.ai.conversationeditsider.6662039e")}</span>
              <UpOutlined className="text-[10px]"/>
            </button>
          </div>
        ) : (
          /* 展开状态：显示所有信息 + 折叠按钮 */
          <div className="flex flex-col gap-1.5 text-gray-400">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium">{i18nText("app.ai.conversationeditsider.5fdc5eae")}</span>
              <button
                className="flex items-center gap-0.5 text-xs hover:text-gray-600 transition-colors cursor-pointer"
                onClick={() => setInfoCollapsed(true)}
              >
                <span>{i18nText("app.ai.conversationeditsider.334a9280")}</span>
                <DownOutlined className="text-[10px]"/>
              </button>
            </div>
            <div className="flex flex-col gap-1">
              <div className="flex items-center justify-between">
                <span className="text-xs">{i18nText("app.ai.conversationeditsider.fbd7966f")}</span>
                <span className="text-xs font-medium">{conversation?.messageCount ?? '-'}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs">{i18nText("app.ai.conversationeditsider.88ce2098")}</span>
                <span className="text-xs font-medium">{conversation?.lastMessageAt ?? '-'}</span>
              </div>
            </div>
          </div>
        )}
      </div>

    </div>
  );
};

export default ConversationEditSider;
