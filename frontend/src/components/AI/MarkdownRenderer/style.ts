import {createStyles} from 'antd-style';

export default createStyles(({token, css}) => ({
  // 重置 tiptap 编辑器容器样式
  editorWrapper: css`

    /* 清除编辑器默认轮廓 */
    .tiptap {
      outline: none;
      font-size: 14px;
      line-height: 1.7;
      color: ${token.colorText};
      word-break: break-word;

      /* 段落 */
      p {
        margin: 0 0 8px;

        &:last-child {
          margin-bottom: 0;
        }
      }

      /* 标题 */
      h1, h2, h3, h4, h5, h6 {
        font-weight: 600;
        line-height: 1.4;
        margin: 16px 0 8px;
        color: ${token.colorTextHeading};

        &:first-child {
          margin-top: 0;
        }
      }

      h1 {
        font-size: 1.4em;
      }

      h2 {
        font-size: 1.25em;
      }

      h3 {
        font-size: 1.1em;
      }

      h4, h5, h6 {
        font-size: 1em;
      }

      /* 列表 */
      ul, ol {
        margin: 6px 0;
        padding-left: 20px;
      }

      li {
        margin: 3px 0;
        line-height: 1.6;
      }

      li > ul,
      li > ol {
        margin: 2px 0;
      }

      /* 任务列表 */
      ul[data-type='taskList'] {
        list-style: none;
        padding-left: 4px;

        li {
          display: flex;
          align-items: flex-start;
          gap: 6px;

          label {
            display: flex;
            align-items: center;
            margin-top: 2px;
          }

          input[type='checkbox'] {
            accent-color: ${token.colorPrimary};
            width: 14px;
            height: 14px;
            cursor: default;
          }

          div {
            flex: 1;
          }
        }
      }

      /* 引用 */
      blockquote {
        margin: 8px 0;
        padding: 6px 12px;
        border-left: 3px solid ${token.colorPrimaryBorder};
        background: ${token.colorFillQuaternary};
        border-radius: 0 6px 6px 0;
        color: ${token.colorTextSecondary};
        font-style: italic;

        p {
          margin: 0;
        }
      }

      /* 行内代码 */
      code {
        font-family: ${token.fontFamilyCode};
        font-size: 0.88em;
        background: ${token.colorFillSecondary};
        border: 1px solid ${token.colorBorderSecondary};
        padding: 1px 5px;
        border-radius: 4px;
        color: ${token.colorError};
      }

      /* 分割线 */
      hr {
        border: none;
        border-top: 1px solid ${token.colorBorderSecondary};
        margin: 12px 0;
      }

      /* 表格 */
      .tableWrapper {
        overflow-x: auto;
        margin: 10px 0;
      }

      table {
        border-collapse: collapse;
        width: 100%;
        font-size: 13px;

        td, th {
          border: 1px solid ${token.colorBorderSecondary};
          padding: 6px 12px;
          text-align: left;
          position: relative;
          vertical-align: top;
          box-sizing: border-box;
          min-width: 60px;
        }

        th {
          background: ${token.colorFillQuaternary};
          font-weight: 600;
        }

        tr:hover td {
          background: ${token.colorFillQuaternary};
        }

        /* 选中单元格高亮（只读模式不触发，但保留） */
        .selectedCell::after {
          display: none;
        }
      }

      /* 链接 */
      a {
        color: ${token.colorPrimary};
        text-decoration: none;

        &:hover {
          text-decoration: underline;
        }
      }

      /* 图片 */
      img {
        max-width: 100%;
        border-radius: 6px;
        margin: 4px 0;
      }

      /* KaTeX 数学公式 */
      .Tiptap-mathematics-editor,
      .Tiptap-mathematics-render {
        font-size: 1em;
      }

      .Tiptap-mathematics-render {
        cursor: default;
        padding: 2px 4px;
        border-radius: 4px;
        background: ${token.colorFillQuaternary};
      }

      /* 块级公式居中 */
      p > .Tiptap-mathematics-render:only-child {
        display: block;
        text-align: center;
        padding: 8px 0;
        background: transparent;
      }
    }
  `,

  // ── 代码块容器 ──────────────────────────────────────────────────────────────
  codeBlock: css`
    position: relative;
    margin: 10px 0;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorFillQuaternary};
  `,
  codeHeader: css`
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 12px;
    background: ${token.colorFillSecondary};
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,
  codeLang: css`
    font-size: 12px;
    color: ${token.colorTextSecondary};
    font-family: ${token.fontFamilyCode};
    text-transform: lowercase;
  `,
  codeCopyBtn: css`
    height: 22px;
    font-size: 11px;
    padding: 0 8px;
    border-radius: 4px;
  `,
  codeContent: css`
    overflow-x: auto;

    pre {
      margin: 0 !important;
      padding: 12px 14px !important;
      background: transparent !important;
      border: none !important;
      border-radius: 0 !important;
      font-size: 13px;
      line-height: 1.6;
      /* 清除 tiptap 内部 code 的行内样式覆盖 */

      code {
        font-family: ${token.fontFamilyCode} !important;
        background: transparent !important;
        border: none !important;
        padding: 0 !important;
        border-radius: 0 !important;
        color: inherit !important;
        font-size: inherit !important;
      }
    }

    /* highlight.js token 颜色（light theme） */
    .hljs-comment,
    .hljs-quote {
      color: #6a737d;
      font-style: italic;
    }

    .hljs-keyword,
    .hljs-selector-tag,
    .hljs-subst {
      color: #d73a49;
      font-weight: bold;
    }

    .hljs-number,
    .hljs-literal,
    .hljs-variable,
    .hljs-template-variable,
    .hljs-tag .hljs-attr {
      color: #005cc5;
    }

    .hljs-string,
    .hljs-doctag {
      color: #032f62;
    }

    .hljs-title,
    .hljs-section,
    .hljs-selector-id {
      color: #6f42c1;
      font-weight: bold;
    }

    .hljs-type,
    .hljs-class .hljs-title {
      color: #6f42c1;
      font-weight: bold;
    }

    .hljs-tag,
    .hljs-name,
    .hljs-attribute {
      color: #22863a;
      font-weight: bold;
    }

    .hljs-regexp,
    .hljs-link {
      color: #032f62;
    }

    .hljs-symbol,
    .hljs-bullet {
      color: #e36209;
    }

    .hljs-built_in,
    .hljs-builtin-name {
      color: #005cc5;
    }

    .hljs-meta {
      color: #6a737d;
    }

    .hljs-deletion {
      background: #ffeef0;
    }

    .hljs-addition {
      background: #f0fff4;
    }

    .hljs-emphasis {
      font-style: italic;
    }

    .hljs-strong {
      font-weight: bold;
    }

    &::-webkit-scrollbar {
      height: 4px;
    }

    &::-webkit-scrollbar-thumb {
      background: ${token.colorFillSecondary};
      border-radius: 2px;
    }
  `,
}));
