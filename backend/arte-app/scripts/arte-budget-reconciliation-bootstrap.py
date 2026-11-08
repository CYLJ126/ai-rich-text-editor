#!/usr/bin/env python3
"""生成一个经人工审阅的具体账户治理授权 SQL；只输出，不连接数据库、不授予核对或全局权限。"""
import argparse
import datetime
import hashlib
import json
import struct
import uuid


def digest(fields, count=False):
    raw = struct.pack('>i', len(fields)) if count else b''
    for field in fields:
        value = str(field).encode('utf-8')
        raw += struct.pack('>i', len(value)) + value
    return hashlib.sha256(raw).hexdigest()


def literal(value):
    return "CONVERT(0x" + str(value).encode('utf-8').hex() + " USING utf8mb4)"


def generate(tenant, workspace, budget_ref, owner, governor, issued_by, generation, operation):
    fields = (tenant, workspace, budget_ref, owner, governor, issued_by, operation)
    if any(not v or len(v) > 256 or '\x00' in v for v in fields) or generation < 0:
        raise ValueError('IDs must contain 1..256 characters; generation must be nonnegative')
    resource = {'tenantId': tenant, 'type': 'budget-account', 'resourceId': budget_ref}
    root = {'resource': resource, 'ownership': {'type': 1, 'tenantId': tenant, 'workspaceId': workspace}, 'visibility': 0, 'aclVersion': 0}
    resource_key = digest([tenant, 'budget-account', budget_ref], True)
    grant_id = 'budget-governor-' + str(uuid.uuid4())
    source_key = digest([resource_key, 'budget-account:assign-reconciler', 'GOVERNANCE', operation,
                         'SUBJECT', governor, tenant, workspace, str(generation)], True)
    grant = {'id': grant_id, 'resource': resource,
             'target': {'kind': 'SUBJECT', 'subjectId': governor, 'scope': {'tenantId': tenant, 'workspaceId': workspace}, 'membershipGeneration': generation},
             'action': 'budget-account:assign-reconciler', 'state': 0, 'expiresAt': None, 'source': 3,
             'sourceOperationId': operation, 'issuedBy': issued_by,
             'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z'), 'version': 0}
    tj, wj, bj, oj, gj, ij = map(literal, (tenant, workspace, budget_ref, owner, governor, issued_by))
    account_key = digest([budget_ref])
    owner_key = digest([tenant, workspace, owner])
    routine = 'arte_er5_bootstrap_' + uuid.uuid4().hex[:12]
    reason = literal(f'Explicit account governor provisioning; owner={owner}; resource-key={resource_key}; trace={operation}')
    # All dynamic strings use hex literals. Any failed check rolls back the complete procedure.
    return f'''-- ER-5 explicit single-account bootstrap. Review every ID before applying to the intended database.
-- No model, account balance, reconciler grant, role or global ai:budget:admin grant is changed.
-- Apply with a trusted deployment identity. Do not use mysql --force. Retain this reviewed artifact.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
DELIMITER $$
CREATE PROCEDURE {routine}()
BEGIN
  DECLARE found_count BIGINT DEFAULT 0;
  DECLARE old_acl BIGINT DEFAULT 0;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  SELECT COUNT(*) INTO found_count FROM arte_platform_workspace_preference WHERE subject_id IN ({gj},{ij});
  IF found_count <> {len(set((governor, issued_by)))} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Initialize trusted subject gates first'; END IF;
  SELECT subject_id FROM arte_platform_workspace_preference WHERE subject_id IN ({gj},{ij}) ORDER BY subject_id FOR UPDATE;
  SELECT id FROM arte_platform_tenant WHERE id={tj} FOR UPDATE;
  SELECT id FROM arte_platform_workspace WHERE tenant_id={tj} AND id={wj} FOR UPDATE;
  SELECT COUNT(*) INTO found_count FROM arte_platform_tenant t JOIN arte_platform_workspace w ON w.tenant_id=t.id
    JOIN arte_platform_tenant_member tm ON tm.tenant_id=t.id AND tm.subject_id={gj}
    JOIN arte_platform_workspace_member wm ON wm.tenant_id=t.id AND wm.workspace_id=w.id AND wm.subject_id=tm.subject_id
    JOIN arte_platform_principal_identity pi ON pi.subject_id=wm.subject_id
    JOIN arte_rbac_user u ON CAST(u.id AS CHAR)=pi.external_user_id AND u.create_time=pi.external_created_at
    WHERE t.id={tj} AND w.id={wj} AND t.state=0 AND w.state=0 AND tm.state=0 AND wm.state=0
      AND wm.generation={generation} AND pi.provider='arte-rbac' AND pi.retired_at IS NULL AND u.status=1 FOR UPDATE;
  IF found_count <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Governor identity or current membership mismatch'; END IF;
  SELECT COUNT(*) INTO found_count FROM arte_ai_account WHERE id_key='{account_key}' AND owner_key='{owner_key}'
    AND JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot,'$.body.budgetRef'))={bj}
    AND JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot,'$.body.owner.tenantId'))={tj}
    AND JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot,'$.body.owner.workspaceId'))={wj}
    AND JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot,'$.body.owner.subjectId'))={oj};
  IF found_count <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Authoritative account owner mismatch'; END IF;
  INSERT INTO arte_platform_resource_access(resource_key,tenant_id,resource_type,resource_id,workspace_id,payload,acl_version)
    SELECT '{resource_key}',{tj},'budget-account',{bj},{wj},{literal(json.dumps(root,ensure_ascii=False,separators=(',',':')))},0
    WHERE NOT EXISTS(SELECT 1 FROM arte_platform_resource_access WHERE resource_key='{resource_key}');
  SELECT acl_version INTO old_acl FROM arte_platform_resource_access WHERE resource_key='{resource_key}' FOR UPDATE;
  SELECT COUNT(*) INTO found_count FROM arte_platform_resource_grant WHERE source_identity_digest='{source_key}';
  IF found_count=0 THEN
    INSERT INTO arte_platform_resource_grant(id,resource_key,source_identity_digest,payload,version)
      VALUES({literal(grant_id)},'{resource_key}','{source_key}',{literal(json.dumps(grant,ensure_ascii=False,separators=(',',':')))},0);
    UPDATE arte_platform_resource_access SET acl_version=old_acl+1,
      payload=JSON_SET(payload,'$.aclVersion',old_acl+1) WHERE resource_key='{resource_key}' AND acl_version=old_acl;
    UPDATE arte_platform_workspace SET version=version+1,auth_epoch=auth_epoch+1 WHERE tenant_id={tj} AND id={wj};
    INSERT INTO arte_platform_audit(id,actor_subject_id,tenant_id,workspace_id,action,operation_id,reason,occurred_sec,occurred_nano)
      VALUES({literal('audit-'+str(uuid.uuid4()))},{ij},{tj},{wj},'budget-account:governor-provisioned',{literal(operation)},{reason},UNIX_TIMESTAMP(UTC_TIMESTAMP()),0);
  END IF;
  COMMIT;
END$$
DELIMITER ;
CALL {routine}();
DROP PROCEDURE {routine};
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('tenant', 'workspace', 'budget-ref', 'owner', 'governor', 'issued-by', 'operation-id'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--membership-generation', required=True, type=int)
    args = parser.parse_args()
    print(generate(args.tenant, args.workspace, args.budget_ref, args.owner, args.governor, args.issued_by, args.membership_generation, args.operation_id))


if __name__ == '__main__':
    main()
