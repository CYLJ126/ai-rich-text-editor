import katex from 'katex';
import 'katex/dist/katex.min.css';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {Button, Modal, Radio, Space} from 'antd';
import './MathFormulaModal.less';

export type MathFormulaType = 'inline' | 'block';

export interface MathFormulaModalProps {
  open: boolean;
  initialLatex?: string;
  initialType?: MathFormulaType;
  onConfirm: (latex: string, type: MathFormulaType) => void;
  onCancel: () => void;
}

interface ExampleItem {
  label: string;
  latex: string;
}

const EXAMPLES: ExampleItem[] = [
  { label: '质能方程', latex: 'E = mc^2' },
  { label: '二次公式', latex: 'x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}' },
  { label: '求和', latex: '\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}' },
  { label: '积分', latex: '\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}' },
];

const MathFormulaModal: React.FC<MathFormulaModalProps> = ({
  open,
  initialLatex = '',
  initialType = 'block',
  onConfirm,
  onCancel,
}) => {
  const [formulaType, setFormulaType] = useState<MathFormulaType>(initialType as MathFormulaType);
  const [latexCode, setLatexCode] = useState<string>(initialLatex);
  const [previewHtml, setPreviewHtml] = useState<string>('');
  const [previewError, setPreviewError] = useState<string>('');
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

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
      setPreviewError(err?.message || '公式解析错误，请检查 LaTeX 语法');
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
      setTimeout(() => {
        textareaRef.current?.focus();
      }, 100);
    }
  }, [open, initialLatex, initialType]);

  const handleExampleClick = useCallback((example: ExampleItem) => {
    setLatexCode(example.latex);
    textareaRef.current?.focus();
  }, []);

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
      title="编辑数学公式"
      open={open}
      onCancel={onCancel}
      footer={null}
      width={480}
      destroyOnHidden
      className="math-formula-modal"
    >
      <div className="math-formula-modal__content">
        {/* 公式类型选择 */}
        <div className="math-formula-modal__type-selector">
          <Radio.Group
            value={formulaType}
            onChange={(e) => setFormulaType(e.target.value as MathFormulaType)}
          >
            <Radio value="inline">行内公式</Radio>
            <Radio value="block">块级公式</Radio>
          </Radio.Group>
        </div>

        {/* LaTeX 输入 */}
        <div className="math-formula-modal__input-section">
          <div className="math-formula-modal__label">LaTeX 代码：</div>
          <textarea
            ref={textareaRef}
            className="math-formula-modal__textarea"
            value={latexCode}
            onChange={(e) => setLatexCode(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="请输入 LaTeX 公式，例如：x^2 + y^2 = z^2"
            rows={4}
            spellCheck={false}
          />
        </div>

        {/* 预览区域 */}
        <div className="math-formula-modal__preview-section">
          <div className="math-formula-modal__label">预览：</div>
          <div
            className={[
              'math-formula-modal__preview',
              previewError ? 'math-formula-modal__preview--error' : '',
              formulaType === 'block' ? 'math-formula-modal__preview--block' : '',
            ]
              .filter(Boolean)
              .join(' ')}
          >
            {previewError ? (
              <span className="math-formula-modal__preview-error-text">{previewError}</span>
            ) : previewHtml ? (
              <span dangerouslySetInnerHTML={{ __html: previewHtml }} />
            ) : (
              <span className="math-formula-modal__preview-placeholder">输入公式后显示预览...</span>
            )}
          </div>
        </div>

        {/* 示例按钮 */}
        <div className="math-formula-modal__examples-section">
          <div className="math-formula-modal__label">示例：</div>
          <Space size={8} wrap>
            {EXAMPLES.map((example) => (
              <Button
                key={example.label}
                size="small"
                className="math-formula-modal__example-btn"
                onClick={() => handleExampleClick(example)}
              >
                {example.label}
              </Button>
            ))}
          </Space>
        </div>

        {/* 操作按钮 */}
        <div className="math-formula-modal__footer">
          <Space>
            <Button onClick={onCancel}>取消</Button>
            <Button type="primary" onClick={handleConfirm} disabled={!latexCode.trim()}>
              保存
            </Button>
          </Space>
        </div>
      </div>
    </Modal>
  );
};

export default MathFormulaModal;
