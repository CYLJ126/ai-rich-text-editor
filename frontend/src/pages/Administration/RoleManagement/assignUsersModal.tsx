import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {assignRoleToUsers, listUserByTarget} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

interface AssignUsersModalProps {
    visible: boolean;
    roleCode: string; // 角色编码
    onCancel: () => void;
    onSuccess: () => void;
}

const userColumns: TableColumn[] = [
    {
        title: '用户名',
        dataIndex: 'userName',
        width: 150,
    },
    {
        title: '姓名',
        dataIndex: 'name',
        width: 100,
    },
    {
        title: '邮箱',
        dataIndex: 'email',
        width: 200,
    },
    {
        title: '手机号',
        dataIndex: 'phone',
        width: 150,
    },
    {
        title: '状态',
        dataIndex: 'status',
        width: 100,
        render: (text: number) => {
            if (text === 0) {
                return (
                    <span
                        style={{
                            backgroundColor: '#f0f9eb',
                            color: '#586163',
                            padding: '2px 8px',
                            borderRadius: '4px',
                        }}
                    >
            初始
          </span>
                );
            } else if (text === 1) {
                return (
                    <span
                        style={{
                            backgroundColor: '#f0f9eb',
                            color: '#52c41a',
                            padding: '2px 8px',
                            borderRadius: '4px',
                        }}
                    >
            正常
          </span>
                );
            } else if (text === 2) {
                return (
                    <span
                        style={{
                            backgroundColor: '#f0f9eb',
                            color: '#c48b1a',
                            padding: '2px 8px',
                            borderRadius: '4px',
                        }}
                    >
            注销
          </span>
                );
            }
            return text;
        },
    },
];

const AssignUsersModal: React.FC<AssignUsersModalProps> = ({
                                                               visible,
                                                               roleCode,
                                                               onCancel,
                                                               onSuccess,
                                                           }) => {
    const userTableRef = useRef<any>(null);

    // 加载用户列表
    const fetchUsersData = async (params: any) => {
        return await listUserByTarget({
            target: roleCode,
            relationType: 'user_to_role',
            assignOrCancel: true,
            ...params,
        });
    };

    // 分配用户
    const handleAssignUsers = () => {
        const selectedUsers = userTableRef.current?.getSelectedRows() || [];
        if (selectedUsers.length === 0) {
            message.warning('请选择至少一个用户').then();
            return;
        }

        const userNames = selectedUsers.map((user: any) => user.userName);

        assignRoleToUsers({
            roleCode: roleCode,
            userNames: userNames,
            assignOrCancel: true,
        })
            .then(() => {
                message.success('用户分配成功').then();
                onSuccess();
            })
            .catch((error) => {
                message.error(`用户分配失败: ${error.message}`).then();
            });
    };

    // 取消分配用户
    const handleCancelUsers = () => {
        const selectedUsers = userTableRef.current?.getSelectedRows() || [];
        if (selectedUsers.length === 0) {
            message.warning('请选择至少一个用户').then();
            return;
        }

        const userNames = selectedUsers.map((user: any) => user.userName);

        assignRoleToUsers({
            roleCode: roleCode,
            userNames: userNames,
            assignOrCancel: false,
        })
            .then(() => {
                message.success('用户取消分配成功').then();
                onSuccess();
            })
            .catch((error) => {
                message.error(`用户取消分配失败: ${error.message}`).then();
            });
    };

    return (
        <Modal
            title={`为角色 ${roleCode} 分配用户`}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    取消
                </Button>,
                <Button key="cancelAssign" onClick={handleCancelUsers}>
                    取消分配
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignUsers}>
                    分配
                </Button>,
            ]}
            width={1000}
            destroyOnHidden
        >
            <SimpleTable
                ref={userTableRef}
                columns={userColumns}
                fetchData={fetchUsersData}
                tableHeight={400}
                defaultPageSize={9999}
                rowKey="id"
                defaultSelectedField="assigned"
            />
        </Modal>
    );
};

export default AssignUsersModal;
