import { i18nText } from '@/utils/i18n';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuSeparator,
  DropdownMenuShortcut,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { PluginKey, TextSelection } from '@tiptap/pm/state';
import { CellSelection, deleteCellSelection } from '@tiptap/pm/tables';
import { Editor, useEditorState } from '@tiptap/react';
import { EllipsisIcon, EllipsisVerticalIcon, EqualIcon } from 'lucide-react';
import { useMemo, useState } from 'react';
import {
  columnMenuPluginKey,
  rowMenuPluginKey,
  TableMenuHandle,
  TableMenuHandleProps,
  TableSelectionOverlay,
  TableSelectionOverlayProps,
} from '@/components/Article/extension';

interface CellMenusState {
  canMergeCell: boolean;
  canSplitCell: boolean;
  canClearContents: boolean;
}

// ─── 表格列操作菜单 ───
const ColumnMenuPopover = ({ editor }: { editor: Editor }) => {
  const [opened, setOpened] = useState(false);
  return (
    <DropdownMenu
      open={opened}
      onOpenChange={setOpened}
      onOpenChangeComplete={(op) => {
        editor
          .chain()
          .command(({ tr }) => {
            tr.setMeta(columnMenuPluginKey, { openedMenu: op });
            return true;
          })
          .run();
      }}
    >
      <DropdownMenuTrigger
        className={cn('w-full h-3 rounded flex items-center justify-center', {
          'bg-primary text-primary-foreground': opened,
          'bg-secondary hover:bg-secondary/70 text-secondary-foreground':
            !opened,
        })}
      >
        <EllipsisIcon className="size-4" />
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="max-h-80 w-40 overflow-hidden overflow-y-auto bg-white dark:bg-neutral-800 ring-xl ring-neutral-300 dark:ring-neutral-600"
        align="start"
      >
        <DropdownMenuGroup>
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().addColumnBefore().run();
            }}
          >
            {i18nText('app.article.table.addColumnBefore')}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().addColumnAfter().run();
            }}
          >
            {i18nText('app.article.table.addColumnAfter')}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().deleteColumn().run();
            }}
            variant="destructive"
          >
            {i18nText('app.article.table.deleteColumn')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

// ─── 表格行操作菜单 ───
const RowMenuPopover = ({ editor }: { editor: Editor }) => {
  const [opened, setOpened] = useState(false);
  return (
    <DropdownMenu
      open={opened}
      onOpenChange={setOpened}
      onOpenChangeComplete={(op) => {
        editor
          .chain()
          .command(({ tr }) => {
            tr.setMeta(rowMenuPluginKey, { openedMenu: op });
            return true;
          })
          .run();
      }}
    >
      {/* 触发按钮，三个点 */}
      <DropdownMenuTrigger
        className={cn('w-3 rounded flex items-center justify-center h-full')}
      >
        <EllipsisVerticalIcon className="size-4 shrink-0" />
      </DropdownMenuTrigger>
      <DropdownMenuContent
        className="max-h-80 w-70 overflow-hidden overflow-y-auto shadow-xl bg-white dark:bg-neutral-800 ring-neutral-300 dark:ring-neutral-600"
        align="start"
        side="right"
      >
        <DropdownMenuGroup>
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().addRowBefore().run();
            }}
          >
            {i18nText('app.article.table.addRowBefore')}
            <DropdownMenuShortcut>Mod+Shift+Enter</DropdownMenuShortcut>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().addRowAfter().run();
            }}
          >
            {i18nText('app.article.table.addRowAfter')}
            <DropdownMenuShortcut>Mod+Enter</DropdownMenuShortcut>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().deleteRow().run();
            }}
            variant="destructive"
          >
            {i18nText('app.article.table.deleteRow')}
            <DropdownMenuShortcut>Mod+⌫</DropdownMenuShortcut>
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

// ─── 单元格操作菜单 ───
const CellMenuPopover = ({ editor }: { editor: Editor }) => {
  const [opened, setOpened] = useState(false);
  const { canMergeCell, canSplitCell, canClearContents } =
    useEditorState<CellMenusState>({
      editor: editor,
      equalityFn: (a, b) => {
        return (
          a.canMergeCell === b?.canMergeCell &&
          a.canSplitCell === b.canSplitCell &&
          a.canClearContents === b.canClearContents
        );
      },
      selector: (instance) => {
        const editor = instance.editor;
        const { selection } = editor.state;
        const { from, to, ranges } = selection;
        if (!instance.editor.isActive('table')) {
          return {
            canMergeCell: false,
            canSplitCell: false,
            canClearContents: false,
          };
        }

        let hasSpannedCell = false;
        let cellSelectionCount = 0;
        let selectionContentSize = 0;

        if (selection instanceof TextSelection) {
          editor.state.doc.nodesBetween(from, to, (node, pos) => {
            const nodeName = node.type.name;
            if (nodeName === 'tableHeader' || nodeName === 'tableCell') {
              const cell = editor.view.nodeDOM(pos) as HTMLTableCellElement;
              hasSpannedCell = cell.colSpan > 1 || cell.rowSpan > 1;
              return false;
            }
            return true;
          });
        }

        if (selection instanceof CellSelection) {
          cellSelectionCount = selection.ranges.length;
          for (const range of ranges) {
            const { $from, $to } = range;
            editor.state.doc.nodesBetween($from.pos, $to.pos, (node) => {
              if (node.isTextblock) {
                selectionContentSize += node.content.size;
              }
              return true;
            });
          }
        }

        return {
          canMergeCell: cellSelectionCount > 1,
          canSplitCell: hasSpannedCell,
          canClearContents: selectionContentSize > 0,
        };
      },
    });

  return (
    <DropdownMenu open={opened} onOpenChange={setOpened}>
      <DropdownMenuTrigger
        className={cn(
          'absolute flex items-center justify-center top-1/2 -translate-y-1/2 hover:-right-2.25 bg-primary size-2 hover:size-4 rounded-full cursor-pointer pointer-events-auto',
          {
            'size-4 -right-2.25': opened,
            '-right-1.25': !opened,
          },
        )}
        render={
          <button
            onPointerDown={(evt) => {
              evt.preventDefault();
              setOpened(true);
            }}
          >
            <EqualIcon
              className={cn(
                'size-3.5 text-primary-foreground opacity-0 hover:opacity-100 text-[var(--ant-color-bg-base)]',
                {
                  'opacity-100': opened,
                },
              )}
            />
          </button>
        }
      ></DropdownMenuTrigger>
      <DropdownMenuContent
        className="flex max-h-80 w-40 flex-col overflow-hidden overflow-y-auto shadow-xl bg-white dark:bg-neutral-800 ring-neutral-300 dark:ring-neutral-600"
        align="start"
        side="bottom"
      >
        <DropdownMenuGroup>
          <DropdownMenuItem
            hidden={!canMergeCell}
            onClick={() => {
              editor.chain().focus().mergeCells().run();
            }}
          >
            {i18nText('app.article.table.mergeCells')}
          </DropdownMenuItem>
          <DropdownMenuItem
            hidden={!canSplitCell}
            onClick={() => {
              editor.chain().focus().splitCell().run();
            }}
          >
            {i18nText('app.article.table.splitCell')}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              editor.chain().focus().toggleHeaderCell().run();
            }}
          >
            {i18nText('app.article.table.toggleHeaderCell')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuSub>
            <DropdownMenuSubTrigger>
              {i18nText('app.article.table.alignment')}
            </DropdownMenuSubTrigger>
            <DropdownMenuPortal>
              <DropdownMenuSubContent className="bg-white dark:bg-neutral-800 ring-neutral-300 dark:ring-neutral-600">
                <DropdownMenuItem
                  onClick={() => {
                    editor
                      .chain()
                      .focus()
                      .setCellAttribute('verticalAlign', 'top')
                      .run();
                  }}
                >
                  {i18nText('app.article.table.alignTop')}
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={() => {
                    editor
                      .chain()
                      .focus()
                      .setCellAttribute('verticalAlign', 'middle')
                      .run();
                  }}
                >
                  {i18nText('app.article.table.alignMiddle')}
                </DropdownMenuItem>
                <DropdownMenuItem
                  onClick={() => {
                    editor
                      .chain()
                      .focus()
                      .setCellAttribute('verticalAlign', 'bottom')
                      .run();
                  }}
                >
                  {i18nText('app.article.table.alignBottom')}
                </DropdownMenuItem>
              </DropdownMenuSubContent>
            </DropdownMenuPortal>
          </DropdownMenuSub>
          <DropdownMenuItem
            hidden={!canClearContents}
            onClick={() => {
              editor
                .chain()
                .focus()
                .command(({ state, dispatch }) => {
                  return deleteCellSelection(state, dispatch);
                })
                .run();
            }}
          >
            {i18nText('app.article.table.clearContents')}
          </DropdownMenuItem>
        </DropdownMenuGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

// ─── 表格操作菜单 ───
export const TableHandle = ({ editor }: { editor: Editor | null }) => {
  const columnMenuPluginProps = useMemo(() => {
    if (!editor) {
      return undefined;
    }
    return {
      editor,
      menuType: 'column',
      pluginKey: columnMenuPluginKey,
      options: {
        placement: 'top-start',
        offset: {
          mainAxis: 4,
        },
      },
    } satisfies TableMenuHandleProps['pluginProps'];
  }, [editor]);

  const rowMenuPluginProps = useMemo(() => {
    if (!editor) {
      return undefined;
    }
    return {
      editor,
      menuType: 'row',
      pluginKey: rowMenuPluginKey,
      options: {
        placement: 'left-start',
        offset: {
          mainAxis: 4,
        },
      },
    } satisfies TableMenuHandleProps['pluginProps'];
  }, [editor]);

  const tableSelectionOverlayProps = useMemo(() => {
    if (!editor) {
      return undefined;
    }
    return {
      editor,
      pluginKey: new PluginKey('table-selection-overlay'),
    } satisfies TableSelectionOverlayProps['pluginProps'];
  }, [editor]);

  if (!editor) {
    return null;
  }

  return (
    <>
      {columnMenuPluginProps && (
        <TableMenuHandle pluginProps={columnMenuPluginProps}>
          {/* 列操作菜单 */}
          <ColumnMenuPopover editor={editor} />
        </TableMenuHandle>
      )}
      {rowMenuPluginProps && (
        <TableMenuHandle pluginProps={rowMenuPluginProps}>
          {/* 行操作菜单 */}
          <RowMenuPopover editor={editor} />
        </TableMenuHandle>
      )}

      {tableSelectionOverlayProps && (
        <TableSelectionOverlay pluginProps={tableSelectionOverlayProps}>
          {/* 单元格操作菜单 */}
          <CellMenuPopover editor={editor} />
        </TableSelectionOverlay>
      )}
    </>
  );
};
