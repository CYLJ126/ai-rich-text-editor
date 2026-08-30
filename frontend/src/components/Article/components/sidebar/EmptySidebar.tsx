import {Empty} from "antd";
import React from "react";

const EmptySidebar: React.FC = () => {
  return (
    <div className="h-full flex justify-center items-center">
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先选择一篇文章"/>
    </div>
  );
}
export default EmptySidebar;
