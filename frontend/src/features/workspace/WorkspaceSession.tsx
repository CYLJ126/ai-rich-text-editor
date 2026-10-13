import {useEffect, useRef} from 'react';
import {clearAccountSession} from '@/features/ainew/storage';

/** Covers account changes outside ChatNew as well as the explicit logout action. */
export default function WorkspaceSession({userId}: { userId: string }) {
  const previous = useRef(userId);
  useEffect(() => {
    if (previous.current && previous.current !== userId) clearAccountSession(previous.current);
    previous.current = userId;
  }, [userId]);
  return null;
}
