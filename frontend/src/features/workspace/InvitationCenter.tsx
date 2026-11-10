import React, {useEffect, useState} from 'react';
import {Alert, Button, Flex, Modal} from 'antd';
import {getMyPendingInvitationCounts} from '@/services/platform/membership';
import {MembershipInbox} from './MembershipPanel';
import {MembershipSession, useMembershipSession} from './MembershipSession';

/** Account-scoped notice; only counts are read until the user opens their inbox. */
export default function InvitationCenter({userId}: { userId: string }) {
  const [open, setOpen] = useState(false);
  const [count, setCount] = useState<number>();
  const [error, setError] = useState(false);
  const [closed, setClosed] = useState(false);
  useEffect(() => {
    let request: AbortController | undefined;
    let ended = false;
    const read = () => {
      if (ended) return;
      request?.abort();
      const current = new AbortController();
      request = current;
      void getMyPendingInvitationCounts({signal: current.signal}).then(value => {
        if (!current.signal.aborted) {
          setCount(value.tenant + value.workspace);
          setError(false);
        }
      }).catch(() => {
        if (!current.signal.aborted) {
          setCount(undefined);
          setError(true);
        }
      });
    };
    const logout = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== userId) return;
      ended = true;
      request?.abort();
      setOpen(false);
      setClosed(true);
      setCount(undefined);
    };
    const changed = (event: Event) => {
      if ((event as CustomEvent<string>).detail === userId) read();
    };
    read();
    window.addEventListener('arte-membership-invitations-changed', changed);
    window.addEventListener('focus', read);
    window.addEventListener('arte-account-session-cleared', logout);
    const timer = window.setInterval(read, 60000);
    return () => {
      ended = true;
      request?.abort();
      window.clearInterval(timer);
      window.removeEventListener('arte-membership-invitations-changed', changed);
      window.removeEventListener('focus', read);
      window.removeEventListener('arte-account-session-cleared', logout);
    };
  }, [userId, open]);
  if (closed) return null;
  return <>
    <Button size="small" onClick={() => setOpen(true)}>{count ? `邀请 · ${count} 条待处理` : '我的邀请'}</Button>
    <Modal title="我的邀请" open={open} onCancel={() => setOpen(false)} footer={null} width={850} destroyOnHidden>
      {open && <MembershipSession key={userId} userId={userId}><InboxContent error={error}/></MembershipSession>}
    </Modal>
  </>;
}

function InboxContent({error}: { error: boolean }) {
  // Status uses the same durable command recovery as the configuration pages.
  return <Flex vertical gap={12}>
    {error && <Alert type="warning" title="邀请数量暂时无法读取，可在下方重新读取邀请。"/>}
    <MembershipStatus/>
    <MembershipInbox workspace initiallyOpen/>
    <MembershipInbox workspace={false} initiallyOpen/>
  </Flex>;
}

function MembershipStatus() {
  return useMembershipSession()?.status;
}
