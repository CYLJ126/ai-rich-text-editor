import { i18nText } from '@/utils/i18n';
import { type Editor, posToDOMRect, ReactRenderer } from '@tiptap/react';
import type { SuggestionOptions } from '@tiptap/suggestion';
import {
  BrainCircuitIcon,
  CodeIcon,
  FileAudioIcon,
  Heading1Icon,
  Heading2Icon,
  Heading3Icon,
  ImageIcon,
  LetterTextIcon,
  ListIcon,
  ListOrderedIcon,
  ListTodoIcon,
  NetworkIcon,
  PencilRulerIcon,
  SeparatorHorizontal,
  ShapesIcon,
  SparklesIcon,
  TableIcon,
  TextQuoteIcon,
  VideoIcon,
} from 'lucide-react';
import type { SlashCommandNodeAttrs } from './slash-command';
import SuggestionList, {
  type CommandSuggestionItem,
  type SuggestionListHandle,
  type SuggestionListProps,
} from './suggestion-list';

type SuggestionType = Omit<
  SuggestionOptions<CommandSuggestionItem, SlashCommandNodeAttrs>,
  'editor'
>;

/**
 * 斜杠建议命令列表
 * 可在此添加自定义命令
 */
const list: CommandSuggestionItem[] = [
  {
    id: 'aiWriter',
    title: i18nText('app.article.slash.aiWriter.title'),
    description: i18nText('app.article.slash.aiWriter.description'),
    keywords: ['ai'],
    icon: SparklesIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).setAiWriter().run();
    },
  },
  {
    id: 'text',
    title: i18nText('app.article.slash.text.title'),
    description: i18nText('app.article.slash.text.description'),
    keywords: ['p', 'paragraph'],
    icon: LetterTextIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .toggleNode('paragraph', 'paragraph')
        .run();
    },
  },
  {
    id: 'h1',
    title: i18nText('app.article.slash.heading1.title'),
    description: i18nText('app.article.slash.heading1.description'),
    keywords: ['title', 'big', 'large', 'heading'],
    icon: Heading1Icon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .setNode('heading', { level: 1 })
        .run();
    },
  },
  {
    id: 'h2',
    title: i18nText('app.article.slash.heading2.title'),
    description: i18nText('app.article.slash.heading2.description'),
    keywords: ['subtitle', 'medium', 'heading'],
    icon: Heading2Icon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .setNode('heading', { level: 2 })
        .run();
    },
  },
  {
    id: 'h3',
    title: i18nText('app.article.slash.heading3.title'),
    description: i18nText('app.article.slash.heading3.description'),
    keywords: ['subtitle', 'small', 'heading'],
    icon: Heading3Icon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .setNode('heading', { level: 3 })
        .run();
    },
  },
  {
    id: 'ul',
    title: i18nText('app.article.slash.bulletList.title'),
    description: i18nText('app.article.slash.bulletList.description'),
    keywords: ['unordered', 'list', 'bullet'],
    icon: ListIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleBulletList().run();
    },
  },
  {
    id: 'ol',
    title: i18nText('app.article.slash.numberedList.title'),
    description: i18nText('app.article.slash.numberedList.description'),
    keywords: ['ordered', 'list'],
    icon: ListOrderedIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleOrderedList().run();
    },
  },
  {
    id: 'task-list',
    title: i18nText('app.article.slash.taskList.title'),
    description: i18nText('app.article.slash.taskList.description'),
    keywords: ['task', 'todo', 'check', 'taskList'],
    icon: ListTodoIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleTaskList().run();
    },
  },
  {
    id: 'divider',
    title: i18nText('app.article.slash.divider.title'),
    description: i18nText('app.article.slash.divider.description'),
    keywords: ['divider'],
    icon: SeparatorHorizontal,
    command: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
  },
  {
    id: 'table',
    title: i18nText('app.article.slash.table.title'),
    description: i18nText('app.article.slash.table.description'),
    keywords: ['table'],
    icon: TableIcon,
    command: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).insertTable().run(),
  },
  {
    id: 'blockquote',
    title: i18nText('app.article.slash.quote.title'),
    description: i18nText('app.article.slash.quote.description'),
    keywords: ['blockquote'],
    icon: TextQuoteIcon,
    command: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .toggleNode('paragraph', 'paragraph')
        .toggleBlockquote()
        .run(),
  },
  {
    id: 'codeBlock',
    title: i18nText('app.article.slash.code.title'),
    description: i18nText('app.article.slash.code.description'),
    keywords: ['codeblock'],
    icon: CodeIcon,
    command: ({ editor, range }) =>
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .toggleCodeBlock({ language: 'plaintext' })
        .run(),
  },
  {
    id: 'mermaid',
    title: i18nText('app.article.slash.mermaid.title'),
    description: i18nText('app.article.slash.mermaid.description'),
    keywords: ['mermaid', 'diagram'],
    icon: ShapesIcon,
    command: () => {},
  },
  {
    id: 'drawio',
    title: i18nText('app.article.slash.drawio.title'),
    description: i18nText('app.article.slash.drawio.description'),
    keywords: [
      'drawio',
      'draw.io',
      'diagram',
      'flowchart',
      'flowcharts',
      'architecture',
      'uml',
    ],
    icon: NetworkIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({
          canvasType: 'drawio',
          title: i18nText('app.article.slash.drawio.title'),
        })
        .run();
    },
  },
  {
    id: 'mindmap',
    title: i18nText('app.article.slash.mindMap.title'),
    description: i18nText('app.article.slash.mindMap.description'),
    keywords: ['mindmap', 'mind map', 'xmind', 'mind maps'],
    icon: BrainCircuitIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({
          canvasType: 'mindmap',
          title: i18nText('app.article.slash.mindMap.title'),
        })
        .run();
    },
  },
  {
    id: 'whiteboard',
    title: i18nText('app.article.slash.canvas.title'),
    description: i18nText('app.article.slash.canvas.description'),
    keywords: ['whiteboard', 'canvas', 'drawing'],
    icon: PencilRulerIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({
          canvasType: 'whiteboard',
          title: i18nText('app.article.slash.canvas.title'),
        })
        .run();
    },
  },
  // {
  //   id: 'chart',
  //   title: 'Chart',
  //   description: 'Render a chart.',
  //   keywords: ['chart'],
  //   icon: ChartPieIcon,
  //   command: () => {},
  // },
  {
    id: 'image',
    title: i18nText('app.article.slash.image.title'),
    description: i18nText('app.article.slash.image.description'),
    keywords: ['image', 'picture', 'photo'],
    icon: ImageIcon,
    command: () => {},
  },
  {
    id: 'audio',
    title: i18nText('app.article.slash.audio.title'),
    description: i18nText('app.article.slash.audio.description'),
    keywords: ['audio', 'music', 'sound'],
    icon: FileAudioIcon,
    command: () => {},
  },
  {
    id: 'video',
    title: i18nText('app.article.slash.video.title'),
    description: i18nText('app.article.slash.video.description'),
    keywords: ['video', 'movie', 'youtube', 'vimeo', 'bilibili'],
    icon: VideoIcon,
    command: () => {},
  },
];

/**
 * 计算建议列表显示位置
 * @param editor
 * @param element
 */
const updatePosition = (editor: Editor, element: HTMLElement) => {
  const getReferenceRect = (): DOMRect => {
    const { from, to } = editor.state.selection;
    return posToDOMRect(editor.view, from, to);
  };

  const refRect = getReferenceRect();
  const elHeight = element.offsetHeight;
  const elWidth = element.offsetWidth;
  const viewportHeight = window.innerHeight;
  const viewportWidth = window.innerWidth;
  const padding = 8;

  // 判断下方空间是否足够
  const spaceBelow = viewportHeight - refRect.bottom;
  const spaceAbove = refRect.top;

  let top: number | undefined;
  let bottom: number | undefined;
  if (spaceBelow >= elHeight + padding) {
    // 下方空间足够，显示在下方
    top = refRect.bottom;
  } else if (spaceAbove >= elHeight + padding) {
    // 下方不够，底边锚定光标并向上显示
    bottom = viewportHeight - refRect.top;
  } else {
    // 上下都不够，选空间更大的一侧，并限制在视口内
    if (spaceBelow >= spaceAbove) {
      top = Math.max(padding, viewportHeight - elHeight - padding);
    } else {
      top = padding;
    }
  }

  // 水平方向：防止超出右/左边界
  let left = refRect.left;
  if (left + elWidth > viewportWidth - padding) {
    left = viewportWidth - elWidth - padding;
  }
  if (left < padding) {
    left = padding;
  }

  element.style.position = 'fixed';
  element.style.top = top === undefined ? 'auto' : `${top}px`;
  element.style.bottom = bottom === undefined ? 'auto' : `${bottom}px`;
  element.style.left = `${left}px`;
  element.style.width = 'max-content';
};

const getSuggestions = (): SuggestionType => {
  return {
    items: ({ query }) => {
      const normalizedQuery = query.toLowerCase();
      const filterFun = (item: CommandSuggestionItem) => {
        return [item.title, ...item.keywords].some((value) =>
          value.toLowerCase().includes(normalizedQuery),
        );
      };
      return list.filter(filterFun);
    },
    render: () => {
      let component: ReactRenderer<SuggestionListHandle, SuggestionListProps>;
      let initialPositionFrame: number | undefined;

      const setInitialPosition = (editor: Editor, element: HTMLElement) => {
        if (!element.isConnected) {
          initialPositionFrame = undefined;
          return;
        }

        if (element.offsetHeight === 0) {
          initialPositionFrame = requestAnimationFrame(() => {
            setInitialPosition(editor, element);
          });
          return;
        }

        updatePosition(editor, element);
        element.style.visibility = 'visible';
        initialPositionFrame = undefined;
      };

      return {
        onStart: (props) => {
          if (initialPositionFrame !== undefined) {
            cancelAnimationFrame(initialPositionFrame);
          }
          component = new ReactRenderer(SuggestionList, {
            props,
            editor: props.editor,
          });
          if (!props.clientRect) {
            return;
          }
          if (component.element instanceof HTMLElement) {
            const element = component.element;
            // 挂载到 body，脱离文档流，不撑高编辑器容器
            element.style.position = 'fixed';
            element.style.zIndex = '50';
            element.style.visibility = 'hidden';
            element.style.top = '0';
            element.style.left = '0';
            document.body.appendChild(element);
            // 等 React 渲染完成，offsetHeight 有值后再定位
            initialPositionFrame = requestAnimationFrame(() => {
              setInitialPosition(props.editor, element);
            });
          }
        },

        onUpdate(props) {
          component.updateProps(props);
        },

        onKeyDown(props) {
          if (props.event.key === 'Escape') {
            // popup?.hide();
            //component.destroy();

            return true;
          }

          return component.ref?.onKeyDown(props) ?? false;
        },

        onExit() {
          // popup?.destroy();
          if (initialPositionFrame !== undefined) {
            cancelAnimationFrame(initialPositionFrame);
            initialPositionFrame = undefined;
          }
          component?.element.remove();
          component?.destroy();
        },
      };
    },
  };
};

export { getSuggestions };
