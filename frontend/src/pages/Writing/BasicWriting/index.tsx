import {i18nText} from '@/utils/i18n';
import {
  CommentOutlined,
  FileAddOutlined,
  FileWordOutlined,
  FolderOutlined,
  HistoryOutlined,
  MenuOutlined,
  MessageOutlined,
  TagsOutlined,
} from '@ant-design/icons';
import { Button, Modal, Splitter } from 'antd';
import dayjs from 'dayjs';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { RightSidebar, type SidePanel } from '@/components';
import { type AIChatHandleRef, ChattingSider } from '@/components/AI';
import {
  ChatProvider,
  type ChatRequestContext,
  useChatData,
} from '@/components/AI/ChatContext';
import type { MessageOperationProps } from '@/components/AI/MessageList';
import {
  ArticleHome,
  RichTextEditor,
  useArticleInfoStore,
  useEditorStore,
} from '@/components/Article';
import {
  ArticleHistoryCompare,
  ArticleHistoryPanel,
  ArticleTags,
  CatalogTreeSidebar,
  CommentsPanel,
  FloatingQuickActions,
  ShareSettingModal,
  TableOfContents,
  WritingManager,
} from '@/components/Article/components';
import { COMMENT_THREAD_CLICK_EVENT } from '@/components/Article/extension/comments/commentsExtension';
import type { RichTextEditorRef } from '@/components/Article/RichTextEditor';
import type {
  ActiveSelectedInfo,
  ArticleHistoryVersion,
  ArticlePanelType,
  PageShowType,
} from '@/types/rt.type';
import { useComponentHeight } from '@/utils/useDynamicHeight';
import styles from './index.less';

const BasicWriting: React.FC = () => {
  const setActiveJumpInfo = useArticleInfoStore(
    (state) => state.setActiveJumpInfo,
  );
  const activeJumpInfo = useArticleInfoStore((state) => state.activeJumpInfo);
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);
  const rawText = useArticleInfoStore((state) => state.rawText);
  const setArticleInfo = useArticleInfoStore((state) => state.setArticleInfo);
  const savingState = useArticleInfoStore((state) => state.savingState);
  const activePanel = useEditorStore((state) => state.activePanel);
  const setActivePanel = useEditorStore((state) => state.setActivePanel);
  const operationMode = useEditorStore((state) => state.operationMode);
  const setOperationMode = useEditorStore((state) => state.setOperationMode);
  const richTextEditorRef = useRef<RichTextEditorRef>(null);
  const { currentUser } = useChatData();
  const editorHeight = useComponentHeight(0, 300);
  const articleEditorRef = useRef<HTMLDivElement>(null);
  const aiWritingChatRef = useRef<AIChatHandleRef>(null);
  const ignoredVersionRef = useRef(0);
  const versionArticleIdRef = useRef<number | undefined>(undefined);
  const localShowArticleScrollbar =
    localStorage.getItem('edit-showArticleScrollbar') === 'false';
  const [showArticleScrollbar, setShowArticleScrollbar] = useState(
    !localShowArticleScrollbar,
  );
  const [historyVersions, setHistoryVersions] = useState<
    ArticleHistoryVersion[]
  >([]);
  const [selectedHistoryId, setSelectedHistoryId] = useState<number>();
  // 读取和修改 URL 中的查询参数
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const openedFromArticleUrlRef = useRef(searchParams.has('articleId'));
  // 编辑区域与侧边栏比例
  const [sizes, setSizes] = useState<number[]>([80, 20]);
  // 页面显示类型：ArticleHome-文章列表页；RichTextEditor-文章编辑器页；
  const [pageShowType, setPageShowType] = useState<PageShowType>(
    searchParams.has('articleId') ? 'RichTextEditor' : 'ArticleHome',
  );
  // 分享弹窗状态
  const [shareModal, setShareModal] = useState<{
    open: boolean;
    resourceType: string;
    resourceId: number;
    resourceName: string;
  }>({ open: false, resourceType: '', resourceId: 0, resourceName: '' });
  const [articleVisitKey, setArticleVisitKey] = useState(0);
  const [ignoreVersionForVisit, setIgnoreVersionForVisit] = useState(false);
  const [versionWarning, setVersionWarning] = useState<{
    rowVersion: number;
    updateBy?: string;
  }>();

  // 选中文章时同步到 URL
  const handleArticleSelect = useCallback(
    async (jumpInfo: ActiveSelectedInfo) => {
      const canSwitch =
        (await richTextEditorRef.current?.confirmBeforeArticleSwitch?.(
          jumpInfo.articleId,
        )) ?? true;
      if (!canSwitch) return;
      const isCurrentArticle = activeJumpInfo?.articleId === jumpInfo.articleId;
      if (isCurrentArticle) {
        setIgnoreVersionForVisit(false);
        setVersionWarning(undefined);
        ignoredVersionRef.current = 0;
        setArticleVisitKey((value) => value + 1);
      }
      setActiveJumpInfo(jumpInfo);
      setPageShowType('RichTextEditor');
      navigate(
        {
          pathname: location.pathname,
          search: `?articleId=${jumpInfo.articleId}`,
          hash: jumpInfo.sectionHeadingId
            ? `#${encodeURIComponent(jumpInfo.sectionHeadingId)}`
            : '',
        },
        { replace: isCurrentArticle },
      );
    },
    [activeJumpInfo?.articleId, location.pathname, navigate, setActiveJumpInfo],
  );

  // 取消文章选中时的操作，如跳到主页、删除当前文章等
  const handleArticleDeselect = useCallback(() => {
    setArticleInfo(undefined);
    setOperationMode(undefined);
    setSearchParams({}, { replace: true });
  }, [setSearchParams, setArticleInfo, setOperationMode]);

  const handleShareSetting = useCallback(
    (resourceType: string, resourceId: number) => {
      setShareModal({
        open: true,
        resourceType,
        resourceId,
        resourceName:
          resourceType === 'CATALOG'
            ? i18nText("app.article.basicwriting.2236d518", {value0: resourceId})
            : i18nText("app.article.basicwriting.da357884", {value0: resourceId}),
      });
    },
    [],
  );

  const handleShareCurrentArticle = useCallback(() => {
    if (articleInfo?.id) {
      handleShareSetting('ARTICLE', articleInfo.id);
    }
  }, [handleShareSetting, articleInfo?.id]);

  const onBackHome = useCallback(() => {
    setPageShowType('ArticleHome');
    setSearchParams({});
  }, [setPageShowType, setSearchParams]);

  const handleAskAi = useCallback(() => {
    setActivePanel('aiChat');
    aiWritingChatRef.current?.openArticleChat().catch(() => undefined);
  }, [setActivePanel]);

  const handleShowHistory = useCallback(() => {
    setSelectedHistoryId(undefined);
    setActivePanel('versionManage');
  }, [setActivePanel]);

  const handleHistoryLoaded = useCallback(
    (versions: ArticleHistoryVersion[]) => {
      setHistoryVersions(versions);
      setSelectedHistoryId((current) =>
        current && versions.some((version) => version.id === current)
          ? current
          : undefined,
      );
    },
    [],
  );

  const handleHistorySelect = useCallback(
    (version: ArticleHistoryVersion) => {
      setSelectedHistoryId(version.id);
      setActivePanel('versionManage');
    },
    [setActivePanel],
  );

  // ── sidePanels 用 useMemo 稳定引用，避免每次渲染重建数组 ──
  const sidePanels = useMemo<SidePanel[]>(
    () => [
      {
        id: 'toc',
        name: i18nText("app.article.basicwriting.3b9e7e6b"),
        icon: <MenuOutlined />,
        noPadding: true,
        component: <TableOfContents />,
      },
      {
        id: 'catalog',
        name: i18nText("app.article.basicwriting.373c02f4"),
        icon: <FolderOutlined />,
        noPadding: true,
        component: (
          <CatalogTreeSidebar
            onArticleSelect={handleArticleSelect}
            onArticleDeselect={handleArticleDeselect}
            onShareSetting={handleShareSetting}
          />
        ),
      },
      {
        id: 'aiChat',
        name: 'AI Chat',
        icon: <MessageOutlined />,
        noPadding: true,
        component: (
          <ChattingSider ref={aiWritingChatRef} height={editorHeight - 33} />
        ),
      },
      {
        id: 'comments',
        name: i18nText("app.article.basicwriting.3ce391de"),
        icon: <CommentOutlined />,
        noPadding: true,
        component: <CommentsPanel active={activePanel === 'comments'} />,
      },
      {
        id: 'writeManage',
        name: i18nText("app.article.basicwriting.574a5bae"),
        icon: <FileWordOutlined />,
        noPadding: true,
        component: (
          <WritingManager
            height={editorHeight}
            currentUser={currentUser?.userName || ''}
          />
        ),
      },
      {
        id: 'tagManage',
        name: i18nText("app.article.basicwriting.01999ddb"),
        icon: <TagsOutlined />,
        noPadding: true,
        component: <ArticleTags active={activePanel === 'tagManage'} />,
      },
      {
        id: 'versionManage',
        name: i18nText("app.article.basicwriting.2467722a"),
        icon: <HistoryOutlined />,
        noPadding: true,
        component: (
          <ArticleHistoryPanel
            article={articleInfo}
            active={activePanel === 'versionManage'}
            selectedId={selectedHistoryId}
            onLoaded={handleHistoryLoaded}
            onSelect={handleHistorySelect}
          />
        ),
      },
    ],
    [
      handleArticleSelect,
      handleArticleDeselect,
      handleShareSetting,
      editorHeight,
      articleInfo,
      activePanel,
      selectedHistoryId,
      handleHistoryLoaded,
      handleHistorySelect,
      currentUser?.userName,
    ],
  );

  useEffect(() => {
    if (operationMode === 'revise') {
      setActivePanel('comments');
    }
  }, [operationMode]);

  useEffect(() => {
    if (
      savingState === 2 &&
      articleInfo &&
      currentUser?.userName &&
      articleInfo.updateBy !== currentUser.userName
    ) {
      setArticleInfo({ ...articleInfo, updateBy: currentUser.userName });
    }
  }, [articleInfo, currentUser?.userName, savingState, setArticleInfo]);

  useEffect(() => {
    if (versionArticleIdRef.current === activeJumpInfo?.articleId) return;
    versionArticleIdRef.current = activeJumpInfo?.articleId;
    ignoredVersionRef.current = 0;
    setIgnoreVersionForVisit(false);
    setVersionWarning(undefined);
    setArticleVisitKey((value) => value + 1);
  }, [activeJumpInfo?.articleId]);

  useEffect(() => {
    const articleId = articleInfo?.id;
    if (
      !articleId ||
      activeJumpInfo?.articleId !== articleId ||
      ignoreVersionForVisit
    ) {
      return;
    }

    const token = localStorage.getItem('user_token');
    if (!token) return;

    let disposed = false;
    let socket: WebSocket | undefined;
    let reconnectTimer: number | undefined;

    const connect = () => {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(
        `${protocol}//${window.location.host}/arte/webSocket/article-version`,
      );

      socket.onopen = () => {
        socket?.send(
          JSON.stringify({
            type: 'subscribe',
            articleId,
            token,
          }),
        );
      };

      socket.onmessage = (event) => {
        const update = JSON.parse(event.data) as {
          type: string;
          articleId: number;
          rowVersion: number;
          updateBy?: string;
        };
        if (
          update.type !== 'article-version' ||
          update.articleId !== articleId
        ) {
          return;
        }

        const currentArticle = useArticleInfoStore.getState().articleInfo;
        if (
          currentArticle?.id !== articleId ||
          update.rowVersion <=
            Math.max(currentArticle.rowVersion ?? 0, ignoredVersionRef.current)
        ) {
          return;
        }

        if (update.updateBy === currentUser?.userName) {
          setArticleInfo({ ...currentArticle, rowVersion: update.rowVersion });
          return;
        }

        setVersionWarning((current) =>
          !current || update.rowVersion > current.rowVersion
            ? { rowVersion: update.rowVersion, updateBy: update.updateBy }
            : current,
        );
      };

      socket.onclose = () => {
        if (!disposed) {
          reconnectTimer = window.setTimeout(connect, 3000);
        }
      };
    };

    connect();
    return () => {
      disposed = true;
      if (reconnectTimer) window.clearTimeout(reconnectTimer);
      socket?.close();
    };
  }, [
    activeJumpInfo?.articleId,
    articleInfo?.id,
    articleVisitKey,
    currentUser?.userName,
    ignoreVersionForVisit,
    setArticleInfo,
  ]);

  useEffect(() => {
    const articleIdFromUrl = searchParams.get('articleId');
    if (!articleIdFromUrl) {
      setPageShowType('ArticleHome');
      setActiveJumpInfo(undefined);
      setArticleInfo(undefined);
      return;
    }

    // 打开带文章 ID 的链接时，加载文章
    const articleId = Number(articleIdFromUrl);
    const sectionHeadingId = location.hash
      ? decodeURIComponent(location.hash.slice(1))
      : undefined;
    // 从目录树点击同一篇文章时，保留其空间来源，避免 URL 同步将共享空间
    // 的选中状态重置为默认的公共空间优先策略。
    if (activeJumpInfo?.articleId !== articleId) {
      setActiveJumpInfo({ articleId, sectionHeadingId });
    } else if (
      sectionHeadingId &&
      activeJumpInfo.sectionHeadingId !== sectionHeadingId
    ) {
      setActiveJumpInfo({ ...activeJumpInfo, sectionHeadingId });
    }
    setPageShowType('RichTextEditor');
  }, [
    activeJumpInfo,
    location.hash,
    searchParams,
    setActiveJumpInfo,
    setArticleInfo,
  ]);

  useEffect(() => {
    if (!openedFromArticleUrlRef.current) return;
    openedFromArticleUrlRef.current = false;
    setActivePanel('toc');
  }, [setActivePanel]);

  useEffect(() => {
    // 监听批注点击事件，切换到批注面板
    const handleCommentThreadClick = () => {
      setActivePanel('comments');
    };

    window.addEventListener(
      COMMENT_THREAD_CLICK_EVENT,
      handleCommentThreadClick,
    );
    return () => {
      window.removeEventListener(
        COMMENT_THREAD_CLICK_EVENT,
        handleCommentThreadClick,
      );
      // 每次进入页面都是在主页，所以离开前清空文章信息和跳转信息
      setArticleInfo(undefined);
      setActiveJumpInfo(undefined);
    };
  }, []);

  const editorHome = useMemo(
    () => <ArticleHome onArticleSelect={handleArticleSelect} />,
    [handleArticleSelect],
  );

  const richTextAreaEditor = useMemo(
    () => (
      <RichTextEditor
        ref={richTextEditorRef}
        editorHeight={editorHeight}
        onBackHome={onBackHome}
        onShareArticle={handleShareCurrentArticle}
      />
    ),
    [editorHeight, handleShareCurrentArticle, onBackHome],
  );

  const showingHistory =
    activePanel === 'versionManage' &&
    Boolean(articleInfo?.id) &&
    Boolean(selectedHistoryId);

  return (
    <div style={{ height: editorHeight, overflow: 'hidden' }}>
      <Splitter className={styles.customSplitter} onResize={setSizes}>
        <Splitter.Panel collapsible size={`${sizes[0]}%`} min="30%" max="90%">
          {/* 文章主页列表 */}
          <div
            style={{
              display: pageShowType === 'ArticleHome' ? 'flex' : 'none',
            }}
          >
            {editorHome}
          </div>
          {/* 文章内容页 */}
          <div
            ref={articleEditorRef}
            className={styles.articleEditorHost}
            data-show-scrollbar={showArticleScrollbar}
            style={{
              display:
                pageShowType === 'RichTextEditor' && !showingHistory
                  ? 'flex'
                  : 'none',
            }}
          >
            {richTextAreaEditor}
            <FloatingQuickActions
              containerRef={articleEditorRef}
              showScrollbar={showArticleScrollbar}
              onShowScrollbarChange={(value) => {
                setShowArticleScrollbar(value);
                localStorage.setItem(
                  'edit-showArticleScrollbar',
                  String(value),
                );
              }}
              toggleShowMetaInfo={() =>
                richTextEditorRef.current?.toggleShowMetaInfo?.()
              }
              onAskAi={handleAskAi}
              onShowHistory={handleShowHistory}
            />
          </div>
          {pageShowType === 'RichTextEditor' &&
            showingHistory &&
            articleInfo && (
              <ArticleHistoryCompare
                article={articleInfo}
                currentContent={rawText ?? ''}
                versions={historyVersions}
                selectedHistoryId={selectedHistoryId}
                height={editorHeight}
                onExit={() => setActivePanel('catalog')}
              />
            )}
        </Splitter.Panel>

        <Splitter.Panel collapsible size={`${sizes[1]}%`} min="10%" max="70%">
          {/* 侧边栏 */}
          <RightSidebar
            panels={sidePanels}
            defaultActiveId="catalog"
            activeId={activePanel}
            onActiveIdChange={(val) => setActivePanel(val as ArticlePanelType)}
          />
        </Splitter.Panel>
      </Splitter>

      {/* 分享文章弹窗 */}
      <ShareSettingModal
        open={shareModal.open}
        resourceType={shareModal.resourceType}
        resourceId={shareModal.resourceId}
        resourceName={shareModal.resourceName}
        onClose={() =>
          setShareModal({
            open: false,
            resourceType: '',
            resourceId: 0,
            resourceName: '',
          })
        }
      />

      <Modal
        open={Boolean(versionWarning)}
        title={i18nText("app.article.basicwriting.dd658217")}
        closable={false}
        maskClosable={false}
        footer={[
          <Button
            key="ignore-visit"
            onClick={() => {
              setVersionWarning(undefined);
              setIgnoreVersionForVisit(true);
            }}
          >
            {i18nText("app.article.basicwriting.8d83623f")}
          </Button>,
          <Button
            key="ignore-once"
            onClick={() => {
              ignoredVersionRef.current = versionWarning?.rowVersion ?? 0;
              setVersionWarning(undefined);
            }}
          >
            {i18nText("app.article.basicwriting.38e985f0")}
          </Button>,
          <Button
            key="reload"
            type="primary"
            onClick={() => window.location.reload()}
          >
            {i18nText("app.article.basicwriting.8cea23b4")}
          </Button>,
        ]}
      >
        <p>
          {versionWarning?.updateBy
            ? i18nText("app.article.basicwriting.84bc4bdd", {value0: versionWarning.updateBy})
            : i18nText("app.article.basicwriting.14c29613")}
        </p>
        <p>
          {i18nText("app.article.basicwriting.0a29472b")}{articleInfo?.rowVersion ?? 0}{i18nText("app.article.basicwriting.c0217ad0")}
          {versionWarning?.rowVersion}{i18nText("app.article.basicwriting.8fe68723")}
        </p>
      </Modal>
    </div>
  );
};

export default function BasicWritingLayout() {
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);
  const editor = useEditorStore((state) => state.editor);
  const buildRequest = useCallback(
    ({
      type,
      activeConversation,
      currentUser,
      sendParams,
      userMessage,
    }: ChatRequestContext) => ({
      convId: activeConversation.convId,
      content: type === 'send' ? sendParams?.content : userMessage.content,
      modelId: type === 'retry' ? activeConversation.modelId : undefined,
      quotedMessageId:
        type === 'send'
          ? sendParams?.quotedMessage?.messageId
          : userMessage.quotedMessageId,
      userName: currentUser?.userName ?? '',
      chatRagRequest: {
        knowledgeBaseType: 'article',
        articleIds: articleInfo ? [articleInfo.id] : [],
      },
    }),
    [articleInfo],
  );
  const extraMenuOperations = useMemo<MessageOperationProps[]>(
    () => [
      {
        key: 'insert-into-current-article',
        label: i18nText("app.article.basicwriting.9b131c70"),
        order: 45,
        icon: <FileAddOutlined />,
        showFunc: (message) =>
          message.role === 'assistant' && articleInfo != null,
        operationFunc: (message) => {
          if (!articleInfo || !editor || editor.isDestroyed) return;
          editor
            .chain()
            .focus()
            .insertContent(message.content, { contentType: 'markdown' })
            .run();
        },
      },
    ],
    [articleInfo, editor],
  );

  // 带富文本编辑器的页面不能用 @/components/PageWrapper 页面缓存包裹 TODO 待解决
  return (
    <ChatProvider
      buildRequest={buildRequest}
      extraMenuOperations={extraMenuOperations}
      createMessageTime={() => dayjs().format('YYYY-MM-DD HH:mm')}
      getAssistantModelId={(_, activeConversation) =>
        activeConversation.modelId
      }
    >
      <BasicWriting />
    </ChatProvider>
  );
}
