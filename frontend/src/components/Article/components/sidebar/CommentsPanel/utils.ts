import type {
  CommentRecord,
  CommentThreadWithComments,
} from '@/components/Article/extension/comments/commentsExtension';
import type {BackendComment, BackendCommentThread} from './types';

export function formatCommentTime(time?: number | string) {
  if (!time) return '';
  const date = new Date(time);
  if (Number.isNaN(date.getTime())) return String(time);
  return date.toLocaleString();
}

export function toTime(value?: number | string) {
  if (!value) return 0;
  if (typeof value === 'number') return value;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function mapBackendComment(comment: BackendComment): CommentRecord {
  return {
    id: comment.commentId ?? '',
    content: comment.content ?? '',
    createdAt: comment.createTime ?? Date.now(),
    updatedAt: comment.updateTime ?? comment.createTime ?? Date.now(),
    deletedAt: comment.deletedAt,
    data: {
      userName: comment.createBy || comment.updateBy || '匿名用户',
    },
  };
}

export function mapBackendThread(
  thread: BackendCommentThread,
): CommentThreadWithComments {
  return {
    id: thread.threadId ?? '',
    createdAt: thread.createTime ?? Date.now(),
    updatedAt: thread.updateTime ?? thread.createTime ?? Date.now(),
    resolvedAt: thread.resolvedAt,
    comments: (thread.comments ?? [])
      .filter((comment) => comment.commentId)
      .map(mapBackendComment),
  };
}

export function scrollCommentThreadIntoView(editor: any, threadId: string) {
  const elements = editor?.view?.dom?.querySelectorAll?.(
    '[data-comment-thread-id]',
  );
  const element = Array.from(elements ?? []).find(
    (item: any) => item.getAttribute('data-comment-thread-id') === threadId,
  ) as HTMLElement | undefined;

  element?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
