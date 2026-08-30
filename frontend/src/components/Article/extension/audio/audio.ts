import {nodePasteRule} from '@tiptap/core';
import Audio from '@tiptap/extension-audio';
import {ReactNodeViewRenderer} from '@tiptap/react';
import {AudioView} from './audio-view';

// Tiptap Audio 默认也会匹配 .webm，但 WebM 可能是视频容器。
// 粘贴 WebM URL 时保留为普通链接，其他明确的音频格式仍可自动转为音频节点。
const AUDIO_URL_REGEX_WITHOUT_WEBM =
  /https?:\/\/[^\s]+?\.(?:mp3|wav|ogg|oga|flac|m4a|aac|opus|weba)(?:\?[^\s]*)?/gi;

export const TiptapAudio = Audio.extend({
  addNodeView() {
    return ReactNodeViewRenderer(AudioView, {
      attrs: () => ({ contentEditable: 'false' }),
    });
  },
  addPasteRules() {
    if (!this.options.addPasteHandler) {
      return [];
    }

    return [
      nodePasteRule({
        find: AUDIO_URL_REGEX_WITHOUT_WEBM,
        type: this.type,
        getAttributes: (match) => ({ src: match[0] }),
      }),
    ];
  },
}).configure({
  controls: true,
  preload: 'metadata',
});
