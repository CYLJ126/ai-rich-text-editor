import Editor, {OnMount} from '@monaco-editor/react';
import {theme} from 'antd';
import {createStyles} from 'antd-style';
import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState,} from 'react';
import {IStandaloneCodeEditor, JsonEditorProps, JsonEditorRef} from './type';

const useStyles = createStyles(({token, css}) => ({
  root: css`
    display: flex;
    flex-direction: column;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadiusLG}px;
    overflow: hidden;
    transition: border-color 0.2s, box-shadow 0.2s;
    background: ${token.colorBgContainer};

    &:focus-within {
      border-color: ${token.colorPrimary};
      box-shadow: 0 0 0 2px ${token.colorPrimaryBg};
    }

    &.is-error {
      border-color: ${token.colorError};

      &:focus-within {
        box-shadow: 0 0 0 2px ${token.colorErrorBg};
      }
    }

    &.is-readonly {
      background: ${token.colorFillAlter};
    }
  `,
  toolbar: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 4px 10px;
    min-height: 32px;
    background: ${token.colorFillAlter};
    border-bottom: 1px solid ${token.colorBorderSecondary};
    user-select: none;
  `,
  toolbarLeft: css`
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: ${token.colorTextTertiary};
  `,
  toolbarRight: css`
    display: flex;
    align-items: center;
    gap: 6px;
  `,
  badge: css`
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 1px 6px;
    border-radius: 10px;
    font-size: 11px;
    font-weight: 500;
    line-height: 18px;

    &.valid {
      color: ${token.colorSuccess};
      background: ${token.colorSuccessBg};
    }

    &.invalid {
      color: ${token.colorError};
      background: ${token.colorErrorBg};
    }
  `,
  formatBtn: css`
    display: inline-flex;
    align-items: center;
    gap: 3px;
    padding: 1px 8px;
    height: 22px;
    font-size: 11px;
    border: 1px solid ${token.colorBorder};
    border-radius: ${token.borderRadius}px;
    background: ${token.colorBgContainer};
    color: ${token.colorText};
    cursor: pointer;
    transition: all 0.15s;

    &:hover:not(:disabled) {
      border-color: ${token.colorPrimary};
      color: ${token.colorPrimary};
    }

    &:disabled {
      cursor: not-allowed;
      opacity: 0.4;
    }
  `,
  editorWrap: css`
    flex: 1;
    overflow: hidden;
    /* 覆盖 monaco 默认背景，与 antd token 统一 */

    .monaco-editor,
    .monaco-editor-background,
    .monaco-editor .margin {
      background-color: transparent !important;
    }
  `,
  errorBar: css`
    display: flex;
    align-items: flex-start;
    gap: 6px;
    padding: 5px 10px;
    font-size: 12px;
    color: ${token.colorError};
    background: ${token.colorErrorBg};
    border-top: 1px solid ${token.colorErrorBorder};
    line-height: 1.5;
    word-break: break-all;
  `,
}));

/** 任意值 → 格式化 JSON 字符串 */
function stringify(val: unknown): string {
  if (val === undefined || val === null) return '';
  if (typeof val === 'string') {
    try {
      return JSON.stringify(JSON.parse(val), null, 2);
    } catch {
      return val;
    }
  }
  try {
    return JSON.stringify(val, null, 2);
  } catch {
    return String(val);
  }
}

/** 解析字符串，返回 [parsed, errorMsg] */
function safeParse(
  raw: string,
): [Record<string, unknown> | unknown[] | null, string | null] {
  const trimmed = raw.trim();
  if (!trimmed) return [null, null];
  try {
    const parsed = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null) {
      return [null, '顶层必须是 JSON 对象 {} 或数组 []'];
    }
    return [parsed as Record<string, unknown> | unknown[], null];
  } catch (e) {
    return [null, e instanceof SyntaxError ? e.message : 'JSON 格式错误'];
  }
}

/** SVG 图标（避免引入 @ant-design/icons 增加 bundle 体积） */
const IconCheck = () => (
  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
    <path
      d="M2 6l3 3 5-5"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

const IconError = () => (
  <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
    <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1.2"/>
    <path
      d="M6 3.5v3M6 8.5v.5"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
    />
  </svg>
);

const IconFormat = () => (
  <svg width="11" height="11" viewBox="0 0 12 12" fill="none">
    <path
      d="M2 3h8M2 6h5M2 9h7"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinecap="round"
    />
  </svg>
);

// 主组件：JSON 编辑器，支持 JSON 格式化和错误检查
const JsonEditor = forwardRef<JsonEditorRef, JsonEditorProps>((props, ref) => {
  const {
    value,
    defaultValue,
    onChange,
    onBlur,
    readOnly = false,
    placeholder,
    height = 300,
    className,
    style,
    showToolbar = true,
    showError = true,
    editorOptions,
    onMount,
    onValidate,
  } = props;

  const {styles, cx} = useStyles();
  const {token} = theme.useToken();
  // 判断受控模式，null/undefined 都不算受控
  const isControlled = value !== null;
  // 初始字符串
  const getInitialStr = () => {
    const source = (isControlled && value != null) ? value : defaultValue;
    if (source !== undefined && source !== null && source !== '') {
      return stringify(source);
    }
    // 空值时用 placeholder 作为示例（只读模式下也展示）
    return placeholder !== undefined ? stringify(placeholder) : '';
  };

  const [internalStr, setInternalStr] = useState<string>(getInitialStr);
  const [error, setError] = useState<string | null>(null);
  const [isValid, setIsValid] = useState<boolean>(true);
  const editorRef = useRef<IStandaloneCodeEditor | null>(null);

  /**
   * 防回环机制：
   * 记录最后一次由 onChange 上报给父组件的字符串。
   * useEffect 中若 stringify(value) === lastReportedStrRef，
   * 说明是自身编辑回传，跳过同步，避免光标跳动。
   * 若不同，说明是外部真实变化（如切换会话），强制同步。
   */
  const lastReportedStrRef = useRef<string | null>(null);

  // ── 受控模式：外部 value 变化 → 同步编辑器 ──
  useEffect(() => {
    if (!isControlled) return;

    const next = stringify(value);

    // 是自身编辑回传：内容没变，跳过，避免光标跳动
    if (next === lastReportedStrRef.current) {
      return;
    }

    // 外部真实变化（切换会话 / 外部 reset 等）：强制同步
    lastReportedStrRef.current = null; // 清除，避免污染下次判断
    setInternalStr(next);
    setError(null);
    setIsValid(true);

    // 同步到 monaco 实例（避免光标跳动，用 model.setValue）
    const model = editorRef.current?.getModel();
    if (model && model.getValue() !== next) {
      model.setValue(next);
    }
  }, [value, isControlled]);

  // ── 内容变化处理 ──
  const handleChange = useCallback(
    (raw: string = '') => {
      setInternalStr(raw);
      const [parsed, err] = safeParse(raw);
      const valid = err === null && (raw.trim() === '' || parsed !== null);
      setError(raw.trim() === '' ? null : err);
      setIsValid(raw.trim() === '' ? true : valid);
      onValidate?.(valid, err);

      if (isControlled) {
        // 记录本次上报的字符串，用于 useEffect 中识别回传
        lastReportedStrRef.current = raw;
      }

      onChange?.(parsed, raw);
    },
    [onChange, onValidate, isControlled],
  );

  // ── 格式化 ──
  const handleFormat = useCallback(() => {
    const model = editorRef.current?.getModel();
    if (!model) return;
    const raw = model.getValue();
    try {
      const formatted = JSON.stringify(JSON.parse(raw), null, 2);
      model.setValue(formatted);
      setInternalStr(formatted);
      setError(null);
      setIsValid(true);
      if (isControlled) {
        lastReportedStrRef.current = formatted;
      }
    } catch {
      // 格式化失败，保持原样
    }
  }, [isControlled]);

  // ── editor mount ──
  const handleMount: OnMount = useCallback(
    (editor, monacoInstance) => {
      editorRef.current = editor;
      // 注册 Shift+Alt+F 格式化快捷键
      editor.addCommand(
        monacoInstance.KeyMod.Shift |
        monacoInstance.KeyMod.Alt |
        monacoInstance.KeyCode.KeyF,
        handleFormat,
      );
      onMount?.(editor, monacoInstance);
      // 注册 blur 事件，组件卸载时自动销毁
      const blurDisposable = editor.onDidBlurEditorWidget(() => {
        const raw = editor.getValue();
        const [parsed] = safeParse(raw);
        onBlur?.(parsed, raw);
      });
      return () => {
        blurDisposable.dispose();
      };
    },
    [handleFormat, onMount, onBlur],
  );

  // ── 暴露 ref API ──
  useImperativeHandle(
    ref,
    () => ({
      getRawValue: () => editorRef.current?.getModel()?.getValue() ?? '',
      getParsedValue: () =>
        safeParse(editorRef.current?.getModel()?.getValue() ?? '')[0],
      format: handleFormat,
      focus: () => editorRef.current?.focus(),
      editor: editorRef.current,
    }),
    [handleFormat],
  );

  // ── 主题映射 ──
  const monacoTheme =
    token.colorBgBase === '#000' || token.colorBgBase === '#141414'
      ? 'vs-dark'
      : 'light';
  // ── 最终展示值 ──
  // 受控模式用外部派生的 internalStr；非受控用本地 state
  const displayValue = internalStr;

  return (
    <div
      className={cx(
        styles.root,
        error ? 'is-error' : undefined,
        readOnly ? 'is-readonly' : undefined,
        className,
      )}
      style={style}
    >
      {/* ── 工具栏 ── */}
      {showToolbar && (
        <div className={styles.toolbar}>
          <div className={styles.toolbarLeft}>
            <span>JSON</span>
            {displayValue.trim() && (
              <span className={cx(styles.badge, isValid ? 'valid' : 'invalid')}>
                {isValid ? <IconCheck/> : <IconError/>}
                {isValid ? '合法' : '非法'}
              </span>
            )}
          </div>
          <div className={styles.toolbarRight}>
            {!readOnly && (
              <button
                type="button"
                className={styles.formatBtn}
                onClick={handleFormat}
                disabled={!!error || !displayValue.trim()}
                title="美化 (Shift+Alt+F)"
              >
                <IconFormat/>
                美化
              </button>
            )}
          </div>
        </div>
      )}

      {/* ── 编辑器主体 ── */}
      <div className={styles.editorWrap}>
        <Editor
          height={height}
          language="json"
          value={displayValue}
          onChange={handleChange}
          onMount={handleMount}
          options={{
            readOnly,
            minimap: {enabled: false},
            fontSize: 13,
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
            automaticLayout: true,
            formatOnPaste: true,
            tabSize: 2,
            wordWrap: 'on',
            folding: true,
            lineDecorationsWidth: 6,
            lineNumbersMinChars: 3,
            overviewRulerLanes: 0,
            hideCursorInOverviewRuler: true,
            overviewRulerBorder: false,
            scrollbar: {
              verticalScrollbarSize: 6,
              horizontalScrollbarSize: 6,
            },
            ...editorOptions,
          }}
          theme={monacoTheme}
        />
      </div>

      {/* ── 错误信息栏 ── */}
      {showError && error && (
        <div className={styles.errorBar}>
          <IconError/>
          <span>{error}</span>
        </div>
      )}
    </div>
  );
});

JsonEditor.displayName = 'JsonEditor';

export default JsonEditor;
