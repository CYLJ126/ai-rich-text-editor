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
          <label className="text-xs text-gray-500 font-medium">会话名称</label>
          <Input
            placeholder="请输入会话名称"
            value={conversationUpsert?.title || ''}
            onChange={(e) => setConversationUpsert({...conversationUpsert, title: e.target.value})}
            onBlur={() => handleUpdate('title', conversationUpsert.title)}
          />
        </div>

        {/* 会话助手 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">会话助手</label>
          <Select
            className="w-full"
            placeholder="请选择会话助手"
            allowClear
            value={conversationUpsert?.assistantId || ''}
            options={assistantOptions}
            onSelect={(value) => handleUpdate('assistantId', value)}
            onClear={() => handleUpdate('assistantId', null)}
          />
        </div>

        {/* 会话模型 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">会话模型</label>
          <Select
            className="w-full"
            placeholder="请选择会话模型"
            allowClear
            value={conversationUpsert.modelId || ''}
            options={modelOptions}
            onSelect={(value) => handleUpdate('modelId', value)}
            onClear={() => handleUpdate('modelId', null)}
          />
        </div>

        {/* 知识库 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">知识库</label>
          <Select
            className="w-full"
            placeholder="请选择知识库"
            allowClear
            options={knowledgeBaseOptions}
            value={conversationUpsert.knowledgeBaseId || ''}
            onSelect={(value) => handleUpdate('knowledgeBaseId', value)}
            onClear={() => handleUpdate('knowledgeBaseId', null)}
          />
        </div>

        {/* 上下文策略 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">上下文策略</label>
          <Select
            className="w-full"
            placeholder="请选择上下文策略"
            value={conversationUpsert.contextStrategy || ''}
            options={contextStrategyOptions}
            onSelect={(value) => handleUpdate('contextStrategy', value)}
          />
        </div>

        {/* 上下文窗口大小 */}
        {
          (conversationUpsert.contextStrategy === 'window' || conversationUpsert.contextStrategy === 'token') &&
          <div className="flex flex-col gap-1">
            <label className="text-xs text-gray-500 font-medium">上下文窗口大小</label>
            <div className="px-2 pb-2">
              <Tooltip title="上下文窗口大小" placement="topRight">
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
          <label className="text-xs text-gray-500 font-medium">系统提示词</label>
          <Input.TextArea
            placeholder="请输入系统提示词"
            className="scrollbar-thin"
            value={conversationUpsert.systemPrompt || ''}
            autoSize={{minRows: 3, maxRows: 15}}
            onChange={(e) => setConversationUpsert({...conversationUpsert, systemPrompt: e.target.value})}
            onBlur={(e) => handleUpdate('systemPrompt', e.target.value)}
          />
        </div>

        {/* 推理力度 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">推理力度</label>
          <Select
            className="w-full"
            placeholder="请选择推理力度（默认继承助手配置）"
            value={conversationUpsert.reasoningEffort || undefined}
            options={reasoningEffortOptions}
            onChange={(value) => handleUpdate('reasoningEffort', value ?? '')}
          />
        </div>

        {/* 文本类型 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">文本类型</label>
          <Select
            className="w-full"
            placeholder="请选择文本类型（默认继承助手配置）"
            value={conversationUpsert.textType || undefined}
            options={textTypeOptions}
            onChange={(value) => handleUpdate('textType', value ?? '')}
          />
        </div>

        {/* 温度 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">
            <span>温度 (Temperature)</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.temperature != null ? conversationUpsert.temperature : '默认'}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title="控制输出随机性，值越高越有创意，值越低越确定" placement="topRight">
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
          <label className="text-xs text-gray-500 font-medium">最大生成 Token 数</label>
          <InputNumber
            className="w-full!"
            placeholder="请输入最大 Token 数"
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
              {conversationUpsert.topP != null ? conversationUpsert.topP : '默认'}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title="top P 核采样概率阈值，与 Temperature 建议只调其一" placement="topRight">
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
              {conversationUpsert.topK != null ? conversationUpsert.topK : '默认'}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title="Top K 解码采样参数" placement="topRight">
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
            <span>存在惩罚 (Presence Penalty)</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.presencePenalty != null ? conversationUpsert.presencePenalty : '默认'}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title="正值鼓励模型讨论新话题，减少重复内容，范围 -2 ~ 2" placement="topRight">
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
            <span>频率惩罚 (Frequency Penalty)</span>
            <span className="ml-2 text-gray-400 font-normal">
              {conversationUpsert.frequencyPenalty != null ? conversationUpsert.frequencyPenalty : '默认'}
            </span>
          </label>
          <div className="px-2 pb-2">
            <Tooltip title="正值降低模型逐字重复相同内容的可能性，范围 -2 ~ 2" placement="topRight">
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
          <label className="text-xs text-gray-500 font-medium">全局记忆功能</label>
          <div
            className="flex items-center justify-between rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-2">
            <span className="text-xs text-gray-500">开启后将跨会话保留用户记忆偏好</span>
            <Switch
              size="medium"
              checked={conversationUpsert.globalMemoryFlag ?? false}
              onChange={(checked) => handleUpdate('globalMemoryFlag', checked)}
            />
          </div>
        </div>

        {/* 查询重写 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">查询重写功能</label>
          <div
            className="flex items-center justify-between rounded-lg border border-slate-300 dark:border-slate-600 px-3 py-2">
            <span className="text-xs text-gray-500">开启后将做查询重写</span>
            <Switch
              size="medium"
              checked={conversationUpsert.queryRewriteFlag ?? false}
              onChange={(checked) => handleUpdate('queryRewriteFlag', checked)}
            />
          </div>
        </div>

        {/* 额外模型参数 */}
        <div className="flex flex-col gap-1">
          <label className="text-xs text-gray-500 font-medium">额外模型参数</label>
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
              总消息条数：<span className="font-medium">{conversation?.messageCount ?? '-'}</span>
            </span>
            <button
              className="flex items-center gap-0.5 text-xs hover:text-gray-600 transition-colors cursor-pointer"
              onClick={() => setInfoCollapsed(false)}
            >
              <span>展开</span>
              <UpOutlined className="text-[10px]"/>
            </button>
          </div>
        ) : (
          /* 展开状态：显示所有信息 + 折叠按钮 */
          <div className="flex flex-col gap-1.5 text-gray-400">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium">统计信息</span>
              <button
                className="flex items-center gap-0.5 text-xs hover:text-gray-600 transition-colors cursor-pointer"
                onClick={() => setInfoCollapsed(true)}
              >
                <span>收起</span>
                <DownOutlined className="text-[10px]"/>
              </button>
            </div>
            <div className="flex flex-col gap-1">
              <div className="flex items-center justify-between">
                <span className="text-xs">总消息条数</span>
                <span className="text-xs font-medium">{conversation?.messageCount ?? '-'}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs">最新消息时间</span>
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
