import {CheckOutlined, CopyOutlined, EditOutlined, ExportOutlined,} from '@ant-design/icons';
import {Plugin, PluginKey} from '@tiptap/pm/state';
import type {EditorView} from '@tiptap/pm/view';
import {useEffect, useRef, useState} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import ClipboardUtil from '@/utils/ClipboardUtil';
import {i18nText} from '@/utils/i18n';

interface PreviewPosition {
  left: number;
  top: number;
}

const ANT_THEME_VARIABLES = [
  '--ant-color-bg-container',
  '--ant-color-border',
  '--ant-color-text-secondary',
] as const;

function LinkHoverCard({
                         href,
                         position,
                         onEdit,
                         onMouseEnter,
                         onMouseLeave,
                       }: {
  href: string;
  position: PreviewPosition;
  onEdit: () => void;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
}) {
  const iconButtonClass =
    'flex size-4 shrink-0 items-center justify-center rounded transition-colors hover:bg-black/6 dark:hover:bg-white/10';
  const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>(
    'idle',
  );
  const resetTimer = useRef<number | undefined>(undefined);

  useEffect(
    () => () => {
      if (resetTimer.current !== undefined) {
        window.clearTimeout(resetTimer.current);
      }
    },
    [],
  );

  const copyLink = async () => {
    const copied = await ClipboardUtil.writeText(href);
    setCopyStatus(copied ? 'copied' : 'failed');
    if (resetTimer.current !== undefined) {
      window.clearTimeout(resetTimer.current);
    }
    resetTimer.current = window.setTimeout(() => setCopyStatus('idle'), 1600);
  };

  const copyTitle =
    copyStatus === 'copied'
      ? i18nText('app.article.mylink.3965cf81')
      : copyStatus === 'failed'
        ? i18nText('app.article.mylink.88d491ea')
        : i18nText('app.article.mylink.4ba16ad7');

  return (
    <div
      className="fixed z-1100 flex max-w-200 items-center gap-2 rounded-md border px-2 py-1.5 text-sm shadow-lg"
      style={{
        left: position.left,
        top: position.top,
        backgroundColor: 'var(--ant-color-bg-container)',
        borderColor: 'var(--ant-color-border)',
        color: 'var(--ant-color-text-secondary)',
      }}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      onMouseDown={(event) => event.preventDefault()}
    >
      <span className="min-w-0 flex-1 truncate" dir="ltr" title={href}>
        {href}
      </span>
      <button
        className={iconButtonClass}
        type="button"
        title={copyTitle}
        aria-label={copyTitle}
        onClick={copyLink}
      >
        {copyStatus === 'copied' ? <CheckOutlined/> : <CopyOutlined/>}
      </button>
      <button
        className={iconButtonClass}
        type="button"
        title={i18nText('app.article.mylink.5af302ba')}
        aria-label={i18nText('app.article.mylink.5af302ba')}
        onClick={onEdit}
      >
        <EditOutlined/>
      </button>
      <button
        className={iconButtonClass}
        type="button"
        title={i18nText('app.article.mylink.dd733b5e')}
        aria-label={i18nText('app.article.mylink.dd733b5e')}
        onClick={() => window.open(href, '_blank', 'noopener,noreferrer')}
      >
        <ExportOutlined/>
      </button>
    </div>
  );
}

class LinkHoverView {
  private anchor?: HTMLAnchorElement;

  private container: HTMLDivElement;

  private destroyed = false;

  private hideTimer?: number;

  private root: Root;

  constructor(
    private readonly view: EditorView,
    private readonly onEdit: (from: number, to: number) => void,
  ) {
    this.container = document.createElement('div');
    document.body.appendChild(this.container);
    this.root = createRoot(this.container);

    view.dom.addEventListener('mouseover', this.handleMouseOver);
    view.dom.addEventListener('mouseout', this.handleMouseOut);
    window.addEventListener('resize', this.updatePosition);
    window.addEventListener('scroll', this.updatePosition, true);
  }

  private clearHideTimer = () => {
    if (this.hideTimer !== undefined) {
      window.clearTimeout(this.hideTimer);
      this.hideTimer = undefined;
    }
  };

  /**
   * 先计算下方是否有足够空间；空间不足时，将悬浮框翻转到链接上方
   * @param anchor
   * @private
   */
  private getPosition(anchor: HTMLAnchorElement): PreviewPosition {
    const rect = anchor.getBoundingClientRect();

    const viewportPadding = 8;
    const gap = 8;
    const estimatedHeight = 44;

    const cardWidth = Math.min(384, window.innerWidth - viewportPadding * 2);
    const left = Math.max(viewportPadding, Math.min(rect.left + 12, window.innerWidth - cardWidth - viewportPadding));
    const belowTop = rect.bottom + gap;
    const aboveTop = rect.top - estimatedHeight - gap;
    const hasEnoughSpaceBelow = belowTop + estimatedHeight <= window.innerHeight - viewportPadding;
    const top = hasEnoughSpaceBelow ? belowTop : Math.max(viewportPadding, aboveTop);

    return {left, top};
  }

  private syncThemeVariables(source: Element) {
    const sourceStyles = window.getComputedStyle(source);

    for (const variableName of ANT_THEME_VARIABLES) {
      const value = sourceStyles.getPropertyValue(variableName).trim();
      if (value) {
        this.container.style.setProperty(variableName, value);
      } else {
        this.container.style.removeProperty(variableName);
      }
    }
  }

  private render = () => {
    if (this.destroyed) {
      return;
    }

    if (!this.anchor?.isConnected) {
      this.hide();
      return;
    }

    const href = this.anchor.getAttribute('href');
    if (!href) {
      this.hide();
      return;
    }

    this.syncThemeVariables(this.anchor);
    this.root.render(
      <LinkHoverCard
        key={href}
        href={href}
        position={this.getPosition(this.anchor)}
        onEdit={this.editLink}
        onMouseEnter={this.clearHideTimer}
        onMouseLeave={this.scheduleHide}
      />,
    );
  };

  private hide = () => {
    this.clearHideTimer();
    this.anchor = undefined;

    if (this.destroyed) {
      return;
    }

    this.root.render(null);
  };

  private scheduleHide = () => {
    this.clearHideTimer();
    this.hideTimer = window.setTimeout(this.hide, 140);
  };

  private editLink = () => {
    if (!this.anchor) return;
    const from = this.view.posAtDOM(this.anchor, 0);
    const to = this.view.posAtDOM(this.anchor, this.anchor.childNodes.length);
    this.hide();
    this.onEdit(from, to);
  };

  private handleMouseOver = (event: Event) => {
    const target = event.target;
    if (!(target instanceof Element)) return;

    const anchor = target.closest<HTMLAnchorElement>('a[href]');
    if (!anchor || !this.view.dom.contains(anchor)) return;

    this.clearHideTimer();
    this.anchor = anchor;
    this.render();
  };

  private handleMouseOut = (event: MouseEvent) => {
    if (!this.anchor) return;
    const relatedTarget = event.relatedTarget;
    if (relatedTarget instanceof Node && this.anchor.contains(relatedTarget)) {
      return;
    }
    this.scheduleHide();
  };

  private updatePosition = () => {
    if (this.anchor) this.render();
  };

  destroy() {
    if (this.destroyed) {
      return;
    }

    this.destroyed = true;
    this.clearHideTimer();
    this.anchor = undefined;
    this.view.dom.removeEventListener('mouseover', this.handleMouseOver);
    this.view.dom.removeEventListener('mouseout', this.handleMouseOut);
    window.removeEventListener('resize', this.updatePosition);
    window.removeEventListener('scroll', this.updatePosition, true);

    // ProseMirror may destroy plugin views while a React component (for
    // example BubbleMenu) is rendering. React 19 warns if another root is
    // synchronously unmounted during that render, so finish the independent
    // root's cleanup in the next task instead.
    window.setTimeout(() => {
      this.root.unmount();
      this.container.remove();
    }, 0);
  }
}

export function createLinkHoverPlugin(
  onEdit: (from: number, to: number) => void,
) {
  return new Plugin({
    key: new PluginKey('linkHoverPreview'),
    view: (view) => new LinkHoverView(view, onEdit),
  });
}
