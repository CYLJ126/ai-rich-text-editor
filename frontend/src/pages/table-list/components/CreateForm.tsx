import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import {FormattedMessage} from '@umijs/max';
import {Button, message} from 'antd';
import {addRule} from '@/services/ant-design-pro/api';
import React from "react";

export interface CreateFormProps {
  reload?: () => void | Promise<void>;
}

const CreateForm: React.FC<CreateFormProps> = ({reload}) => (
  <ModalForm<API.RuleListItem>
    title={
      <FormattedMessage
        id="pages.searchTable.createForm.newRule"
        defaultMessage="New rule"
      />
    }
    trigger={
      <Button type="primary">
        <FormattedMessage id="pages.searchTable.new" defaultMessage="New"/>
      </Button>
    }
    modalProps={{destroyOnHidden: true}}
    onFinish={async (values) => {
      await addRule(values);
      await reload?.();
      message.success('Created successfully');
      return true;
    }}
  >
    <ProFormText
      name="name"
      label={
        <FormattedMessage
          id="pages.searchTable.updateForm.ruleName.nameLabel"
          defaultMessage="Rule name"
        />
      }
      rules={[{required: true, message: 'Please enter a rule name'}]}
    />
    <ProFormTextArea
      name="desc"
      label={
        <FormattedMessage
          id="pages.searchTable.titleDesc"
          defaultMessage="Description"
        />
      }
    />
    <ProFormSelect
      name="status"
      label={
        <FormattedMessage
          id="pages.searchTable.titleStatus"
          defaultMessage="Status"
        />
      }
      initialValue={0}
      options={[
        {label: 'Shut down', value: 0},
        {label: 'Running', value: 1},
        {label: 'Online', value: 2},
        {label: 'Abnormal', value: 3},
      ]}
    />
  </ModalForm>
);

export default CreateForm;
