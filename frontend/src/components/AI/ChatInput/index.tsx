import {i18nText} from '@/utils/i18n';
import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState,} from 'react';
import {Button, Input, Tooltip, Upload,} from 'antd';
import {
  CloseOutlined,
  FileImageOutlined,
  PaperClipOutlined,
  PictureOutlined,
  SearchOutlined,
  SendOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import {createStyles} from 'antd-style';
import {ModelSelector} from '@/components';
import type {UploadFile} from 'antd/es/upload';
import {Conversation, ModelConfig, SendMessageParams} from "@/types/ai.type";

const useStyles = createStyles(({token, css}) => ({
  container: css`
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 16px 16px;
    background: ${token.colorBgContainer};
    border-top: 1px solid ${token.colorBorderSecondary};
    flex-shrink: 0;
  `,
  quotedBar: css`
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    background: ${token.colorPrimaryBg};
    border-radius: ${token.borderRadius}px;
    border-left: 3px solid ${token.colorPrimary};
    font-size: 12px;
    color: ${token.colorTextSecondary};
  `,
  quotedText: css`
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  toolBar: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    flex-wrap: wrap;
  `,
  toolLeft: css`
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  `,
  toolRight: css`
    display: flex;
    align-items: center;
    gap: 8px;
  `,
  toolBtn: css`
    font-size: 13px;
    color: ${token.colorTextSecondary};
    padding: 4px 8px;
    border-radius: 6px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 4px;
    transition: color 0.15s, background 0.15s;
    border: 1px solid transparent;

    &:hover {
      color: ${token.colorText};
      background: ${token.colorFillSecondary};
    }

    &.active {
      color: ${token.colorPrimary};
      background: ${token.colorPrimaryBg};
      border-color: ${token.colorPrimaryBorder};
    }
  `,
  senderWrapper: css`
    .ant-sender {
      border-radius: ${token.borderRadiusLG}px;
      border: 1px solid ${token.colorBorder};
      transition: border-color 0.2s, box-shadow 0.2s;

      &:focus-within {
        border-color: ${token.colorPrimary};
        box-shadow: 0 0 0 2px ${token.colorPrimaryBg};
      }
    }

    .ant-sender-content {
      padding: 8px 12px;
      min-height: 72px;
      max-height: 240px;
      overflow-y: auto;

      &::-webkit-scrollbar {
        width: 4px;
      }

      &::-webkit-scrollbar-thumb {
        background: ${token.colorFillSecondary};
        border-radius: 2px;
      }
    }
  `,
  fileList: css`
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 4px 0;
  `,
  fileTag: css`
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 2px 8px;
    background: ${token.colorFillSecondary};
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 6px;
    font-size: 12px;
    color: ${token.colorTextSecondary};
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  `,
  fileRemove: css`
    cursor: pointer;
    color: ${token.colorTextQuaternary};
    flex-shrink: 0;
    font-size: 11px;

    &:hover {
      color: ${token.colorError};
    }
  `,
  charCount: css`
    font-size: 11px;
    color: ${token.colorTextQuaternary};

    &.warn {
      color: ${token.colorWarning};
    }

    &.over {
      color: ${token.colorError};
    }
  `,
  sendBtn: css`
    height: 36px;
    min-width: 80px;
    border-radius: ${token.borderRadius}px;
    font-size: 14px;
    display: flex;
    align-items: center;
    gap: 4px;
  `,
  stopBtn: css`
    height: 36px;
    min-width: 80px;
    border-radius: ${token.borderRadius}px;
    font-size: 14px;
    display: flex;
    align-items: center;
    gap: 4px;
    border-color: ${token.colorError};
    color: ${token.colorError};

    &:hover {
      background: ${token.colorErrorBg} !important;
      border-color: ${token.colorError} !important;
      color: ${token.colorError} !important;
    }
  `,
}));

// ─── 常量 ───
export const DEFAULT_CHAT_INPUT_HEIGHT = 140;
const MAX_CHARS = 4000;
const ACCEPT_IMAGE = '.jpg,.jpeg,.png,.gif,.webp,.svg';
const ACCEPT_FILE = '.pdf,.doc,.docx,.txt,.md,.csv,.xlsx';

// ─── Ref Handle ───
export interface ChatInputHandle {
  focus: () => void;
  disable: () => void;
  setInputValue: (val: string) => void;
  clear: () => void;
}

// ─── Props ───
export interface ChatInputProps {
  currentConv?: Conversation | null;
  disabled?: boolean;
  quotedMessage?: { messageId: string; content: string } | null;
  onClearQuote?: () => void;
  onSend?: () => void;
  onHeightChange?: (height: number) => void;
  sendMessage: (params: SendMessageParams) => void;
  isStreaming?: boolean;
  stopStreaming?: () => void;
}

// ─── 聊天框组件：文件列表、输入框、工具栏、发送按钮 ───
const ChatInput = forwardRef<ChatInputHandle, ChatInputProps>(
  ({
     currentConv,
     disabled = false,
     quotedMessage,
     onClearQuote,
     onSend,
     onHeightChange,
     sendMessage,
     isStreaming,
     stopStreaming
   }, ref) => {
    const {styles, cx} = useStyles();
    const height = useRef<number>(DEFAULT_CHAT_INPUT_HEIGHT);

    const [inputValue, setInputValue] = useState<string>('');
    const [fileList, setFileList] = useState<UploadFile[]>([]);
    const [activeModel, setActiveModel] = useState<ModelConfig | undefined>(undefined);
    const [enableSearch, setEnableSearch] = useState(false);
    const [enableVision, setEnableVision] = useState(false);
    const [generateImage, setGenerateImage] = useState(false);
    const [enableThinking, setEnableThinking] = useState(false);
    const senderRef = useRef<any>(undefined);

    const clearSendInfo = useCallback(() => {
      setInputValue('');
      setFileList([]);
    }, []);

    // 暴露方法给父组件
    useImperativeHandle(ref, () => ({
      focus: () => senderRef.current?.focus(),
      disable: () => {
      }, // TODO
      setInputValue: (val: string) => setInputValue(val),
      clear: clearSendInfo,
    }));

    const charLen = inputValue.length;
    const charWarn = charLen > MAX_CHARS * 0.8;
    const charOver = charLen > MAX_CHARS;

    const canSend =
      currentConv?.convId &&
      activeModel &&
      !disabled &&
      !isStreaming &&
      !charOver &&
      (inputValue.trim().length > 0 || fileList.length > 0);

    // 发送
    const handleSend = useCallback(async () => {
      if (!canSend) return;
      const content = inputValue.trim();
      const files = fileList.map((f) => f.originFileObj!).filter(Boolean);
      setInputValue('');
      setFileList([]);
      const attachments = files.map((f) => ({
        file: f,
        attachmentId: f.uid,
        fileName: f.name,
        fileSize: f.size,
        fileUrl: 'f.url', // TODO
        fileType: f.type,
        thumbnailUrl: 'f.thumbUrl', // TODO
      }));
      sendMessage({
        content,
        attachments,
        quotedMessage: quotedMessage || undefined,
        model: activeModel,
        enableSearch,
        enableVision,
        enableThinking,
        generateImage,
      });
      onClearQuote?.();
      onSend?.();
    }, [
      canSend,
      inputValue,
      fileList,
      enableSearch,
      enableVision,
      enableThinking,
      generateImage,
      sendMessage,
      quotedMessage,
      onClearQuote,
      onSend,
    ]);

    // 键盘快捷键：Enter 发送，Shift+Enter 换行
    const handleKeyDown = useCallback(
      (e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
          console.log('触发发送事件')
          e.preventDefault();
          handleSend().then();
        }
      },
      [handleSend],
    );

    // 文件上传前处理
    const handleBeforeUpload = useCallback(
      (file: UploadFile) => {
        setFileList((prev) => [...prev, file]);
        return false; // 阻止自动上传
      },
      [],
    );

    // 移除文件
    const handleRemoveFile = useCallback((uid: string) => {
      setFileList((prev) => prev.filter((f) => f.uid !== uid));
    }, []);

    // 切换会话时清空
    useEffect(() => {
      setInputValue('');
      setFileList([]);
    }, [currentConv]);

    // 监听输入框高度变化
    useEffect(() => {
      const element = senderRef?.current?.resizableTextArea?.textArea;
      if (!element) return;
      const resizeObserver = new ResizeObserver((entries) => {
        for (const entry of entries) {
          let inputHeight = entry.contentRect.height;
          let filesHeight = fileList?.length > 0 ? 50 : 0;
          inputHeight = Math.max(inputHeight + 80 + filesHeight, DEFAULT_CHAT_INPUT_HEIGHT + filesHeight);
          height.current = inputHeight;
          onHeightChange?.(inputHeight);
        }
      });
      resizeObserver.observe(element);
      return () => {
        resizeObserver.disconnect();
      };
    }, [fileList]);

    // 监听附件列表以调整高度
    useEffect(() => {
      if (fileList?.length > 0) {
        height.current = height.current + 50;
        onHeightChange?.(height.current);
      }
    }, [fileList]);

    return (
      <div className={styles.container} style={{height: height.current}}>
        {/* 引用消息条 */}
        {quotedMessage && (
          <div className={styles.quotedBar}>
            <span style={{flexShrink: 0, color: '#1677ff', fontSize: 12}}>
              {i18nText("app.ai.chatinput.024309b7")}
            </span>
            <span className={styles.quotedText}>{quotedMessage.content}</span>
            <CloseOutlined
              style={{cursor: 'pointer', fontSize: 11, flexShrink: 0}}
              onClick={onClearQuote}
            />
          </div>
        )}

        {/* 文件列表 */}
        {fileList.length > 0 && (
          <div className={styles.fileList}>
            {fileList.map((f) => (
              <div key={f.uid} className={styles.fileTag} title={f.name}>
                <FileImageOutlined style={{flexShrink: 0}}/>
                <span style={{overflow: 'hidden', textOverflow: 'ellipsis'}}>
                  {f.name}
                </span>
                <CloseOutlined
                  className={styles.fileRemove}
                  onClick={() => handleRemoveFile(f.uid)}
                />
              </div>
            ))}
          </div>
        )}

        {/* 输入框 */}
        <div className={styles.senderWrapper}>
          <Input.TextArea
            className="overflow-auto scrollbar-none"
            ref={senderRef}
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={i18nText("app.ai.chatinput.b4975a10")}
            disabled={disabled || isStreaming}
            autoSize={{minRows: 3, maxRows: 16}}
          />
        </div>

        {/* 工具栏 */}
        <div className={styles.toolBar}>
          <div className={styles.toolLeft}>
            {/* 模型选择 */}
            <ModelSelector onSelect={setActiveModel}/>

            {/* 深度思考 */}
            <Tooltip title={i18nText("app.ai.chatinput.6056ef00")}>
              <span
                className={cx(styles.toolBtn, enableThinking && 'active')}
                onClick={() => setEnableThinking(!enableThinking)}
              >
                <ThunderboltOutlined/>
                <span>{i18nText("app.ai.chatinput.3b36c153")}</span>
              </span>
            </Tooltip>

            {/* 启用视觉 */}
            <Tooltip title={i18nText("app.ai.chatinput.c5b56cce")}>
              <span
                className={cx(styles.toolBtn, enableVision && 'active')}
                onClick={() => setEnableVision(!enableVision)}
              >
                <PictureOutlined/>
                <span>{i18nText("app.ai.chatinput.377a72a8")}</span>
              </span>
            </Tooltip>

            {/* 生成图片 */}
            <Tooltip title={i18nText("app.ai.chatinput.3d1e59a1")}>
              <span
                className={cx(styles.toolBtn, generateImage && 'active')}
                onClick={() => setGenerateImage(!generateImage)}
              >
                <PictureOutlined/>
                <span>{i18nText("app.ai.chatinput.b81bd99e")}</span>
              </span>
            </Tooltip>

            {/* 联网搜索 */}
            <Tooltip title={i18nText("app.ai.chatinput.5d73cd74")}>
              <span
                className={cx(styles.toolBtn, enableSearch && 'active')}
                onClick={() => setEnableSearch(!enableSearch)}
              >
                <SearchOutlined/>
                <span>{i18nText("app.ai.chatinput.cc513812")}</span>
              </span>
            </Tooltip>

            {/* 上传图片 */}
            <Tooltip title={i18nText("app.ai.chatinput.f9af39e3")}>
              <Upload
                accept={ACCEPT_IMAGE}
                showUploadList={false}
                beforeUpload={handleBeforeUpload as any}
                multiple
              >
                <span className={styles.toolBtn}>
                  <FileImageOutlined/>
                </span>
              </Upload>
            </Tooltip>

            {/* 上传文件 */}
            <Tooltip title={i18nText("app.ai.chatinput.36b0293e")}>
              <Upload
                accept={ACCEPT_FILE}
                showUploadList={false}
                beforeUpload={handleBeforeUpload as any}
                multiple
              >
                <span className={styles.toolBtn}>
                  <PaperClipOutlined/>
                </span>
              </Upload>
            </Tooltip>
          </div>

          <div className={styles.toolRight}>
            {/* 字数统计 */}
            <span
              className={cx(
                styles.charCount,
                charWarn && !charOver && 'warn',
                charOver && 'over',
              )}
            >
              {charLen}/{MAX_CHARS}
            </span>

            {/* 发送 / 停止 */}
            {isStreaming ? (
              <Button
                className={styles.stopBtn}
                icon={<StopOutlined/>}
                onClick={stopStreaming}
                variant="outlined"
              >
                {i18nText("app.ai.chatinput.84a6ca33")}
              </Button>
            ) : (
              <Button
                className={styles.sendBtn}
                type="primary"
                icon={<SendOutlined/>}
                onClick={handleSend}
                disabled={!canSend}
              >
                {i18nText("app.ai.chatinput.36f4a4dc")}
              </Button>
            )}
          </div>
        </div>
      </div>
    );
  },
);

export default ChatInput;
