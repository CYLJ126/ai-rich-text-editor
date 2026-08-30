package com.nip.app.web.controller.base;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nip.app.api.base.SummaryService;
import com.nip.app.pojo.base.SummaryDto;
import com.nip.app.pojo.base.SummaryPo;
import com.nip.core.pojo.ResultContext;
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
        Assert.notNull(param.getTargetId(), "查询总结内容时，目标 ID 不能为空");
        Assert.notNull(param.getType(), "查询总结内容时，目标类型不能为空");
        QueryWrapper<SummaryDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(SummaryPo.COL_TARGET_ID, param.getTargetId());
        queryWrapper.eq(SummaryPo.COL_TYPE, param.getType());
        queryWrapper.eq(Objects.nonNull(param.getId()), SummaryPo.COL_ID, param.getId());
        SummaryDto one = summaryService.getOne(queryWrapper);
        if (Objects.isNull(one)) {
            Assert.notNull(param.getContent(), "查询总结内容时，内容不能为空");
            return ResultContext.wrap(() -> summaryService.save(param));
        } else {
            one.setContent(StrUtil.nullToEmpty(param.getContent()));
            return ResultContext.wrap(one, summaryService::updateById);
        }
    }

    @PostMapping("/getSummaryByTargetIdAndType")
    @PreAuthorize("@pcs.check('summary:list')")
    public ResultContext<SummaryDto> getSummaryByTargetIdAndType(@RequestBody SummaryDto param) {
        Assert.notNull(param.getTargetId(), "查询总结内容时，目标 ID 不能为空");
        Assert.notNull(param.getType(), "查询总结内容时，目标类型不能为空");
        return ResultContext.wrap(() -> summaryService.getSummaryByTargetIdAndType(param.getTargetId(), param.getType()));
    }

    @PostMapping("/formatContent")
    @PreAuthorize("@pcs.check('summary:format')")
    public ResultContext<String> formatContent(@RequestBody SummaryDto param) {
        Assert.notNull(param.getContent(), "内容不能为空");
        Assert.notNull(param.getOperationType(), "操作类型不能为空");
        return ResultContext.wrap(param.getContent(), param.getOperationType(), summaryService::formatContent);
    }

}
