import React, {memo, useEffect, useState} from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Button} from 'antd';

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
export default memo(function MarkdownText({text, streaming = false}: { text: string; streaming?: boolean }) {
  const [frame, setFrame] = useState(text);
  useEffect(() => {
    if (!streaming) return;
    const request = requestAnimationFrame(() => setFrame(text));
    return () => cancelAnimationFrame(request);
  }, [text, streaming]);
  // Raw HTML is skipped, and react-markdown's default URL transform rejects unsafe protocols.
  return <div className="ainew-markdown"><Markdown skipHtml remarkPlugins={[remarkGfm]}
                                                   components={components}>{streaming ? frame : text}</Markdown></div>;
});
