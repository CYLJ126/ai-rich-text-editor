import katex from 'katex';
import {i18nText} from '@/utils/i18n';
import 'katex/dist/katex.min.css';
import {Button, Modal, Radio, Space, Tabs} from 'antd';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import './MathFormulaModal.less';
import {MATH_SYMBOL_SECTIONS, type MathSymbolItem} from './mathSymbols';

export type MathFormulaType = 'inline' | 'block';

export interface MathFormulaModalProps {
  open: boolean;
  initialLatex?: string;
  initialType?: MathFormulaType;
  onConfirm: (latex: string, type: MathFormulaType) => void;
  onCancel: () => void;
}

interface MathSymbolTableProps {
  symbols: MathSymbolItem[];
  onInsert: (latex: string) => void;
}

const MathSymbolTable: React.FC<MathSymbolTableProps> = ({
                                                           symbols,
                                                           onInsert,
                                                         }) => (
  <div className="math-formula-modal__symbol-table-scroll">
    <table className="math-formula-modal__symbol-table">
      <thead>
      <tr>
        <th scope="col">符号</th>
        <th scope="col">输入</th>
        <th scope="col">描述</th>
      </tr>
      </thead>
      <tbody>
      {symbols.map((symbol) => {
        const symbolHtml = katex.renderToString(symbol.latex, {
          throwOnError: false,
          output: 'html',
        });
        return (
          <tr key={`${symbol.latex}-${symbol.description}`}>
            <td>
              <button
                type="button"
                className="math-formula-modal__symbol-button"
                title={`${symbol.description}（点击插入 ${symbol.latex}）`}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => onInsert(symbol.latex)}
              >
                {/* biome-ignore lint/security/noDangerouslySetInnerHtml: KaTeX escapes the input and generates the required formula markup. */}
                <span dangerouslySetInnerHTML={{__html: symbolHtml}}/>
              </button>
            </td>
            <td>
              <code>{symbol.latex}</code>
            </td>
            <td>{symbol.description}</td>
          </tr>
        );
      })}
      </tbody>
    </table>
  </div>
);

const MathFormulaModal: React.FC<MathFormulaModalProps> = ({
  open,
  initialLatex = '',
  initialType = 'block',
  onConfirm,
  onCancel,
}) => {
  const [formulaType, setFormulaType] = useState<MathFormulaType>(
    initialType as MathFormulaType,
  );
  const [latexCode, setLatexCode] = useState<string>(initialLatex);
  const [previewHtml, setPreviewHtml] = useState<string>('');
  const [previewError, setPreviewError] = useState<string>('');
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const selectionRef = useRef({
    start: initialLatex.length,
    end: initialLatex.length,
  });

  // 渲染预览
  const renderPreview = useCallback((latex: string, type: MathFormulaType) => {
    if (!latex.trim()) {
      setPreviewHtml('');
      setPreviewError('');
      return;
    }
    try {
      const html = katex.renderToString(latex, {
        displayMode: type === 'block',
        throwOnError: true,
        output: 'html',
        trust: true,
      });
      setPreviewHtml(html);
      setPreviewError('');
    } catch (err: any) {
      setPreviewHtml('');
      setPreviewError(
        err?.message ||
        i18nText('app.article.mathformula.mathformulamodal.48c97624'),
      );
    }
  }, []);

  // latex 或 type 变化时重新渲染预览
  useEffect(() => {
    renderPreview(latexCode, formulaType);
  }, [latexCode, formulaType, renderPreview]);

  // 弹窗打开时重置状态
  useEffect(() => {
    if (open) {
      setFormulaType(initialType as MathFormulaType);
      setLatexCode(initialLatex);
      selectionRef.current = {
        start: initialLatex.length,
        end: initialLatex.length,
      };
      setTimeout(() => {
        const textarea = textareaRef.current;
        textarea?.focus();
        textarea?.setSelectionRange(initialLatex.length, initialLatex.length);
      }, 100);
    }
  }, [open, initialLatex, initialType]);

  const rememberSelection = useCallback((textarea: HTMLTextAreaElement) => {
    selectionRef.current = {
      start: textarea.selectionStart,
      end: textarea.selectionEnd,
    };
  }, []);

  const handleSymbolInsert = useCallback(
    (symbol: string) => {
      const textarea = textareaRef.current;
      const currentSelection = textarea
        ? {start: textarea.selectionStart, end: textarea.selectionEnd}
        : selectionRef.current;
      const start = Math.min(currentSelection.start, latexCode.length);
      const end = Math.min(currentSelection.end, latexCode.length);
      const nextLatex = `${latexCode.slice(0, start)}${symbol}${latexCode.slice(end)}`;
      const nextCursorPosition = start + symbol.length;

      setLatexCode(nextLatex);
      selectionRef.current = {
        start: nextCursorPosition,
        end: nextCursorPosition,
      };

      requestAnimationFrame(() => {
        textarea?.focus();
        textarea?.setSelectionRange(nextCursorPosition, nextCursorPosition);
      });
    },
    [latexCode],
  );

  const handleConfirm = useCallback(() => {
    if (!latexCode.trim()) return;
    onConfirm(latexCode.trim(), formulaType);
  }, [latexCode, formulaType, onConfirm]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        handleConfirm();
      }
    },
    [handleConfirm],
  );

  return (
    <Modal
      title={i18nText('app.article.mathformula.mathformulamodal.480646f1')}
      open={open}
      onCancel={onCancel}
      footer={null}
      width={1200}
      destroyOnHidden
      centered
      className="math-formula-modal"
    >
      <div className="gap-1">
        {/* 公式类型选择 */}
        <div className="">
          <Radio.Group
            value={formulaType}
            onChange={(e) => setFormulaType(e.target.value as MathFormulaType)}
          >
            <Radio value="inline">
              {i18nText('app.article.mathformula.mathformulamodal.404f8313')}
            </Radio>
            <Radio value="block">
              {i18nText('app.article.mathformula.mathformulamodal.00f836fe')}
            </Radio>
          </Radio.Group>
        </div>

        <div className="math-formula-modal__editor-grid">
          {/* LaTeX 输入 */}
          <div className="math-formula-modal__input-section">
            <div className="math-formula-modal__label">
              {i18nText('app.article.mathformula.mathformulamodal.e74c03ef')}
            </div>
            <textarea
              ref={textareaRef}
              className="math-formula-modal__textarea"
              value={latexCode}
              onChange={(event) => {
                setLatexCode(event.target.value);
                rememberSelection(event.target);
              }}
              onSelect={(event) => rememberSelection(event.currentTarget)}
              onKeyUp={(event) => rememberSelection(event.currentTarget)}
              onKeyDown={handleKeyDown}
              placeholder={i18nText(
                'app.article.mathformula.mathformulamodal.62e2f16f',
              )}
              rows={5}
              spellCheck={false}
            />
          </div>

          {/* 预览区域 */}
          <div className="math-formula-modal__preview-section">
            <div className="math-formula-modal__label">
              {i18nText('app.article.mathformula.mathformulamodal.1c3452c2')}
            </div>
            <div
              className={[
                'math-formula-modal__preview',
                previewError ? 'math-formula-modal__preview--error' : '',
                formulaType === 'block'
                  ? 'math-formula-modal__preview--block'
                  : '',
              ]
                .filter(Boolean)
                .join(' ')}
            >
              {previewError ? (
                <span className="math-formula-modal__preview-error-text">
                  {previewError}
                </span>
              ) : previewHtml ? (
                // biome-ignore lint/security/noDangerouslySetInnerHtml: KaTeX escapes the input and generates the required formula markup.
                <span dangerouslySetInnerHTML={{__html: previewHtml}}/>
              ) : (
                <span className="math-formula-modal__preview-placeholder">
                  {i18nText(
                    'app.article.mathformula.mathformulamodal.be18a95c',
                  )}
                </span>
              )}
            </div>
          </div>
        </div>

        {/* 数学符号选择 */}
        <div className="math-formula-modal__symbols-section">
          <Tabs
            size="small"
            tabBarGutter={10}
            items={MATH_SYMBOL_SECTIONS.map((section) => ({
              key: section.key,
              label: section.title,
              children: (
                <MathSymbolTable
                  symbols={section.symbols}
                  onInsert={handleSymbolInsert}
                />
              ),
            }))}
          />
        </div>

        {/* 操作按钮 */}
        <div className="math-formula-modal__footer">
          <Space>
            <Button onClick={onCancel}>
              {i18nText('app.article.mathformula.mathformulamodal.fe95a273')}
            </Button>
            <Button
              type="primary"
              onClick={handleConfirm}
              disabled={!latexCode.trim()}
            >
              {i18nText('app.article.mathformula.mathformulamodal.794a238f')}
            </Button>
          </Space>
        </div>
      </div>
    </Modal>
  );
};

export default MathFormulaModal;
