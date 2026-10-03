import {createStyles} from 'antd-style';
import React, {Children, isValidElement, memo} from 'react';
import Markdown, {type Components} from 'react-markdown';
import {i18nText as t} from '@/utils/i18n';
import CopyTextButton from './CopyTextButton';

const useStyles = createStyles(({css, token}) => ({
  content: css`
    min-width: 0; overflow-wrap: anywhere; line-height: 1.7;
    p { white-space: pre-wrap; }
    h1, h2, h3, h4, h5, h6 { font-size: 1.2em; margin: 16px 0 8px; }
    blockquote { margin-inline: 0; padding-inline-start: 12px; border-inline-start: 3px solid ${token.colorBorder}; color: ${token.colorTextSecondary}; }
    pre { margin: 8px 0; padding: 12px; overflow-x: auto; white-space: pre; background: ${token.colorFillQuaternary}; border-radius: ${token.borderRadius}px; }
    code { font-family: ${token.fontFamilyCode}; }
    :not(pre) > code { background: ${token.colorFillQuaternary}; padding: 2px 4px; border-radius: 4px; }
  `,
}));

const components: Components = {
  a: ({href, children}) =>
    href ? (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        referrerPolicy="no-referrer"
      >
        {children}
      </a>
    ) : (
      <span>{children}</span>
    ),
  // Model text cannot trigger remote image loads or introduce raw HTML.
  img: ({alt}) => <span>{alt}</span>,
  pre: ({children}) => {
    const code = Children.toArray(children)[0];
    const text = isValidElement<{ children?: React.ReactNode }>(code)
      ? String(code.props.children ?? '')
      : '';
    return (
      <div>
        <CopyTextButton text={text} label={t('app.aiNew.copyCode')}/>
        <pre>{children}</pre>
      </div>
    );
  },
};

export default memo(function AssistantMarkdown({text}: { text: string }) {
  const {styles} = useStyles();
  return (
    <div className={styles.content}>
      <Markdown
        skipHtml
        components={components}
        urlTransform={(url) => (/^https?:\/\//i.test(url) ? url : '')}
      >
        {text}
      </Markdown>
    </div>
  );
});
