import {i18nText} from '@/utils/i18n';
import Link from '@tiptap/extension-link';
import {message} from 'antd';

export const configureLink = () => {
  return [
    Link.configure({
      openOnClick: true,
      autolink: true,
      defaultProtocol: 'https',
      protocols: ['http', 'https'],
      isAllowedUri: (url: string, ctx: any) => {},
      shouldAutoLink: (url: string) => {
        try {
          // construct URL
          const parsedUrl = url.includes(':')
            ? new URL(url)
            : new URL(`https://${url}`);

          // only auto-link if the domain is not in the disallowed list
          const disallowedDomains = [
            'example-no-autolink.com',
            'another-no-autolink.com',
          ];
          const domain = parsedUrl.hostname;

          return !disallowedDomains.includes(domain);
        } catch {
          return false;
        }
      },
    } as any),
  ];
};

export function toggleLink(editor: any) {
  const previousUrl = editor.getAttributes('link').href;
  const url = window.prompt('URL', previousUrl);
  // cancelled
  if (url == null) {
    return;
  }
  // empty
  if (url === '') {
    editor.chain().focus().extendMarkRange('link').unsetLink().run();
    return;
  }
  // update link
  try {
    editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run();
  } catch (e: any) {
    message.error(i18nText("app.article.mylink.bf9e0257"), e.message).then();
  }
}
