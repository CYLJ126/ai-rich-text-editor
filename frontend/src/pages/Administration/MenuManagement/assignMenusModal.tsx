import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {
    assignMenusToRole,
    assignMenusToUser,
    listMenusBySource,
} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

interface AssignMenusModalProps {
    visible: boolean;
    sourceName: string; // 角色名或用户名
    sourceType: 'role' | 'user'; // 区分角色还是用户
    onCancel: () => void;
    onSuccess: () => void;
}

const menuColumns: TableColumn[] = [
    {
        title: '菜单名称',
        dataIndex: 'menuName',
        width: 150,
    },
    {
        title: '菜单编码',
        dataIndex: 'menuCode',
        width: 100,
    },
    {
        title: '菜单路径',
        dataIndex: 'menuUrl',
        width: 200,
    },
    {
        title: '父菜单',
        dataIndex: 'fatherId',
        width: 100,
    },
    {
        title: '描述',
        dataIndex: 'description',
        width: 300,
    },
];

const AssignMenusModal: React.FC<AssignMenusModalProps> = ({
                                                               visible,
                                                               sourceName,
                                                               sourceType,
                                                               onCancel,
                                                               onSuccess,
                                                           }) => {
    const menuTableRef = useRef<any>(null);

    // 加载菜单列表
    const fetchMenusData = async (params: any) => {
        const bindingType = sourceType === 'role' ? 'role_to_menu' : 'user_to_menu';
        return await listMenusBySource({
            source: sourceName,
            bindingType: bindingType,
            ...params,
        });
    };

    // 分配菜单
    const handleAssignMenus = () => {
        const selectedMenus = menuTableRef.current?.getSelectedRows() || [];
        if (selectedMenus.length === 0) {
            message.warning('请选择至少一个菜单').then();
            return;
        }

        const menuCodes = selectedMenus.map((menu: any) => menu.menuCode);
        let assignPromise;

        if (sourceType === 'role') {
            assignPromise = assignMenusToRole({
                roleCode: sourceName,
                menus: menuCodes,
            });
        } else {
            assignPromise = assignMenusToUser({
                userName: sourceName,
                menus: menuCodes,
            });
        }

        assignPromise
            .then(() => {
                message.success('菜单分配成功').then();
                onSuccess();
            })
            .catch((error) => {
                message.error(`菜单分配失败: ${error.message}`).then();
            });
    };

    // 获取弹窗标题
    const getModalTitle = () => {
        if (sourceType === 'role') {
            return `为角色 ${sourceName} 分配菜单`;
        } else {
            return `为用户 ${sourceName} 分配菜单`;
        }
    };

    return (
        <Modal
            title={getModalTitle()}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    取消
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignMenus}>
                    分配
                </Button>,
            ]}
            width={1000}
            destroyOnHidden
        >
            <SimpleTable
                ref={menuTableRef}
                columns={menuColumns}
                fetchData={fetchMenusData}
                tableHeight={400}
                defaultSize={500}
                rowKey="id"
                defaultSelectedField="assigned"
            />
        </Modal>
    );
};

export default AssignMenusModal;
