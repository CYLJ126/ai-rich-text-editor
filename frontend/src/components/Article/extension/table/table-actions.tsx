import {MediumOutlined} from '@ant-design/icons';
import type {Editor} from '@tiptap/react';
import {App} from 'antd';
import {CopyIcon, ScissorsIcon, TrashIcon} from 'lucide-react';
import React, {useEffect, useMemo, useRef} from 'react';
import {createPortal} from 'react-dom';
import {Button} from '@/components/ui/button';
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

const TABLE_ACTION_ICON_CLASS =
  'size-4 text-[var(--ant-color-text-tertiary)] dark:text-[var(--ant-color-bg-spotlight)]';

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
        <Button
          aria-label={action.label}
          className="size-7 cursor-pointer opacity-70 hover:opacity-100"
          data-table-action
          key={action.key}
          onClick={() => runAction(action)}
          onMouseDown={(event) => event.preventDefault()}
          size="icon"
          title={action.label}
          type="button"
          variant={action.destructive ? 'destructive' : 'secondary'}
        >
          {action.icon}
        </Button>
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
        icon: <CopyIcon className={TABLE_ACTION_ICON_CLASS} strokeWidth={3}/>,
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
        icon: <ScissorsIcon className={TABLE_ACTION_ICON_CLASS} strokeWidth={3}/>,
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
        icon: <MediumOutlined className={TABLE_ACTION_ICON_CLASS}/>,
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
        icon: <TrashIcon className={TABLE_ACTION_ICON_CLASS} strokeWidth={3}/>,
        destructive: true,
        onClick: ({editor: currentEditor, tablePos}) =>
          deleteTableAt(currentEditor, tablePos),
      },
    ],
    [message],
  );

  return <TableActions actions={actions} editor={editor}/>;
};
