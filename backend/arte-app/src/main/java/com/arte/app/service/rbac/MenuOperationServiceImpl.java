package com.arte.app.service.rbac;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.rbac.MenuOperationService;
import com.arte.app.api.rbac.RoleService;
import com.arte.app.api.rbac.UserService;
import com.arte.app.mapper.rbac.MenuOperationMapper;
import com.arte.app.pojo.rbac.MenuOperationDto;
import com.arte.app.pojo.rbac.MenuOperationPo;
import com.arte.app.pojo.rbac.param.MenuOperationParam;
import com.arte.core.cache.Cache;
import com.arte.core.cache.CacheableDataSource;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.PageView;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 菜单操作表 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Slf4j
@Service
public class MenuOperationServiceImpl extends ServiceImpl<MenuOperationMapper, MenuOperationDto> implements MenuOperationService {

    @Resource
    private RoleService roleService;

    @Resource
    private UserService userService;

    @Resource(name = "menuOperationCache")
    @Lazy
    private CacheableDataSource<String, MenuOperationDto> cacheableDataSource;

    @Resource(name = "menuOperationCache")
    @Lazy
    private Cache<String, MenuOperationDto> menuOperationCache;

    @Override
    public PageView<MenuOperationDto> selectMenuOperations(MenuOperationParam param) {
        return this.page(param, getQueryWrapper(param));
    }

    @Override
    public MenuOperationDto getMenuOperation(MenuOperationParam param) {
        return cacheableDataSource.get(getCacheKey(param), (key) -> this.getOne(getQueryWrapper(param)));
    }

    @Override
    public Boolean addMenuOperation(MenuOperationDto param) {
        String cacheKey = param.getAuthority();
        if (menuOperationCache.exists(cacheKey)) {
            log.info("操作权限已存在，菜单：{}，操作码：{}", param.getMenuCode(), param.getOperationCode());
            return false;
        }
        boolean result = this.save(param);
        if (result) {
            menuOperationCache.put(cacheKey, param);
        }
        return result;
    }

    @Override
    public Boolean updateMenuOperation(MenuOperationDto param) {
        AtomicBoolean result = new AtomicBoolean(false);
        cacheableDataSource.putAndUpdate(param.getAuthority(), param, (key, tempMenuOperation) -> {
            result.set(this.updateById(tempMenuOperation));
        });
        return result.get();
    }

    @Transactional(rollbackFor = Throwable.class)
    @Override
    public Boolean deactivateMenuOperation(MenuOperationParam menuOperationParam) {
        menuOperationParam.setSize(999999);
        StatusEnum targetStatus = menuOperationParam.getStatus();
        menuOperationParam.setStatus(null);
        if (StatusEnum.CLOSED.equals(targetStatus)) {
            return innerDeactivateMenuOperation(menuOperationParam);
        } else if (StatusEnum.DOING.equals(targetStatus)) {
            return activateMenuOperation(menuOperationParam);
        } else {
            log.info("操作权限状态异常，菜单：{}，操作码：{}，状态：{}", menuOperationParam.getMenuCodes(), menuOperationParam.getOperationCode(), targetStatus);
            return false;
        }
    }

    private boolean innerDeactivateMenuOperation(MenuOperationParam param) {
        PageView<MenuOperationDto> menuOperations = this.selectMenuOperations(param);
        int size = (int) menuOperations.getSize();
        Set<String> keysToEvict = new HashSet<>(size);
        Set<String> authoritiesToCancel = new HashSet<>(size);
        List<Integer> idsToClose = new ArrayList<>(size);
        menuOperations.forEach(menuOperation -> {
            idsToClose.add(menuOperation.getId());
            keysToEvict.add(menuOperation.getAuthority());
            authoritiesToCancel.add(menuOperation.getAuthority());
        });
        boolean result = CollUtil.split(idsToClose, 1000).stream().allMatch(idBatch -> {
            UpdateWrapper<MenuOperationDto> updateWrapper = new UpdateWrapper<>();
            updateWrapper.set(MenuOperationPo.COL_STATUS, StatusEnum.CLOSED);
            updateWrapper.in(MenuOperationPo.COL_ID, idBatch);
            return this.update(updateWrapper);
        });
        if (result) {
            menuOperationCache.multiEvict(keysToEvict);
            // 逐出用户和角色的操作权限缓存
            roleService.cancelOperationsToRole(Collections.emptyList(), authoritiesToCancel);
            userService.cancelOperationsToUser(Collections.emptyList(), authoritiesToCancel);
        }
        return result;
    }

    private boolean activateMenuOperation(MenuOperationParam param) {
        PageView<MenuOperationDto> menuOperations = this.selectMenuOperations(param);
        int size = (int) menuOperations.getSize();
        List<Integer> idsToActive = new ArrayList<>(size);
        menuOperations.forEach(menuOperation -> {
            idsToActive.add(menuOperation.getId());
            menuOperation.setStatus(StatusEnum.DOING);
        });
        boolean result = CollUtil.split(idsToActive, 1000).stream().allMatch(idBatch -> {
            UpdateWrapper<MenuOperationDto> updateWrapper = new UpdateWrapper<>();
            updateWrapper.set(MenuOperationPo.COL_STATUS, StatusEnum.DOING);
            updateWrapper.in(MenuOperationPo.COL_ID, idBatch);
            return this.update(updateWrapper);
        });
        if (result) {
            menuOperationCache.multiPut(menuOperations.stream().collect(Collectors.toMap(MenuOperationDto::getAuthority, Function.identity())));
        }
        return result;
    }

    private static String getCacheKey(MenuOperationParam param) {
        Set<String> menuCodes = param.getMenuCodes();
        if (CollUtil.size(menuCodes) != 1 || CharSequenceUtil.isBlank(param.getOperationCode())) {
            throw new IllegalArgumentException("查询菜单操作缓存时，必须指定唯一的菜单编码和操作编码");
        }
        return menuCodes.iterator().next() + ":" + param.getOperationCode();
    }

    private static QueryWrapper<MenuOperationDto> getQueryWrapper(MenuOperationParam menuOperationParam) {
        QueryWrapper<MenuOperationDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(menuOperationParam.getId()), MenuOperationPo.COL_ID, menuOperationParam.getId());
        wrapper.in(CollUtil.isNotEmpty(menuOperationParam.getMenuCodes()), MenuOperationPo.COL_MENU_CODE, menuOperationParam.getMenuCodes());
        wrapper.eq(CharSequenceUtil.isNotBlank(menuOperationParam.getOperationCode()), MenuOperationPo.COL_OPERATION_CODE, menuOperationParam.getOperationCode());
        wrapper.eq(CharSequenceUtil.isNotBlank(menuOperationParam.getOperationName()), MenuOperationPo.COL_OPERATION_NAME, menuOperationParam.getOperationName());
        wrapper.eq(Objects.nonNull(menuOperationParam.getStatus()), MenuOperationPo.COL_STATUS, menuOperationParam.getStatus());
        return wrapper;
    }
}
