import {Input, Modal} from 'antd';
import React, {useEffect, useState} from 'react';
import {i18nText as t} from '@/utils/i18n';

export function validConversationTitle(title: string): boolean {
  return (
    title.trim().length > 0 &&
    title.length <= 256 &&
    !Array.from(title).some((character) => {
      const code = character.charCodeAt(0);
      return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
    })
  );
}

export default function ConversationDialog({
                                             open,
                                             initialTitle,
                                             renaming,
                                             busy,
                                             onCancel,
                                             onSave,
                                           }: {
  open: boolean;
  initialTitle: string;
  renaming: boolean;
  busy: boolean;
  onCancel: () => void;
  onSave: (title: string) => Promise<void>;
}) {
  const [title, setTitle] = useState(initialTitle);
  useEffect(() => {
    if (open) setTitle(initialTitle);
  }, [open, initialTitle]);
  return (
    <Modal
      open={open}
      title={t(renaming ? 'app.aiNew.rename' : 'app.aiNew.create')}
      okText={t('app.aiNew.save')}
      cancelText={t('app.aiNew.cancel')}
      confirmLoading={busy}
      closable={!busy}
      mask={{closable: !busy}}
      onCancel={() => {
        if (!busy) onCancel();
      }}
      okButtonProps={{disabled: !validConversationTitle(title) || busy}}
      cancelButtonProps={{disabled: busy}}
      onOk={() => onSave(title.trim())}
    >
      <label htmlFor="ai-new-conversation-title">
        {t('app.aiNew.titleLabel')}
      </label>
      <Input
        id="ai-new-conversation-title"
        autoFocus
        value={title}
        maxLength={256}
        showCount
        disabled={busy}
        onChange={(event) => setTitle(event.target.value)}
        onPressEnter={() => {
          if (!busy && validConversationTitle(title)) void onSave(title.trim());
        }}
      />
    </Modal>
  );
}
