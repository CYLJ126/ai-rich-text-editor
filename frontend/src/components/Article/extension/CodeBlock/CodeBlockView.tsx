import {
  NodeViewContent,
  NodeViewWrapper,
  ReactNodeViewProps,
} from '@tiptap/react';
import { common } from 'lowlight';
import { useEffect, useMemo, useState } from 'react';
import { Button, Input, Popover } from 'antd';
import { i18nText } from '@/utils/i18n';
import {
  CheckOutlined,
  CopyOutlined,
  DeleteOutlined,
  DownOutlined,
  UpOutlined,
} from '@ant-design/icons';
import { createStyles } from 'antd-style';

const useStyles = createStyles(({ css, token }) => ({
  wrapper: css`
    margin: 1.5rem 0;
    border-radius: ${token.borderRadius}px;
    overflow: hidden;
    border: 1px solid #374151;
    font-family: "Fira Code", "Cascadia Code", "JetBrains Mono", monospace;
  `,

  header: css`
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    background-color: #151f2d;
    border-bottom: 1px solid #414e65;
  `,

  langTrigger: css`
    display: flex;
    align-items: center;
    gap: 4px;
    color: #9ca3af;
    cursor: pointer;
    font-size: 13px;
    padding: 2px 6px;
    border-radius: 4px;
    border: none;
    background: transparent;
    transition: color 0.2s, background 0.2s;

    &:hover {
      color: #f3f4f6;
      background: #374151;
    }
  `,

  headerActions: css`
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 8px;
  `,

  iconBtn: css`
    color: #9ca3af !important;
    background: transparent !important;
    border: none !important;
    box-shadow: none !important;
    padding: 4px !important;
    height: auto !important;
    line-height: 1 !important;

    &:hover {
      color: #f3f4f6 !important;
    }

    &:disabled {
      opacity: 0.5 !important;
      cursor: not-allowed !important;
    }
  `,

  codeBody: css`
    display: flex;
    background-color: #1f2937;
    overflow-x: auto;
    /* 隐藏滚动条但保留滚动 */
    scrollbar-width: thin;
    scrollbar-color: #374151 transparent;

    &::-webkit-scrollbar {
      height: 6px;
    }
    &::-webkit-scrollbar-track {
      background: transparent;
    }
    &::-webkit-scrollbar-thumb {
      background-color: #374151;
      border-radius: 3px;
    }
  `,

  lineNumbers: css` // 行号样式
    position: sticky;
    left: 0;
    top: 0;
    padding: 12px 8px 12px 8px;
    background-color: #1f2937;
    color: rgba(156, 163, 175, 0.6);
    text-align: right;
    user-select: none;
    font-size: 14px;
    line-height: 1.5;
    min-width: 50px;
    border-right: 1px solid #374151;
  `,

  lineNumber: css`
    display: block;
    line-height: 1.5;
  `,

  pre: css`
    margin: 0 !important;
    padding: 12px !important;
    background: transparent !important;
    font-size: 14px !important;
    line-height: 1.5 !important;
    flex: 1;
    overflow: visible;
  `,

  code: css`
    background: transparent !important;
    color: #e5e7eb !important;
    font-family: inherit !important;
    font-size: inherit !important;
    white-space: pre !important;
    padding: 0 !important;
  `,

  // 下拉内容
  popoverContent: css`
    padding: 0;
    width: 160px;
  `,

  searchInput: css`
    margin: 6px;
    width: calc(100% - 12px);
  `,

  langList: css`
    max-height: 240px;
    overflow-y: auto;
    padding-left: 3px;

    scrollbar-width: thin;
    scrollbar-color: #d9d9d9 transparent;
    &::-webkit-scrollbar {
      width: 4px;
    }
    &::-webkit-scrollbar-thumb {
      background-color: #d9d9d9;
      border-radius: 2px;
    }
  `,

  langItem: css`
    padding: 5px 8px;
    border-radius: 4px;
    cursor: pointer;
    font-size: 13px;
    transition: background 0.15s;

    &:hover {
      background: var(--ant-color-fill-secondary);
    }
  `,

  langItemActive: css`
    background: var(--ant-color-fill-secondary);
    color: var(--ant-color-primary);
    font-weight: 500;
  `,
}));

// 代码扩展 - 代码块视图
const CodeBlockView = ({
  editor,
  extension,
  node,
  getPos,
}: ReactNodeViewProps) => {
  const { styles: s, cx } = useStyles();

  const [search, setSearch] = useState('');
  const [popoverOpen, setPopoverOpen] = useState(false);
  const [isCopied, setCopied] = useState(false);
  const [lineCount, setLineCount] = useState(1);
  // false-代码块正常显示；true-代码块折叠
  const [foldFlag, setFoldFlag] = useState(false);

  // 语言列表
  const languages = useMemo(() => {
    return Object.keys(common).sort();
  }, []);

  // 行数统计
  useEffect(() => {
    const lines = node.textContent.split('\n');
    setLineCount(lines.length || 1);
  }, [node.textContent]);

  const languageClassPrefix =
    extension.options.languageClassPrefix ?? 'language-';
  const language: string = node.attrs.language ?? 'plaintext';

  // 过滤语言列表
  const filteredLanguages = useMemo(() => {
    if (!search.trim()) return languages;
    return languages.filter((l) =>
      l.toLowerCase().includes(search.toLowerCase()),
    );
  }, [languages, search]);

  // 复制代码
  const handleCopy = () => {
    if (isCopied) return;
    navigator.clipboard.writeText(node.textContent).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  // 删除节点
  const handleDelete = () => {
    const pos = getPos();
    if (pos === undefined) return;
    editor
      .chain()
      .focus()
      .command(({ tr }) => {
        tr.delete(pos, pos + node.nodeSize);
        return true;
      })
      .run();
  };

  // 切换语言
  const handleSelectLanguage = (lang: string) => {
    editor
      .chain()
      .focus(undefined, { scrollIntoView: false })
      .setCodeBlock({ language: lang })
      .run();
    setPopoverOpen(false);
    setSearch('');
  };

  // 语言选择器下拉内容
  const popoverContent = (
    <div className={s.popoverContent}>
      <Input
        size="small"
        placeholder={i18nText('app.common.search.placeholder')}
        className={s.searchInput}
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        allowClear
        autoFocus
      />
      <div className={s.langList}>
        {filteredLanguages.length === 0 ? (
          <div style={{ padding: '8px', color: '#999', fontSize: 12 }}>
            {i18nText('app.common.noResults')}
          </div>
        ) : (
          filteredLanguages.map((l) => (
            <div
              key={l}
              className={cx(s.langItem, l === language && s.langItemActive)}
              onClick={() => handleSelectLanguage(l)}
            >
              {l}
            </div>
          ))
        )}
      </div>
    </div>
  );

  return (
    <NodeViewWrapper>
      <div className={s.wrapper}>
        {/* 顶栏 */}
        <div className={s.header}>
          {/* 语言选择器 */}
          <Popover
            open={popoverOpen}
            onOpenChange={(open) => {
              setPopoverOpen(open);
              if (open) setSearch('');
            }}
            content={popoverContent}
            trigger="click"
            placement="bottomLeft"
            styles={{ container: { padding: 0 } }}
            arrow={false}
          >
            <button className={s.langTrigger} type="button">
              <span>{language}</span>
              <DownOutlined style={{ fontSize: 11 }} />
            </button>
          </Popover>

          {/* 右侧操作按钮 */}
          <div className={s.headerActions}>
            {/* 复制 */}
            <Button
              type="text"
              size="small"
              className={s.iconBtn}
              disabled={isCopied}
              onClick={handleCopy}
              icon={
                isCopied ? (
                  <CheckOutlined style={{ color: '#4ade80' }} />
                ) : (
                  <CopyOutlined />
                )
              }
              title={i18nText('app.article.code.copy')}
            />
            {/* 删除 */}
            <Button
              type="text"
              size="small"
              className={s.iconBtn}
              onClick={handleDelete}
              icon={<DeleteOutlined />}
              title={i18nText('app.article.code.delete')}
            />
            {/* 展开收起 */}
            <Button
              type="text"
              size="small"
              className={s.iconBtn}
              onClick={() => {
                setFoldFlag(!foldFlag);
              }}
              icon={foldFlag ? <DownOutlined /> : <UpOutlined />}
              title={i18nText('app.article.code.fold')}
            />
          </div>
        </div>

        {/* 代码区域 */}
        {!foldFlag && (
          <div className={s.codeBody}>
            {/* 行号 */}
            <div className={s.lineNumbers}>
              {Array.from({ length: lineCount }, (_, i) => (
                <span key={i} className={s.lineNumber}>
                  {i + 1}
                </span>
              ))}
            </div>

            {/* 代码内容 */}
            <pre className={s.pre}>
              <code
                className={cx(
                  s.code,
                  language ? `${languageClassPrefix}${language}` : undefined,
                )}
              >
                <NodeViewContent
                  style={{ whiteSpace: 'pre', display: 'block' }}
                />
              </code>
            </pre>
          </div>
        )}
      </div>
    </NodeViewWrapper>
  );
};

export default CodeBlockView;
