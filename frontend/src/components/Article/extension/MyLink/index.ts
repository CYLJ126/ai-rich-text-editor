import {type Editor, getMarkRange} from '@tiptap/core';
import Link from '@tiptap/extension-link';
import type {Mark} from '@tiptap/pm/model';
import {cn} from '@/lib/utils';
import {i18nText} from '@/utils/i18n';
import {showLinkEditDialog} from './LinkEditDialog';
import {createLinkHoverPlugin} from './LinkHoverPreview';

const MyLink = Link.extend({
  addProseMirrorPlugins() {
    return [
      ...(this.parent?.() ?? []),
      createLinkHoverPlugin((from, to) => {
        this.editor.commands.setTextSelection({from, to});
        toggleLink(this.editor);
      }),
    ];
  },
});

export const configureLink = () => {
  return [
    MyLink.configure({
      openOnClick: true,
      autolink: true,
      defaultProtocol: 'https',
      protocols: ['http', 'https'],
      shouldAutoLink: (url: string) => {
        // 避免 linkify 将“安装.net”等中文文本误判为裸域名。
        return /^https?:\/\/\S+$/i.test(url);
      },
      HTMLAttributes: {
        class: cn(
          'my-link',
          'text-foreground! underline underline-offset-[3px] transition-colors cursor-pointer',
        ),
      },
    }),
  ];
};

interface LinkEditTarget {
  from: number;
  to: number;
  text: string;
  marks: readonly Mark[];
  isEditing: boolean;
}

function getLinkEditTarget(editor: Editor): LinkEditTarget {
  const {doc, selection, schema} = editor.state;
  const linkType = schema.marks.link;
  const linkRange = linkType
    ? getMarkRange(selection.$from, linkType)
    : undefined;
  const shouldEditWholeLink = editor.isActive('link') && linkRange;
  const from = shouldEditWholeLink ? linkRange.from : selection.from;
  const to = shouldEditWholeLink ? linkRange.to : selection.to;
  const $from = doc.resolve(from);

  return {
    from,
    to,
    text: doc.textBetween(from, to, ' '),
    marks: $from.nodeAfter?.marks ?? $from.marks(),
    isEditing: Boolean(shouldEditWholeLink),
  };
}

function toMarkJson(marks: readonly Mark[]) {
  return marks
    .filter((mark) => mark.type.name !== 'link')
    .map((mark) => ({type: mark.type.name, attrs: mark.attrs}));
}

function replaceLinkText(
  editor: Editor,
  target: LinkEditTarget,
  text: string,
  href?: string,
) {
  const marks = toMarkJson(target.marks);
  if (href) marks.push({type: 'link', attrs: {href}});

  return editor
    .chain()
    .focus()
    .insertContentAt(
      {from: target.from, to: target.to},
      {type: 'text', text, marks},
    )
    .setTextSelection(target.from + text.length)
    .run();
}

export function toggleLink(editor: Editor) {
  if (editor.isDestroyed) return;

  const target = getLinkEditTarget(editor);
  const previousUrl = target.isEditing
    ? String(editor.getAttributes('link').href ?? '')
    : '';

  showLinkEditDialog({
    initialText: target.text,
    initialUrl: previousUrl,
    isEditing: target.isEditing,
    onSubmit: ({text, url}) => {
      try {
        if (text !== target.text) {
          return replaceLinkText(editor, target, text, url)
            ? undefined
            : i18nText('app.article.mylink.bf9e0257');
        }

        const applied = editor
          .chain()
          .focus()
          .setTextSelection({from: target.from, to: target.to})
          .setLink({href: url})
          .setTextSelection(target.to)
          .run();
        return applied ? undefined : i18nText('app.article.mylink.bf9e0257');
      } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        return `${i18nText('app.article.mylink.bf9e0257')} ${detail}`;
      }
    },
    onRemove: () => {
      try {
        const removed = editor
          .chain()
          .focus()
          .setTextSelection({from: target.from, to: target.to})
          .unsetLink()
          .setTextSelection(target.to)
          .run();
        return removed ? undefined : i18nText('app.article.mylink.bf9e0257');
      } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        return `${i18nText('app.article.mylink.bf9e0257')} ${detail}`;
      }
    },
  });
}
