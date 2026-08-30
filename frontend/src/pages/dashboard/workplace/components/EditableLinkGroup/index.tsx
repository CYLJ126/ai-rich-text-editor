import {i18nText} from '@/utils/i18n';
import { PlusOutlined } from '@ant-design/icons';
import { Button } from 'antd';
import React, { createElement } from 'react';
import useStyles from './index.style';
export type EditableLink = {
  title: string;
  href: string;
  id?: string;
};
type EditableLinkGroupProps = {
  onAdd: () => void;
  links: EditableLink[];
  linkElement: any;
};
const EditableLinkGroup: React.FC<EditableLinkGroupProps> = (props) => {
  const { styles } = useStyles();
  const { links = [], linkElement = 'a', onAdd = () => {} } = props;
  return (
    <div className={styles.linkGroup}>
      {links.map((link) =>
        createElement(
          linkElement,
          {
            key: `linkGroup-item-${link.id || link.title}`,
            to: link.href,
            href: link.href,
          },
          link.title,
        ),
      )}
      <Button size="small" type="primary" ghost onClick={onAdd}>
        <PlusOutlined /> {i18nText("app.dashboard.editablelinkgroup.14ffdee3")}
      </Button>
    </div>
  );
};

export default EditableLinkGroup;
