import {i18nText} from '@/utils/i18n';
import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {assignRolesToUser, listRolesByUser} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

interface AssignRoleModalProps {
    visible: boolean;
    userName: string;
    onCancel: () => void;
    onSuccess: () => void;
}

const roleColumns: TableColumn[] = [
    {
        title: i18nText("app.administration.usermanagement.assignrolemodal.006e400c"),
        dataIndex: 'roleName',
        width: 150,
    },
    {
        title: i18nText("app.administration.usermanagement.assignrolemodal.8e2405ff"),
        dataIndex: 'roleCode',
        width: 150,
    },
    {
        title: i18nText("app.administration.usermanagement.assignrolemodal.b7f8970d"),
        dataIndex: 'description',
        width: 200,
    },
];

const AssignRoleModal: React.FC<AssignRoleModalProps> = ({
                                                             visible,
                                                             userName,
                                                             onCancel,
                                                             onSuccess,
                                                         }) => {
    const roleTableRef = useRef<any>(null);

    // 加载角色列表
    const fetchRoleData = async (params: any) => {
        return await listRolesByUser({...params, userNames: [userName]});
    };

    // 分配角色
    const handleAssignRoles = () => {
        const selectedRoles = roleTableRef.current?.getSelectedRows() || [];
        if (selectedRoles.length === 0) {
            message.warning(i18nText("app.administration.usermanagement.assignrolemodal.847b6e67")).then();
            return;
        }

        const param = {
            userName: userName,
            roles: selectedRoles.map((role: any) => role.roleCode),
        };

        assignRolesToUser(param)
            .then(() => {
                message.success(i18nText("app.administration.usermanagement.assignrolemodal.738e7bbe")).then();
                onSuccess();
            })
            .catch((error) => {
                message.error(i18nText("app.administration.usermanagement.assignrolemodal.068d8acb", {value0: error.message})).then();
            });
    };

    return (
        <Modal
            title={i18nText("app.administration.usermanagement.assignrolemodal.ba93ba47", {value0: userName})}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    {i18nText("app.administration.usermanagement.assignrolemodal.91f57db6")}
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignRoles}>
                    {i18nText("app.administration.usermanagement.assignrolemodal.c81d51bf")}
                </Button>,
            ]}
            width={1000}
            destroyOnHidden
        >
            <SimpleTable
                ref={roleTableRef}
                columns={roleColumns}
                fetchData={fetchRoleData}
                tableHeight={400}
                defaultPageSize={20}
                rowKey="id"
                defaultSelectedField="assigned"
            />
        </Modal>
    );
};

export default AssignRoleModal;
