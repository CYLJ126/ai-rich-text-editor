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
        message.error('获取公钥失败，请联系管理员').then();
        return;
      }
      if (!currentUser?.userName) {
        message.error('获取用户名失败，请联系管理员').then();
        return;
      }

      const result = await changePassword({
        userName: currentUser?.userName,
        oldPassword: GMCrypto.sm2Encrypt(values.oldPassword, pubKey),
        newPassword: GMCrypto.sm2Encrypt(values.newPassword, pubKey),
      });

      if (result === true) {
        message.success('密码修改成功，请重新登录');
        localStorage.removeItem('user_token');
        onCancel();
        history.push('/user/login');
      } else {
        message.error('修改失败，请检查原密码是否正确');
      }
    } catch (error) {
      // 表单校验失败或接口异常，不处理弹窗关闭
      if (error && (error as any).errorFields) {
        // 表单校验错误，antd 自动展示，无需额外处理
        return;
      }
      message.error('修改失败，请稍后重试');
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
      title="修改密码"
      open={open}
      onOk={handleOk}
      onCancel={handleCancel}
      okText="确认修改"
      cancelText="取消"
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
          label="原密码"
          name="oldPassword"
          rules={[
            {required: true, message: '请输入原密码'},
            {min: 6, message: '密码长度不能少于 6 位'},
          ]}
        >
          <Input.Password placeholder="请输入原密码"/>
        </Form.Item>

        <Form.Item
          label="新密码"
          name="newPassword"
          rules={[
            {required: true, message: '请输入新密码'},
            {min: 6, message: '密码长度不能少于 6 位'},
            ({getFieldValue}) => ({
              validator(_, value) {
                if (!value || getFieldValue('oldPassword') !== value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('新密码不能与原密码相同'));
              },
            }),
          ]}
        >
          <Input.Password placeholder="请输入新密码"/>
        </Form.Item>

        <Form.Item
          label="确认新密码"
          name="confirmPassword"
          dependencies={['newPassword']}
          rules={[
            {required: true, message: '请再次输入新密码'},
            ({getFieldValue}) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('两次输入的新密码不一致'));
              },
            }),
          ]}
        >
          <Input.Password placeholder="请再次输入新密码"/>
        </Form.Item>
      </Form>
    </Modal>
  );
};
export default ChangePasswordModal;
