import {i18nText} from '@/utils/i18n';
import { LikeOutlined, MessageFilled, StarTwoTone } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Flex, List, Tag } from 'antd';
import React from 'react';
import { ArticleListContent } from '@/components';
import type { ListItemDataType } from '../../data.d';
import { queryFakeList } from '../../service';
import useStyles from './index.style';

const IconText: React.FC<{
  icon: React.ReactNode;
  text: React.ReactNode;
}> = ({ icon, text }) => (
  <span>
    {icon} {text}
  </span>
);

const Articles: React.FC = () => {
  const { styles } = useStyles();

  // 获取tab列表数据
  const { data: listData } = useQuery({
    queryKey: ['articles-list', 30],
    queryFn: () => queryFakeList({ count: 30 }).then((res) => res.data),
  });
  return (
    <List<ListItemDataType>
      size="large"
      className={styles.articleList}
      rowKey="id"
      itemLayout="vertical"
      dataSource={listData?.list || []}
      style={{
        margin: '0 -24px',
      }}
      renderItem={(item) => (
        <List.Item
          key={item.id}
          actions={[
            <IconText key="star" icon={<StarTwoTone />} text={item.star} />,
            <IconText key="like" icon={<LikeOutlined />} text={item.like} />,
            <IconText
              key="message"
              icon={<MessageFilled />}
              text={item.message}
            />,
          ]}
        >
          <List.Item.Meta
            title={
              <a className={styles.listItemMetaTitle} href={item.href}>
                {item.title}
              </a>
            }
            description={
              <Flex wrap gap="small">
                <Tag>Ant Design</Tag>
                <Tag>{i18nText("app.account.articles.d32a4d57")}</Tag>
                <Tag>{i18nText("app.account.articles.d2c86c67")}</Tag>
              </Flex>
            }
          />
          <ArticleListContent data={item} />
        </List.Item>
      )}
    />
  );
};
export default Articles;
