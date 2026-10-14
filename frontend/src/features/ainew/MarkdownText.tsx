import React, {memo, useLayoutEffect, useState} from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Button} from 'antd';
import {markChatDomCommit} from '@/services/arte-ai/latency';

export async function copyText(text: string, report: (message: string) => void) {
  try {
    await navigator.clipboard.writeText(text);
    report('已复制');
  } catch {
    report('复制失败，请选择文字后手动复制。');
  }
}

function CodeBlock({children, ...props}: React.ComponentProps<'pre'>) {
  const [notice, setNotice] = useState('');
  const child = React.isValidElement<{ children?: React.ReactNode }>(children) ? children.props.children : children;
  return <div className="ainew-code">
    <Button size="small" onClick={() => void copyText(String(child ?? ''), setNotice)}>复制代码</Button>
    {notice && <span role="status">{notice}</span>}
    <pre {...props}>{children}</pre>
  </div>;
}

const components = {pre: CodeBlock};
export default memo(function MarkdownText({text, invocationId}: {
  text: string;
  streaming?: boolean;
  invocationId?: string
}) {
  const hasText = text.length > 0;
  useLayoutEffect(() => {
    if (invocationId && hasText) return markChatDomCommit(invocationId);
    return undefined;
  }, [invocationId, hasText]);
  // 流式状态已在会话层按帧合并，此处直接绘制，避免再等待一帧或持续取消重排。
  return <div className="ainew-markdown"><Markdown skipHtml remarkPlugins={[remarkGfm]}
                                                   components={components}>{text}</Markdown></div>;
});
