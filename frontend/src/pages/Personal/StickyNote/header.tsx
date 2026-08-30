import React from 'react';
import {PlusSquareOutlined, ReloadOutlined} from '@ant-design/icons';
import styles from './header.less';
import {useStickyNoteData} from './stickyNoteContext';
import {TimeHeader} from '@/components';
import {MyTime} from '@/components/TimeHeader';
import {Cascader} from 'antd';

export default function Header() {
  const {
    whichDay,
    setWhichDay,
    fetchStickies,
    addBlankOne,
    stickyTags,
    queryParam,
    setQueryParam,
    isLoading,
  } = useStickyNoteData();

  /**
   * 处理时间改变事件，刷新活动列表页，及刷新头部信息
   * @param myTime 新的时间
   */
  async function handleTimeChange(myTime: MyTime) {
    setWhichDay(myTime);
    await fetchStickies({endDate: myTime.label});
  }

  /**
   * 处理标签改变事件，标签支持多选
   * @param value 选中的标签列表
   */
  function handleTagChange(value: any[]) {
    console.log('选中标签-带路径：', value);
    let tags: string[] = [];
    value.forEach((tagPath) => {
      tags.push(tagPath[tagPath.length - 1]);
    });
    console.log('选中标签：', tags);
    setQueryParam({...queryParam, tags: tags});
  }

  return (
    <TimeHeader onTimeChange={handleTimeChange} myTime={whichDay}>
      {/* 添加新便笺 */}
      <PlusSquareOutlined
        className={styles.plusItem}
        onClick={addBlankOne}
        style={{opacity: isLoading ? 0.5 : 1}}
      />
      {/* 刷新便笺列表 */}
      <ReloadOutlined
        className={styles.refresh}
        onClick={() => fetchStickies({endDate: whichDay.label})}
        style={{opacity: isLoading ? 0.5 : 1}}
        spin={isLoading}
      />
      <Cascader
        className={styles.tagCascader}
        options={stickyTags}
        expandTrigger="hover"
        onChange={handleTagChange}
        fieldNames={{label: 'name', value: 'id'}}
        multiple
        disabled={isLoading}
      />
      <hr className={styles.headerLine}/>
    </TimeHeader>
  );
}
