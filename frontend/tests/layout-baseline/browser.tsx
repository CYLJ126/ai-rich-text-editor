import React, {useEffect} from 'react';
import {createRoot} from 'react-dom/client';
import {ConfigProvider} from 'antd';
import ChatWorkspaceLayout from '@/features/ainew/ChatWorkspaceLayout';
import {type ChatLayout, useChatLayout} from '@/features/ainew/useChatLayout';
import '@/pages/AI/ChatNew/style.css';

declare global {
  interface Window {
    layoutBaseline: { layout: ChatLayout; mounts: number; unmounts: number };
  }
}

const facts = {mounts: 0, unmounts: 0};

function Chat() {
  useEffect(() => {
    facts.mounts++;
    return () => {
      facts.unmounts++;
    };
  }, []);
  return <textarea aria-label="消息草稿" defaultValue="保留的消息"/>;
}

function Harness() {
  const layout = useChatLayout();
  window.layoutBaseline = Object.assign(facts, {layout});
  return <ConfigProvider theme={{cssVar: {}}}>
    <ChatWorkspaceLayout layout={layout}
                         list={<input aria-label="列表草稿" defaultValue="名称稿"/>}
                         settings={<input aria-label="参数草稿" defaultValue="512"/>}>
      <Chat/>
    </ChatWorkspaceLayout>
  </ConfigProvider>;
}

const host = document.getElementById('root');
if (!host) throw new Error('Missing layout acceptance root');
createRoot(host).render(<Harness/>);
