import {i18nText} from '@/utils/i18n';
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
        title: i18nText("app.administration.rolemanagement.assignusersmodal.1add1dfc"),
        dataIndex: 'userName',
        width: 150,
    },
    {
        title: i18nText("app.administration.rolemanagement.assignusersmodal.155d3a5b"),
        dataIndex: 'name',
        width: 100,
    },
    {
        title: i18nText("app.administration.rolemanagement.assignusersmodal.b61afcbb"),
        dataIndex: 'email',
        width: 200,
    },
    {
        title: i18nText("app.administration.rolemanagement.assignusersmodal.5fe25560"),
        dataIndex: 'phone',
        width: 150,
    },
    {
        title: i18nText("app.administration.rolemanagement.assignusersmodal.29d800c1"),
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
            {i18nText("app.administration.rolemanagement.assignusersmodal.3eaa66d8")}
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
            {i18nText("app.administration.rolemanagement.assignusersmodal.a41be1b9")}
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
            {i18nText("app.administration.rolemanagement.assignusersmodal.f0c79820")}
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
            message.warning(i18nText("app.administration.rolemanagement.assignusersmodal.4b5bbc47")).then();
            return;
        }

        const userNames = selectedUsers.map((user: any) => user.userName);

        assignRoleToUsers({
            roleCode: roleCode,
            userNames: userNames,
            assignOrCancel: true,
        })
            .then(() => {
                message.success(i18nText("app.administration.rolemanagement.assignusersmodal.f7c95bdd")).then();
                onSuccess();
            })
            .catch((error) => {
                message.error(i18nText("app.administration.rolemanagement.assignusersmodal.54db523f", {value0: error.message})).then();
            });
    };

    // 取消分配用户
    const handleCancelUsers = () => {
        const selectedUsers = userTableRef.current?.getSelectedRows() || [];
        if (selectedUsers.length === 0) {
            message.warning(i18nText("app.administration.rolemanagement.assignusersmodal.4b5bbc47")).then();
            return;
        }

        const userNames = selectedUsers.map((user: any) => user.userName);

        assignRoleToUsers({
            roleCode: roleCode,
            userNames: userNames,
            assignOrCancel: false,
        })
            .then(() => {
                message.success(i18nText("app.administration.rolemanagement.assignusersmodal.c6be296d")).then();
                onSuccess();
            })
            .catch((error) => {
                message.error(i18nText("app.administration.rolemanagement.assignusersmodal.506c5b26", {value0: error.message})).then();
            });
    };

    return (
        <Modal
            title={i18nText("app.administration.rolemanagement.assignusersmodal.f38b94b8", {value0: roleCode})}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    {i18nText("app.administration.rolemanagement.assignusersmodal.453f9b5a")}
                </Button>,
                <Button key="cancelAssign" onClick={handleCancelUsers}>
                    {i18nText("app.administration.rolemanagement.assignusersmodal.a277ed43")}
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignUsers}>
                    {i18nText("app.administration.rolemanagement.assignusersmodal.15ad839e")}
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
