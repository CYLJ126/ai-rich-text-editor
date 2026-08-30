import Youtube from '@tiptap/extension-youtube';
import {cn} from '@/lib/utils';

export const TiptapYoutube = Youtube.configure({
  // 粘贴 YouTube URL 时保留为普通链接；slash command 等手动插入方式不受影响。
  addPasteHandler: false,
  HTMLAttributes: {
    class: cn('border border-muted'),
  },
  nocookie: true,
});
