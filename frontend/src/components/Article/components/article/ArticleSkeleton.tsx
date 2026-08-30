import { Card, Col, Row, Skeleton } from 'antd';
import React, { type FC } from 'react';

export interface ArticleSkeletonProps {
  showType: 'cards' | 'list';
}

// cards 模式：4列 * 2行 = 8个
const cardSkeletonKeys = Array.from(
  { length: 8 },
  (_, i) => `card-skeleton-${i + 1}`,
);

// list 模式：1列 * 3行 = 3个
const listSkeletonKeys = Array.from(
  { length: 3 },
  (_, i) => `list-skeleton-${i + 1}`,
);

const ArticleSkeleton: FC<ArticleSkeletonProps> = ({ showType }) => {
  if (showType === 'cards') {
    return (
      <Row gutter={[16, 16]} className="w-full">
        {cardSkeletonKeys.map((key) => (
          <Col key={key} xs={24} sm={12} lg={6}>
            <Card className="h-[282]">
              <Skeleton active paragraph={{ rows: 3 }} />
            </Card>
          </Col>
        ))}
      </Row>
    );
  }

  return (
    <Row gutter={[0, 16]}>
      {listSkeletonKeys.map((key) => (
        <Col key={key} span={24}>
          <Card className="w-full">
            <Skeleton active paragraph={{ rows: 3 }} />
          </Card>
        </Col>
      ))}
    </Row>
  );
};

export default ArticleSkeleton;
