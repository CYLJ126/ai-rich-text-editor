# ARTE

[English](README.md) | [简体中文](README.zh-CN.md)

ARTE（AI Rich Text Editor）是一个由 Java 后端和 Web 前端组成的 AI 辅助富文本编辑项目。

## 仓库结构

- `backend/`：基于 Java 21 的 Maven 多模块项目。
- `frontend/`：Web 客户端。
- `deploy/`：容器及数据库部署文件。

后端和部署说明请参阅 [backend/README.md](backend/README.md) 和
[deploy/DOCKER_DEPLOY.md](deploy/DOCKER_DEPLOY.md)。

## 部署前的安全配置

请将示例配置复制为被 Git 忽略的本地配置文件，并在服务接入网络前替换所有占位值和默认凭据。
不要提交 API 密钥、数据库密码、JWT 密钥、加密密钥或 `.env` 文件。

安全问题请按照 [SECURITY.md](SECURITY.md) 中的方式报告。

## 许可证

除非文件或目录中另有说明，对于 ARTE 版权所有者有权授权的仓库内容，均采用
[MIT License](LICENSE) 发布，第三方库、模型、素材和部署组件分别适用其各自许可证。

MIT License **不会**取代或覆盖第三方代码、依赖库、素材、模型或服务的许可证、版权声明及
署名要求。特别是，部分后端源文件保留了其上游项目的 Apache-2.0 声明，运行时依赖也继续
适用各自的许可证。详情请参阅 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 和
[LICENSES/](LICENSES/)。

发布构建产物前，应针对实际解析出的精确依赖版本重新生成并审核完整的依赖及许可证清单。
仓库中的第三方声明是人工维护的摘要，不能替代发布时的正式审查。
