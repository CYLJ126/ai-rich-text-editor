import {MediumOutlined} from '@ant-design/icons';
import type {Editor} from '@tiptap/react';
import {App} from 'antd';
import {CopyIcon, ScissorsIcon, Trash2Icon} from 'lucide-react';
import React, {useEffect, useMemo, useRef} from 'react';
import {createPortal} from 'react-dom';
import {i18nText} from '@/utils/i18n';
import {TableActionsPlugin, tableActionsPluginKey,} from './table-actions-plugin';
import {copyTable, copyTableAsMarkdown, cutTable, deleteTableAt,} from './table-clipboard';

export interface TableActionContext {
  editor: Editor;
  tablePos: number;
}

export interface TableActionItem {
  key: string;
  label: string;
  icon: React.ReactNode;
  onClick: (context: TableActionContext) => boolean | Promise<boolean>;
  destructive?: boolean;
}

export interface TableActionsProps {
  editor: Editor;
  actions: TableActionItem[];
}

/** Generic table action bar. Add another icon by appending an action item. */
export const TableActions = ({editor, actions}: TableActionsProps) => {
  const rootElementRef = useRef<HTMLDivElement | null>(null);
  if (!rootElementRef.current) {
    rootElementRef.current = document.createElement('div');
  }

  useEffect(() => {
    const rootElement = rootElementRef.current;
    if (!rootElement || editor.isDestroyed) {
      return;
    }

    rootElement.className = 'table-actions';
    rootElement.contentEditable = 'false';
    rootElement.style.visibility = 'hidden';

    editor.registerPlugin(TableActionsPlugin({editor, element: rootElement}));

    return () => {
      editor.unregisterPlugin(tableActionsPluginKey);
      window.requestAnimationFrame(() => rootElement.remove());
    };
  }, [editor]);

  const runAction = (action: TableActionItem) => {
    const tablePos = tableActionsPluginKey.getState(editor.state)?.tablePos;
    if (tablePos === null || tablePos === undefined) {
      return;
    }

    void action.onClick({editor, tablePos});
  };

  return createPortal(
    <div className="table-actions__group" role="toolbar">
      {actions.map((action) => (
        <button
          aria-label={action.label}
          className="table-actions__button"
          data-destructive={action.destructive || undefined}
          key={action.key}
          onClick={() => runAction(action)}
          onMouseDown={(event) => event.preventDefault()}
          title={action.label}
          type="button"
        >
          {action.icon}
        </button>
      ))}
    </div>,
    rootElementRef.current,
  );
};

export const DefaultTableActions = ({editor}: { editor: Editor }) => {
  const {message} = App.useApp();
  const actions = useMemo<TableActionItem[]>(
    () => [
      {
        key: 'copy',
        label: i18nText('app.article.table.copyTable'),
        icon: <CopyIcon/>,
        onClick: async ({editor: currentEditor, tablePos}) => {
          const succeeded = await copyTable(currentEditor, tablePos);
          if (succeeded) {
            message.success(i18nText('app.article.table.copyTableSuccess'));
          } else {
            message.error(i18nText('app.article.table.clipboardError'));
          }
          return succeeded;
        },
      },
      {
        key: 'cut',
        label: i18nText('app.article.table.cutTable'),
        icon: <ScissorsIcon/>,
        onClick: async ({editor: currentEditor, tablePos}) => {
          const succeeded = await cutTable(currentEditor, tablePos);
          if (!succeeded) {
            message.error(i18nText('app.article.table.clipboardError'));
          }
          return succeeded;
        },
      },
      {
        key: 'copy-markdown',
        label: i18nText('app.article.table.copyTableAsMarkdown'),
        icon: <MediumOutlined/>,
        onClick: async ({editor: currentEditor, tablePos}) => {
          const succeeded = await copyTableAsMarkdown(currentEditor, tablePos);
          if (succeeded) {
            message.success(
              i18nText('app.article.table.copyTableAsMarkdownSuccess'),
            );
          } else {
            message.error(i18nText('app.article.table.clipboardError'));
          }
          return succeeded;
        },
      },
      {
        key: 'delete',
        label: i18nText('app.article.table.deleteTable'),
        icon: <Trash2Icon/>,
        destructive: true,
        onClick: ({editor: currentEditor, tablePos}) =>
          deleteTableAt(currentEditor, tablePos),
      },
    ],
    [message],
  );

  return <TableActions actions={actions} editor={editor}/>;
};
