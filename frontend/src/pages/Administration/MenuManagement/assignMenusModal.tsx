import {i18nText} from '@/utils/i18n';
import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {
    assignMenusToRole,
    assignMenusToUser,
    listMenusBySource,
} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

export interface AssignMenusModalProps {
    visible: boolean;
    sourceName: string; // 角色名或用户名
    sourceType: 'role' | 'user'; // 区分角色还是用户
    onCancel: () => void;
    onSuccess: () => void;
}

const menuColumns: TableColumn[] = [
    {
        title: i18nText("app.administration.menumanagement.assignmenusmodal.d1347172"),
        dataIndex: 'menuName',
        width: 150,
    },
    {
        title: i18nText("app.administration.menumanagement.assignmenusmodal.69254608"),
        dataIndex: 'menuCode',
        width: 100,
    },
    {
        title: i18nText("app.administration.menumanagement.assignmenusmodal.18e5104f"),
        dataIndex: 'menuUrl',
        width: 200,
    },
    {
        title: i18nText("app.administration.menumanagement.assignmenusmodal.f3421ea9"),
        dataIndex: 'fatherId',
        width: 100,
    },
    {
        title: i18nText("app.administration.menumanagement.assignmenusmodal.543fafe5"),
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
    const handleAssignMenus = async () => {
        const selectedMenus = menuTableRef.current?.getSelectedRows() || [];

        const menuCodes = selectedMenus.map((menu: any) => menu.menuCode);
        const assignPromise = sourceType === 'role'
            ? assignMenusToRole({
                roleCode: sourceName,
                menus: menuCodes,
            })
            : assignMenusToUser({
                userName: sourceName,
                menus: menuCodes,
            });

        try {
            const result = await assignPromise;
            if (!result) throw new Error('Assign menus returned false');
            message.success(i18nText("app.administration.menumanagement.assignmenusmodal.539070aa"));
            onSuccess();
        } catch (error: unknown) {
            const errorMessage = error instanceof Error ? error.message : '';
            message.error(i18nText("app.administration.menumanagement.assignmenusmodal.1ffcd32c", {value0: errorMessage}));
        }
    };

    // 获取弹窗标题
    const getModalTitle = () => {
        if (sourceType === 'role') {
            return i18nText("app.administration.menumanagement.assignmenusmodal.b19fbad1", {value0: sourceName});
        } else {
            return i18nText("app.administration.menumanagement.assignmenusmodal.9558c886", {value0: sourceName});
        }
    };

    return (
        <Modal
            title={getModalTitle()}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    {i18nText("app.administration.menumanagement.assignmenusmodal.dff84585")}
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignMenus}>
                    {i18nText("app.administration.menumanagement.assignmenusmodal.a770c87f")}
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
