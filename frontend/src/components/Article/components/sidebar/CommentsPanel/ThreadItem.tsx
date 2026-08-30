import {i18nText} from '@/utils/i18n';
import {CheckOutlined, CommentOutlined, DeleteOutlined, RollbackOutlined,} from '@ant-design/icons';
import {Button, message, Tag} from 'antd';
import React, {useCallback, useState} from 'react';
import {
  type CommentRecord,
  type CommentThread,
  hoverOffThread,
  hoverThread,
} from '@/components/Article/extension/comments/commentsExtension';
import {
  addComment as requestAddComment,
  deleteComment as requestDeleteComment,
  deleteCommentThread as requestDeleteCommentThread,
  resolveCommentThread as requestResolveCommentThread,
  unresolveCommentThread as requestUnresolveCommentThread,
  updateComment as requestUpdateComment,
} from '@/services/ant-design-pro/richText';
import CommentCard from './CommentCard';
import ReplyComposer from './ReplyComposer';
import {formatCommentTime} from './utils';
import {useArticleInfoStore, useEditorStore} from "@/components/Article";


type ThreadItemProps = {
  thread: CommentThread;
  articleId?: number;
  canComment: boolean;
  currentUserName?: string;
  articleAuthor?: string;
  active: boolean;
  open: boolean;
  onSelect: (threadId: string) => void;
  onReload: () => Promise<void>;
  onSave?: (contentJson: string | undefined) => Promise<boolean>;
};

export default function ThreadItem({
  thread,
  articleId,
  canComment,
  currentUserName,
  articleAuthor,
  active,
  open,
  onSelect,
  onReload,
                                     onSave,
}: ThreadItemProps) {
  const editor = useEditorStore(state => state.editor);
  const commentsProvider = useArticleInfoStore(state => state.commentsProvider);

  const comments = commentsProvider.getThreadComments(thread.id, true);
  const firstComment = comments[0];
  const repliesCount = Math.max(0, comments.length - 1);
  const [threadSubmitting, setThreadSubmitting] = useState(false);
  const isArticleAuthor =
    Boolean(articleAuthor) && articleAuthor === currentUserName;
  const isResolved = Boolean(thread.resolvedAt);
  const canDeleteThread =
    canComment &&
    !isResolved &&
    (isArticleAuthor || firstComment?.data.userName === currentUserName);

  const requireArticleId = useCallback(() => {
    if (!canComment) {
      message.warning(i18nText("app.article.commentspanel.threaditem.28165fbc")).then();
      return;
    }
    if (!articleId) {
      message.warning(i18nText("app.article.commentspanel.threaditem.c7e5e32e")).then();
      return;
    }
    return articleId;
  }, [articleId, canComment]);

  const handleResolve = useCallback(async () => {
    const currentArticleId = requireArticleId();
    if (!currentArticleId) return;

    setThreadSubmitting(true);
    try {
      await requestResolveCommentThread(currentArticleId, thread.id);
      await onReload();
    } finally {
      setThreadSubmitting(false);
    }
  }, [onReload, requireArticleId, thread.id]);

  const handleUnresolve = useCallback(async () => {
    const currentArticleId = requireArticleId();
    if (!currentArticleId) return;

    setThreadSubmitting(true);
    try {
      await requestUnresolveCommentThread(currentArticleId, thread.id);
      await onReload();
    } finally {
      setThreadSubmitting(false);
    }
  }, [onReload, requireArticleId, thread.id]);

  const handleDeleteThread = useCallback(async () => {
    const currentArticleId = requireArticleId();
    if (!currentArticleId) return;

    setThreadSubmitting(true);
    try {
      await requestDeleteCommentThread(currentArticleId, thread.id);
      editor?.commands.removeThread({id: thread.id});
      await onSave?.(undefined);
      await onReload();
    } finally {
      setThreadSubmitting(false);
    }
  }, [
    onReload,
    requireArticleId,
    onSave,
    thread.id,
  ]);

  const handleUpdateComment = useCallback(
    async (commentId: string, content: string) => {
      const currentArticleId = requireArticleId();
      if (!currentArticleId) return;

      await requestUpdateComment({
        articleId: currentArticleId,
        threadId: thread.id,
        commentId,
        content,
      });
      await onReload();
    },
    [onReload, requireArticleId, thread.id],
  );

  const handleDeleteComment = useCallback(
    async (commentId: string) => {
      const currentArticleId = requireArticleId();
      if (!currentArticleId) return;

      await requestDeleteComment({
        articleId: currentArticleId,
        threadId: thread.id,
        commentId,
        deleteContent: true,
      });
      await onReload();
    },
    [onReload, requireArticleId, thread.id],
  );

  const handleReply = useCallback(
    async (content: string) => {
      const currentArticleId = requireArticleId();
      if (!currentArticleId) return;

      await requestAddComment({
        articleId: currentArticleId,
        threadId: thread.id,
        content,
      });
      await onReload();
    },
    [onReload, requireArticleId, thread.id],
  );

  const handleSelectComment = useCallback(
    (comment: CommentRecord) => {
      console.debug('[comments] selected comment/reply', {
        thread,
        comment,
        comments,
      });
      onSelect(thread.id);
    },
    [comments, onSelect, thread],
  );

  return (
    <div
      onMouseEnter={() => hoverThread(editor, [thread.id])}
      onMouseLeave={() => hoverOffThread(editor)}
    >
      <div
        className={[
          'comment-thread-card cursor-pointer rounded-md border bg-[var(--ant-color-bg-container)] p-3',
          active
            ? 'border-[var(--ant-color-primary)] shadow-sm'
            : 'border-[var(--ant-color-border-secondary)] hover:border-[var(--ant-color-primary-border)]',
          thread.resolvedAt ? 'bg-[var(--ant-color-fill-quaternary)]' : '',
        ].join(' ')}
        onClick={() => {
          console.debug('[comments] selected thread', {
            thread,
            comments,
          });
          onSelect(thread.id);
        }}
      >
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <CommentOutlined className="text-blue-500" />
            <span className="text-sm font-medium text-[var(--ant-color-text)]">
              {firstComment?.data.userName || i18nText("app.article.commentspanel.threaditem.5e0613b9")}
            </span>
          </div>
          <Tag color={thread.resolvedAt ? 'success' : 'processing'}>
            {thread.resolvedAt ? i18nText("app.article.commentspanel.threaditem.1650959b") : i18nText("app.article.commentspanel.threaditem.8779bb88")}
          </Tag>
        </div>

        {firstComment ? (
          <div className="mt-3">
            <CommentCard
              comment={firstComment}
              canEdit={
                canComment &&
                !isResolved &&
                firstComment.data.userName === currentUserName
              }
              canDelete={
                canComment &&
                !isResolved &&
                (isArticleAuthor ||
                  firstComment.data.userName === currentUserName)
              }
              onUpdate={(value) => handleUpdateComment(firstComment.id, value)}
              onDelete={() => handleDeleteComment(firstComment.id)}
              onSelect={handleSelectComment}
            />
          </div>
        ) : null}

        {!open && repliesCount > 0 ? (
          <div className="mt-2 text-xs text-[var(--ant-color-text-tertiary)]">
            {repliesCount} {i18nText("app.article.commentspanel.threaditem.6538a581")}
          </div>
        ) : null}

        {open ? (
          <>
            <div className="mt-3 flex flex-wrap justify-end gap-2">
              {thread.resolvedAt && canComment ? (
                <Button
                  size="small"
                  icon={<RollbackOutlined />}
                  loading={threadSubmitting}
                  disabled={threadSubmitting}
                  onClick={(event) => {
                    event.stopPropagation();
                    handleUnresolve().then();
                  }}
                >
                  {i18nText("app.article.commentspanel.threaditem.f72272d7")}
                </Button>
              ) : null}
              {!thread.resolvedAt && canComment ? (
                <Button
                  size="small"
                  icon={<CheckOutlined />}
                  loading={threadSubmitting}
                  disabled={threadSubmitting}
                  onClick={(event) => {
                    event.stopPropagation();
                    handleResolve().then();
                  }}
                >
                  {i18nText("app.article.commentspanel.threaditem.0c7ad594")}
                </Button>
              ) : null}
              {canDeleteThread ? (
                <Button
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                  loading={threadSubmitting}
                  disabled={threadSubmitting}
                  onClick={(event) => {
                    event.stopPropagation();
                    handleDeleteThread().then();
                  }}
                >
                  {i18nText("app.article.commentspanel.threaditem.16f6df6c")}
                </Button>
              ) : null}
            </div>

            {thread.resolvedAt ? (
              <div className="mt-3 rounded bg-[var(--ant-color-success-bg)] px-3 py-2 text-xs text-[var(--ant-color-success-text)]">
                {i18nText("app.article.commentspanel.threaditem.97b77a92")} {formatCommentTime(thread.resolvedAt)}
              </div>
            ) : null}

            {comments.slice(1).length > 0 ? (
              <div className="mt-3 flex flex-col gap-2">
                {comments.slice(1).map((comment) => (
                  <CommentCard
                    key={comment.id}
                    comment={comment}
                    canEdit={
                      canComment &&
                      !isResolved &&
                      comment.data.userName === currentUserName
                    }
                    canDelete={
                      canComment &&
                      !isResolved &&
                      (isArticleAuthor ||
                        comment.data.userName === currentUserName)
                    }
                    onUpdate={(value) => handleUpdateComment(comment.id, value)}
                    onDelete={() => handleDeleteComment(comment.id)}
                    onSelect={handleSelectComment}
                  />
                ))}
              </div>
            ) : null}

            {!thread.resolvedAt ? (
              <ReplyComposer canSubmit={canComment} onSubmit={handleReply} />
            ) : null}
          </>
        ) : null}
      </div>
    </div>
  );
}
