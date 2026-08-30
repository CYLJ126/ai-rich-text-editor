import type {Editor} from '@tiptap/core';
import {message} from 'antd';
import dayjs, {type Dayjs} from 'dayjs';
import {create} from 'zustand';
import {extractHeadingsFromDoc} from '@/components/Article/components/sidebar/TableOfContents';
import {CommentsProvider} from '@/components/Article/extension/comments/commentsExtension';
import type {TagNode} from '@/components/MyTagTree';
import {addArticle, updateArticle} from '@/services/ant-design-pro/richText';
import type {ActiveSelectedInfo, ArticleInfoType, ArticlePermission, ArticleSaveStatus,} from '@/types/rt.type';

// ---------- 文章信息状态类型 ----------
interface ArticleInfoState {
  // 状态
  activeJumpInfo: ActiveSelectedInfo | undefined; // 当前文章跳转信息
  articleInfo: ArticleInfoType | undefined; // 文章信息
  rawText: string | undefined; // 原始文本内容
  characterCount: number; // 字符计数
  articlePermission: ArticlePermission | undefined; // 用户的文章权限
  lastRawTextEditTime: Dayjs | undefined; // 最后一次原始文本编辑时间
  lastRichTextEditTime: Dayjs | undefined; // 最后一次富文本编辑时间
  commentsProvider: CommentsProvider; // 评注提供器
  tags: TagNode[]; // 文章标签列表
  savingState: ArticleSaveStatus; // 是否正在保存文章，用于控制同时只能发起一次保存请求和状态显示

  // 动作
  setActiveJumpInfo: (jumpInfo: ActiveSelectedInfo | undefined) => void;
  setArticleInfo: (articleInfo: ArticleInfoType | undefined) => void; // 设置文章信息
  setRawText: (rawText: string | undefined) => void; // 设置原始文本内容
  setCharacterCount: (characterCount: number) => void; // 设置字符计数
  markRichTextEdited: (time: Dayjs | undefined) => void; // 标记富文本编辑器有用户编辑
  markRawTextEdited: (time: Dayjs | undefined) => void; // 标记原始文本编辑器有用户编辑
  setTags: (tags: TagNode[]) => void; // 设置文章标签列表
  createArticle: (title: string, catalogId?: number) => Promise<void>; // 创建文章
  saveArticle: (
    editor: Editor | undefined,
    triggerType?: 'auto' | 'manual',
  ) => Promise<boolean>; // 保存文章
  setSavingState: (savingState: ArticleSaveStatus) => void; // 设置保存状态
}

export const useArticleInfoStore = create<ArticleInfoState>((set, get) => ({
  articleInfo: undefined,
  activeJumpInfo: undefined,
  rawText: undefined,
  characterCount: 0,
  articlePermission: undefined,
  commentsProvider: new CommentsProvider(),
  tags: [],
  lastRichTextEditTime: undefined,
  lastRawTextEditTime: undefined,
  isSaving: false,
  savingState: 0,

  setActiveJumpInfo: (activeJumpInfo) => {
    console.log('activeJumpInfo 跳转信息：', activeJumpInfo);
    set({ activeJumpInfo });
  },
  setArticleInfo: (articleInfo: ArticleInfoType | undefined) => {
    set((state) => ({
      articleInfo,
      savingState:
        articleInfo?.id && articleInfo.id === state.articleInfo?.id
          ? state.savingState
          : 0,
    }));
  },
  setRawText: (rawText: string | undefined) => {
    set(() => ({ rawText }));
  },
  setCharacterCount: (characterCount: number) => {
    set(() => ({ characterCount }));
  },
  markRichTextEdited: (lastRichTextEditTime: Dayjs | undefined) => {
    set(() => ({ lastRichTextEditTime }));
  },
  markRawTextEdited: (lastRawTextEditTime: Dayjs | undefined) => {
    set(() => ({ lastRawTextEditTime }));
  },
  setTags: (tags: TagNode[]) => {
    set(() => ({ tags }));
  },
  setSavingState: (savingState: ArticleSaveStatus) => {
    set(() => ({ savingState }));
  },
  createArticle: async (title: string, catalogId?: number) => {
    const newArticle = await addArticle({ title, catalogId });
    if (!newArticle) {
      message.error(`创建文章[${title}]失败`).then();
      return;
    }
    set(() => ({
      articleInfo: newArticle,
      operationMode: 'edit',
      characterCount: newArticle.wordCount || 0,
    }));
  },
  /**
   * 保存文章内容
   * 1. 根据编辑时间，同步两侧编辑器；
   * 2. 校验：内容未改变或小于 10 个字符，不予保存；
   * 3. 保存到后端，更新文章信息；
   * 4. 更新 editor 中的文章信息；
   */
  saveArticle: async (
    editor: Editor | undefined,
    triggerType: 'auto' | 'manual' = 'auto',
  ) => {
    const isManualSave = triggerType === 'manual';
    if (!editor || editor.isDestroyed) {
      // 如果走到这儿，则是异常情况，应作其他处理
      message.error('编辑器实例不存在，无法保存文章').then();
      set(() => ({ savingState: 3 }));
      return false;
    }
    const articleInfo = get().articleInfo;
    if (get().savingState === 1) {
      isManualSave && message.warning('文章在保存中，请勿重复保存').then();
      return false;
    }
    if (!articleInfo?.id) {
      message.warning('文章 ID 不存在，无法保存到后端').then();
      set(() => ({ savingState: 3 }));
      return false;
    }
    if (!articleInfo.canWrite) {
      message.warning('当前文章为只读权限，无法保存').then();
      set(() => ({ savingState: 0 }));
      return false;
    }
    let content = get().rawText;
    if (!content) {
      message.warning('原始文本内容为空，无法保存').then();
      set(() => ({ savingState: 3 }));
      return false;
    }
    const lastRichTextEditTime = get().lastRichTextEditTime;
    const lastRawTextEditTime = get().lastRawTextEditTime;
    // 当两边都被编辑过时，取最新的一边
    if (lastRichTextEditTime && lastRawTextEditTime) {
      if (lastRichTextEditTime > lastRawTextEditTime) {
        // 原始文本更新 → 转为编辑器内容同步到 rawText
        content = editor.getMarkdown();
        set(() => ({ rawText: content }));
      } else {
        // 富文本更新 → 将 rawText 同步到编辑器内容
        editor.commands.setContent(content, { contentType: 'markdown' });
      }
    }
    // 重置时间戳
    set(() => ({
      lastRawTextEditTime: undefined,
      lastRichTextEditTime: undefined,
    }));
    if (content.length < 10) {
      // 简单预防，避免 ctrl + A 或者复制粘贴等操作，把原本有的大量内容全部替换了
      if (isManualSave) {
        set(() => ({ savingState: 3 }));
        message.warning('内容未改变或小于 10 个字符，不予保存').then();
      }
      return false;
    }
    set(() => ({ savingState: 1 }));
    try {
      const jsonText = JSON.stringify(editor.getJSON());
      const result = await updateArticle({
        ...articleInfo,
        contentJson: jsonText,
        contentText: content,
        characterCount: get().characterCount,
        updateTime: undefined,
      });
      if (result === undefined || result === null) {
        console.log('保存文章失败');
        set(() => ({ savingState: 3 }));
        return false;
      }
      if (result === false) {
        isManualSave && message.info('文章内容没有变化，无需保存').then();
        set(() => ({ savingState: 0 }));
        return true;
      }
      // 保存请求期间用户可能离开页面，useEditor 会销毁旧实例。
      // 销毁后的 commands getter 会访问已清空的 commandManager，不能再读取。
      if (editor.isDestroyed) {
        set(() => ({ savingState: 2 }));
        return true;
      }
      console.log(`文章已保存到后端，ID: ${articleInfo?.id}`);
      const latestArticleInfo = get().articleInfo;
      const tempArticleInfo = {
        ...articleInfo,
        rowVersion:
          latestArticleInfo?.id === articleInfo.id
            ? latestArticleInfo.rowVersion
            : articleInfo.rowVersion,
        updateTime: dayjs(),
        headings: extractHeadingsFromDoc(editor?.getJSON()),
      };
      set(() => ({ articleInfo: tempArticleInfo }));
      editor.commands.setArticleInfo(tempArticleInfo);
      isManualSave && message.success('文章保存成功').then();
      set(() => ({ savingState: 2 }));
      return true;
    } catch (e) {
      console.error('保存到后端失败:', e);
      set(() => ({ savingState: 3 }));
      if (isManualSave) {
        message.warning('文章保存失败，请联系管理员').then();
      }
      return false;
    }
  },
}));
