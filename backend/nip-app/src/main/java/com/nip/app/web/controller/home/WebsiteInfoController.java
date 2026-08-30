package com.nip.app.web.controller.home;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.CharSequenceUtil;
import com.nip.app.api.home.WebsiteInfoService;
import com.nip.app.common.enums.WebsiteInfoTypeEnum;
import com.nip.app.pojo.home.website.WebsiteInfoDto;
import com.nip.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * <p>
 * 资讯网站信息 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-04-12
 */
@RestController
@RequestMapping("/homePage/websiteInfo")
public class WebsiteInfoController {

    @Resource
    private WebsiteInfoService websiteInfoService;

    @PostMapping("/listWebsiteNews")
    @PreAuthorize("@pcs.check('website:list')")
    public ResultContext<List<WebsiteInfoDto>> listWebsiteNews(@RequestBody WebsiteInfoDto param) {
        Assert.notNull(param.getTagName(), "新闻标签名不能为空");
        WebsiteInfoTypeEnum websiteInfoType = WebsiteInfoTypeEnum.getByLabel(param.getTagName());
        if (Objects.isNull(websiteInfoType)) {
            return ResultContext.fail("不存在此新闻类型");
        } else {
            param.setType(websiteInfoType.getValue());
            return ResultContext.wrap(param, websiteInfoService::listByType);
        }
    }

    @PostMapping("/refreshNews")
    @PreAuthorize("@pcs.check('website:refresh')")
    public ResultContext<Boolean> refreshNews() {
        return ResultContext.wrap(() -> websiteInfoService.refreshNews());
    }

    @PostMapping("/getWebsiteLogo")
    @PreAuthorize("@pcs.check('website:list')")
    public ResponseEntity<org.springframework.core.io.Resource> getWebsiteLogo(@RequestBody WebsiteInfoDto param) {
        Assert.notNull(param.getId(), "网站 ID不能为空");
        Assert.notNull(param.getLogoUrl(), "logo 地址不能为空");
        // 如果获取不到 logo，返回默认 logo，此时会清空 param 中的 logoUrl 字段，不是合理的处理方式
        byte[] imageBytes = websiteInfoService.getLogoImg(param);
        if (imageBytes == null || imageBytes.length == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        // 包装为 Resource 对象
        ByteArrayResource resource = new ByteArrayResource(imageBytes);

        // 构建响应头
        HttpHeaders headers = new HttpHeaders();
        // 设置 Media Type 为 PNG
        headers.setContentType(getMediaType(param.getLogoUrl()));
        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    private MediaType getMediaType(String url) {
        String suffix = CharSequenceUtil.subAfter(url, ".", true);
        if (CharSequenceUtil.equalsAnyIgnoreCase(suffix, "jpg", "jpeg")) {
            return MediaType.IMAGE_JPEG;
        } else if (CharSequenceUtil.equalsAnyIgnoreCase(suffix, "gif")) {
            return MediaType.IMAGE_GIF;
        } else if (CharSequenceUtil.equalsAnyIgnoreCase(suffix, "svg")) {
            return new MediaType("image", "svg+xml");
        }
        // 默认 png
        return MediaType.IMAGE_PNG;
    }

}
