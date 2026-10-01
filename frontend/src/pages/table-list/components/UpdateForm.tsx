import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
} from '@ant-design/pro-components';
import {FormattedMessage} from '@umijs/max';
import {message} from 'antd';
import type {ReactElement} from 'react';
import {updateRule} from '@/services/ant-design-pro/api';

export interface UpdateFormProps {
  trigger: ReactElement;
  onOk?: () => void | Promise<void>;
  values: API.RuleListItem;
}

const UpdateForm: React.FC<UpdateFormProps> = ({trigger, onOk, values}) => (
  <ModalForm<API.RuleListItem>
    title={
      <FormattedMessage
        id="pages.searchTable.updateForm.title"
        defaultMessage="Update rule"
      />
    }
    trigger={trigger}
    initialValues={values}
    modalProps={{destroyOnHidden: true}}
    onFinish={async (formValues) => {
      await updateRule({...values, ...formValues});
      await onOk?.();
      message.success('Updated successfully');
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
      options={[
        {label: 'Shut down', value: 0},
        {label: 'Running', value: 1},
        {label: 'Online', value: 2},
        {label: 'Abnormal', value: 3},
      ]}
    />
  </ModalForm>
);

export default UpdateForm;
