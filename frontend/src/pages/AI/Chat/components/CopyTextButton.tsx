import {Button, Space, Typography} from 'antd';
import {useEffect, useRef, useState} from 'react';
import {i18nText as t} from '@/utils/i18n';

export default function CopyTextButton({
                                         text,
                                         label,
                                       }: {
  text: string;
  label: string;
}) {
  const [state, setState] = useState<'idle' | 'copying' | 'copied' | 'failed'>(
    'idle',
  );
  const generation = useRef(0);
  useEffect(() => {
    generation.current++;
    setState('idle');
    return () => {
      generation.current++;
    };
  }, [text]);
  const copy = async () => {
    const current = generation.current;
    setState('copying');
    try {
      await navigator.clipboard.writeText(text);
      if (current === generation.current) setState('copied');
    } catch {
      if (current === generation.current) setState('failed');
    }
  };
  return (
    <Space wrap size="small">
      <Button
        size="small"
        aria-label={label}
        loading={state === 'copying'}
        onClick={() => {
          void copy();
        }}
      >
        {label}
      </Button>
      <Typography.Text
        role="status"
        type={state === 'failed' ? 'warning' : 'secondary'}
      >
        {state === 'copied'
          ? t('app.aiNew.copied')
          : state === 'failed'
            ? t('app.aiNew.copyFailed')
            : ''}
      </Typography.Text>
    </Space>
  );
}
