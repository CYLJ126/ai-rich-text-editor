import type {
  SuggestionKeyDownProps,
  SuggestionProps,
} from '@tiptap/suggestion';
import type { LucideIcon } from 'lucide-react';
import { i18nText } from '@/utils/i18n';
import React, {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { AudioInputDialog } from '../audio';
import { ImageInputDialog } from '../image';
import { MermaidInputDialog } from '../mermaid';
import { VideoInputDialog } from '../video';
import type { SlashCommandNodeAttrs, SuggestionItem } from './slash-command';

export interface CommandSuggestionItem extends SuggestionItem {
  icon: LucideIcon;
}

export type SuggestionListProps = SuggestionProps<
  CommandSuggestionItem,
  SlashCommandNodeAttrs
>;

export interface SuggestionListHandle {
  onKeyDown: (props: SuggestionKeyDownProps) => boolean;
}

// 高亮样式常量，使用内联样式避免 Tailwind 作用域问题
const SELECTED_STYLE = {
  backgroundColor: '#f1f5f9', // slate-100，作为高亮色
  borderRadius: '6px',
};

const DEFAULT_STYLE = {
  backgroundColor: 'transparent',
  borderRadius: '6px',
};

/**
 * 斜杠命令列表项组件
 */
export const SuggestionList = forwardRef<
  SuggestionListHandle,
  SuggestionListProps
>(function SuggestionList(props, ref) {
  const [openMermaidInputDialog, setOpenMermaidInputDialog] = useState(false);
  const [openAudioInputDialog, setOpenAudioInputDialog] = useState(false);
  const [openImageInputDialog, setOpenImageInputDialog] = useState(false);
  const [openVideoInputDialog, setOpenVideoInputDialog] = useState(false);

  // 全部用 ref，不用 useState 驱动高亮
  const selectedIndexRef = useRef(0);
  const itemsRef = useRef(props.items);
  const commandRef = useRef(props.command);
  // 存储每个列表项的 DOM 引用
  const itemRefs = useRef<(HTMLDivElement | null)[]>([]);

  // 标记当前是否处于键盘导航状态
  const isKeyboardNavigatingRef = useRef(false);
  // 用于取消 debounce 的 timer
  const keyboardNavTimerRef = useRef<ReturnType<typeof setTimeout> | null>(
    null,
  );

  // 同步最新值到 ref
  useEffect(() => {
    itemsRef.current = props.items;
  }, [props.items]);

  useEffect(() => {
    commandRef.current = props.command;
  }, [props.command]);

  // items 变化时重置 index 并刷新高亮
  useEffect(() => {
    selectedIndexRef.current = 0;
    applyHighlight(0);
  }, [props.items]);

  // 直接操作 DOM 更新高亮
  const applyHighlight = (nextIndex: number, withScroll = true) => {
    itemRefs.current.forEach((el, i) => {
      if (!el) return;
      if (i === nextIndex) {
        Object.assign(el.style, SELECTED_STYLE);
        // 仅在需要时（键盘导航）才滚动
        if (withScroll) {
          el.scrollIntoView({ block: 'nearest' });
        }
      } else {
        Object.assign(el.style, DEFAULT_STYLE);
      }
    });
  };

  // 键盘导航时调用：设置 flag，滚动结束后自动清除
  const applyHighlightByKeyboard = (nextIndex: number) => {
    // 标记键盘导航激活
    isKeyboardNavigatingRef.current = true;

    applyHighlight(nextIndex, true);

    // 清除上一个 timer
    if (keyboardNavTimerRef.current !== null) {
      clearTimeout(keyboardNavTimerRef.current);
    }
    // scrollIntoView 是同步的，但浏览器派发 mousemove 是异步微任务后
    // 用 setTimeout(0) 确保在 mousemove 事件处理完后再解除锁定
    keyboardNavTimerRef.current = setTimeout(() => {
      isKeyboardNavigatingRef.current = false;
      keyboardNavTimerRef.current = null;
    }, 100); // 100ms 足够覆盖滚动结束后的 mousemove
  };

  const selectItem = (index: number) => {
    const item = itemsRef.current[index];
    if (!item) return;

    if (item.id === 'mermaid') {
      setOpenMermaidInputDialog(true);
      return;
    }
    if (item.id === 'audio') {
      setOpenAudioInputDialog(true);
      return;
    }
    if (item.id === 'image') {
      setOpenImageInputDialog(true);
      return;
    }
    if (item.id === 'video') {
      setOpenVideoInputDialog(true);
      return;
    }

    commandRef.current(item);
  };

  useImperativeHandle(
    ref,
    () => ({
      onKeyDown: ({ event }) => {
        if (event.key === 'ArrowUp') {
          const next =
            (selectedIndexRef.current + itemsRef.current.length - 1) %
            itemsRef.current.length;
          selectedIndexRef.current = next;
          // 使用键盘专用方法
          applyHighlightByKeyboard(next);
          return true;
        }

        if (event.key === 'ArrowDown') {
          const next = (selectedIndexRef.current + 1) % itemsRef.current.length;
          selectedIndexRef.current = next;
          // 使用键盘专用方法
          applyHighlightByKeyboard(next);
          return true;
        }

        if (event.key === 'Enter') {
          selectItem(selectedIndexRef.current);
          return true;
        }

        return false;
      },
    }),
    [],
  );

  return (
    <>
      <div
        style={{
          zIndex: 50,
          display: 'flex',
          flexDirection: 'column',
          gap: '2px',
          borderRadius: '6px',
          padding: '4px',
          maxHeight: '320px',
          width: '288px',
          overflowY: 'auto',
          border: '1px solid #cbd5e1',
          backgroundColor: '#ffffff',
          boxShadow: '0 4px 6px -1px rgb(0 0 0 / 0.1)',
          scrollbarWidth: 'thin',
        }}
      >
        {props.items.length > 0 ? (
          props.items.map((item, i) => (
            <div
              key={item.id}
              ref={(el) => {
                itemRefs.current[i] = el;
                // 初始化时立即设置第一项高亮
                if (el) {
                  Object.assign(
                    el.style,
                    i === 0 ? SELECTED_STYLE : DEFAULT_STYLE,
                  );
                }
              }}
              style={{
                display: 'flex',
                gap: '8px',
                padding: '4px',
                cursor: 'pointer',
                color: '#1e293b',
                transition: 'background-color 0.15s',
              }}
              onMouseMove={() => {
                // 键盘导航期间忽略鼠标事件
                if (isKeyboardNavigatingRef.current) return;
                if (selectedIndexRef.current === i) return;

                selectedIndexRef.current = i;
                // 鼠标 hover 不需要 scrollIntoView
                applyHighlight(i, false);
              }}
              onMouseLeave={() => {
                // 键盘导航期间忽略鼠标事件
                if (isKeyboardNavigatingRef.current) return;
                applyHighlight(selectedIndexRef.current, false);
              }}
              onClick={() => {
                if (item.id === 'mermaid') {
                  setOpenMermaidInputDialog(true);
                  return;
                }
                if (item.id === 'audio') {
                  setOpenAudioInputDialog(true);
                  return;
                }
                if (item.id === 'image') {
                  setOpenImageInputDialog(true);
                  return;
                }
                if (item.id === 'video') {
                  setOpenVideoInputDialog(true);
                  return;
                }
                props.command(item);
              }}
            >
              <div
                style={{
                  width: '40px',
                  height: '40px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  border: '1px solid #cbd5e1',
                  backgroundColor: '#f8fafc',
                  borderRadius: '5px',
                  flexShrink: 0,
                }}
              >
                <item.icon style={{ width: '20px', height: '20px' }} />
              </div>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '2px',
                }}
              >
                <p
                  style={{
                    fontWeight: 500,
                    fontSize: '14px',
                    margin: 0,
                    lineHeight: '1.4',
                  }}
                >
                  {item.title}
                </p>
                <span
                  style={{
                    fontSize: '12px',
                    color: '#64748b',
                    lineHeight: '1.4',
                  }}
                >
                  {item.description}
                </span>
              </div>
            </div>
          ))
        ) : (
          <div
            style={{
              padding: '8px 12px',
              fontSize: '14px',
              color: '#64748b',
            }}
          >
            {i18nText('app.common.noResults')}
          </div>
        )}
      </div>
      <MermaidInputDialog
        isOpen={openMermaidInputDialog}
        onOpenChange={setOpenMermaidInputDialog}
        onInsert={(code) => {
          props.command({
            command: ({ editor, range }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .setMermaid({ code })
                .run();
            },
          });
          setOpenMermaidInputDialog(false);
        }}
      />
      <AudioInputDialog
        isOpen={openAudioInputDialog}
        onOpenChange={setOpenAudioInputDialog}
        onSubmit={(attributes) => {
          props.command({
            command: ({ editor, range }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .setAudio(attributes)
                .run();
            },
          });
          setOpenAudioInputDialog(false);
        }}
      />
      <ImageInputDialog
        isOpen={openImageInputDialog}
        onOpenChange={setOpenImageInputDialog}
        onSubmit={(attributes) => {
          props.command({
            command: ({ editor, range }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .setImage({
                  src: attributes.src,
                  alt: attributes.alt ?? undefined,
                  title: attributes.title ?? undefined,
                  width: attributes.width ?? undefined,
                  height: attributes.height ?? undefined,
                })
                .run();
            },
          });
          setOpenImageInputDialog(false);
        }}
      />
      <VideoInputDialog
        isOpen={openVideoInputDialog}
        onOpenChange={setOpenVideoInputDialog}
        onSubmit={(attributes) => {
          props.command({
            command: ({ editor, range }) => {
              editor
                .chain()
                .focus()
                .deleteRange(range)
                .setVideo(attributes)
                .run();
            },
          });
          setOpenVideoInputDialog(false);
        }}
      />
    </>
  );
});

export default SuggestionList;
