import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';

export interface SearchMatch {
  from: number;
  to: number;
}

export interface SearchHighlightMeta {
  matches: SearchMatch[];
  activeIndex: number;
}

export const searchHighlightPluginKey = new PluginKey<DecorationSet>(
  'searchHighlight',
);

export const SearchHighlight = Extension.create({
  name: 'searchHighlight',

  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: searchHighlightPluginKey,
        state: {
          init: () => DecorationSet.empty,
          apply(transaction, decorationSet) {
            const meta = transaction.getMeta(searchHighlightPluginKey) as
              | SearchHighlightMeta
              | undefined;

            if (meta) {
              return DecorationSet.create(
                transaction.doc,
                meta.matches.map((match, index) =>
                  Decoration.inline(match.from, match.to, {
                    class:
                      index === meta.activeIndex
                        ? 'search-match search-match-current'
                        : 'search-match',
                  }),
                ),
              );
            }

            return transaction.docChanged
              ? decorationSet.map(transaction.mapping, transaction.doc)
              : decorationSet;
          },
        },
        props: {
          decorations(state) {
            return searchHighlightPluginKey.getState(state);
          },
        },
      }),
    ];
  },
});
