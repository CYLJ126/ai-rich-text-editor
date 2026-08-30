/**
 * 由于菜单配置中的按钮无法在前端转换成图标，所以只能通过 Map 映射成图标组件。
 */
import {
  AlertOutlined,
  AppstoreOutlined,
  CrownOutlined,
  HomeOutlined,
  LoadingOutlined,
  ReadOutlined,
  RobotOutlined,
  SettingOutlined,
  SignatureOutlined,
  SmileOutlined,
  TagOutlined,
  ToolOutlined,
  UserOutlined,
} from '@ant-design/icons';
// @Deprecated: 该文件已被废弃，建议使用 DynamicIcon 组件
const IconMap = {
  smile: <SmileOutlined />,
  crown: <CrownOutlined />,
  userOutlined: <UserOutlined />,
  tool: <ToolOutlined />,
  tag: <TagOutlined />,
  loading: <LoadingOutlined />,
  book: <ReadOutlined />,
  administration: <SettingOutlined />,
  alert: <AlertOutlined />,
  appstore: <AppstoreOutlined />,
  homePage: <HomeOutlined />,
  robot: <RobotOutlined />,
  write: <SignatureOutlined/>,
};

export default IconMap;
