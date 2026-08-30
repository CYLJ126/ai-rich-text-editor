import {i18nText} from '@/utils/i18n';
import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { importImageByUrl } from '@/services/upload';

const HTTP_IMAGE_URL =
  /^http:\/\/[^\s]+\.(?:png|jpe?g|gif|webp|svg)(?:[?#].*)?$/i;

async function replaceHttpImages(html: string) {
  const document = new DOMParser().parseFromString(html, 'text/html');
  const images = Array.from(
    document.querySelectorAll<HTMLImageElement>('img[src^="http://"]'),
  );
  const urls = [...new Set(images.map((image) => image.src))];
  const imported = new Map<string, string>();
  const failed: string[] = [];

  await Promise.all(
    urls.map(async (url) => {
      try {
        imported.set(url, await importImageByUrl(url));
      } catch (error) {
        console.error('HTTP image import failed:', url, error);
        failed.push(url);
      }
    }),
  );
  images.forEach((image) => {
    const storedUrl = imported.get(image.src);
    if (storedUrl) image.src = storedUrl;
  });
  return { html: document.body.innerHTML, failed };
}

export const InsecureImagePasteHandler = Extension.create({
  name: 'insecureImagePasteHandler',

  addProseMirrorPlugins() {
    const editor = this.editor;
    return [
      new Plugin({
        key: new PluginKey('insecureImagePasteHandler'),
        props: {
          handlePaste(_view, event) {
            if (window.location.protocol !== 'https:') return false;
            const clipboard = event.clipboardData;
            if (!clipboard) return false;
            const html = clipboard.getData('text/html');
            const text = clipboard.getData('text/plain').trim();
            const hasHttpImage =
              html && /<img\b[^>]*\bsrc=["']http:\/\//i.test(html);
            const isHttpImageUrl = !html && HTTP_IMAGE_URL.test(text);
            if (!hasHttpImage && !isHttpImageUrl) return false;

            event.preventDefault();
            const range = {
              from: editor.state.selection.from,
              to: editor.state.selection.to,
            };
            void (async () => {
              try {
                if (isHttpImageUrl) {
                  const storedUrl = await importImageByUrl(text);
                  editor
                    .chain()
                    .focus()
                    .insertContentAt(range, {
                      type: 'image',
                      attrs: { src: storedUrl },
                    })
                    .run();
                  return;
                }
                const result = await replaceHttpImages(html);
                editor
                  .chain()
                  .focus()
                  .insertContentAt(range, result.html)
                  .run();
                if (result.failed.length) {
                  window.alert(
                    i18nText("app.article.extension.insecureimagepastehandler.a81cc2f4", {value0: result.failed.length}),
                  );
                }
              } catch (error: any) {
                console.error('HTTP image paste handling failed:', error);
                window.alert(error?.message ?? i18nText("app.article.extension.insecureimagepastehandler.cbf7c27c"));
              }
            })();
            return true;
          },
        },
      }),
    ];
  },
});
