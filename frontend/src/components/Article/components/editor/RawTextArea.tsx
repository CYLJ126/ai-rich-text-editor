import {App, Input} from 'antd';
import React, {useCallback} from 'react';
import {uploadFile, uploadImage} from '@/services/upload';
import styles from './RawTextArea.less';
import {useArticleInfoStore, useEditorStore} from "@/components/Article";
import dayjs from "dayjs";

function RawTextArea() {
  const editorStyle = useEditorStore(state => state.editorStyle);
  const viewSize = useEditorStore(state => state.viewSize);
  const rawText = useArticleInfoStore(state => state.rawText);
  const setRawText = useArticleInfoStore(state => state.setRawText);
  const articleInfo = useArticleInfoStore(state => state.articleInfo);
  const operationMode = useEditorStore(state => state.operationMode);
  const editAreaHeight = useEditorStore(state => state.editAreaHeight);
  const markRawTextEdited = useArticleInfoStore(state => state.markRawTextEdited);
  const setSavingState = useArticleInfoStore(state => state.setSavingState);

  const isReadOnly = !articleInfo?.canWrite || operationMode !== 'edit';
  const { message } = App.useApp();

  const handlePaste = useCallback(
    async (e: React.ClipboardEvent) => {
      const items = e.clipboardData?.items;
      if (!items) return;

      for (let i = 0; i < items.length; i++) {
        const item = items[i];
        const file = item.getAsFile();
        if (!file) continue;

        if (item.type.startsWith('image/')) {
          e.preventDefault();
          try {
            const url = await uploadImage(file);
            insertTextAtCursor(`![image](${url})`);
          } catch {
            message.error('图片上传失败');
          }
        } else if (item.kind === 'file') {
          e.preventDefault();
          try {
            const url = await uploadFile(file);
            insertTextAtCursor(`[${file.name}](${url})`);
          } catch {
            message.error('文件上传失败');
          }
        }
      }
    },
    [message],
  );

  const insertTextAtCursor = (text: string) => {
    const textarea = document.getElementById(
      'raw-text',
    ) as HTMLTextAreaElement | null;
    if (!textarea) return;
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const newText = rawText ? rawText.slice(0, start) + text + rawText.slice(end) : text;
    setRawText(newText);
    setTimeout(() => {
      textarea.selectionStart = textarea.selectionEnd = start + text.length;
    }, 0);
  };

  return (
    <div
      style={{
        height: editAreaHeight,
        display: 'flex',
        justifyContent: 'center',
      }}
    >
      <Input.TextArea
        id="raw-text"
        className={`${styles.rawTextArea}`}
        style={{
          width: viewSize === 0 ? '100%' : viewSize,
          backgroundColor: editorStyle.backgroundColor,
        }}
        readOnly={isReadOnly}
        value={rawText}
        onChange={(e) => {
          if (isReadOnly) return;
          setRawText(e.target.value);
          markRawTextEdited(dayjs());
          setSavingState(4);
        }}
        onPaste={isReadOnly ? undefined : handlePaste}
      />
    </div>
  );
}

export default RawTextArea;
