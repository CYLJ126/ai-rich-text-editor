-- 已有 app 库升级：账号主键扩大为有符号 BIGINT，保留现有值、自增及主键约束。
-- 新建库使用 arte-rbac-ddl-mysql.sql，不需要再执行本脚本。
-- 部署时先升级数据库，再整体切换应用；勿在新旧代码混跑期间创建超出 INT 范围的账号。
-- 此脚本不改 subjectId、身份映射、用户名授权关系或其他资源的主键。
ALTER TABLE arte_rbac_user
    MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键';
