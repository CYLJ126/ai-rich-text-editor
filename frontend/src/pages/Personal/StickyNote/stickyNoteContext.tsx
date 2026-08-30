import { i18nText } from '@/utils/i18n';
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  addSticky,
  deleteSticky,
  listStickies,
} from '@/services/ant-design-pro/dailyWork';
import { MyTime } from '@/components/TimeHeader';
import dayjs from 'dayjs';
import { message } from 'antd';
import { StickyNoteInfo } from './type';
import { TagItem } from '@/components/TagsSelector';

interface StickyNoteContextType {
  stickyTags: TagItem[];
  queryParam: object;
  setQueryParam: (queryParam: object) => void;
  stickies: StickyNoteInfo[];
  fetchStickies: (queryParam: object) => Promise<void>;
  addBlankOne: () => Promise<void>;
  whichDay: MyTime;
  setWhichDay: (whichDay: MyTime) => void;
  deleteLogical: (id: number) => Promise<void>;
  isLoading: boolean;
}

const StickyNoteContext = createContext<StickyNoteContextType | undefined>(
  undefined,
);

export function StickyNoteProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const cached = localStorage.getItem('stickyTags');
  const stickyTags = cached ? JSON.parse(cached) : [];
  const [whichDay, setWhichDay] = useState<MyTime>({
    type: 'day',
    value: dayjs(),
    time: dayjs(),
    label: dayjs().format('YYYY-MM-DD'),
  });
  const [queryParam, setQueryParam] = useState({});
  const [stickies, setStickies] = useState<StickyNoteInfo[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  // 向后端查询便笺
  const fetchStickies = useCallback(
    async (param: any) => {
      setIsLoading(true);
      try {
        const res = await listStickies({ ...queryParam, ...param, size: 100 });
        setStickies((res?.records as StickyNoteInfo[]) || []);
      } catch (error) {
        console.error('获取便笺失败:', error);
        setStickies([]);
      } finally {
        setIsLoading(false);
      }
    },
    [queryParam],
  );

  // 添加空白便笺
  const addBlankOne = useCallback(async () => {
    const stickyNote = {
      title: i18nText('app.common.stickynote.stickynotecontext.ac8e3c4a'),
      content: i18nText('app.common.stickynote.stickynotecontext.ee153502'),
      width: 300,
      height: 200,
      x: 0,
      y: 0,
    };
    try {
      await addSticky(stickyNote);
      await fetchStickies({ endDate: whichDay.label });
    } catch (error) {
      message
        .error(i18nText('app.common.stickynote.stickynotecontext.faa6ce38'))
        .then();
    }
  }, [fetchStickies, whichDay]);

  // 逻辑删除便笺
  const deleteLogical = useCallback(
    async (stickyId: number) => {
      try {
        const res = await deleteSticky({ id: stickyId });
        if (res) {
          await fetchStickies({ endDate: whichDay.label });
        } else {
          message
            .warning(
              i18nText('app.sticky.delete.failedWithId', { value0: stickyId }),
            )
            .then();
        }
      } catch (error) {
        message
          .error(
            i18nText('app.sticky.delete.failed', { value0: String(error) }),
          )
          .then();
      }
    },
    [fetchStickies, whichDay],
  );

  const value = useMemo(
    () => ({
      stickyTags,
      queryParam,
      setQueryParam,
      stickies,
      fetchStickies,
      addBlankOne,
      whichDay,
      setWhichDay,
      deleteLogical,
      isLoading,
    }),
    [
      queryParam,
      stickies,
      addBlankOne,
      fetchStickies,
      whichDay,
      isLoading,
      deleteLogical,
    ],
  );

  useEffect(() => {
    fetchStickies({ endDate: dayjs().format('YYYY-MM-DD') }).then();
  }, [queryParam]);

  return (
    <StickyNoteContext.Provider value={value}>
      {children}
    </StickyNoteContext.Provider>
  );
}

// 自定义 hook 方便使用
export function useStickyNoteData() {
  const context = useContext(StickyNoteContext);
  if (!context) {
    throw new Error('useStickyNoteData must be used within a DataProvider');
  }
  return context;
}
