import React from 'react';
import {createRoot} from 'react-dom/client';
import {ConfigProvider} from 'antd';
import ChatNewPage from '../../src/pages/AI/ChatNew';

createRoot(document.getElementById('root')!).render(<ConfigProvider theme={{cssVar: true}}>
  <div style={{height: '100vh', padding: 8, display: 'flex', flexDirection: 'column', gap: 8, boxSizing: 'border-box'}}>
    <div style={{fontSize: 12}}>S5 隔离验收 · 会话／搜索／历史／候选经真实 HTTP 读取 MySQL＋ES／IK＋Redis · 固定测试身份，未验证生产
      RBAC，不调用模型
    </div>
    <div style={{flex: 1, minHeight: 0}}><ChatNewPage/></div>
  </div>
</ConfigProvider>);
