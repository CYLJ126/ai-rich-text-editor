package com.arte.app.web.controller.richtext;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import com.arte.app.api.richtext.CatalogService;
import com.arte.app.api.richtext.ShareService;
import com.arte.app.common.constant.RbacConstant;
import com.arte.app.common.enums.ResourceTypeEnum;
import com.arte.app.mapper.rbac.RbacRelationMapper;
import com.arte.app.mapper.richtext.CatalogMapper;
import com.arte.app.pojo.rbac.RoleDto;
import com.arte.app.pojo.richtext.CatalogDto;
import com.arte.app.pojo.richtext.SpaceCatalogsDto;
import com.arte.app.pojo.richtext.param.CopyToMySpaceParam;
import com.arte.app.pojo.richtext.param.PublicToggleParam;
import com.arte.app.pojo.richtext.param.PublishToPublicParam;
import com.arte.app.service.richtext.PermissionValidator;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 目录控制器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/11 ✾
 **/
@Slf4j
@RestController
@RequestMapping("/richText/catalog")
public class CatalogController {

    @Resource
    private CatalogService catalogService;

    @Resource
    private ShareService shareService;

    @Resource
    private CatalogMapper catalogMapper;

    @Resource
    private PermissionValidator permissionValidator;

    @Resource
    private RbacRelationMapper rbacRelationMapper;

    /**
     * 获取目录树
     */
    @AnonymousAccess
    @PostMapping("/listCatalogs")
    public ResultContext<List<CatalogDto>> listCatalogs(@RequestBody CatalogDto param) {
        param.setCreateBy(UserContext.getUserName());
        // 先查出一级目录列表，再递归构建树
        List<CatalogDto> tree = catalogService.listRecursive(param);
        return ResultContext.success(tree);
    }

    /**
     * 新增目录节点
     */
    @AnonymousAccess
    @PostMapping("/addCatalog")
    public ResultContext<Boolean> addCatalog(@RequestBody CatalogDto param) {
        Assert.notNull(param.getName(), MessageUtils.get("error.field.catalogNameRequired"));
        if (publicRootCatalog(param)) {
            Assert.isTrue(currentUserIsAdmin(), MessageUtils.get("error.catalog.adminRootOnly"));
        }
        CatalogDto parent = null;
        if (param.getFatherId() != null) {
            permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), param.getFatherId());
            parent = catalogMapper.getByIdUnfiltered(param.getFatherId());
            if (parent == null) {
                throw new BusinessException("error.catalog.parentNotFound");
            }
            param.setIsPublic(Boolean.TRUE.equals(parent.getIsPublic()));
        } else if (param.getIsPublic() == null) {
            param.setIsPublic(false);
        }
        String userName = UserContext.getUserOnlineInfo().getUserName();
        param.setCreateBy(parent != null ? parent.getCreateBy() : userName);
        param.setUpdateBy(userName);
        param.setCreateTime(LocalDateTime.now());
        param.setUpdateTime(param.getCreateTime());
        param.setId(null);
        param.setOrderId(catalogService.findMaxOrder(param.getFatherId()) + 1);
        boolean save = catalogService.save(param);
        if (!save) {
            return ResultContext.fail();
        }
        // 新建子目录时继承父目录的分享设置
        if (param.getFatherId() != null) {
            shareService.inheritFromParent(ResourceTypeEnum.CATALOG.getValue(), param.getFatherId(),
                    ResourceTypeEnum.CATALOG.getValue(), param.getId());
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 更新目录节点（重命名等）
     */
    @AnonymousAccess
    @PostMapping("/updateCatalog")
    public ResultContext<Boolean> updateCatalog(@RequestBody CatalogDto param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.catalogIdRequired"));
        permissionValidator.assertCanWrite(ResourceTypeEnum.CATALOG.getValue(), param.getId());
        CatalogDto updateParam = new CatalogDto();
        updateParam.setId(param.getId());
        updateParam.setName(param.getName());
        updateParam.setDescription(param.getDescription());
        updateParam.setUpdateBy(UserContext.getUserOnlineInfo().getUserName());
        updateParam.setUpdateTime(LocalDateTime.now());
        boolean update = catalogService.updateById(updateParam);
        if (!update) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 删除目录节点（递归删除所有子目录）
     */
    @AnonymousAccess
    @PostMapping("/deleteCatalog")
    public ResultContext<Boolean> deleteCatalog(@RequestBody CatalogDto param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.catalogIdRequired"));
        permissionValidator.assertCanDelete(ResourceTypeEnum.CATALOG.getValue(), param.getId());
        Boolean removed = catalogService.removeRecursive(param.getId());
        if (!removed) {
            return ResultContext.fail();
        }
        return ResultContext.success(Boolean.TRUE);
    }

    /**
     * 拖拽排序/移动层级
     */
    @AnonymousAccess
    @PostMapping("/reorderCatalogs")
    public ResultContext<Boolean> reorderCatalogs(@RequestBody List<CatalogDto> list) {
        Assert.notEmpty(list, MessageUtils.get("error.field.catalogListRequired"));
        for (CatalogDto item : list) {
            Assert.notNull(item.getId(), MessageUtils.get("error.field.catalogIdRequired"));
            permissionValidator.assertCanWrite(ResourceTypeEnum.CATALOG.getValue(), item.getId());
            if (item.getFatherId() != null) {
                Assert.isFalse(item.getId().equals(item.getFatherId()), MessageUtils.get("error.catalog.cannotMoveToItself"));
                permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), item.getFatherId());
            }
        }
        validateAcyclicReorder(list);
        validateSameSpaceReorder(list);
        return ResultContext.wrap(() -> catalogService.reorder(list, 1, true));
    }

    /**
     * 获取三大空间目录树（我的空间 / 与我分享 / 公共空间）
     */
    @AnonymousAccess
    @PostMapping("/listSpaceCatalogs")
    public ResultContext<SpaceCatalogsDto> listSpaceCatalogs() {
        SpaceCatalogsDto result = catalogService.listSpaceCatalogs();
        return ResultContext.success(result);
    }

    /**
     * 切换目录公共状态
     */
    @AnonymousAccess
    @PostMapping("/togglePublic")
    public ResultContext<Boolean> togglePublic(@RequestBody PublicToggleParam req) {
        Integer id = req.getId();
        Boolean isPublic = req.getIsPublic();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.catalogIdRequired"));
        Assert.notNull(isPublic, "isPublic不能为空");
        permissionValidator.assertCanWrite(ResourceTypeEnum.CATALOG.getValue(), id);
        if (targetCatalogId != null) {
            permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        }
        catalogService.togglePublic(id, isPublic, targetCatalogId);
        return ResultContext.success(true);
    }

    /**
     * 复制目录到我的空间
     */
    @AnonymousAccess
    @PostMapping("/copyToMySpace")
    public ResultContext<CatalogDto> copyToMySpace(@RequestBody CopyToMySpaceParam req) {
        Integer id = req.getId();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.catalogIdRequired"));
        Assert.notNull(targetCatalogId, MessageUtils.get("error.field.targetCatalogIdRequired"));
        permissionValidator.assertCanRead(ResourceTypeEnum.CATALOG.getValue(), id);
        permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        CatalogDto result = catalogService.copyToMySpace(id, targetCatalogId);
        return ResultContext.success(result);
    }

    /**
     * 发布目录到公共空间（需选择目标目录）
     */
    @AnonymousAccess
    @PostMapping("/publishToPublic")
    public ResultContext<Boolean> publishToPublic(@RequestBody PublishToPublicParam req) {
        Integer id = req.getId();
        Integer targetCatalogId = req.getTargetCatalogId();
        Assert.notNull(id, MessageUtils.get("error.field.catalogIdRequired"));
        permissionValidator.assertCanWrite(ResourceTypeEnum.CATALOG.getValue(), id);
        if (targetCatalogId != null) {
            permissionValidator.assertCanCreateChild(ResourceTypeEnum.CATALOG.getValue(), targetCatalogId);
        }
        catalogService.publishToPublic(id, targetCatalogId);
        return ResultContext.success(true);
    }

    private void validateAcyclicReorder(List<CatalogDto> list) {
        Map<Integer, Integer> submittedParents = new HashMap<>();
        for (CatalogDto item : list) {
            submittedParents.put(item.getId(), item.getFatherId());
        }
        for (CatalogDto item : list) {
            Set<Integer> visited = new HashSet<>();
            Integer current = item.getId();
            while (current != null) {
                if (!visited.add(current)) {
                    throw new BusinessException("error.catalog.cycle");
                }
                current = resolveSubmittedOrStoredFatherId(current, submittedParents);
            }
        }
    }

    private Integer resolveSubmittedOrStoredFatherId(Integer catalogId, Map<Integer, Integer> submittedParents) {
        if (submittedParents.containsKey(catalogId)) {
            return submittedParents.get(catalogId);
        }
        CatalogDto catalog = catalogMapper.getByIdUnfiltered(catalogId);
        return catalog == null ? null : catalog.getFatherId();
    }

    private void validateSameSpaceReorder(List<CatalogDto> list) {
        for (CatalogDto item : list) {
            if (item.getFatherId() == null) {
                continue;
            }
            CatalogDto child = catalogMapper.getByIdUnfiltered(item.getId());
            CatalogDto parent = catalogMapper.getByIdUnfiltered(item.getFatherId());
            if (child == null || parent == null) {
                throw new BusinessException("error.catalog.notFound");
            }
            boolean childPublic = Boolean.TRUE.equals(child.getIsPublic());
            boolean parentPublic = Boolean.TRUE.equals(parent.getIsPublic());
            if (childPublic != parentPublic) {
                throw new BusinessException("error.catalog.publicStateMismatch");
            }
        }
    }

    private boolean publicRootCatalog(CatalogDto param) {
        return param.getFatherId() == null && Boolean.TRUE.equals(param.getIsPublic());
    }

    private boolean currentUserIsAdmin() {
        String userName = UserContext.getUserName();
        if (userName == null) {
            return false;
        }
        List<RoleDto> roles = rbacRelationMapper.listRolesByUserName(userName);
        return roles != null && roles.stream()
                .map(RoleDto::getRoleCode)
                .anyMatch(RbacConstant.ADMIN_ROLE_CODE::equals);
    }
}
