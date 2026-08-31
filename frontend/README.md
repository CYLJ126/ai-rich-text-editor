## 项目结构

**app.tsx**

主入口文件，包含布局、路由、水印等配置，从后端加载菜单的逻辑在此。

**assets**

资源目录，包含全局资源，如图片、字体等。

**config**

配置目录，包含全局配置。routes.ts 废弃，从后端获取。

**components**

组件目录，包含全局组件。

（一）页面和布局等基础组件

- `Footer`：`src/components/Footer` ：页脚组件，用于在页面底部显示页脚信息。
- `HeaderDropdown`：`src/components/HeaderDropdown` ：头部下拉菜单组件，用于在页面顶部显示下拉菜单，包含用户信息、设置等。
- `PageWrapper`：`src/components/PageWrapper` ：页面包装器组件，用于在页面顶部和底部添加页脚和头部下拉菜单。
- `RightContent`：`src/components/RightContent` ：右侧内容组件，用于在页面右侧显示内容。
- `TabsLayout`：`src/components/TabsLayout` ：选项卡布局组件，用于在页面顶部显示选项卡，每个选项卡对应一个路由。

（二）表单、表格等相关组件

- `DynamicForm`：`src/components/DynamicForm` ：动态表单组件，用于根据后端返回的表单配置动态生成表单。
- `SearchForm`：`src/components/SearchForm` ：搜索表单组件，用于在页面顶部显示搜索表单。
- `SimpleTable`：`src/components/SimpleTable` ：简单表格组件，用于在页面显示简单表格。

（三）其他组件

- `MyColorPicker`：`src/components/MyColorPicker` ：颜色选择器组件，用于选择颜色。
- `ProgressBar`：`src/components/ProgressBar` ：进度条组件，用于显示操作进度。
- `TagsSelector`：`src/components/TagsSelector` ：标签选择器组件，用于选择标签。
- `TimeHeader`：`src/components/TimeHeader` ：时间头组件，用于在页面顶部显示时间信息。
- `RichTextEditor`：`src/components/RichTextEditor` ：基于 Tiptap 的富文本编辑器组件，默认支持 Markdown。

**icons**

图标目录，包含全局图标组件，可以自己添加图标，如到 [svgrepo](https://www.svgrepo.com/) 下载图标，然后添加到 `src/icons` 目录下。

app.tsx 中，从后端获取菜单信息并转换成前端路由时，不支持后端指定图标名，转换成对应图标，所以需要一个 IconMap 来映射图标名和图标组件。

**layouts**

布局目录，包含包含全局布局组件，通常不修改。

**locales**

国际化目录，包含全局国际化组件，可以自己添加国际化配置。

一般如果添加了菜单或页面提示信息什么的，需要在 `locales` 目录下添加对应的国际化配置，只添加 US、ZN、TW 三个配置即可。要使用国际化，可：

```ts
import { history, useIntl, useLocation } from '@umijs/max';

const intl = useIntl();
let label = intl.formatMessage(intlCode); // intlCode 要在 locales 中定义，如 menu.HomePage
```

## 页面

`src/pages` 目录下包含所有页面组件。

- `account`：`src/pages/account` ：个人账号管理页面，包含用户信息、密码修改等功能。
- `Administration`：`src/pages/Administration` ：管理员页面，包含用户管理、菜单管理、角色管理等功能。
- `HomePage`：`src/pages/HomePage` ：首页，包含欢迎信息、操作统计等功能。
- `Learn`：`src/pages/Learn` ：学习页面，包含组件使用示例、功能验证等功能。
- `Tools`：`src/pages/Tools` ：工具页面，包含一些常用的工具，如格式化文档等。
  - `CacheTest`：`src/pages/Tools/CacheTest` ：缓存测试页面，用于测试 KeepAlive 页面缓存功能。
- `user`：`src/pages/user` ：用户页面，包含登录页、注册页等。
