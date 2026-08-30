import { Outlet } from '@umijs/max';
import { AliveScope } from 'react-activation';

export default function Layout() {
  return (
    <AliveScope>
      <div id="test-pro-layout">
        <Outlet />
      </div>
    </AliveScope>
  );
}
