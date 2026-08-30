import {i18nText} from '@/utils/i18n';
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
        <Descriptions title={i18nText("app.common.dynamicform.historytab.918bfa8e")} column={2} bordered>
            <Descriptions.Item label={i18nText("app.common.dynamicform.historytab.153c61e2")}>{data.createTime || i18nText("app.common.dynamicform.historytab.e63d8ef0")}</Descriptions.Item>
            <Descriptions.Item label={i18nText("app.common.dynamicform.historytab.a1f234ee")}>{data.createBy || i18nText("app.common.dynamicform.historytab.e63d8ef0")}</Descriptions.Item>
            <Descriptions.Item label={i18nText("app.common.dynamicform.historytab.2f1f93e4")}>{data.updateTime || i18nText("app.common.dynamicform.historytab.e63d8ef0")}</Descriptions.Item>
            <Descriptions.Item label={i18nText("app.common.dynamicform.historytab.f302cb43")}>{data.updateBy || i18nText("app.common.dynamicform.historytab.e63d8ef0")}</Descriptions.Item>
        </Descriptions>
    );
};

export default HistoryTab;
