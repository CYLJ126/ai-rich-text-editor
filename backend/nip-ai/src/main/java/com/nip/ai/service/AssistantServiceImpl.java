package com.nip.ai.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nip.ai.api.AssistantService;
import com.nip.ai.mapper.AssistantMapper;
import com.nip.ai.pojo.assistant.AssistantDto;
import com.nip.ai.pojo.assistant.AssistantParam;
import com.nip.ai.pojo.assistant.AssistantPo;
import com.nip.core.pojo.PageView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 助手表 服务实现类
 *
 * @author zhangsc
 * @since 2026-07-13
 */
@Slf4j
@Service
public class AssistantServiceImpl extends ServiceImpl<AssistantMapper, AssistantDto> implements AssistantService {

    @Override
    public PageView<AssistantDto> listAssistants(AssistantParam param) {
        return page(param, getQueryWrapper(param));
    }

    @Override
    public AssistantDto getDefaultAssistant(String userName) {
        QueryWrapper<AssistantDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(AssistantPo.COL_DEFAULT_FLAG, true);
        queryWrapper.eq(AssistantPo.COL_CREATE_BY, userName);
        return getOne(queryWrapper);
    }

    private QueryWrapper<AssistantDto> getQueryWrapper(AssistantParam param) {
        QueryWrapper<AssistantDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(param.getId() != null, AssistantPo.COL_ID, param.getId());
        queryWrapper.like(StrUtil.isNotBlank(param.getName()), AssistantPo.COL_NAME, "%" + param.getName() + "%");
        queryWrapper.eq(param.getStatus() != null, AssistantPo.COL_STATUS, param.getStatus());
        queryWrapper.eq(param.getDescription() != null, AssistantPo.COL_DESCRIPTION, param.getDescription());
        if (CollUtil.isNotEmpty(param.orders())) {
            param.orders().forEach(order -> queryWrapper.orderBy(true, order.isAsc(), order.getColumn()));
        }
        return queryWrapper;
    }
}
