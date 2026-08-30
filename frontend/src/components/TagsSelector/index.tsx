import {i18nText} from '@/utils/i18n';
import {MinusCircleOutlined} from '@ant-design/icons';
import {Badge, Button, Cascader, Checkbox, Select} from 'antd';
import React, {useMemo, useState} from 'react';
import styles from './index.less';

export interface TagItem {
  id: number;
  name: string;
  children?: TagItem[];
}

export interface MyTagProps {
  tag: TagItem;
  clickTag?: (tag: TagItem) => void;
  removeTag?: (tag: TagItem) => void;
  color: string;
  buttonStyle?: React.CSSProperties;
}

const MyTag: React.FC<MyTagProps> = ({
                                       tag,
                                       clickTag,
                                       removeTag,
                                       color,
                                       buttonStyle = {},
                                     }) => {
  const myButtonStyle: React.CSSProperties = {
    height: 20,
    color: '#ffffff',
    backgroundColor: color,
    whiteSpace: 'nowrap',
    border: 'none',
    borderRadius: '20%',
    fontSize: 14,
    ...buttonStyle,
  };

  return (
    <Badge
      count={
        <MinusCircleOutlined
          className={styles.closeIcon}
          onClick={(e) => {
            removeTag?.(tag);
            e.stopPropagation();
          }}
        />
      }
    >
      <Button onClick={() => clickTag?.(tag)} style={myButtonStyle}>
        {tag.name}
      </Button>
    </Badge>
  );
};

interface TagsSelectorProps {
  selectTag?: (tag: TagItem) => void;
  onChange?: (tags: TagItem[]) => void;
  options: TagItem[];
  width?: React.CSSProperties['width'];
}

/**
 * 标签选择器
 * @param selectTag 兼容旧调用：新增一个标签时触发
 * @param onChange 选择变化时返回当前所有已选标签
 * @param options 标签选项
 * @param width 输入框宽度
 */
const TagsSelector: React.FC<TagsSelectorProps> = ({
                                                     selectTag,
                                                     onChange,
                                                     options,
                                                     width = 250,
                                                   }) => {
  const [selectedTags, setSelectedTags] = useState<TagItem[]>([]);

  const allTags = useMemo(() => {
    const result: TagItem[] = [];

    const collectTags = (items: TagItem[]) => {
      items.forEach((item) => {
        result.push(item);
        if (item.children?.length) {
          collectTags(item.children);
        }
      });
    };

    collectTags(options);
    return result;
  }, [options]);

  const tagById = useMemo(
    () => new Map(allTags.map((tag) => [tag.id, tag])),
    [allTags],
  );
  const selectedIds = useMemo(
    () => new Set(selectedTags.map((tag) => tag.id)),
    [selectedTags],
  );

  const updateSelectedTags = (nextTags: TagItem[]) => {
    setSelectedTags(nextTags);
    onChange?.(nextTags);
  };

  const toggleTag = (tag: TagItem) => {
    const checked = selectedIds.has(tag.id);
    const nextTags = checked
      ? selectedTags.filter((item) => item.id !== tag.id)
      : [...selectedTags, tag];

    updateSelectedTags(nextTags);

    if (!checked) {
      selectTag?.(tag);
    }
  };

  const handleSelectChange = (ids: number[]) => {
    const nextTags = ids
      .map((id) => tagById.get(id))
      .filter((tag): tag is TagItem => Boolean(tag));

    updateSelectedTags(nextTags);
  };

  return (
    <Select<number[]>
      className={styles.cascader}
      style={{width}}
      mode="multiple"
      value={selectedTags.map((tag) => tag.id)}
      options={allTags.map((tag) => ({label: tag.name, value: tag.id}))}
      onChange={handleSelectChange}
      placeholder={i18nText("app.common.tagsselector.c7484dbc")}
      showSearch={false}
      allowClear
      popupMatchSelectWidth={false}
      popupRender={() => (
        <Cascader.Panel
          options={options}
          expandTrigger="hover"
          changeOnSelect
          fieldNames={{label: 'name', value: 'id'}}
          optionRender={(option) => (
            <>
              <Checkbox
                className={styles.optionCheckbox}
                checked={selectedIds.has(option.id as number)}
                style={{pointerEvents: 'none'}}
              />
              <span>{option.name}</span>
            </>
          )}
          onChange={(_, selectedOptions) => {
            const selectedTag = selectedOptions[selectedOptions.length - 1] as
              | TagItem
              | undefined;

            if (selectedTag) {
              toggleTag(selectedTag);
            }
          }}
        />
      )}
    />
  );
};

export default TagsSelector;
export { MyTag };
