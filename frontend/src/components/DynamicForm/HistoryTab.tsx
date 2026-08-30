import React from 'react';
import {Descriptions} from 'antd';

interface HistoryTabProps {
    data: {
        createTime?: string;
        createBy?: string;
        updateTime?: string;
        updateBy?: string;
    };
}

const HistoryTab: React.FC<HistoryTabProps> = ({data}) => {
    return (
        <Descriptions title="记录信息" column={2} bordered>
            <Descriptions.Item label="创建时间">{data.createTime || '暂无'}</Descriptions.Item>
            <Descriptions.Item label="创建人">{data.createBy || '暂无'}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{data.updateTime || '暂无'}</Descriptions.Item>
            <Descriptions.Item label="更新人">{data.updateBy || '暂无'}</Descriptions.Item>
        </Descriptions>
    );
};

export default HistoryTab;
