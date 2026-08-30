import {i18nText} from '@/utils/i18n';
import {useMutation, useQueryClient} from '@tanstack/react-query';
import {history, Link} from '@umijs/max';
import {Button, Col, Form, Input, message, Popover, Progress, Row, Select, Space,} from 'antd';
import type {Store} from 'antd/es/form/interface';
import type {FC} from 'react';
import {useEffect, useState} from 'react';
import {fakeRegister} from './service';
import useStyles from './styles';

const FormItem = Form.Item;
const { Option } = Select;

const passwordProgressMap: {
  ok: 'success';
  pass: 'normal';
  poor: 'exception';
} = {
  ok: 'success',
  pass: 'normal',
  poor: 'exception',
};

const getPasswordStatus = (value?: string) => {
  if (value && value.length > 9) {
    return 'ok';
  }
  if (value && value.length > 5) {
    return 'pass';
  }
  return 'poor';
};

const PasswordProgress: React.FC<{
  value?: string;
  styles: Record<string, string>;
}> = ({value, styles}) => {
  const passwordStatus = getPasswordStatus(value);
  return value?.length ? (
    <div className={styles[`progress-${passwordStatus}`]}>
      <Progress
        status={passwordProgressMap[passwordStatus]}
        size={6}
        percent={value.length * 10 > 100 ? 100 : value.length * 10}
        showInfo={false}
      />
    </div>
  ) : null;
};

const Register: FC = () => {
  const { styles } = useStyles();
  const [count, setCount]: [number, any] = useState(0);
  const [open, setVisible]: [boolean, any] = useState(false);
  const [prefix, setPrefix]: [string, any] = useState('86');
  const [popover, setPopover]: [boolean, any] = useState(false);
  const confirmDirty = false;
  let interval: number | undefined;
  const queryClient = useQueryClient();

  const passwordStatusMap = {
    ok: (
      <div className={styles.success}>
        <span>{i18nText("app.user.register.e99e63f6")}</span>
      </div>
    ),
    pass: (
      <div className={styles.warning}>
        <span>{i18nText("app.user.register.6cb6303f")}</span>
      </div>
    ),
    poor: (
      <div className={styles.error}>
        <span>{i18nText("app.user.register.ae04324f")}</span>
      </div>
    ),
  };

  const [form] = Form.useForm();
  useEffect(
    () => () => {
      clearInterval(interval);
    },
    [interval],
  );
  const onGetCaptcha = () => {
    let counts = 59;
    setCount(counts);
    interval = window.setInterval(() => {
      counts -= 1;
      setCount(counts);
      if (counts === 0) {
        clearInterval(interval);
      }
    }, 1000);
  };
  const { isPending: submitting, mutate: register } = useMutation({
    mutationFn: (formValues: Store) => {
      const payload = {
        mail: formValues.email,
        password: formValues.password,
        confirm: formValues.confirm,
        mobile: formValues.mobile,
        captcha: formValues.captcha,
        prefix: formValues.prefix,
      };
      return fakeRegister(payload);
    },
    onSuccess: (data, params) => {
      if (data.status === 'ok') {
        queryClient.invalidateQueries({queryKey: ['current-user']});
        message.success(i18nText("app.user.register.a5cd621a"));
        history.push({
          pathname: `/user/register-result?account=${params.mail}`,
        });
      }
    },
  });
  const onFinish = (values: Store) => {
    register(values);
  };
  const checkConfirm = (_: any, value: string) => {
    const promise = Promise;
    if (value && value !== form.getFieldValue('password')) {
      return promise.reject(i18nText("app.user.register.e3f448cb"));
    }
    return promise.resolve();
  };
  const checkPassword = (_: any, value: string) => {
    const promise = Promise;
    // 没有值的情况
    if (!value) {
      setVisible(!!value);
      return promise.reject(i18nText("app.user.register.937768e1"));
    }
    // 有值的情况
    if (!open) {
      setVisible(!!value);
    }
    setPopover(!popover);
    if (value.length < 6) {
      return promise.reject('');
    }
    if (value && confirmDirty) {
      form.validateFields(['confirm']);
    }
    return promise.resolve();
  };
  const changePrefix = (value: string) => {
    setPrefix(value);
  };
  const password = Form.useWatch('password', form);
  const passwordStatus = getPasswordStatus(password);
  return (
    <div className={styles.main}>
      <h3>{i18nText("app.user.register.9e9b663f")}</h3>
      <Form form={form} name="UserRegister" onFinish={onFinish}>
        <FormItem
          name="email"
          rules={[
            {
              required: true,
              message: i18nText("app.user.register.ed7c6b00"),
            },
            {
              type: 'email',
              message: i18nText("app.user.register.c694e888"),
            },
          ]}
        >
          <Input size="large" placeholder={i18nText("app.user.register.749cf570")} />
        </FormItem>
        <Popover
          getPopupContainer={(node) => {
            if (node?.parentNode) {
              return node.parentNode as HTMLElement;
            }
            return node;
          }}
          content={
            open && (
              <div
                style={{
                  padding: '4px 0',
                }}
              >
                {passwordStatusMap[passwordStatus]}
                <PasswordProgress value={password} styles={styles}/>
                <div
                  style={{
                    marginTop: 10,
                  }}
                >
                  <span>{i18nText("app.user.register.33a1be33")}</span>
                </div>
              </div>
            )
          }
          overlayStyle={{
            width: 240,
          }}
          placement="right"
          open={open}
        >
          <FormItem
            name="password"
            className={
              form.getFieldValue('password') &&
              form.getFieldValue('password').length > 0 &&
              styles.password
            }
            rules={[
              {
                validator: checkPassword,
              },
            ]}
          >
            <Input
              size="large"
              type="password"
              placeholder={i18nText("app.user.register.d3723e22")}
            />
          </FormItem>
        </Popover>
        <FormItem
          name="confirm"
          rules={[
            {
              required: true,
              message: i18nText("app.user.register.eca1e2f1"),
            },
            {
              validator: checkConfirm,
            },
          ]}
        >
          <Input size="large" type="password" placeholder={i18nText("app.user.register.eca1e2f1")} />
        </FormItem>
        <FormItem
          name="mobile"
          rules={[
            {
              required: true,
              message: i18nText("app.user.register.063458db"),
            },
            {
              pattern: /^\d{11}$/,
              message: i18nText("app.user.register.4b20f201"),
            },
          ]}
        >
          <Space.Compact style={{ width: '100%' }}>
            <Select
              size="large"
              value={prefix}
              onChange={changePrefix}
              style={{
                width: '30%',
              }}
            >
              <Option value="86">+86</Option>
              <Option value="87">+87</Option>
            </Select>

            <Input size="large" placeholder={i18nText("app.user.register.b4e2531c")} />
          </Space.Compact>
        </FormItem>
        <Row gutter={8}>
          <Col span={16}>
            <FormItem
              name="captcha"
              rules={[
                {
                  required: true,
                  message: i18nText("app.user.register.4cd908cb"),
                },
              ]}
            >
              <Input size="large" placeholder={i18nText("app.user.register.e45bd1a8")} />
            </FormItem>
          </Col>
          <Col span={8}>
            <Button
              size="large"
              disabled={!!count}
              className={styles.getCaptcha}
              onClick={onGetCaptcha}
            >
              {count ? `${count} s` : i18nText("app.user.register.65ba0858")}
            </Button>
          </Col>
        </Row>
        <FormItem>
          <div className={styles.footer}>
            <Button
              size="large"
              loading={submitting}
              className={styles.submit}
              type="primary"
              htmlType="submit"
            >
              <span>{i18nText("app.user.register.9e9b663f")}</span>
            </Button>
            <Link to="/user/login" prefetch>
              <span>{i18nText("app.user.register.859eb883")}</span>
            </Link>
          </div>
        </FormItem>
      </Form>
    </div>
  );
};
export default Register;
