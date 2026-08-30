import {i18nText} from '@/utils/i18n';
import {Button, Input} from 'antd';
import React, {type MouseEvent, useState} from 'react';

const { TextArea } = Input;

type ReplyComposerProps = {
  disabled?: boolean;
  canSubmit: boolean;
  onSubmit: (value: string) => Promise<void> | void;
};

export default function ReplyComposer({
  disabled,
  canSubmit,
  onSubmit,
}: ReplyComposerProps) {
  const [value, setValue] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const stopThreadSelect = (event: MouseEvent) => {
    event.stopPropagation();
  };

  return (
    <div
      className="mt-3"
      onClick={stopThreadSelect}
      onMouseDown={stopThreadSelect}
    >
      <TextArea
        autoSize={{ minRows: 2, maxRows: 4 }}
        disabled={disabled || submitting || !canSubmit}
        placeholder={i18nText("app.article.commentspanel.replycomposer.dcf0fecd")}
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
      <div className="mt-2 flex justify-end">
        <Button
          type="primary"
          size="small"
          loading={submitting}
          disabled={disabled || submitting || !canSubmit || !value.trim()}
          onClick={async (event) => {
            event.stopPropagation();
            setSubmitting(true);
            try {
              await onSubmit(value.trim());
              setValue('');
            } finally {
              setSubmitting(false);
            }
          }}
        >
          {i18nText("app.article.commentspanel.replycomposer.663d32ba")}
        </Button>
      </div>
    </div>
  );
}
