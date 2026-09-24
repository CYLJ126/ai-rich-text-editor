import {CellSelection} from '@tiptap/pm/tables';
import {type Editor, findParentNodeClosestToPos, useEditorState} from '@tiptap/react';
import {MyColorPicker} from '@/components';
import {i18nText} from '@/utils/i18n';
import {tableActionsPluginKey} from './table-actions-plugin';
import type {TableBorderStyle, TableDensity} from './table';

interface TableStyleState {
  striped: boolean;
  headerColored: boolean;
  borderStyle: TableBorderStyle;
  density: TableDensity;
  canStyleCell: boolean;
  cellBackgroundColor: string | null;
}

const BORDER_OPTIONS: Array<{ label: string; value: TableBorderStyle }> = [
  {label: i18nText('app.article.table.borderAll'), value: 'all'},
  {label: i18nText('app.article.table.borderHorizontal'), value: 'horizontal'},
  {label: i18nText('app.article.table.borderVertical'), value: 'vertical'},
  {label: i18nText('app.article.table.borderNone'), value: 'none'},
];

const DENSITY_OPTIONS: Array<{ label: string; value: TableDensity }> = [
  {label: i18nText('app.article.table.densityCompact'), value: 'compact'},
  {label: i18nText('app.article.table.densityNarrow'), value: 'narrow'},
  {label: i18nText('app.article.table.densityStandard'), value: 'standard'},
  {label: i18nText('app.article.table.densityWide'), value: 'wide'},
  {label: i18nText('app.article.table.densitySpacious'), value: 'spacious'},
];

const CELL_BACKGROUND_COLORS = [
  '#FFFFFF',
  '#F2F1E8',
  '#FEF7E0',
  '#E8F0E6',
  '#DBEDFA',
  '#F3E8F0',
];

const OPTION_BUTTON_CLASS =
  'rounded border px-2 py-1 text-xs transition-colors hover:bg-[var(--ant-color-fill-tertiary)]';

const findSelectedCell = (editor: Editor) => {
  const {selection} = editor.state;

  if (selection instanceof CellSelection) {
    const cell = selection.$anchorCell.nodeAfter;
    if (
      cell?.type.name === 'tableCell' ||
      cell?.type.name === 'tableHeader'
    ) {
      return {node: cell, pos: selection.$anchorCell.pos};
    }
  }

  return findParentNodeClosestToPos(
    selection.$from,
    (node) =>
      node.type.name === 'tableCell' || node.type.name === 'tableHeader',
  );
};

export const updateActiveTableStyle = (
  editor: Editor,
  attributes: Partial<{
    striped: boolean;
    headerColored: boolean;
    borderStyle: TableBorderStyle;
    density: TableDensity;
  }>,
) => {
  const tablePos = tableActionsPluginKey.getState(editor.state)?.tablePos;
  if (tablePos === null || tablePos === undefined) {
    return false;
  }

  const tableNode = editor.state.doc.nodeAt(tablePos);
  if (tableNode?.type.name !== 'table') {
    return false;
  }

  editor.view.dispatch(
    editor.state.tr.setNodeMarkup(tablePos, undefined, {
      ...tableNode.attrs,
      ...attributes,
    }),
  );
  return true;
};

export const TableStylePanel = ({editor}: { editor: Editor }) => {
  const styleState = useEditorState<TableStyleState>({
    editor,
    equalityFn: (previous, next) =>
      next !== null &&
      previous.striped === next.striped &&
      previous.headerColored === next.headerColored &&
      previous.borderStyle === next.borderStyle &&
      previous.density === next.density &&
      previous.canStyleCell === next.canStyleCell &&
      previous.cellBackgroundColor === next.cellBackgroundColor,
    selector: ({editor: currentEditor}) => {
      const tablePos = tableActionsPluginKey.getState(currentEditor.state)?.tablePos;
      const tableNode =
        tablePos === null || tablePos === undefined
          ? null
          : currentEditor.state.doc.nodeAt(tablePos);
      const selectedCell = findSelectedCell(currentEditor);
      const selectedTable = selectedCell
        ? findParentNodeClosestToPos(
          currentEditor.state.doc.resolve(selectedCell.pos),
          (node) => node.type.name === 'table',
        )
        : undefined;
      const canStyleCell =
        tableNode?.type.name === 'table' && selectedTable?.pos === tablePos;

      return {
        striped: tableNode?.attrs.striped === true,
        headerColored: tableNode?.attrs.headerColored !== false,
        borderStyle: (tableNode?.attrs.borderStyle || 'all') as TableBorderStyle,
        density: (tableNode?.attrs.density || 'standard') as TableDensity,
        canStyleCell,
        cellBackgroundColor: canStyleCell
          ? selectedCell?.node.attrs.backgroundColor || null
          : null,
      };
    },
  });

  const setCellBackgroundColor = (backgroundColor: string | null) => {
    if (!styleState.canStyleCell) {
      return;
    }

    editor
      .chain()
      .focus()
      .setCellAttribute('backgroundColor', backgroundColor)
      .run();
  };

  return (
    <div
      className="w-72 space-y-4"
      onMouseDown={(event) => event.stopPropagation()}
    >
      <label className="flex cursor-pointer items-center justify-between gap-4 text-sm font-medium">
        <span>{i18nText('app.article.table.stripedRows')}</span>
        <input
          checked={styleState.striped}
          className="size-4 accent-[var(--ant-color-primary)]"
          onChange={(event) =>
            updateActiveTableStyle(editor, {striped: event.target.checked})
          }
          type="checkbox"
        />
      </label>

      <label className="flex cursor-pointer items-center justify-between gap-4 text-sm font-medium">
        <span>{i18nText('app.article.table.headerColored')}</span>
        <input
          checked={styleState.headerColored}
          className="size-4 accent-[var(--ant-color-primary)]"
          onChange={(event) =>
            updateActiveTableStyle(editor, {
              headerColored: event.target.checked,
            })
          }
          type="checkbox"
        />
      </label>

      <div className="space-y-2">
        <div className="text-sm font-medium">
          {i18nText('app.article.table.borderStyle')}
        </div>
        <div className="grid grid-cols-2 gap-2">
          {BORDER_OPTIONS.map((option) => (
            <button
              className={`${OPTION_BUTTON_CLASS} ${
                styleState.borderStyle === option.value
                  ? 'border-[var(--ant-color-primary)] bg-[var(--ant-color-primary-bg)] text-[var(--ant-color-primary)]'
                  : 'border-[var(--ant-color-border)]'
              }`}
              key={option.value}
              onClick={() =>
                updateActiveTableStyle(editor, {borderStyle: option.value})
              }
              onMouseDown={(event) => event.preventDefault()}
              type="button"
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-2">
        <div className="text-sm font-medium">
          {i18nText('app.article.table.cellDensity')}
        </div>
        <div className="grid grid-cols-3 gap-2">
          {DENSITY_OPTIONS.map((option) => (
            <button
              className={`${OPTION_BUTTON_CLASS} ${
                styleState.density === option.value
                  ? 'border-[var(--ant-color-primary)] bg-[var(--ant-color-primary-bg)] text-[var(--ant-color-primary)]'
                  : 'border-[var(--ant-color-border)]'
              }`}
              key={option.value}
              onClick={() =>
                updateActiveTableStyle(editor, {density: option.value})
              }
              onMouseDown={(event) => event.preventDefault()}
              type="button"
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      <div className="space-y-2">
        <div className="text-sm font-medium">
          {i18nText('app.article.table.cellBackgroundColor')}
        </div>
        <div className="flex items-center gap-2">
          <div
            className={
              styleState.canStyleCell
                ? undefined
                : 'pointer-events-none opacity-40'
            }
          >
            <MyColorPicker
              getPopupContainer={(triggerNode) =>
                triggerNode.closest<HTMLElement>('[data-content-type="table"]') ||
                document.body
              }
              initialStyle={
                styleState.cellBackgroundColor
                  ? undefined
                  : {
                    backgroundColor:
                      'var(--ant-color-fill-secondary, #f0f0f0)',
                    border:
                      '1px solid var(--ant-color-text-quaternary, #bfbfbf)',
                    boxShadow:
                      '0 0 0 1px var(--ant-color-bg-container, #ffffff)',
                  }
              }
              initialColorOptions={CELL_BACKGROUND_COLORS}
              notify={setCellBackgroundColor}
              value={styleState.cellBackgroundColor || '#ffffff'}
            />
          </div>
          <button
            className={`${OPTION_BUTTON_CLASS} border-[var(--ant-color-border)] disabled:cursor-not-allowed disabled:opacity-40`}
            disabled={!styleState.canStyleCell || !styleState.cellBackgroundColor}
            onClick={() => setCellBackgroundColor(null)}
            onMouseDown={(event) => event.preventDefault()}
            type="button"
          >
            {i18nText('app.article.table.clearCellBackground')}
          </button>
        </div>
        {!styleState.canStyleCell && (
          <div className="text-xs text-[var(--ant-color-text-tertiary)]">
            {i18nText('app.article.table.selectCellForBackground')}
          </div>
        )}
      </div>
    </div>
  );
};
