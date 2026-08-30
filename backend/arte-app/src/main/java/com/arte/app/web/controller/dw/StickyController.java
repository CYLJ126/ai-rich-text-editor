package com.arte.app.web.controller.dw;

import cn.hutool.core.lang.Assert;
import com.arte.app.api.dw.StickyService;
import com.arte.app.pojo.dw.StickyDto;
import com.arte.app.pojo.dw.param.StickyParam;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 便笺表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-06-20
 */
@Slf4j
@RestController
@RequestMapping("/dw/sticky")
public class StickyController {

    private static final String TIP_ID_CANNOT_BLANK = "便笺 ID 不能为空";

    @Resource
    private StickyService stickyService;

    @RequestMapping("/listStickies")
    @PreAuthorize("@pcs.check('sticky:list')")
    public PageView<StickyDto> listStickies(@RequestBody StickyParam param) {
        return stickyService.listStickies(param);
    }

    @RequestMapping("/getStickyById")
    @PreAuthorize("@pcs.check('sticky:list')")
    public ResultContext<StickyDto> getStickyById(@RequestBody StickyDto param) {
        return ResultContext.wrap(param, stickyService::getStickyById);
    }

    @RequestMapping("/addSticky")
    @PreAuthorize("@pcs.check('sticky:add')")
    public ResultContext<StickyDto> addSticky(@RequestBody StickyDto param) {
        return ResultContext.wrap(param, stickyService::addSticky);
    }

    @RequestMapping("/updateSticky")
    @PreAuthorize("@pcs.check('sticky:update')")
    public ResultContext<Boolean> updateSticky(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::updateSticky);
    }

    @RequestMapping("/resizeSticky")
    @PreAuthorize("@pcs.check('sticky:update')")
    public ResultContext<Boolean> resizeSticky(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::resizeSticky);
    }

    @RequestMapping("/orderSticky")
    @PreAuthorize("@pcs.check('sticky:update')")
    public ResultContext<Boolean> orderSticky(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::orderSticky);
    }

    @RequestMapping("/foldSticky")
    @PreAuthorize("@pcs.check('sticky:update')")
    public ResultContext<Boolean> foldSticky(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::foldSticky);
    }

    @RequestMapping("/switchThemeColor")
    @PreAuthorize("@pcs.check('sticky:update')")
    public ResultContext<Boolean> switchThemeColor(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::switchThemeColor);
    }

    @RequestMapping("/deleteSticky")
    @PreAuthorize("@pcs.check('sticky:delete')")
    @Transactional
    public ResultContext<Boolean> deleteSticky(@RequestBody StickyDto param) {
        Assert.notNull(param.getId(), TIP_ID_CANNOT_BLANK);
        return ResultContext.wrap(param, stickyService::deleteSticky);
    }
}
