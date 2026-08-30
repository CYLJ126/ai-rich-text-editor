import {cn} from "@/lib/utils";
import {AiWriter} from "@/components/Article/extension/ai-writer";
import {AiCompletionExtension} from '@/components/Article/extension/ai-completion';
import '@/components/Article/extension/ai-completion/ai-completion.css';
import {MyTranslatorExtension} from '@/components/Article/extension/MyTranslatorExtension';

const aiWriter = AiWriter.configure({
  HTMLAttributes: {
    class: cn('py-3 px-1 select-none'),
  },
});

const aiCompletion = AiCompletionExtension.configure({
  enabled: true, // TODO 替换为配置
  debounceMs: 400,
  preferCurrentSection: true,
  onCompletionAccept: (text) => {
    console.log('[AI Completion] 已接受:', text);
  },
  onCompletionDismiss: () => {
    console.log('[AI Completion] 已取消');
  },
  onError: (err) => {
    console.error('[AI Completion] 错误:', err);
  },
});

export const configAiExtensions = [
  aiWriter,
  aiCompletion,
  MyTranslatorExtension,
]
