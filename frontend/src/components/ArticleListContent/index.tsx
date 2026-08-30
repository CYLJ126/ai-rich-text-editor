import {i18nText} from '@/utils/i18n';
import { Avatar } from 'antd';
import dayjs from 'dayjs';
import React from 'react';
import useStyles from './index.style';

export type ArticleListContentProps = {
  data: {
    content?: React.ReactNode;
    updatedAt?: number;
    avatar?: string;
    owner?: string;
    href?: string;
  };
};

const ArticleListContent: React.FC<ArticleListContentProps> = ({
  data: { content, updatedAt, avatar, owner, href },
}) => {
  const { styles } = useStyles();
  return (
    <div>
      <div className={styles.description}>{content}</div>
      <div className={styles.extra}>
        <Avatar src={avatar} size="small" />
        <a href={href}>{owner}</a> {i18nText("app.common.articlelistcontent.fea05c1d")} <a href={href}>{href}</a>
        <em>{dayjs(updatedAt).format('YYYY-MM-DD HH:mm')}</em>
      </div>
    </div>
  );
};

export default ArticleListContent;
