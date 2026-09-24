import {NodeSelection, Plugin, PluginKey} from '@tiptap/pm/state';
import type {EditorView} from '@tiptap/pm/view';
import {type Editor, findParentNodeClosestToPos} from '@tiptap/react';

export interface TableActionsPluginState {
  tablePos: number | null;
}

export interface TableActionsPluginProps {
  editor: Editor;
  element: HTMLElement;
  pluginKey?: PluginKey<TableActionsPluginState>;
}

export const tableActionsPluginKey = new PluginKey<TableActionsPluginState>(
  'table-actions',
);

const findTableFromSelection = (view: EditorView) => {
  const {selection} = view.state;

  if (
    selection instanceof NodeSelection &&
    selection.node.type.name === 'table'
  ) {
    return selection.from;
  }

  return findParentNodeClosestToPos(
    selection.$from,
    (node) => node.type.name === 'table',
  )?.pos;
};

const findTableFromElement = (view: EditorView, element: HTMLElement) => {
  let tablePos: number | undefined;

  view.state.doc.descendants((node, pos) => {
    if (node.type.name !== 'table') {
      return true;
    }

    if (view.nodeDOM(pos) === element) {
      tablePos = pos;
      return false;
    }

    return true;
  });

  return tablePos;
};

/**
 * Keeps one extensible table toolbar mounted above the hovered or selected table.
 */
export const TableActionsPlugin = ({
                                     editor,
                                     element,
                                     pluginKey = tableActionsPluginKey,
                                   }: TableActionsPluginProps) => {
  let hoveredTable: HTMLElement | null = null;
  let activeTable: HTMLElement | null = null;
  let initialSyncFrame: number | undefined;

  const setPluginTablePos = (view: EditorView, tablePos: number | null) => {
    if (pluginKey.getState(view.state)?.tablePos === tablePos) {
      return;
    }

    view.dispatch(view.state.tr.setMeta(pluginKey, {tablePos}));
  };

  const hide = (view: EditorView) => {
    activeTable = null;
    element.style.visibility = 'hidden';
    element.style.opacity = '0';
    element.remove();
    setPluginTablePos(view, null);
  };

  const show = (view: EditorView, table: HTMLElement, tablePos: number) => {
    const controls = table.querySelector<HTMLElement>('.table-controls');
    if (!controls) {
      hide(view);
      return;
    }

    if (activeTable !== table || element.parentElement !== controls) {
      controls.appendChild(element);
      activeTable = table;
    }

    element.style.visibility = 'visible';
    element.style.opacity = '1';
    setPluginTablePos(view, tablePos);
  };

  const sync = (view: EditorView) => {
    if (!editor.isEditable || editor.isDestroyed) {
      hide(view);
      return;
    }

    if (hoveredTable && !hoveredTable.isConnected) {
      hoveredTable = null;
    }

    const hoveredTablePos = hoveredTable
      ? findTableFromElement(view, hoveredTable)
      : undefined;
    const tablePos = hoveredTablePos ?? findTableFromSelection(view);

    if (tablePos === undefined) {
      hide(view);
      return;
    }

    const table = view.nodeDOM(tablePos);
    if (!(table instanceof HTMLElement)) {
      hide(view);
      return;
    }

    show(view, table, tablePos);
  };

  return new Plugin<TableActionsPluginState>({
    key: pluginKey,
    state: {
      init: () => ({tablePos: null}),
      apply: (transaction, value) => {
        const meta = transaction.getMeta(pluginKey) as
          | Partial<TableActionsPluginState>
          | undefined;
        if (meta && 'tablePos' in meta) {
          return {tablePos: meta.tablePos ?? null};
        }

        if (value.tablePos !== null && transaction.docChanged) {
          const mapped = transaction.mapping.mapResult(value.tablePos);
          return {tablePos: mapped.deleted ? null : mapped.pos};
        }

        return value;
      },
    },
    view: (view) => {
      initialSyncFrame = window.requestAnimationFrame(() => sync(view));

      return {
        update: (updatedView, previousState) => {
          const selectionChanged = !previousState.selection.eq(
            updatedView.state.selection,
          );
          const documentChanged = !previousState.doc.eq(updatedView.state.doc);

          if (selectionChanged || documentChanged) {
            sync(updatedView);
          }
        },
        destroy: () => {
          if (initialSyncFrame !== undefined) {
            window.cancelAnimationFrame(initialSyncFrame);
          }
          element.remove();
        },
      };
    },
    props: {
      handleDOMEvents: {
        mousemove: (view, event) => {
          const target = event.target;
          if (!(target instanceof Element)) {
            return false;
          }

          const table = target.closest<HTMLElement>(
            '[data-content-type="table"]',
          );
          if (table !== hoveredTable) {
            hoveredTable = table;
            sync(view);
          }

          return false;
        },
        mouseleave: (view) => {
          hoveredTable = null;
          sync(view);
          return false;
        },
      },
    },
  });
};
