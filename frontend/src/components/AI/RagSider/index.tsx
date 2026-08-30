import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {KnowledgeBaseTypeEnum} from "@/types/ai.type";
import {Select} from "antd";
import ArticleRagSider from "./ArticleRagSider";
import {useComponentHeight} from "@/utils/useDynamicHeight";
import {isEmpty} from "@/utils/reflectUtil";
import {listKnowledgeBaseTypes} from "@/services/ant-design-pro/ai.rbac";

export type RagSiderProps = {
  height?: number;
  onSelect?: (chatRagRequest: any) => void;
}

const RagSider: React.FC<RagSiderProps> = ({height, onSelect}) => {
  const [knowledgeBaseType, setKnowledgeBaseType] = useState<KnowledgeBaseTypeEnum>('article');
  const containerHeight = useComponentHeight(95, height || window.innerHeight - 95);
  const [knowledgeBaseOptions, setKnowledgeBaseOptions] = useState<any[]>([]);

  useEffect(() => {
    // 加载知识库下拉
    listKnowledgeBaseTypes().then((res) => {
      res && setKnowledgeBaseOptions(res);
    });
  }, []);

  const handleSelect = useCallback((param: any) => {
    if (isEmpty(param)) {
      // 空对象，清空参数，否则后端会去检索
      onSelect?.({});
    } else {
      onSelect?.({...param, knowledgeBaseType});
    }
  }, [knowledgeBaseType]);

  const ragComponent = useMemo(() => {
    switch (knowledgeBaseType) {
      case 'article':
        return <ArticleRagSider onSelect={handleSelect}/>;
      default:
        return <div>暂不支持</div>;
    }
  }, [knowledgeBaseType]);
  return (
    <div style={{height: containerHeight}} className="overflow-auto scrollbar-none">
      <Select
        value={knowledgeBaseType || ''}
        options={knowledgeBaseOptions}
        className="w-full mb-[10px]!"
        onChange={setKnowledgeBaseType}
      />
      <div className="overflow-auto scrollbar-none" style={{height: containerHeight - 45}}>
        {ragComponent}
      </div>
    </div>
  );
}

export default RagSider;
