import React from 'react';
import WebsiteInfos from './WebsiteInfos';
import KeepAlive from 'react-activation';

function HomePage() {
  return (
    <div>
      <WebsiteInfos />
    </div>
  );
}

export default () => {
  return (
    <KeepAlive name="/HomePage">
      <HomePage />
    </KeepAlive>
  );
};
