import {i18nText} from '@/utils/i18n';
import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {
    assignOperationsToRole,
    assignOperationsToUser,
    listMenuOperationsBySource,
} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

export interface AssignOperationsModalProps {
    visible: boolean;
    sourceName: string; // 角色名或用户名
    sourceType: 'role' | 'user'; // 区分角色还是用户
    onCancel: () => void;
    onSuccess: () => void;
}

const operationColumns: TableColumn[] = [
    {
        title: i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.bcb54330"),
        dataIndex: 'operationName',
        width: 150,
    },
    {
        title: i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.e064ee96"),
        dataIndex: 'menuCode',
        width: 100,
    },
    {
        title: i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.512860ba"),
        dataIndex: 'operationCode',
        width: 100,
    },
    {
        title: i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.27a54f93"),
        dataIndex: 'description',
        width: 300,
    },
];

const AssignOperationsModal: React.FC<AssignOperationsModalProps> = ({
                                                                         visible,
                                                                         sourceName,
                                                                         sourceType,
                                                                         onCancel,
                                                                         onSuccess,
                                                                     }) => {
    const operationTableRef = useRef<any>(null);

    // 加载权限列表
    const fetchOperationsData = async (params: any) => {
        const bindingType = sourceType === 'role' ? 'role_to_operation' : 'user_to_operation';
        return await listMenuOperationsBySource({
            source: sourceName,
            bindingType: bindingType,
            ...params,
        });
    };

    // 分配权限
    const handleAssignOperations = async () => {
        const selectedOperations = operationTableRef.current?.getSelectedRows() || [];

        const operationCodes = selectedOperations.map(
            (operation: any) => `${operation.menuCode}:${operation.operationCode}`,
        );
        const assignPromise = sourceType === 'role'
            ? assignOperationsToRole({
                roleCode: sourceName,
                menuOperations: operationCodes,
            })
            : assignOperationsToUser({
                userName: sourceName,
                menuOperations: operationCodes,
            });

        try {
            const result = await assignPromise;
            if (!result) throw new Error('Assign operations returned false');
            message.success(i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.045ccc08"));
            onSuccess();
        } catch (error: unknown) {
            const errorMessage = error instanceof Error ? error.message : '';
            message.error(i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.f8373591", {value0: errorMessage}));
        }
    };

    // 获取弹窗标题
    const getModalTitle = () => {
        if (sourceType === 'role') {
            return i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.88c7c888", {value0: sourceName});
        } else {
            return i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.d39ef32c", {value0: sourceName});
        }
    };

    return (
        <Modal
            title={getModalTitle()}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    {i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.16677176")}
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignOperations}>
                    {i18nText("app.administration.menuoperationmanagement.assignoperationsmodal.5030849e")}
                </Button>,
            ]}
            width={1000}
            destroyOnHidden
        >
            <SimpleTable
                ref={operationTableRef}
                columns={operationColumns}
                fetchData={fetchOperationsData}
                tableHeight={400}
                defaultSize={500}
                rowKey="id"
                defaultSelectedField="assigned"
            />
        </Modal>
    );
};

export default AssignOperationsModal;
