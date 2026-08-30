import React from "react";
import {Skeleton} from 'antd';

interface ConversationSkeletonListProps {
  length?: number;
}

// ─── 骨架屏 ───
const ConversationSkeletonList: React.FC<ConversationSkeletonListProps> = ({length = 6}) => {
  return (
    <>
      {Array.from({length}).map((_, i) => (
        <div key={i} className="flex items-center gap-2 px-3 py-2.5">
          <Skeleton.Avatar active size={36}/>
          <div style={{flex: 1}}>
            <Skeleton.Input active size="small" style={{width: 150, marginBottom: 4}}/>
            <Skeleton.Input active size="small" style={{width: 200, height: 16}}/>
          </div>
        </div>
      ))}
    </>
  );
};
export default ConversationSkeletonList;
