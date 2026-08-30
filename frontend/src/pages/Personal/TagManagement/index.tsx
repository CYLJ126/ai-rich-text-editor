import React from 'react';
import MyTagTree from "@/components/MyTagTree";
import {useComponentHeight} from "@/utils/useDynamicHeight";

const TagManagement: React.FC = () => {
  let tagPageHeight = useComponentHeight(50, 500);
  return (
    <MyTagTree pageHeight={tagPageHeight}/>
  )
}

export default TagManagement;
