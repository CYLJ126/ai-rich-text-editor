import {Plugin, PluginKey} from '@tiptap/pm/state';

export const floatingResultPluginKey = new PluginKey('floatingResult');

export interface FloatingResultState {
  visible: boolean;
  content: string;
  position: { top: number; left: number } | null;
  selectionFrom: number;
  selectionTo: number;
}

const initialState: FloatingResultState = {
  visible: false,
  content: '',
  position: null,
  selectionFrom: 0,
  selectionTo: 0,
};

export function createFloatingResultPlugin() {
  return new Plugin({
    key: floatingResultPluginKey,
    state: {
      init() {
        return initialState;
      },
      apply(tr, prevState) {
        const meta = tr.getMeta(floatingResultPluginKey);
        if (meta) {
          return {...prevState, ...meta};
        }
        return prevState;
      },
    },
  });
}
