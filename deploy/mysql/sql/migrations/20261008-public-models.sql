-- 已有数据库升级时执行一次；已有模型保留为私有配置。
ALTER TABLE arte_ai_model_config
    ADD COLUMN public_flag tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否服务端公共模型';

create table arte_ai_model_access
(
    model_config_id int not null,
    subject_type varchar(8) not null comment 'user 或 role',
    subject varchar(64) not null comment '用户名或角色编码',
    primary key (model_config_id, subject_type, subject),
    constraint fk_model_access_config foreign key (model_config_id)
        references arte_ai_model_config (id) on delete cascade
) comment '公共模型授权；无授权记录时所有登录用户可用';
