package com.arte.app.web.controller.base;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.arte.app.api.base.SummaryService;
import com.arte.app.pojo.base.SummaryDto;
import com.arte.app.pojo.base.SummaryPo;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * <p>
 * 总结内容表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-02-08
 */
@RestController
@RequestMapping("/base/summary")
public class SummaryController {

    @Resource
    private SummaryService summaryService;

    @PostMapping("/saveSummary")
    @PreAuthorize("@pcs.check('summary:add')")
    public ResultContext<Boolean> saveSummary(@RequestBody SummaryDto param) {
        Assert.notNull(param.getTargetId(), MessageUtils.get("error.field.summaryTargetIdRequired"));
        Assert.notNull(param.getType(), MessageUtils.get("error.field.summaryTargetTypeRequired"));
        QueryWrapper<SummaryDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SummaryPo.COL_TARGET_ID, param.getTargetId());
        queryWrapper.eq(SummaryPo.COL_TYPE, param.getType());
        queryWrapper.eq(Objects.nonNull(param.getId()), SummaryPo.COL_ID, param.getId());
        SummaryDto one = summaryService.getOne(queryWrapper);
        if (Objects.isNull(one)) {
            Assert.notNull(param.getContent(), MessageUtils.get("error.field.summaryContentRequired"));
            return ResultContext.wrap(() -> summaryService.save(param));
        } else {
            one.setContent(StrUtil.nullToEmpty(param.getContent()));
            return ResultContext.wrap(one, summaryService::updateById);
        }
    }

    @PostMapping("/getSummaryByTargetIdAndType")
    @PreAuthorize("@pcs.check('summary:list')")
    public ResultContext<SummaryDto> getSummaryByTargetIdAndType(@RequestBody SummaryDto param) {
        Assert.notNull(param.getTargetId(), MessageUtils.get("error.field.summaryTargetIdRequired"));
        Assert.notNull(param.getType(), MessageUtils.get("error.field.summaryTargetTypeRequired"));
        return ResultContext.wrap(() -> summaryService.getSummaryByTargetIdAndType(param.getTargetId(), param.getType()));
    }

    @PostMapping("/formatContent")
    @PreAuthorize("@pcs.check('summary:format')")
    public ResultContext<String> formatContent(@RequestBody SummaryDto param) {
        Assert.notNull(param.getContent(), MessageUtils.get("error.field.contentRequired"));
        Assert.notNull(param.getOperationType(), MessageUtils.get("error.field.operationTypeRequired"));
        return ResultContext.wrap(param.getContent(), param.getOperationType(), summaryService::formatContent);
    }

}
