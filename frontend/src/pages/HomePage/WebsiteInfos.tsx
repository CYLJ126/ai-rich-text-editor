import {Card, Carousel, Col, Image, Popover, Row, Space, Tabs} from 'antd';
import React, {useEffect, useRef, useState} from 'react';
import styles from './WebsiteInfos.less';
import {getWebsiteLogo, listWebsiteNews} from '@/services/ant-design-pro/homePage';

const openWebsite = (url: string) => {
  // 打开新标签页
  window.open(url, '_blank', 'noopener, noreferrer');
};

function NewsCard({ news, width }: { news: any; width: any }) {
  return (
    <Card
      title={news.title}
      hoverable
      className={styles.card}
      style={{ width: width }}
      size={'small'}
      onClick={() => openWebsite(news.url)}
    >
      <p>{news.summary}</p>
    </Card>
  );
}

/**
 * 气泡卡片，悬浮显示新闻列表
 *
 * @param newsList
 * @constructor
 */
function PopoverList({ newsList }: { newsList: any }) {
  const time = new Date().getTime();
  return (
    <div className={styles.popoverContentWrapper}>
      <Space orientation="vertical" size="small">
        {newsList.map((news: any) => (
          <NewsCard key={news.title + '_' + time} news={news} width="100%" />
        ))}
      </Space>
    </div>
  );
}

/**
 * 每个网站
 *
 * @param websiteParam 网站信息参数
 * @param cardWidth 样式宽度
 * @constructor
 */
function WebsiteInfo({ websiteParam, cardWidth }: { websiteParam: any; cardWidth: any }) {
  const { id, module, newsList, logoUrl, moduleUrl } = websiteParam;
  const [imageUrl, setImageUrl] = useState();

  useEffect(() => {
    const fetchImage = async () => {
      try {
        const response = await getWebsiteLogo(id, logoUrl);
        // @ts-ignore
        const url = URL.createObjectURL(response);
        setImageUrl(url);
      } catch (error) {
        console.error('获取图片失败:', error);
      }
    };
    fetchImage().then();
    return () => {
      // 组件卸载时释放 Blob URL
      if (imageUrl) URL.revokeObjectURL(imageUrl);
    };
  }, []);

  const time = new Date().getTime();
  return (
    <Popover
      autoAdjustOverflow
      placement="topLeft"
      content={<PopoverList newsList={newsList} />}
      classNames={{ root: styles.newsPopover }}
      getPopupContainer={(triggerNode) => triggerNode.parentElement!}
      onPopupAlign={(domNode) => {
        const popover = domNode as HTMLElement;
        const rect = popover.getBoundingClientRect();
        const viewportWidth = window.innerWidth;
        const viewportHeight = window.innerHeight;

        if (rect.left < 20) {
          // 左侧溢出
          popover.style.left = `${20 - rect.left}px`;
        } else if (rect.right > viewportWidth - 20) {
          // 右侧溢出
          const overflow = rect.right - viewportWidth;
          popover.style.left = `${parseFloat(popover.style.left) - overflow - 20}px`;
        }

        if (rect.top < 20) {
          // 顶部溢出
          popover.style.top = `${20 - rect.top}px`;
        } else if (rect.bottom > viewportHeight - 20) {
          // 底部溢出
          let delta;
          if (popover.style.top === 'auto') {
            delta = viewportHeight - 600;
          } else {
            const overflow = rect.bottom - viewportHeight;
            delta = parseFloat(popover.style.top) - overflow - 20;
          }
          if (delta < 20) {
            delta = 20 - delta;
          }
          popover.style.top = delta + 'px';
        }
      }}
    >
      <a href={moduleUrl} target="_blank" rel="noreferrer">
        <Row align="middle" style={{ width: cardWidth }}>
          <Col span={12} style={{ height: '40px' }}>
            <Image width={100} preview={false} src={imageUrl} className={styles.logoImg} />
          </Col>
          <Col span={12} align={'end'}>
            <span className={styles.websiteModule}>{module}</span>
          </Col>
        </Row>
      </a>
      {/* 走马灯 */}
      <Carousel arrows autoplay dotPlacement={'top'} dots={false} className={styles.carousel}>
        {newsList.map((news: any) => (
          <NewsCard news={news} width={cardWidth} key={news.title + '_' + time} />
        ))}
      </Carousel>
    </Popover>
  );
}

/**
 * 每个新闻 Tab 页内容
 *
 * @param id 标签，即 Tab 类型
 * @constructor
 */
function NewsTabContent({tagName}) {
  const [websiteList, setWebsiteList] = useState([]);
  const [cardWidth, setCardWidth] = useState('42vh');
  const gridRef = useRef<HTMLDivElement>(null); // 步骤1：创建ref引用
  useEffect(() => {
    listWebsiteNews({tagName}).then((result) => setWebsiteList(result));
  }, []);

  // 监听容器尺寸变化
  useEffect(() => {
    const gridElement = gridRef.current;
    if (!gridElement) return;

    // 在ResizeObserver内添加防抖逻辑
    let resizeTimeout: any;
    // 步骤2：创建 IntersectionObserver，只在可见时监听
    const resizeObserver = new ResizeObserver((entries) => {
      if (resizeTimeout) {
        clearTimeout(resizeTimeout);
      }
      resizeTimeout = setTimeout(() => {
        // 激活ResizeObserver
        for (const entry of entries) {
          // 步骤3：计算有效宽度（包含padding和border）
          const totalWidth = entry.target.clientWidth;

          // 步骤4：计算目标宽度并设置CSS变量
          const tabWidth = (totalWidth - 40) / 4;
          entry.target.style.setProperty('--news-tab-width', `${tabWidth}px`);
          setCardWidth(tabWidth + 'px');
        }
      }, 1000); // 150ms延迟可自行调整
    });

    resizeObserver.observe(gridElement);

    return () => {
      resizeObserver.disconnect(); // 清理观察器
    };
  }, []);

  const time = new Date().getTime();
  return (
    <div
      id="website-content-grid"
      ref={gridRef}
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)', // 四列等宽
        gap: '10px', // 元素间距
        alignItems: 'stretch', // 元素高度对齐方式
      }}
    >
      {websiteList &&
        websiteList.map((item) => (
          <WebsiteInfo key={item.id + '_' + time} websiteParam={item} cardWidth={cardWidth} />
        ))}
    </div>
  );
}

export default function WebsiteInfos() {
  const [newsTab, setNewsTab] = useState([]);

  useEffect(() => {
    const websiteTags = JSON.parse(localStorage.getItem('websiteTags') || '[]');
    if (websiteTags && websiteTags.length > 0) {
      setNewsTab(
        websiteTags.map((tag: any) => {
          return {label: tag.name, key: tag.id, children: <NewsTabContent tagName={tag.name}/>};
        }),
      );
    }
  }, []);

  /**
   * 动态设置每个标签宽度，使其占满一行
   */
  useEffect(() => {
    if (newsTab.length === 0) return;
    // 使用setTimeout确保DOM已经更新
    const timer = setTimeout(() => {
      const navWrap = document.querySelector('.ant-tabs-nav-wrap');
      const tabs = document.querySelectorAll('#website-news-tabs .ant-tabs-tab');

      if (navWrap && tabs.length > 0) {
        let navWrapWidth = navWrap.offsetWidth;
        navWrapWidth = navWrapWidth < 200 ? 200 : navWrapWidth;
        const tabCount = tabs.length;
        const tabWidth = navWrapWidth / tabCount;

        // 设置每个tab的宽度
        tabs.forEach((tab) => {
          tab.style.width = `${tabWidth}px`;
          tab.style.textAlign = 'center'; // 使文字居中
        });
      }
    }, 0);

    return () => clearTimeout(timer);
  }, [newsTab]);

  return <Tabs id={'website-news-tabs'} animated items={newsTab} className={styles.newsTabs} />;
}
