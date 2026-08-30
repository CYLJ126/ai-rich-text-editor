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
        placeholder="回复这个批注线程..."
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
          回复
        </Button>
      </div>
    </div>
  );
}
