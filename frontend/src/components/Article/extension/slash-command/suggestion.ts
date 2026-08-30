import {type Editor, posToDOMRect, ReactRenderer} from '@tiptap/react';
import type {SuggestionOptions} from '@tiptap/suggestion';
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
import type {SlashCommandNodeAttrs} from './slash-command';
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
    title: 'AI Writer',
    description: 'Ask AI with custom prompt.',
    keywords: ['ai'],
    icon: SparklesIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).setAiWriter().run();
    },
  },
  {
    id: 'text',
    title: 'Text',
    description: 'Just start typing with plain text.',
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
    title: 'Heading 1',
    description: 'Big section heading.',
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
    title: 'Heading 2',
    description: 'Medium section heading.',
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
    title: 'Heading 3',
    description: 'Small section heading.',
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
    title: 'Bullet List',
    description: 'Create a simple bullet list.',
    keywords: ['unordered', 'list', 'bullet'],
    icon: ListIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleBulletList().run();
    },
  },
  {
    id: 'ol',
    title: 'Numbered List',
    description: 'Create a list with numbering.',
    keywords: ['ordered', 'list'],
    icon: ListOrderedIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleOrderedList().run();
    },
  },
  {
    id: 'task-list',
    title: 'Task List',
    description: 'Create a task list',
    keywords: ['task', 'todo', 'check', 'taskList'],
    icon: ListTodoIcon,
    command: ({ editor, range }) => {
      editor.chain().focus().deleteRange(range).toggleTaskList().run();
    },
  },
  {
    id: 'divider',
    title: 'Divider',
    description: 'Create a horizontal divider.',
    keywords: ['divider'],
    icon: SeparatorHorizontal,
    command: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).setHorizontalRule().run(),
  },
  {
    id: 'table',
    title: 'Table',
    description: 'Capture a table.',
    keywords: ['table'],
    icon: TableIcon,
    command: ({ editor, range }) =>
      editor.chain().focus().deleteRange(range).insertTable().run(),
  },
  {
    id: 'blockquote',
    title: 'Quote',
    description: 'Capture a quote.',
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
    title: 'Code',
    description: 'Capture a code snippet.',
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
    title: 'Mermaid',
    description: 'Render a mermaid diagram.',
    keywords: ['mermaid', 'diagram'],
    icon: ShapesIcon,
    command: () => {},
  },
  {
    id: 'drawio',
    title: 'Draw.io',
    description: 'Create flowcharts, architecture diagrams, or UML diagrams.',
    keywords: ['drawio', 'draw.io', 'diagram', 'flowchart', 'flowcharts', 'architecture', 'uml'],
    icon: NetworkIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({ canvasType: 'drawio', title: 'Draw.io' })
        .run();
    },
  },
  {
    id: 'mindmap',
    title: 'Mind Map',
    description: 'Create mind maps via Mind Elixir.',
    keywords: ['mindmap', 'mind map', 'xmind', 'mind maps'],
    icon: BrainCircuitIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({ canvasType: 'mindmap', title: 'Mind Map' })
        .run();
    },
  },
  {
    id: 'whiteboard',
    title: 'Freehand Canvas',
    description: 'Insert expandable freehand canvas.',
    keywords: ['whiteboard', 'canvas', 'drawing'],
    icon: PencilRulerIcon,
    command: ({ editor, range }) => {
      editor
        .chain()
        .focus()
        .deleteRange(range)
        .insertCanvasBlock({ canvasType: 'whiteboard', title: 'Freehand Canvas' })
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
    title: 'Image',
    description: 'Insert an image from a link.',
    keywords: ['image', 'picture', 'photo'],
    icon: ImageIcon,
    command: () => {},
  },
  {
    id: 'audio',
    title: 'Audio',
    description: 'Insert a native audio player.',
    keywords: ['audio', 'music', 'sound'],
    icon: FileAudioIcon,
    command: () => {},
  },
  {
    id: 'video',
    title: 'Video',
    description: 'Insert a video or platform player.',
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
