import {i18nText} from '@/utils/i18n';
import React, {useState} from "react";
import {Form, Input, message, Modal} from "antd";
import {changePassword} from "@/services/ant-design-pro/base";
import {history} from "@@/core/history";
import {useModel} from "@@/exports";
import {GMCrypto} from "@/utils/crypto/gmCrypto";

/** 修改密码弹窗 */
const ChangePasswordModal: React.FC<{
  open: boolean;
  onCancel: () => void;
}> = ({open, onCancel}) => {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const {initialState} = useModel('@@initialState');
  const {currentUser} = initialState ?? {};

  const handleOk = async () => {
    try {
      const values = await form.validateFields();
      setLoading(true);

      let pubKey = localStorage.getItem('platform-public-key');
      if (!pubKey) {
        message.error(i18nText("app.account.components.changepasswordmodal.192f052e")).then();
        return;
      }
      if (!currentUser?.userName) {
        message.error(i18nText("app.account.components.changepasswordmodal.6daa422a")).then();
        return;
      }

      const result = await changePassword({
        userName: currentUser?.userName,
        oldPassword: GMCrypto.sm2Encrypt(values.oldPassword, pubKey),
        newPassword: GMCrypto.sm2Encrypt(values.newPassword, pubKey),
      });

      if (result === true) {
        message.success(i18nText("app.account.components.changepasswordmodal.07487ab2"));
        localStorage.removeItem('user_token');
        onCancel();
        history.push('/user/login');
      } else {
        message.error(i18nText("app.account.components.changepasswordmodal.254b3180"));
      }
    } catch (error) {
      // 表单校验失败或接口异常，不处理弹窗关闭
      if (error && (error as any).errorFields) {
        // 表单校验错误，antd 自动展示，无需额外处理
        return;
      }
      message.error(i18nText("app.account.components.changepasswordmodal.d005f2ab"));
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    form.resetFields();
    onCancel();
  };

  return (
    <Modal
      title={i18nText("app.account.components.changepasswordmodal.4b44b122")}
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      okText={i18nText("app.account.components.changepasswordmodal.01a20a77")}
      cancelText={i18nText("app.account.components.changepasswordmodal.df6b77ee")}
      confirmLoading={loading}
      destroyOnHidden={true}
    >
      <Form
        form={form}
        layout="vertical"
        autoComplete="off"
        style={{marginTop: 16}}
      >
        <Form.Item
          label={i18nText("app.account.components.changepasswordmodal.aec1363e")}
          name="oldPassword"
          rules={[
            {required: true, message: i18nText("app.account.components.changepasswordmodal.de68d7c6")},
            {min: 6, message: i18nText("app.account.components.changepasswordmodal.3400d748")},
          ]}
        >
          <Input.Password placeholder={i18nText("app.account.components.changepasswordmodal.de68d7c6")}/>
        </Form.Item>

        <Form.Item
          label={i18nText("app.account.components.changepasswordmodal.61595278")}
          name="newPassword"
          rules={[
            {required: true, message: i18nText("app.account.components.changepasswordmodal.4494a4a9")},
            {min: 6, message: i18nText("app.account.components.changepasswordmodal.3400d748")},
            ({getFieldValue}) => ({
              validator(_, value) {
                if (!value || getFieldValue('oldPassword') !== value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(i18nText("app.account.components.changepasswordmodal.43a36df3")));
              },
            }),
          ]}
        >
          <Input.Password placeholder={i18nText("app.account.components.changepasswordmodal.4494a4a9")}/>
        </Form.Item>

        <Form.Item
          label={i18nText("app.account.components.changepasswordmodal.fcf61618")}
          name="confirmPassword"
          dependencies={['newPassword']}
          rules={[
            {required: true, message: i18nText("app.account.components.changepasswordmodal.cc2213b2")},
            ({getFieldValue}) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error(i18nText("app.account.components.changepasswordmodal.4adb4fff")));
              },
            }),
          ]}
        >
          <Input.Password placeholder={i18nText("app.account.components.changepasswordmodal.cc2213b2")}/>
        </Form.Item>
      </Form>
    </Modal>
  );
};
export default ChangePasswordModal;
