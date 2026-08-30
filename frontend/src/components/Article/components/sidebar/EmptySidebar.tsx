import {i18nText} from '@/utils/i18n';
import {Empty} from "antd";
import React from "react";

const EmptySidebar: React.FC = () => {
  return (
    <div className="h-full flex justify-center items-center">
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={i18nText("app.article.sidebar.emptysidebar.bb2aac16")}/>
    </div>
  );
}
export default EmptySidebar;
