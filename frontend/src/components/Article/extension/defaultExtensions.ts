import {mergeAttributes} from '@tiptap/core';
import {Details, DetailsContent, DetailsSummary,} from '@tiptap/extension-details';
import FileHandler from '@tiptap/extension-file-handler';
import Heading from '@tiptap/extension-heading';
import {Highlight} from '@tiptap/extension-highlight';
import {TaskItem, TaskList} from '@tiptap/extension-list';
import {TableCell, TableHeader, TableRow} from '@tiptap/extension-table';
import TextAlign from '@tiptap/extension-text-align';
import {CharacterCount} from '@tiptap/extensions';
import {Markdown} from '@tiptap/markdown';
import StarterKit from '@tiptap/starter-kit';
import {common, createLowlight} from 'lowlight';
import {MAX_CHARACTER_COUNT} from '@/components/Article/components/article/CharacterCount';
import {CustomCodeBlock} from '@/components/Article/extension/CodeBlock';
import {ContentHelperExtension} from '@/components/Article/extension/ContentHelper';
import {CanvasBlock} from '@/components/Article/extension/canvas';
import {InsecureImagePasteHandler} from '@/components/Article/extension/InsecureImagePasteHandler';
import {TiptapImage} from '@/components/Article/extension/image';
import {configureMathFormula} from '@/components/Article/extension/MathFormula';
import {MyTextStyle} from '@/components/Article/extension/MyTextStyle';
import {Mermaid} from '@/components/Article/extension/mermaid';
import NestedReactContentNode from '@/components/Article/extension/NestedReactContentNode';
import {PasteCodeBlockHandler} from '@/components/Article/extension/PasteCodeBlockHandler';
import {PasteStyleHandler} from '@/components/Article/extension/PasteStyleHandler';
import {Selection} from '@/components/Article/extension/selection';
import {TiptapSubscript} from '@/components/Article/extension/subscript';
import {TiptapSuperscript} from '@/components/Article/extension/superscript';
import {CustomTable} from '@/components/Article/extension/table';
import {cn} from '@/lib/utils';
import {uploadFile, uploadImage} from '@/services/upload';
import {SearchHighlight} from "@/components/Article/extension/SearchHighlight";

export const TiptapStarterKit = StarterKit.configure({
  bulletList: {
    HTMLAttributes: {
      class: cn('list-disc list-outside leading-3 -mt-2'),
      'data-type': 'bulletList',
    },
  },
  orderedList: {
    HTMLAttributes: {
      class: cn('list-decimal list-outside leading-3 -mt-2'),
      'data-type': 'orderedList',
    },
  },
  listItem: {
    HTMLAttributes: {
      class: cn('leading-normal -mb-2'),
    },
  },
  blockquote: {
    HTMLAttributes: {
      class: cn('text-slate-400 font-normal not-italic'),
    },
  },
  codeBlock: false,
  code: {
    HTMLAttributes: {
      class: cn(
        'rounded-sm bg-stone-100 text-sky-700 px-1 py-0.5 font-mono font-medium before:content-none after:content-none',
      ),
      spellcheck: 'false',
    },
  },
  horizontalRule: {
    HTMLAttributes: {
      class: cn('my-4 bg-border border-border'),
    },
  },
  dropcursor: {
    color: '#DBEAFE',
    width: 4,
  },
  heading: false,
  link: {
    defaultProtocol: 'https',
    protocols: ['http', 'https'],
    // 避免 linkify 将“安装.net”等中文文本误判为裸域名。
    shouldAutoLink: (url: string) => /^https?:\/\/\S+$/i.test(url),
    HTMLAttributes: {
      class: cn(
        'my-link',
        'text-foreground! underline underline-offset-[3px] transition-colors cursor-pointer',
      ),
    },
  },
});

export const TiptapHeading = Heading.extend({
  renderHTML({ node, HTMLAttributes }) {
    const hasLevel = this.options.levels.includes(node.attrs.level);
    const level = hasLevel ? node.attrs.level : this.options.levels[0];

    if (node.textContent) {
      return [
        `h${level}`,
        mergeAttributes(this.options.HTMLAttributes, HTMLAttributes, {
          id: node.textContent.replaceAll(/\s+/g, '-').toLowerCase(),
        }),
        0,
      ];
    }
    return [
      `h${level}`,
      mergeAttributes(this.options.HTMLAttributes, HTMLAttributes),
      0,
    ];
  },
});

export const configTaskList = () => [
  // TODO 嵌套列表是时，从 markdown 转换 tiptap 时，不会渲染子节点
  TaskItem.configure({
    nested: true,
    HTMLAttributes: {
      'data-type': 'taskItem',
    },
  }),
  TaskList.configure({
    itemTypeName: 'taskItem',
    HTMLAttributes: {
      'data-type': 'taskList',
    },
  }),
];

export const lowlight = createLowlight(common);
export const codeBlock = CustomCodeBlock.configure({
  HTMLAttributes: {
    class: cn(
      'rounded bg-gray-800! dark:bg-gray-900! text-gray-200 border p-5 font-mono font-medium',
    ),
    spellcheck: false,
  },
  enableTabIndentation: true,
  tabSize: 2,
  defaultLanguage: 'plaintext',
  lowlight: lowlight,
});

export const TiptapTextAlign = TextAlign.configure({
  types: ['heading', 'paragraph', 'math'],
});

export const TiptapTable = CustomTable.configure({
  HTMLAttributes: {
    class: cn('table-auto border-collapse w-full not-prose'),
  },
  lastColumnResizable: false,
  allowTableNodeSelection: true,
  resizable: true,
});

export const TiptapTableHeader = TableHeader.configure({
  HTMLAttributes: {
    class: cn(
      'bg-muted dark:bg-gray-900 border border-default p-2 text-start min-w-37.5 font-semibold',
    ),
  },
});

export const TiptapTableCell = TableCell.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      verticalAlign: {
        default: 'top',
        parseHTML: (element) => {
          return element.style.verticalAlign || 'top';
        },
        renderHTML: (attributes) => {
          return { style: `vertical-align: ${attributes.verticalAlign}` };
        },
      },
    };
  },
}).configure({
  HTMLAttributes: {
    class: cn('border border-default p-2 min-w-37.5'),
  },
});

export const TipTapMarkdown = Markdown.configure({
  indentation: {
    style: 'tab', // 'space' or 'tab'
    size: 1, // Number of spaces or tabs
  },
  markedOptions: {
    gfm: true, // GitHub Flavored Markdown
    breaks: false, // Convert \n to <br>
    pedantic: false, // Strict Markdown mode
  },
});

export const TiptapCharacterCount = CharacterCount.configure({
  limit: MAX_CHARACTER_COUNT,
});

export const mermaid = Mermaid.configure({
  HTMLAttributes: {
    class: cn('mermaid'),
  },
});

export const selection = Selection.configure({
  HTMLAttributes: {
    class: 'selection',
  },
});

// const chart = Chart.configure({
//   HTMLAttributes: {
//     class: cn("border p-2 my-4 w-full flex items-center justify-center aspect-video"),
//   },
// })

const MP3_MIME_TYPES = ['audio/mpeg', 'audio/mp3', 'audio/x-mpeg'];
const VIDEO_MIME_TYPES = [
  'video/mp4',
  'application/mp4',
  'video/webm',
  'video/quicktime',
  'video/m4v',
  'video/x-m4v',
];
const VIDEO_FILE_PATTERN = /\.(?:mp4|m4v|mov|webm)$/i;

const isMp3File = (file: File) =>
  MP3_MIME_TYPES.includes(file.type) ||
  file.name.toLowerCase().endsWith('.mp3');

const isVideoFile = (file: File) =>
  VIDEO_MIME_TYPES.includes(file.type) || VIDEO_FILE_PATTERN.test(file.name);

export const fileHandlerExtension = FileHandler.configure({
  allowedMimeTypes: [
    'image/png',
    'image/jpeg',
    'image/gif',
    'image/webp',
    'image/svg+xml',
    ...MP3_MIME_TYPES,
    ...VIDEO_MIME_TYPES,
    'application/pdf',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'application/vnd.ms-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/vnd.ms-powerpoint',
    'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    'application/zip',
    'application/x-zip-compressed',
    'text/plain',
    'text/csv',
  ],
  onDrop: (currentEditor, files, pos) => {
    for (const file of files) {
      if (file.type.startsWith('image/')) {
        uploadImage(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(pos, {
              type: 'image',
              attrs: { src: url },
            })
            .focus()
            .run();
        });
      } else if (isMp3File(file)) {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(pos, {
              type: 'audio',
              attrs: {
                src: url,
                controls: true,
                preload: 'metadata',
                controlslist: 'nodownload',
              },
            })
            .focus()
            .run();
        });
      } else if (isVideoFile(file)) {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(pos, {
              type: 'video',
              attrs: { src: url },
            })
            .focus()
            .run();
        });
      } else {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(pos, {
              type: 'text',
              marks: [{ type: 'link', attrs: { href: url, target: '_blank' } }],
              text: file.name,
            })
            .focus()
            .run();
        });
      }
    }
  },
  onPaste: (currentEditor, files, htmlContent) => {
    if (htmlContent) return false;
    for (const file of files) {
      if (file.type.startsWith('image/')) {
        uploadImage(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(currentEditor.state.selection.anchor, {
              type: 'image',
              attrs: { src: url },
            })
            .focus()
            .run();
        });
      } else if (isMp3File(file)) {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(currentEditor.state.selection.anchor, {
              type: 'audio',
              attrs: {
                src: url,
                controls: true,
                preload: 'metadata',
                controlslist: 'nodownload',
              },
            })
            .focus()
            .run();
        });
      } else if (isVideoFile(file)) {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(currentEditor.state.selection.anchor, {
              type: 'video',
              attrs: { src: url },
            })
            .focus()
            .run();
        });
      } else {
        uploadFile(file).then((url) => {
          currentEditor
            .chain()
            .insertContentAt(currentEditor.state.selection.anchor, {
              type: 'text',
              marks: [{ type: 'link', attrs: { href: url, target: '_blank' } }],
              text: file.name,
            })
            .focus()
            .run();
        });
      }
    }
    return true;
  },
});

export const defaultExtensions = [
  TiptapStarterKit,
  ContentHelperExtension,
  TiptapHeading,
  Highlight.configure({
    multicolor: true, // 开启高亮多色支持
  }),
  TiptapTextAlign,
  TiptapTable,
  TiptapTableHeader,
  Details,
  DetailsSummary,
  DetailsContent,
  ...configTaskList(),
  TiptapSubscript,
  TiptapSuperscript,
  TableRow,
  TiptapTableCell,
  TiptapCharacterCount,
  TiptapImage,
  TipTapMarkdown,
  MyTextStyle,
  PasteStyleHandler,
  PasteCodeBlockHandler,
  InsecureImagePasteHandler,
  configureMathFormula({
    enableClickEdit: true,
  }),
  codeBlock,
  CanvasBlock,
  mermaid,
  NestedReactContentNode,
  selection,
  fileHandlerExtension,
  SearchHighlight,
];
