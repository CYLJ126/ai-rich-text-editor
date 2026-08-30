import {Extension} from '@tiptap/core';
import type {RefObject} from 'react';
import type {ArticleInfoType} from '@/types/rt.type';

export interface MyArticleInfoRefOptions {
  articleInfoRef: RefObject<ArticleInfoType | null | undefined>;
}

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    myArticleInfoRef: {
      updateArticleInfo: (patch: Partial<ArticleInfoType>) => ReturnType;
      setArticleInfo: (info: ArticleInfoType) => ReturnType;
    };
  }

  interface Editor {
    readonly articleInfo: ArticleInfoType | null;
    readonly articleInfoRef: RefObject<ArticleInfoType | null | undefined>;
  }
}

/**
 * 文章信息引用扩展
 * 用于在编辑器中操作文章信息，如获取、更新、设置等
 */
export const MyArticleInfoRef = Extension.create<MyArticleInfoRefOptions>({
  name: 'myArticleInfoRef',

  addOptions() {
    return {
      articleInfoRef: {current: null},
    };
  },

  addStorage() {
    return {
      // 把 ref 对象本身存在 storage 里
      // storage 不会被 TipTap 代理克隆，存什么取什么
      _ref: null as RefObject<ArticleInfoType | null | undefined> | null,
    };
  },

  onCreate() {
    const {editor, options} = this;

    // 在 onCreate 时将 ref 写入 storage
    // 此时 options.articleInfoRef 已经是 configure 传入的值
    // 即使 options 被代理，ref 对象的引用仍然正确
    editor.storage.myArticleInfoRef._ref = options.articleInfoRef;

    const getRef = () =>
      editor.storage.myArticleInfoRef._ref as RefObject<ArticleInfoType | null | undefined>;

    Object.defineProperty(editor, 'articleInfo', {
      get(): ArticleInfoType | null {
        // 从 storage 取 ref，而不是从 options 取
        return getRef()?.current ?? null;
      },
      enumerable: false,
      configurable: true,
    });

    Object.defineProperty(editor, 'articleInfoRef', {
      get(): RefObject<ArticleInfoType | null | undefined> {
        return getRef();
      },
      enumerable: false,
      configurable: true,
    });
  },

  onDestroy() {
    const {editor} = this;
    editor.storage.myArticleInfoRef._ref = null;
    try {
      Object.defineProperty(editor, 'articleInfo', {value: null, configurable: true});
      Object.defineProperty(editor, 'articleInfoRef', {value: null, configurable: true});
    } catch { /* ignore */
    }
  },

  addCommands() {
    // 从 storage 取 ref，保证和 getter 读的是同一个对象
    const getRef = () =>
      this.editor.storage.myArticleInfoRef._ref as RefObject<ArticleInfoType | null | undefined>;

    return {
      updateArticleInfo:
        (patch: Partial<ArticleInfoType>) =>
          () => {
            const ref = getRef();
            if (!ref) return false;
            ref.current = ref.current == null
              ? (patch as ArticleInfoType)
              : {...ref.current, ...patch};
            return true;
          },

      setArticleInfo:
        (info: ArticleInfoType) =>
          () => {
            const ref = getRef();
            if (!ref) return false;
            ref.current = info;
            return true;
          },
    };
  },
});
