package com.nip.app.service.rbac;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.app.api.rbac.RbacRelationService;
import com.nip.app.common.enums.RbacRelationEnum;
import com.nip.app.mapper.rbac.RbacRelationMapper;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.app.pojo.rbac.RbacRelationDto;
import com.nip.app.pojo.rbac.RbacRelationPo;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.app.pojo.rbac.param.RbacRelationParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * RBAC 关系表服务实现类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Service
public class RbacRelationServiceImpl extends ServiceImpl<RbacRelationMapper, RbacRelationDto> implements RbacRelationService {

    @Override
    public List<RoleDto> listRolesByUserName(String userName) {
        return baseMapper.listRolesByUserName(userName);
    }

    @Override
    public List<MenuDto> listMenusByUserName(String userName) {
        return baseMapper.listMenusByUserName(userName);
    }

    @Override
    public List<RbacRelationDto> listOperationsBySource(RbacRelationParam param) {
        return baseMapper.listBySourceName(param.getBindingType(), param.getSource());
    }

    @Transactional(rollbackFor = Throwable.class)
    @Override
    public Boolean bind(String source, List<String> targets, RbacRelationEnum type) {
        deleteBySource(source, type);
        List<RbacRelationDto> relations = targets.stream().map(targetCode -> {
            RbacRelationDto relation = new RbacRelationDto();
            relation.setBindingType(type);
            relation.setSource(source);
            relation.setTarget(targetCode);
            return relation;
        }).toList();
        return this.saveBatch(relations);
    }

    @Override
    public Boolean deleteBySource(String source, RbacRelationEnum type) {
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, type)
                .eq(RbacRelationPo.COL_SOURCE, source);
        return this.remove(queryWrapper);
    }

    @Override
    public List<RbacRelationDto> listBySource(String source, RbacRelationEnum type) {
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, type)
                .eq(RbacRelationPo.COL_SOURCE, source);
        return this.list(queryWrapper);
    }

    @Override
    public List<RbacRelationDto> listByTarget(String target, RbacRelationEnum type) {
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, type)
                .eq(RbacRelationPo.COL_TARGET, target);
        return this.list(queryWrapper);
    }
}
