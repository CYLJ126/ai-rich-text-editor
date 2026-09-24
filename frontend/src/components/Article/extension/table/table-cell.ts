import {TableCell, TableHeader} from '@tiptap/extension-table';
import {cn} from '@/lib/utils';

const cellBackgroundColorAttribute = () => ({
  default: null,
  parseHTML: (element: HTMLElement) =>
    element.style.backgroundColor || null,
  renderHTML: (attributes: Record<string, unknown>) => {
    if (!attributes.backgroundColor) {
      return {};
    }
    return {style: `background-color: ${attributes.backgroundColor}`};
  },
});

export const TiptapTableHeader = TableHeader.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      backgroundColor: cellBackgroundColorAttribute(),
    };
  },
}).configure({
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
          return {style: `vertical-align: ${attributes.verticalAlign}`};
        },
      },
      backgroundColor: cellBackgroundColorAttribute(),
    };
  },
}).configure({
  HTMLAttributes: {
    class: cn('border border-default p-2 min-w-37.5'),
  },
});
