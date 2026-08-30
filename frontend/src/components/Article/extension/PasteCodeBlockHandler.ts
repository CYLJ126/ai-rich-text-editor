import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { codeToContent } from '@/components/Article/extension/mermaid';

type FencedCode = {
  code: string;
  language: string | null;
};

function parseFencedCode(text: string): FencedCode | null {
  const match = text.match(
    /^```([^\s`]*)[ \t]*\r?\n([\s\S]*?)\r?\n?```[ \t]*$/,
  );
  if (!match) return null;

  return {
    language: match[1] ? match[1].toLowerCase() : null,
    code: match[2],
  };
}

function wrapStandaloneLanguageCode(html: string): string {
  const document = new DOMParser().parseFromString(html, 'text/html');

  document.querySelectorAll('code[class]').forEach((code) => {
    const hasLanguage = Array.from(code.classList).some((className) =>
      className.startsWith('language-'),
    );
    const parent = code.parentElement;
    if (!hasLanguage || parent?.tagName.toLowerCase() === 'pre') return;

    const pre = document.createElement('pre');
    const codeClone = code.cloneNode(true);
    pre.appendChild(codeClone);

    if (
      parent?.tagName.toLowerCase() === 'p' &&
      parent.childNodes.length === 1
    ) {
      parent.replaceWith(pre);
    } else {
      code.replaceWith(pre);
    }
  });

  return document.body.innerHTML;
}

export const PasteCodeBlockHandler = Extension.create({
  name: 'pasteCodeBlockHandler',

  transformPastedHTML(html) {
    return wrapStandaloneLanguageCode(html);
  },

  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('pasteCodeBlockHandler'),
        props: {
          handlePaste(view, event) {
            const fencedCode = parseFencedCode(
              event.clipboardData?.getData('text/plain') ?? '',
            );
            if (!fencedCode) return false;

            const { code, language } = fencedCode;
            const { schema, tr } = view.state;
            const node =
              language === 'mermaid' && schema.nodes.mermaid
                ? schema.nodeFromJSON({
                    type: 'mermaid',
                    content: codeToContent(code),
                  })
                : schema.nodes.codeBlock?.create(
                    { language },
                    code ? schema.text(code) : undefined,
                  );

            if (!node) return false;
            view.dispatch(tr.replaceSelectionWith(node).scrollIntoView());
            return true;
          },
        },
      }),
    ];
  },
});
