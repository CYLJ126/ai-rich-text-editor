package com.arte.app.service.rbac;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.rbac.MenuOperationService;
import com.arte.app.api.rbac.MenuService;
import com.arte.app.mapper.rbac.MenuMapper;
import com.arte.app.pojo.BaseDto;
import com.arte.app.pojo.rbac.MenuDto;
import com.arte.app.pojo.rbac.MenuPo;
import com.arte.app.pojo.rbac.param.MenuOperationParam;
import com.arte.app.pojo.rbac.param.MenuParam;
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
 * 菜单表 服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@Slf4j
@Service
public class MenuServiceImpl extends ServiceImpl<MenuMapper, MenuDto> implements MenuService {

    @Resource
    private MenuOperationService menuOperationService;

    @Resource(name = "menuCache")
    @Lazy
    private CacheableDataSource<String, MenuDto> cacheableDataSource;

    @Resource(name = "menuCache")
    @Lazy
    private Cache<String, MenuDto> menuCache;

    @Override
    public List<MenuDto> listRecursive(MenuDto param) {
        List<MenuDto> list = baseMapper.listRecursive(param);
        if (CollUtil.isEmpty(list)) {
            return Collections.emptyList();
        }
        List<MenuDto> rootList = new ArrayList<>();
        Map<Integer, MenuDto> map = list.stream().collect(Collectors.toMap(MenuDto::getId, Function.identity()));
        list.forEach(item -> {
            if (Objects.isNull(item.getFatherId())) {
                rootList.add(item);
            } else {
                MenuDto parent = map.get(item.getFatherId());
                if (Objects.nonNull(parent)) {
                    if (Objects.isNull(parent.getChildren())) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(item);
                }
            }
        });
        return rootList;
    }

    @Override
    public Boolean addMenu(MenuDto param) {
        boolean result = this.save(param);
        if (result) {
            menuCache.put(param.getMenuCode(), param);
        }
        return result;
    }

    @Override
    public Boolean updateMenu(MenuDto param) {
        AtomicBoolean result = new AtomicBoolean(false);
        cacheableDataSource.putAndUpdate(param.getMenuCode(), param, (key, tempMenu) -> {
            result.set(this.updateById(tempMenu));
        });
        return result.get();
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public Boolean deactivateMenu(MenuParam param) {
        UpdateWrapper<MenuDto> wrapper = new UpdateWrapper<>();
        wrapper.set(MenuPo.COL_STATUS, StatusEnum.CLOSED);
        wrapper.eq(MenuPo.COL_MENU_CODE, param.getMenuCode());
        wrapper.eq(MenuPo.COL_ID, param.getId());
        boolean result = this.update(wrapper);
        if (result) {
            menuCache.evict(param.getMenuCode());
            // TODO: 逐出子菜单缓存和关联的角色、用户权限缓存
            // 逐出操作权限缓存
            menuOperationService.deactivateMenuOperation(new MenuOperationParam()
                    .setMenuCode(param.getMenuCode())
                    .setStatus(StatusEnum.CLOSED));
        }
        return result;
    }

    @Override
    public MenuDto getMenu(MenuParam param) {
        if (CharSequenceUtil.isNotBlank(param.getMenuCode())) {
            return getMenuByCode(param.getMenuCode());
        }
        return this.getOne(getQueryWrapper(param));
    }

    @Override
    public MenuDto getMenuByCode(String menuCode) {
        return cacheableDataSource.get(menuCode, (key) -> this.getOne(getQueryWrapper(new MenuParam().setMenuCode(menuCode))));
    }

    @Override
    public PageView<MenuDto> selectMenus(MenuParam param) {
        QueryWrapper<MenuDto> queryWrapper = getQueryWrapper(param);
        return this.page(param, queryWrapper);
    }

    private static QueryWrapper<MenuDto> getQueryWrapper(MenuParam param) {
        QueryWrapper<MenuDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(param.getId()), MenuPo.COL_ID, param.getId());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getMenuCode()), MenuPo.COL_MENU_CODE, param.getMenuCode());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getMenuName()), MenuPo.COL_MENU_NAME, param.getMenuName());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getMenuUrl()), MenuPo.COL_MENU_URL, param.getMenuUrl());
        wrapper.eq(Objects.nonNull(param.getFatherId()), MenuPo.COL_PARENT_ID, param.getFatherId());
        wrapper.eq(Objects.nonNull(param.getStatus()), MenuPo.COL_STATUS, param.getStatus());
        wrapper.eq(Objects.nonNull(param.getShowFlag()), MenuPo.COL_SHOW_FLAG, param.getShowFlag());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getCreateBy()), BaseDto.COL_CREATE_BY, param.getCreateBy());
        wrapper.ge(Objects.nonNull(param.getCreateTimeFloor()), BaseDto.COL_CREATE_TIME, param.getCreateTimeFloor());
        wrapper.le(Objects.nonNull(param.getCreateTimeCeil()), BaseDto.COL_CREATE_TIME, param.getCreateTimeCeil());
        wrapper.orderByAsc(MenuPo.COL_ORDER_ID);
        return wrapper;
    }
}
