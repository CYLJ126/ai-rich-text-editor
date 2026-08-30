import {i18nText} from '@/utils/i18n';
import { CommentOutlined } from '@ant-design/icons';
import { useModel } from '@umijs/max';
import { Button, Empty, Input, message, Radio, Tag, Tooltip } from 'antd';
import type { TextAreaRef } from 'antd/es/input/TextArea';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useArticleInfoStore, useEditorStore } from '@/components/Article';
import { extractHeadingsFromDoc } from '@/components/Article/components/sidebar/TableOfContents';
import {
  COMMENT_COMPOSER_OPEN_EVENT,
  COMMENT_THREAD_CLICK_EVENT,
  type CommentThread,
  getCommentThreadOrder,
} from '@/components/Article/extension/comments/commentsExtension';
import {
  createCommentThread as requestCreateCommentThread,
  deleteCommentThread as requestDeleteCommentThread,
  listCommentThreads as requestListCommentThreads,
  updateArticleCommentMarks,
} from '@/services/ant-design-pro/richText';
import styles from './index.less';
import ThreadItem from './ThreadItem';
import type { BackendCommentThread, CommentCurrentUser } from './types';
import { mapBackendThread, scrollCommentThreadIntoView, toTime } from './utils';

const { TextArea } = Input;

export default function CommentsPanel({ active }: { active: boolean }) {
  const editor = useEditorStore((state) => state.editor);
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);
  const setArticleInfo = useArticleInfoStore((state) => state.setArticleInfo);
  const saveArticle = useArticleInfoStore((state) => state.saveArticle);
  const savingState = useArticleInfoStore((state) => state.savingState);
  const commentsProvider = useArticleInfoStore(
    (state) => state.commentsProvider,
  );
  const operationMode = useEditorStore((state) => state.operationMode);

  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser as
    | CommentCurrentUser
    | undefined;
  const currentUserName = currentUser?.userName || currentUser?.name;
  const articleId = articleInfo?.id;
  const articleAuthor = articleInfo?.createBy;
  const canComment = Boolean(articleInfo?.canComment || articleInfo?.canWrite);
  const canComposeComment =
    operationMode === 'edit' || operationMode === 'revise';

  const [threads, setThreads] = useState<CommentThread[]>([]);
  const [showUnresolved, setShowUnresolved] = useState(true);
  const [selectedThread, setSelectedThread] = useState<string>();
  const [selectionEmpty, setSelectionEmpty] = useState(true);
  const [commentValue, setCommentValue] = useState('');
  const [docVersion, setDocVersion] = useState(0);
  const [creatingThread, setCreatingThread] = useState(false);
  const composerRef = useRef<HTMLDivElement>(null);
  const commentInputRef = useRef<TextAreaRef>(null);

  const loadThreads = useCallback(async () => {
    if (!articleId || !canComment) {
      commentsProvider.replaceThreads([]);
      setSelectedThread(undefined);
      return;
    }

    const result = await requestListCommentThreads(articleId);
    const backendThreads = (Array.isArray(result) ? result : []).map(
      mapBackendThread,
    );
    commentsProvider.replaceThreads(
      backendThreads.filter((thread) => thread.id),
    );
  }, [articleId, canComment, commentsProvider]);

  useEffect(() => {
    return commentsProvider.subscribe((currentThreads) => {
      setThreads(currentThreads);
    });
  }, [commentsProvider]);

  useEffect(() => {
    if (!active) {
      commentsProvider.replaceThreads([]);
      setSelectedThread(undefined);
      return;
    }
    loadThreads().then();
  }, [active, commentsProvider, loadThreads]);

  useEffect(() => {
    if (!editor) return;

    const syncSelection = () => {
      setSelectionEmpty(editor.state.selection.empty);
    };
    const syncDocOrder = () => {
      setDocVersion((version) => version + 1);
    };

    syncSelection();
    editor.on('selectionUpdate', syncSelection);
    editor.on('transaction', syncDocOrder);

    return () => {
      editor.off('selectionUpdate', syncSelection);
      editor.off('transaction', syncDocOrder);
    };
  }, [editor]);

  const orderedThreads = useMemo(() => {
    const threadOrder = getCommentThreadOrder(editor);
    const orderMap = new Map(
      threadOrder.map((threadId, index) => [threadId, index]),
    );

    return [...threads].sort((first, second) => {
      const firstOrder = orderMap.get(first.id) ?? Number.MAX_SAFE_INTEGER;
      const secondOrder = orderMap.get(second.id) ?? Number.MAX_SAFE_INTEGER;

      if (firstOrder !== secondOrder) return firstOrder - secondOrder;
      return toTime(first.createdAt) - toTime(second.createdAt);
    });
  }, [threads, docVersion]);

  const filteredThreads = useMemo(
    () =>
      orderedThreads.filter((thread) =>
        showUnresolved ? !thread.resolvedAt : Boolean(thread.resolvedAt),
      ),
    [orderedThreads, showUnresolved],
  );

  useEffect(() => {
    if (
      selectedThread &&
      !filteredThreads.some((thread) => thread.id === selectedThread)
    ) {
      setSelectedThread(undefined);
    }
  }, [filteredThreads, selectedThread]);

  useEffect(() => {
    const handleCommentThreadClick = (event: Event) => {
      const threadId = (event as CustomEvent<{ threadId?: string }>).detail
        ?.threadId;
      if (!threadId) return;

      const thread = threads.find((item) => item.id === threadId);
      if (thread) {
        setShowUnresolved(!thread.resolvedAt);
      }
      setSelectedThread(threadId);
    };

    window.addEventListener(
      COMMENT_THREAD_CLICK_EVENT,
      handleCommentThreadClick,
    );
    return () => {
      window.removeEventListener(
        COMMENT_THREAD_CLICK_EVENT,
        handleCommentThreadClick,
      );
    };
  }, [threads]);

  useEffect(() => {
    const handleOpenComposer = () => {
      requestAnimationFrame(() => commentInputRef.current?.focus());
    };

    window.addEventListener(COMMENT_COMPOSER_OPEN_EVENT, handleOpenComposer);
    return () => {
      window.removeEventListener(
        COMMENT_COMPOSER_OPEN_EVENT,
        handleOpenComposer,
      );
    };
  }, []);

  const saveArticleCommentMarks = async (contentJson: string | undefined) => {
    if (!articleInfo?.id) {
      console.warn('文章 ID 不存在，无法保存批注标记');
      return false;
    }
    if (!articleInfo?.canComment && !articleInfo?.canWrite) {
      console.warn('文章权限不足，无法保存批注标记');
      return false;
    }
    const jsonText = contentJson ?? JSON.stringify(editor?.getJSON());
    const result = await updateArticleCommentMarks({
      id: articleInfo.id,
      contentJson: jsonText,
    });
    if (result) {
      const newArticle = {
        ...articleInfo,
        contentJson: jsonText,
        headings: extractHeadingsFromDoc(editor?.getJSON()),
      };
      setArticleInfo(newArticle);
      editor?.commands?.setArticleInfo(newArticle);
      return true;
    }
    message.warning(i18nText("app.article.commentspanel.605e86df")).then();
    return false;
  };

  const handleCreateThread = useCallback(async () => {
    if (!commentValue.trim()) {
      message.info(i18nText("app.article.commentspanel.5de5651e")).then();
      return;
    }
    if (!canComment) {
      message.warning(i18nText("app.article.commentspanel.1dfa2171")).then();
      return;
    }
    if (!articleId) {
      message.warning(i18nText("app.article.commentspanel.29e3dc27")).then();
      return;
    }

    if (!editor || editor.state.selection.empty) {
      message.info(i18nText("app.article.commentspanel.e6ffee38")).then();
      return;
    }

    if (operationMode === 'edit' && savingState === 1) {
      message.info(i18nText("app.article.commentspanel.099efd24")).then();
      return;
    }
    if (operationMode === 'edit' && savingState === 4) {
      setCreatingThread(true);
      const saved = await saveArticle(editor, 'auto');
      setCreatingThread(false);
      if (!saved) {
        message.warning(i18nText("app.article.commentspanel.a9a21514")).then();
        return;
      }
    }
    if (editor.state.selection.empty) {
      message.info(i18nText("app.article.commentspanel.2c8a502f")).then();
      return;
    }

    const { from, to } = editor.state.selection;
    const commentMarkType = editor.schema.marks.comments;
    if (!commentMarkType) {
      message.error(i18nText("app.article.commentspanel.e4bf81a9")).then();
      return;
    }

    setCreatingThread(true);
    let createdThreadId: string | undefined;
    try {
      const thread = (await requestCreateCommentThread({
        articleId,
        content: commentValue.trim(),
      })) as BackendCommentThread | undefined;
      const threadId = thread?.threadId;
      createdThreadId = threadId;

      if (!threadId) {
        message.error(i18nText("app.article.commentspanel.802c3792")).then();
        return;
      }

      const mark = commentMarkType.create({ threadId });
      const nextDoc = editor.state.tr.addMark(from, to, mark).doc;
      const saveSuccess = await saveArticleCommentMarks(
        JSON.stringify(nextDoc.toJSON()),
      );
      if (!saveSuccess) {
        await requestDeleteCommentThread(articleId, threadId).catch(() => {
          console.warn('回滚批注线程失败', threadId);
        });
        return;
      }

      editor.view.dispatch(editor.state.tr.addMark(from, to, mark));
      editor.view.focus();
      setCommentValue('');
      setSelectedThread(threadId);
      await loadThreads();
    } catch (error) {
      if (createdThreadId) {
        requestDeleteCommentThread(articleId, createdThreadId).catch(() => {
          console.warn('回滚批注线程失败', createdThreadId);
        });
      }
      console.error('创建批注失败', error);
      message.error(i18nText("app.article.commentspanel.802c3792")).then();
    } finally {
      setCreatingThread(false);
    }
  }, [
    articleId,
    canComment,
    commentValue,
    loadThreads,
    operationMode,
    saveArticle,
    savingState,
    saveArticleCommentMarks,
  ]);

  const handleSelectThread = useCallback(
    (threadId: string) => {
      setSelectedThread(threadId);
      editor
        ?.chain()
        .focus()
        .selectThread({ id: threadId, updateSelection: true })
        .run();
      requestAnimationFrame(() => {
        scrollCommentThreadIntoView(editor, threadId);
      });
    },
    [editor],
  );

  const handleComposerFocus = useCallback(() => {
    if (!editor || editor.state.selection.empty) return;
    editor.view.dispatch(editor.state.tr);
  }, [editor]);

  const handleComposerBlur = useCallback(
    (event: React.FocusEvent<HTMLDivElement>) => {
      if (composerRef.current?.contains(event.relatedTarget as Node | null)) {
        return;
      }
      if (!editor || editor.state.selection.empty) return;
      editor.commands.setTextSelection(editor.state.selection.to);
    },
    [editor],
  );

  return (
    <div className={styles.commentsPanel}>
      <div className="flex h-full flex-col bg-[var(--ant-color-bg-container)]">
        <div className="border-b border-[var(--ant-color-border-secondary)] p-3">
          <div className="flex items-center justify-between gap-3">
            <div className="text-[15px] font-semibold text-[var(--ant-color-text)]">
              {i18nText("app.article.commentspanel.6fa3af44")}
            </div>
            <Tag color={showUnresolved ? 'blue' : 'green'}>
              {filteredThreads.length} {i18nText("app.article.commentspanel.090fdf48")}
            </Tag>
          </div>

          <div className="mt-3">
            <Radio.Group
              size="small"
              optionType="button"
              buttonStyle="solid"
              value={showUnresolved ? 'open' : 'resolved'}
              onChange={(event) =>
                setShowUnresolved(event.target.value === 'open')
              }
              options={[
                { label: i18nText("app.article.commentspanel.7c1a276a"), value: 'open' },
                { label: i18nText("app.article.commentspanel.854c2fd4"), value: 'resolved' },
              ]}
            />
          </div>

          <div
            ref={composerRef}
            className="mt-3"
            onFocus={handleComposerFocus}
            onBlur={handleComposerBlur}
          >
            <TextArea
              ref={commentInputRef}
              autoSize={{ minRows: 2, maxRows: 5 }}
              disabled={!canComposeComment || !canComment}
              placeholder={
                !canComment
                  ? i18nText("app.article.commentspanel.1dfa2171")
                  : canComposeComment
                    ? i18nText("app.article.commentspanel.76614cbe")
                    : i18nText("app.article.commentspanel.3abafc40")
              }
              value={commentValue}
              onChange={(event) => setCommentValue(event.target.value)}
            />
            <div className="mt-2 flex items-center justify-between gap-3">
              <span className="text-xs text-[var(--ant-color-text-tertiary)]">
                {selectionEmpty ? i18nText("app.article.commentspanel.945ef85b") : i18nText("app.article.commentspanel.36857387")}
              </span>
              <Tooltip title={selectionEmpty ? i18nText("app.article.commentspanel.e721f0a4") : undefined}>
                <Button
                  type="primary"
                  size="small"
                  icon={<CommentOutlined />}
                  disabled={
                    !canComposeComment ||
                    selectionEmpty ||
                    creatingThread ||
                    !articleId ||
                    !canComment
                  }
                  loading={creatingThread}
                  onClick={() => handleCreateThread().then()}
                >
                  {i18nText("app.article.commentspanel.bd406a8b")}
                </Button>
              </Tooltip>
            </div>
          </div>
        </div>

        <div className="flex-1 overflow-auto p-3">
          {filteredThreads.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={
                showUnresolved ? i18nText("app.article.commentspanel.4c3e55da") : i18nText("app.article.commentspanel.550110b0")
              }
            />
          ) : (
            <div className="flex flex-col gap-3">
              {filteredThreads.map((thread) => (
                <ThreadItem
                  key={thread.id}
                  thread={thread}
                  active={selectedThread === thread.id}
                  open={selectedThread === thread.id}
                  onSelect={handleSelectThread}
                  articleId={articleId}
                  canComment={canComment}
                  currentUserName={currentUserName}
                  articleAuthor={articleAuthor}
                  onReload={loadThreads}
                  onSave={saveArticleCommentMarks}
                />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
