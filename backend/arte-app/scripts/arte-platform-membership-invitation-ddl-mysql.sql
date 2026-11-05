-- C11. Install after arte-platform-workspace-ddl-mysql.sql.
CREATE TABLE IF NOT EXISTS arte_platform_membership_invitation
(
    id
    VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    tenant_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    workspace_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    target_subject_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    issued_by VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    role INT NOT NULL,
    state INT NOT NULL,
    expires_at DATETIME
(
    6
) NOT NULL,
    accepted_member_id VARCHAR
(
    256
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    version BIGINT NOT NULL,
    KEY ix_platform_membership_invitation_scope
(
    tenant_id,
    workspace_id
),
    KEY ix_platform_membership_invitation_target
(
    target_subject_id,
    id
),
    FOREIGN KEY
(
    target_subject_id
) REFERENCES arte_platform_principal_identity
(
    subject_id
),
    FOREIGN KEY
(
    issued_by
) REFERENCES arte_platform_principal_identity
(
    subject_id
),
    FOREIGN KEY
(
    tenant_id
) REFERENCES arte_platform_tenant
(
    id
),
    FOREIGN KEY
(
    tenant_id,
    workspace_id
) REFERENCES arte_platform_workspace
(
    tenant_id,
    id
),
    CHECK
(
    role =
    2
    OR
(
    workspace_id
    IS
    NOT
    NULL
    AND
    role =
    3
)),
    CHECK
(
    state
    BETWEEN
    0
    AND
    3
    AND
    version
    >=
    0
),
    CHECK
(
(
    state =
    1
    AND
    accepted_member_id
    IS
    NOT
    NULL
) OR
(
    state
    <>
    1
    AND
    accepted_member_id
    IS
    NULL
))
    ) ENGINE=InnoDB;
