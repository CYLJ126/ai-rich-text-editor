import {isValidAudioUrl} from '@tiptap/extension-audio';
import {Button, Checkbox, Input, Modal, Select, Typography} from 'antd';
import {useEffect, useState} from 'react';

/**
 * preload：页面加载时提前下载多少内容
 *  - none：不预加载，用户点击播放后再请求音频。适合文章中音频较多、希望节省流量。
 *  - metadata：只预加载时长、编码等元数据，通常是最佳默认值。
 *  - auto：允许浏览器预加载整个音频，播放更快，但可能消耗较多流量。
 *
 * controlslist：隐藏原生播放器中的某些按钮，只有在 controls 开启时才有效，可以传多个空格分隔的值：
 *  - nodownload：隐藏下载按钮。
 *  - noremoteplayback：隐藏投屏、AirPlay 等远程播放入口。
 *  - nofullscreen：隐藏全屏入口，对音频通常意义不大。
 *  - noplaybackrate：部分浏览器支持，用于隐藏倍速菜单，但并非标准兼容值。
 *
 * crossorigin：跨域请求音频时是否启用 CORS，例如文章网站是 https://a.com，音频在 https://cdn.b.com/audio.mp3，这就是跨域。
 * - 不设置：普通跨域播放通常可以正常工作，但不能安全地交给 Web Audio API 进行波形分析、频谱分析等操作。
 * - anonymous：发起 CORS 请求，但不携带 Cookie、HTTP 身份认证等凭证。音频服务器必须返回合适的 Access-Control-Allow-Origin。
 * - use-credentials：携带 Cookie 或认证信息。服务器还必须允许凭证请求，通常用于需要登录授权的私有音频。
 */
export interface AudioAttributes {
  src: string;
  controls: boolean;
  autoplay: boolean;
  loop: boolean;
  muted: boolean;
  preload: 'auto' | 'metadata' | 'none' | null; // 预加载音频
  controlslist?: string; // 隐藏原生播放器中的某些按钮
  crossorigin?: '' | 'anonymous' | 'use-credentials'; // 跨域请求音频时是否启用 CORS
  disableremoteplayback: boolean; // 是否禁用远程播放
}

const DEFAULT_ATTRIBUTES: AudioAttributes = {
  src: '',
  controls: true,
  autoplay: false,
  loop: true,
  muted: false,
  preload: 'metadata',
  controlslist: 'nodownload',
  crossorigin: '',
  disableremoteplayback: false,
};

export function AudioInputDialog({
  value,
  isOpen,
  onOpenChange,
  onSubmit,
}: {
  value?: Partial<AudioAttributes>;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: (attributes: AudioAttributes) => void;
}) {
  const [attributes, setAttributes] = useState<AudioAttributes>({
    ...DEFAULT_ATTRIBUTES,
    ...value,
    controls: true,
  });
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!isOpen) return;

    setAttributes({ ...DEFAULT_ATTRIBUTES, ...value, controls: true });
    setError(undefined);
  }, [isOpen, value]);

  const updateAttribute = <Key extends keyof AudioAttributes>(
    key: Key,
    nextValue: AudioAttributes[Key],
  ) => {
    setAttributes((current) => ({ ...current, [key]: nextValue }));
    setError(undefined);
  };

  const handleSubmit = () => {
    const src = attributes.src.trim();
    if (!src) {
      setError('请输入音频地址');
      return;
    }
    if (!isValidAudioUrl(src)) {
      setError('请输入有效的音频地址');
      return;
    }

    onSubmit({
      ...attributes,
      src,
      controls: true,
      controlslist: attributes.controlslist?.trim() || undefined,
      crossorigin: attributes.crossorigin || undefined,
    });
  };

  const fieldStyle = {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 6,
  };

  return (
    <Modal
      open={isOpen}
      title={value ? '修改音频' : '插入音频'}
      width={640}
      mask={{ closable: false }}
      onCancel={() => onOpenChange(false)}
      destroyOnHidden
      footer={
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button onClick={() => onOpenChange(false)}>取消</Button>
          <Button type="primary" onClick={handleSubmit}>
            {value ? '更新' : '插入'}
          </Button>
        </div>
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <label htmlFor="audio-src" style={fieldStyle}>
          <span>音频地址</span>
          <Input
            id="audio-src"
            value={attributes.src}
            status={error ? 'error' : undefined}
            placeholder="https://.../audio.mp3"
            autoFocus
            onChange={(event) => updateAttribute('src', event.target.value)}
            onPressEnter={handleSubmit}
          />
          {error && <Typography.Text type="danger">{error}</Typography.Text>}
        </label>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
            gap: 16,
          }}
        >
          <label htmlFor="audio-preload" style={fieldStyle}>
            <span>preload</span>
            <Select
              id="audio-preload"
              value={attributes.preload ?? ''}
              options={[
                { value: 'metadata', label: 'metadata - 预加载元数据' },
                { value: 'auto', label: 'auto - 自动预加载音频' },
                { value: 'none', label: 'none - 不预加载' },
              ]}
              onChange={(nextValue) =>
                updateAttribute(
                  'preload',
                  (nextValue || null) as AudioAttributes['preload'],
                )
              }
            />
          </label>

          <label htmlFor="audio-crossorigin" style={fieldStyle}>
            <span>crossorigin</span>
            <Select
              id="audio-crossorigin"
              value={attributes.crossorigin ?? ''}
              options={[
                { value: '', label: '(unset) - 不启用 CORS' },
                {
                  value: 'anonymous',
                  label: 'anonymous - 不携带跨域凭证',
                },
                {
                  value: 'use-credentials',
                  label: 'use-credentials - 携带跨域凭证',
                },
              ]}
              onChange={(nextValue) =>
                updateAttribute(
                  'crossorigin',
                  nextValue as AudioAttributes['crossorigin'],
                )
              }
            />
          </label>
        </div>

        <label htmlFor="audio-controls-list" style={fieldStyle}>
          <span>controlslist</span>
          <Select
            id="audio-controls-list"
            value={attributes.controlslist ?? ''}
            options={[
              { value: '', label: '(unset) - 不限制原生控制项' },
              { value: 'nodownload', label: 'nodownload - 隐藏下载按钮' },
              {
                value: 'noremoteplayback',
                label: 'noremoteplayback - 隐藏远程播放入口',
              },
              {
                value: 'nofullscreen',
                label: 'nofullscreen - 隐藏全屏入口',
              },
              {
                value: 'noplaybackrate',
                label: 'noplaybackrate - 隐藏倍速菜单（部分浏览器）',
              },
              {
                value: 'nodownload noremoteplayback',
                label: 'nodownload noremoteplayback - 隐藏下载和远程播放',
              },
            ]}
            onChange={(nextValue) =>
              updateAttribute('controlslist', nextValue || undefined)
            }
          />
        </label>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 20px' }}>
          <Checkbox
            checked={attributes.autoplay}
            onChange={(event) =>
              updateAttribute('autoplay', event.target.checked)
            }
          >
            自动播放
          </Checkbox>
          <Checkbox
            checked={attributes.loop}
            onChange={(event) => updateAttribute('loop', event.target.checked)}
          >
            循环播放
          </Checkbox>
          <Checkbox
            checked={attributes.muted}
            onChange={(event) => updateAttribute('muted', event.target.checked)}
          >
            静音
          </Checkbox>
          <Checkbox
            checked={attributes.disableremoteplayback}
            onChange={(event) =>
              updateAttribute('disableremoteplayback', event.target.checked)
            }
          >
            禁用远程播放
          </Checkbox>
        </div>
      </div>
    </Modal>
  );
}
