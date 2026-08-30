import {Mark, mergeAttributes} from '@tiptap/core';
import {Plugin, TextSelection} from '@tiptap/pm/state';

export const COMMENT_THREAD_CLICK_EVENT = 'rich-text-comment-thread-click';
export const COMMENT_COMPOSER_OPEN_EVENT = 'rich-text-comment-composer-open';

export interface CommentUserData {
  userName?: string;
}

export interface CommentRecord {
  id: string;
  content: string;
  createdAt: number | string;
  updatedAt: number | string;
  deletedAt?: number | string;
  data: CommentUserData;
}

export interface CommentThread {
  id: string;
  createdAt: number | string;
  updatedAt: number | string;
  resolvedAt?: number | string;
}

export type CommentThreadWithComments = CommentThread & {
  comments: CommentRecord[];
};

type ThreadsListener = (threads: CommentThread[]) => void;

export class CommentsProvider {
  private threads: CommentThreadWithComments[] = [];

  private listeners = new Set<ThreadsListener>();

  subscribe(callback: ThreadsListener) {
    this.listeners.add(callback);
    callback(this.getThreads());

    return () => {
      this.listeners.delete(callback);
    };
  }

  createThread(input: {
    id?: string;
    content: string;
    commentData?: CommentUserData;
  }) {
    const now = Date.now();
    const thread: CommentThreadWithComments = {
      id: input.id ?? createCommentThreadId(),
      createdAt: now,
      updatedAt: now,
      comments: [
        {
          id: createCommentId(),
          content: input.content,
          createdAt: now,
          updatedAt: now,
          data: input.commentData ?? {},
        },
      ],
    };

    this.threads = [thread, ...this.threads];
    this.notify();
    return thread;
  }

  replaceThreads(threads: CommentThreadWithComments[]) {
    this.threads = threads;
    this.notify();
  }

  getThreads(threadOrder?: string[]) {
    const orderMap = new Map(
      threadOrder?.map((threadId, index) => [threadId, index]) ?? [],
    );

    return this.threads
      .map(({ comments: _comments, ...thread }) => thread)
      .sort((first, second) => {
        const firstOrder = orderMap.get(first.id) ?? Number.MAX_SAFE_INTEGER;
        const secondOrder = orderMap.get(second.id) ?? Number.MAX_SAFE_INTEGER;

        if (firstOrder !== secondOrder) return firstOrder - secondOrder;
        return toTime(first.createdAt) - toTime(second.createdAt);
      });
  }

  getThreadComments(threadId: string, includeDeleted = false) {
    const thread = this.threads.find((item) => item.id === threadId);
    if (!thread) return [];

    return includeDeleted
      ? thread.comments
      : thread.comments.filter((comment) => !comment.deletedAt);
  }

  addComment(
    threadId: string,
    comment: Pick<CommentRecord, 'content' | 'data'> &
      Partial<Pick<CommentRecord, 'id' | 'createdAt' | 'updatedAt'>>,
  ) {
    const now = Date.now();
    this.threads = this.threads.map((thread) => {
      if (thread.id !== threadId) return thread;

      return {
        ...thread,
        updatedAt: now,
        comments: [
          ...thread.comments,
          {
            id: comment.id ?? createCommentId(),
            content: comment.content,
            createdAt: comment.createdAt ?? now,
            updatedAt: comment.updatedAt ?? now,
            data: comment.data ?? {},
          },
        ],
      };
    });
    this.notify();
  }

  updateComment(
    threadId: string,
    commentId: string,
    patch: Partial<Pick<CommentRecord, 'content' | 'data'>>,
  ) {
    const now = Date.now();
    this.threads = this.threads.map((thread) => {
      if (thread.id !== threadId) return thread;

      return {
        ...thread,
        updatedAt: now,
        comments: thread.comments.map((comment) =>
          comment.id === commentId
            ? { ...comment, ...patch, updatedAt: now }
            : comment,
        ),
      };
    });
    this.notify();
  }

  deleteComment(
    threadId: string,
    commentId: string,
    options: { deleteContent?: boolean } = {},
  ) {
    const now = Date.now();
    this.threads = this.threads.map((thread) => {
      if (thread.id !== threadId) return thread;

      return {
        ...thread,
        updatedAt: now,
        comments: thread.comments.map((comment) =>
          comment.id === commentId
            ? {
                ...comment,
                content: options.deleteContent ? '' : comment.content,
                deletedAt: now,
                updatedAt: now,
              }
            : comment,
        ),
      };
    });
    this.notify();
  }

  deleteThread(threadId: string) {
    this.threads = this.threads.filter((thread) => thread.id !== threadId);
    this.notify();
  }

  resolveThread(threadId: string) {
    const now = Date.now();
    this.threads = this.threads.map((thread) =>
      thread.id === threadId
        ? { ...thread, resolvedAt: now, updatedAt: now }
        : thread,
    );
    this.notify();
  }

  unresolveThread(threadId: string) {
    const now = Date.now();
    this.threads = this.threads.map((thread) => {
      if (thread.id !== threadId) return thread;
      const { resolvedAt: _resolvedAt, ...rest } = thread;
      return { ...rest, updatedAt: now };
    });
    this.notify();
  }

  private notify() {
    const threads = this.getThreads();
    this.listeners.forEach((listener) => {
      listener(threads);
    });
  }
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    comments: {
      setThread: (attrs: {
        threadId?: string;
        content: string;
        commentData?: CommentUserData;
      }) => ReturnType;
      selectThread: (attrs: {
        id: string;
        updateSelection?: boolean;
      }) => ReturnType;
      unselectThread: () => ReturnType;
      removeThread: (attrs: { id: string }) => ReturnType;
      resolveThread: (attrs: { id: string }) => ReturnType;
      unresolveThread: (attrs: { id: string }) => ReturnType;
      updateComment: (attrs: {
        threadId: string;
        id: string;
        content: string;
        data?: CommentUserData;
      }) => ReturnType;
    };
  }
}

export const CommentsExtension = Mark.create<{
  provider: CommentsProvider;
}>({
  name: 'comments',

  inclusive: false,

  addOptions() {
    return {
      provider: new CommentsProvider(),
    };
  },

  addStorage() {
    return {
      focusedThreads: [] as string[],
      hoveredThreads: [] as string[],
    };
  },

  addAttributes() {
    return {
      threadId: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-comment-thread-id'),
        renderHTML: (attributes) => {
          if (!attributes.threadId) return {};
          return { 'data-comment-thread-id': attributes.threadId };
        },
      },
    };
  },

  parseHTML() {
    return [
      {
        tag: 'span[data-comment-thread-id]',
      },
    ];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      'span',
      mergeAttributes(HTMLAttributes, {
        class: 'tiptap-comment-thread',
      }),
      0,
    ];
  },

  addCommands() {
    return {
      setThread:
        ({ threadId, content, commentData }) =>
        ({ editor, commands }) => {
          if (editor.state.selection.empty) return false;

          const thread = this.options.provider.createThread({
            id: threadId,
            content,
            commentData,
          });

          this.storage.focusedThreads = [thread.id];
          return commands.setMark(this.name, { threadId: thread.id });
        },
      selectThread:
        ({ id, updateSelection = true }) =>
        ({ editor, tr, dispatch }) => {
          this.storage.focusedThreads = [id];

          if (updateSelection) {
            const range: { from: number; to: number } | null = findThreadRange(
              editor.state.doc,
              this.name,
              id,
            );
            if (range) {
              tr.setSelection(
                TextSelection.create(editor.state.doc, range.from, range.to),
              ).scrollIntoView();
            }
          }

          if (dispatch) dispatch(tr);
          return true;
        },
      unselectThread:
        () =>
        ({ tr, dispatch }) => {
          this.storage.focusedThreads = [];
          if (dispatch) dispatch(tr);
          return true;
        },
      removeThread:
        ({ id }) =>
        ({ editor, tr, dispatch }) => {
          const markType = editor.schema.marks[this.name];

          this.options.provider.deleteThread(id);
          editor.state.doc.descendants((node, pos) => {
            if (!node.isText) return;
            const hasThreadMark = node.marks.some(
              (mark) => mark.type === markType && mark.attrs.threadId === id,
            );
            if (hasThreadMark) {
              tr.removeMark(pos, pos + node.nodeSize, markType);
            }
          });

          this.storage.focusedThreads = [];
          if (dispatch) dispatch(tr);
          return true;
        },
      resolveThread:
        ({ id }) =>
        () => {
          this.options.provider.resolveThread(id);
          return true;
        },
      unresolveThread:
        ({ id }) =>
        () => {
          this.options.provider.unresolveThread(id);
          return true;
        },
      updateComment:
        ({ threadId, id, content, data }) =>
        () => {
          this.options.provider.updateComment(threadId, id, {
            content,
            data,
          });
          return true;
        },
    };
  },

  addProseMirrorPlugins() {
    return [
      new Plugin({
        props: {
          handleClick: (view, _pos, event) => {
            const target = event.target as HTMLElement | null;
            const threadElement = target?.closest?.(
              '[data-comment-thread-id]',
            ) as HTMLElement | null;
            const threadId = threadElement?.dataset.commentThreadId;

            if (!threadId) return false;

            const range = findThreadRange(view.state.doc, this.name, threadId);
            const tr = view.state.tr;
            if (range) {
              tr.setSelection(
                TextSelection.create(view.state.doc, range.from, range.to),
              ).scrollIntoView();
            }

            this.storage.focusedThreads = [threadId];
            view.dispatch(tr);
            window.dispatchEvent(
              new CustomEvent(COMMENT_THREAD_CLICK_EVENT, {
                detail: { threadId },
              }),
            );
            return true;
          },
        },
      }),
    ];
  },
});

export function hoverThread(editor: any, threadIds: string[]) {
  if (!editor?.storage?.comments) return;
  editor.storage.comments.hoveredThreads = threadIds;
  editor.view.dispatch(editor.state.tr);
}

export function hoverOffThread(editor: any) {
  if (!editor?.storage?.comments) return;
  editor.storage.comments.hoveredThreads = [];
  editor.view.dispatch(editor.state.tr);
}

export function getCommentThreadOrder(editor: any) {
  const order: string[] = [];
  const seen = new Set<string>();

  editor?.state?.doc?.descendants((node: any) => {
    if (!node.isText) return;

    node.marks.forEach((mark: any) => {
      const threadId = mark.attrs?.threadId;
      if (mark.type.name === 'comments' && threadId && !seen.has(threadId)) {
        seen.add(threadId);
        order.push(threadId);
      }
    });
  });

  return order;
}

function createCommentThreadId() {
  return `thread-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function createCommentId() {
  return `comment-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function toTime(value?: number | string) {
  if (!value) return 0;
  if (typeof value === 'number') return value;
  const time = new Date(value).getTime();
  return Number.isNaN(time) ? 0 : time;
}

function findThreadRange(
  doc: any,
  markName: string,
  threadId: string,
): { from: number; to: number } | null {
  let range: { from: number; to: number } | null = null;

  doc.descendants((node: any, pos: number) => {
    if (!node.isText || range) return;

    const hasThreadMark = node.marks.some(
      (mark: any) =>
        mark.type.name === markName && mark.attrs.threadId === threadId,
    );

    if (hasThreadMark) {
      range = { from: pos, to: pos + node.nodeSize };
    }
  });

  return range;
}
