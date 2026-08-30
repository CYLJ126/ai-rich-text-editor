import Mention from '@tiptap/extension-mention'
import suggestion from './suggestion.ts'

export const MyMention = Mention.configure({
  name: 'mention',
  HTMLAttributes: {
    class: 'myMention',
  },
  suggestion,
} as any);
