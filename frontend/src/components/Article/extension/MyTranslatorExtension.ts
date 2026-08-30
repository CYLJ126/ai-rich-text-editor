import type {Editor, JSONContent} from '@tiptap/core';
import {Extension} from '@tiptap/core';
import {STREAM_COMPLETION_URL, streamChat,} from '@/services/ant-design-pro/ai.chat';
import {extractDocText} from '@/components/Article/extension/ai-completion/context-builder';
import {sliceAiContext} from '@/utils/ai';

interface TranslationRange {
  from: number;
  to: number;
}

async function callTranslateAPI(
  editor: Editor,
  text: string,
  range: TranslationRange,
): Promise<string> {
  let translated = '';
  let requestError: Error | null = null;
  const startResult = extractDocText(editor.state.doc, range.from);
  const endResult = extractDocText(editor.state.doc, range.to);
  const context = sliceAiContext(
    startResult.fullText,
    startResult.cursorOffset,
    endResult.cursorOffset,
    editor.aiModel ?? undefined,
  );

  await streamChat(
    STREAM_COMPLETION_URL,
    {
      modelId: editor.aiModel?.id,
      originalText: text,
      content: `${context.before}${text}${context.after}`, // 待翻译内容上下文
      characterCountCeil: editor.aiModel?.continuationCharacterCountCeil,
      generateType: 'translate',
      scene: 'writing_prompt',
    },
    {
      onContent: (delta) => {
        translated += delta;
      },
      onError: (error) => {
        requestError = new Error(error?.message ?? 'AI 翻译失败');
      },
    },
  );

  if (requestError) throw requestError;
  return translated;
}

/**
 * 翻译扩展
 */
declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    translator: {
      /** 翻译选中的行级内容 */
      translateSelection: () => ReturnType;
      /** 翻译并替换选中的行级内容 */
      translateAndReplaceSelection: () => ReturnType;
      /** 翻译并插入选中的行级内容 */
      translateSelectionAndInsertAfter: () => ReturnType;
      /** 翻译当前整个块级内容 */
      translateCurrentBlock: () => ReturnType;
      /** 翻译并替换当前整个块级内容 */
      translateAndReplaceBlock: () => ReturnType;
      /** 翻译块级内容并插入为引用 */
      translateAndInsertAsQuote: () => ReturnType;
    };
  }
}

export const MyTranslatorExtension = Extension.create({
  name: 'translator',
  addCommands() {
    return {
      // 翻译选中的行级内容
      translateSelection:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const info = editor.commands.getSelectionInfo() as any;
          if (!info?.hasSelection) return false;
          const translated = await callTranslateAPI(
            editor,
            info.text,
            info.range,
          );
          editor.commands.showFloatingResult(translated, '翻译结果');
          return true;
        },

      // 翻译并替换选中的行级内容
      translateAndReplaceSelection:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const info = editor.commands.getSelectionInfo() as any;
          if (!info?.hasSelection) return false;
          const translated = await callTranslateAPI(
            editor,
            info.text,
            info.range,
          );
          return editor.commands.replaceSelectionInline([
            { type: 'text', text: translated } as JSONContent,
          ]);
        },

      // 翻译并插入选中的行级内容
      translateSelectionAndInsertAfter:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const info = editor.commands.getSelectionInfo() as any;
          if (!info?.hasSelection) return false;
          const translated = await callTranslateAPI(
            editor,
            info.text,
            info.range,
          );
          return editor.commands.replaceSelectionInline([
            {
              type: 'text',
              text: `${info.text}（${translated}）`,
            } as JSONContent,
          ]);
        },

      // 翻译当前整个块级内容
      translateCurrentBlock:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const block = editor.commands.getCurrentBlockInfo() as any;
          if (!block) return false;
          const translated = await callTranslateAPI(
            editor,
            block.text,
            block.range,
          );
          editor.commands.showFloatingResult(translated, '翻译结果');
          return true;
        },

      // 翻译并替换当前整个块级内容
      translateAndReplaceBlock:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const block = editor.commands.getCurrentBlockInfo() as any;
          if (!block) return false;
          const translated = await callTranslateAPI(
            editor,
            block.text,
            block.range,
          );
          return editor.commands.replaceSelectionBlock({
            type: block.type,
            content: [{ type: 'text', text: translated }],
          } as JSONContent);
        },

      // 翻译并插入引用
      translateAndInsertAsQuote:
        () =>
        async ({ editor }: any): Promise<boolean> => {
          const block = editor.commands.getCurrentBlockInfo() as any;
          if (!block) return false;
          const translated = await callTranslateAPI(
            editor,
            block.text,
            block.range,
          );
          return editor.commands.insertBlockContent(
            {
              type: 'blockquote',
              content: [
                {
                  type: 'paragraph',
                  content: [{ type: 'text', text: translated }],
                },
              ],
            } as JSONContent,
            'after',
          );
        },
    };
  },
} as any);
