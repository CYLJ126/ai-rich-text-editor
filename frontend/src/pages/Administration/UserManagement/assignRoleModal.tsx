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
        title: '角色名称',
        dataIndex: 'roleName',
        width: 150,
    },
    {
        title: '角色编码',
        dataIndex: 'roleCode',
        width: 150,
    },
    {
        title: '描述',
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
            message.warning('请选择至少一个角色').then();
            return;
        }

        const param = {
            userName: userName,
            roles: selectedRoles.map((role: any) => role.roleCode),
        };

        assignRolesToUser(param)
            .then(() => {
                message.success('角色分配成功').then();
                onSuccess();
            })
            .catch((error) => {
                message.error(`角色分配失败: ${error.message}`).then();
            });
    };

    return (
        <Modal
            title={`为用户 ${userName} 分配角色`}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    取消
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignRoles}>
                    分配
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
