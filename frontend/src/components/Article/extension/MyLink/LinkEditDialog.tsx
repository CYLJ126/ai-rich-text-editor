import {Button, Input, Modal, Typography} from 'antd';
import {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {i18nText} from '@/utils/i18n';

interface LinkValues {
  text: string;
  url: string;
}

interface LinkEditDialogOptions {
  initialText: string;
  initialUrl: string;
  isEditing: boolean;
  onSubmit: (values: LinkValues) => string | undefined;
  onRemove: () => string | undefined;
}

function normalizeLinkUrl(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return undefined;

  const protocol = trimmed.match(/^([a-z][a-z\d+.-]*):/i)?.[1]?.toLowerCase();
  if (protocol && protocol !== 'http' && protocol !== 'https') {
    return undefined;
  }

  return trimmed;
}

function LinkEditDialog({
                          initialText,
                          initialUrl,
                          isEditing,
                          onSubmit,
                          onRemove,
                          onClosed,
                        }: LinkEditDialogOptions & { onClosed: () => void }) {
  const [open, setOpen] = useState(true);
  const [text, setText] = useState(initialText);
  const [url, setUrl] = useState(initialUrl);
  const [error, setError] = useState<string>();

  const close = () => setOpen(false);
  const submit = () => {
    const normalizedUrl = normalizeLinkUrl(url);
    if (!text.trim()) {
      setError(i18nText('app.article.mylink.1e6f312c'));
      return;
    }
    if (!normalizedUrl) {
      setError(i18nText('app.article.mylink.7a236fa8'));
      return;
    }

    const submitError = onSubmit({text, url: normalizedUrl});
    if (submitError) {
      setError(submitError);
      return;
    }
    close();
  };

  const fieldStyle = {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 6,
  };

  return (
    <Modal
      open={open}
      title={i18nText('app.article.mylink.c02980f4')}
      width={520}
      mask={{closable: false}}
      destroyOnHidden
      onCancel={close}
      afterClose={onClosed}
      footer={
        <div
          style={{
            display: 'flex',
            justifyContent: isEditing ? 'space-between' : 'flex-end',
            gap: 8,
          }}
        >
          {isEditing && (
            <Button
              danger
              type="text"
              onClick={() => {
                const removeError = onRemove();
                if (removeError) {
                  setError(removeError);
                  return;
                }
                close();
              }}
            >
              {i18nText('app.article.mylink.f1798d4a')}
            </Button>
          )}
          <div style={{display: 'flex', gap: 8}}>
            <Button onClick={close}>
              {i18nText('app.article.mylink.30f41c89')}
            </Button>
            <Button type="primary" onClick={submit}>
              {i18nText('app.article.mylink.6214de17')}
            </Button>
          </div>
        </div>
      }
    >
      <div style={{display: 'flex', flexDirection: 'column', gap: 16}}>
        <label htmlFor="link-display-text" style={fieldStyle}>
          <span>{i18nText('app.article.mylink.09d2505a')}</span>
          <Input
            id="link-display-text"
            autoFocus
            value={text}
            status={!text.trim() && error ? 'error' : undefined}
            onChange={(event) => {
              setText(event.target.value);
              setError(undefined);
            }}
          />
        </label>
        <label htmlFor="link-address" style={fieldStyle}>
          <span>{i18nText('app.article.mylink.a11c65d9')}</span>
          <Input
            id="link-address"
            value={url}
            status={error && text.trim() ? 'error' : undefined}
            placeholder="https://example.com"
            onChange={(event) => {
              setUrl(event.target.value);
              setError(undefined);
            }}
            onPressEnter={submit}
          />
        </label>
        {error && <Typography.Text type="danger">{error}</Typography.Text>}
      </div>
    </Modal>
  );
}

export function showLinkEditDialog(options: LinkEditDialogOptions) {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);

  root.render(
    <LinkEditDialog
      {...options}
      onClosed={() => {
        root.unmount();
        container.remove();
      }}
    />,
  );
}
