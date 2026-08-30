import {i18nText} from '@/utils/i18n';
import {
  ArrowDownOutlined,
  ArrowLeftOutlined,
  ArrowUpOutlined,
} from '@ant-design/icons';
import { DiffEditor, type DiffOnMount } from '@monaco-editor/react';
import { Button, Empty, Select, Space, Spin, Typography } from 'antd';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useThemeContext } from '@/contexts/ThemeContext';
import { getArticleHistoryById } from '@/services/ant-design-pro/richText';
import type { ArticleHistoryVersion, ArticleInfoType } from '@/types/rt.type';

export interface ArticleHistoryCompareProps {
  article: ArticleInfoType;
  currentContent: string;
  versions: ArticleHistoryVersion[];
  selectedHistoryId?: number;
  height: number;
  onExit: () => void;
}

type DiffEditorInstance = Parameters<DiffOnMount>[0];
type VersionValue = 'current' | number;

const ArticleHistoryCompare: React.FC<ArticleHistoryCompareProps> = ({
  article,
  currentContent,
  versions,
  selectedHistoryId,
  height,
  onExit,
}) => {
  const { isDark } = useThemeContext();
  const editorRef = useRef<DiffEditorInstance | null>(null);
  const [historyContents, setHistoryContents] = useState<
    Record<number, string>
  >({});
  const [loading, setLoading] = useState(false);
  const [leftVersion, setLeftVersion] = useState<VersionValue>('current');
  const [rightVersion, setRightVersion] = useState<VersionValue>(
    selectedHistoryId ?? 'current',
  );
  const [differenceIndex, setDifferenceIndex] = useState(-1);
  const [differenceCount, setDifferenceCount] = useState(0);

  useEffect(() => {
    if (selectedHistoryId) {
      setRightVersion(selectedHistoryId);
    }
  }, [selectedHistoryId]);

  useEffect(() => {
    setHistoryContents({});
  }, [article.id]);

  useEffect(() => {
    const historyIds = Array.from(
      new Set(
        [leftVersion, rightVersion].filter(
          (value): value is number => value !== 'current',
        ),
      ),
    );
    const missingIds = historyIds.filter(
      (id) => historyContents[id] === undefined,
    );
    if (missingIds.length === 0) {
      setLoading(false);
      return;
    }

    let disposed = false;
    setLoading(true);
    Promise.all(
      missingIds.map((id) =>
        getArticleHistoryById(id).then(
          (detail) => [id, detail.content ?? ''] as const,
        ),
      ),
    )
      .then((details) => {
        if (!disposed) {
          setHistoryContents((current) => ({
            ...current,
            ...Object.fromEntries(details),
          }));
        }
      })
      .catch(() => undefined)
      .finally(() => {
        if (!disposed) setLoading(false);
      });
    return () => {
      disposed = true;
    };
  }, [historyContents, leftVersion, rightVersion]);

  const options = useMemo(
    () => [
      { label: i18nText("app.article.historysidebar.articlehistorycompare.84f7e479"), value: 'current' as const },
      ...versions.map((version) => ({
        label: `V${version.versionNo} · ${version.modifiedBy} · ${version.modifiedTime}`,
        value: version.id,
      })),
    ],
    [versions],
  );

  const resolveContent = useCallback(
    (value: VersionValue) => {
      if (value === 'current') return currentContent;
      return historyContents[value] ?? '';
    },
    [currentContent, historyContents],
  );

  const originalContent = resolveContent(leftVersion);
  const modifiedContent = resolveContent(rightVersion);
  const comparisonReady = [leftVersion, rightVersion].every(
    (value) => value === 'current' || historyContents[value] !== undefined,
  );

  const refreshDifferences = useCallback(() => {
    const changes = editorRef.current?.getLineChanges() ?? [];
    setDifferenceCount(changes.length);
    setDifferenceIndex(changes.length > 0 ? 0 : -1);
  }, []);

  const handleMount: DiffOnMount = useCallback(
    (editor) => {
      editorRef.current = editor;
      const synchronizedOptions = {
        wordWrap: 'on' as const,
        wordWrapOverride1: 'on' as const,
        wordWrapOverride2: 'on' as const,
        wrappingIndent: 'same' as const,
        wordWrapColumn: 0,
        scrollBeyondLastColumn: 0,
      };
      editor.getOriginalEditor().updateOptions(synchronizedOptions);
      editor.getModifiedEditor().updateOptions(synchronizedOptions);
      editor.updateOptions({ diffWordWrap: 'on' });
      editor.onDidUpdateDiff(refreshDifferences);
      refreshDifferences();
    },
    [refreshDifferences],
  );

  useEffect(() => {
    setDifferenceCount(0);
    setDifferenceIndex(-1);
  }, [leftVersion, rightVersion]);

  const jumpDifference = useCallback(
    (direction: 1 | -1) => {
      const editor = editorRef.current;
      const changes = editor?.getLineChanges() ?? [];
      if (!editor || changes.length === 0) return;
      const nextIndex =
        (differenceIndex + direction + changes.length) % changes.length;
      const change = changes[nextIndex];
      editor
        .getOriginalEditor()
        .revealLineInCenter(change.originalStartLineNumber);
      editor
        .getModifiedEditor()
        .revealLineInCenter(change.modifiedStartLineNumber);
      setDifferenceIndex(nextIndex);
    },
    [differenceIndex],
  );

  if (versions.length === 0) {
    return (
      <div style={{ height, width: '100%', paddingTop: 80 }}>
        <Empty description={i18nText("app.article.historysidebar.articlehistorycompare.1df0ad4d")}>
          <Button onClick={onExit}>{i18nText("app.article.historysidebar.articlehistorycompare.ffa54d79")}</Button>
        </Empty>
      </div>
    );
  }

  return (
    <div style={{ height, width: '100%', minWidth: 0 }}>
      <div
        style={{
          height: 48,
          padding: '8px 12px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          borderBottom: '1px solid var(--ant-color-border-secondary)',
        }}
      >
        <Button icon={<ArrowLeftOutlined />} onClick={onExit}>
          {i18nText("app.article.historysidebar.articlehistorycompare.ffa54d79")}
        </Button>
        <Select
          aria-label={i18nText("app.article.historysidebar.articlehistorycompare.fd9a5c58")}
          style={{ minWidth: 220, flex: 1 }}
          options={options}
          value={leftVersion}
          onChange={(value) => setLeftVersion(value)}
        />
        <Typography.Text type="secondary">{i18nText("app.article.historysidebar.articlehistorycompare.5a096142")}</Typography.Text>
        <Select
          aria-label={i18nText("app.article.historysidebar.articlehistorycompare.b24c8a0a")}
          style={{ minWidth: 220, flex: 1 }}
          options={options}
          value={rightVersion}
          onChange={(value) => setRightVersion(value)}
        />
        <Space.Compact>
          <Button
            aria-label={i18nText("app.article.historysidebar.articlehistorycompare.1146d73c")}
            icon={<ArrowUpOutlined />}
            disabled={differenceCount === 0}
            onClick={() => jumpDifference(-1)}
          />
          <Button
            aria-label={i18nText("app.article.historysidebar.articlehistorycompare.f6f6809a")}
            icon={<ArrowDownOutlined />}
            disabled={differenceCount === 0}
            onClick={() => jumpDifference(1)}
          >
            {differenceCount === 0
              ? i18nText("app.article.historysidebar.articlehistorycompare.9fa8804d")
              : `${differenceIndex + 1}/${differenceCount}`}
          </Button>
        </Space.Compact>
      </div>
      <Spin spinning={loading}>
        {comparisonReady && (
          <DiffEditor
            key={`${article.id}-${leftVersion}-${rightVersion}`}
            height={height - 48}
            language="markdown"
            original={originalContent}
            modified={modifiedContent}
            theme={isDark ? 'vs-dark' : 'light'}
            onMount={handleMount}
            options={{
              automaticLayout: true,
              readOnly: true,
              originalEditable: false,
              renderSideBySide: true,
              diffWordWrap: 'on',
              minimap: { enabled: false },
              wordWrap: 'on',
              wordWrapOverride1: 'on',
              wordWrapOverride2: 'on',
              wrappingIndent: 'same',
              wordWrapColumn: 0,
              scrollBeyondLastColumn: 0,
              scrollBeyondLastLine: false,
            }}
          />
        )}
      </Spin>
    </div>
  );
};

export default ArticleHistoryCompare;
