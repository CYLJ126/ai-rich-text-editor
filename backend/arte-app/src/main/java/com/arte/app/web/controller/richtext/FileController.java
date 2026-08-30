package com.arte.app.web.controller.richtext;

import com.arte.app.service.richtext.RichTextFileStorageService;
import com.arte.app.service.richtext.RemoteImageDownloadService;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Rich text file upload controller.
 *
 * @author CYLJ126
 * @since 2026/5/10
 */
@Slf4j
@RestController
@RequestMapping("/richText/file")
public class FileController {

    @Resource
    private RichTextFileStorageService richTextFileStorageService;

    @Resource
    private RemoteImageDownloadService remoteImageDownloadService;

    /**
     * Upload an image and return a browser-accessible URL.
     */
    @PostMapping("/uploadImage")
    @AnonymousAccess
    public ResultContext<String> uploadImage(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "folder", required = false) String folder) {
        if (file == null || file.isEmpty()) {
            return ResultContext.fail("上传文件不能为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResultContext.fail("仅支持上传图片文件");
        }

        try {
            String url = richTextFileStorageService.uploadImage(file, folder);
            log.info("图片上传成功，链接: {}", url);
            return ResultContext.success(url, "上传成功");
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return ResultContext.fail("图片上传失败: " + e.getMessage());
        }
    }

    /**
     * Upload a file and return a browser-accessible URL.
     */
    @PostMapping("/uploadFile")
    @AnonymousAccess
    public ResultContext<String> uploadFile(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "folder", required = false) String folder) {
        if (file == null || file.isEmpty()) {
            return ResultContext.fail("上传文件不能为空");
        }

        try {
            String url = richTextFileStorageService.uploadFile(file, folder);
            log.info("文件上传成功，链接: {}", url);
            return ResultContext.success(url, "上传成功");
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResultContext.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/importImage")
    @PreAuthorize("isAuthenticated()")
    public ResultContext<String> importImage(@RequestParam("url") String url) {
        try {
            MultipartFile image = remoteImageDownloadService.download(url);
            String storedUrl = richTextFileStorageService.uploadImage(image, "images/article/remote");
            return ResultContext.success(storedUrl, "转存成功");
        } catch (Exception e) {
            log.error("远程图片转存失败，url: {}", url, e);
            return ResultContext.fail("远程图片转存失败: " + e.getMessage());
        }
    }

    @GetMapping("/readText")
    @AnonymousAccess
    public ResultContext<String> readText(@RequestParam("url") String url) {
        try {
            return ResultContext.success(richTextFileStorageService.readTextByUrl(url));
        } catch (Exception e) {
            log.error("富文本文件读取失败，url: {}", url, e);
            return ResultContext.fail("文件读取失败: " + e.getMessage());
        }
    }

    @PostMapping("/deleteFile")
    @AnonymousAccess
    public ResultContext<Void> deleteFile(@RequestParam("url") String url) {
        try {
            richTextFileStorageService.deleteByUrl(url);
            return ResultContext.success(null);
        } catch (Exception e) {
            log.error("富文本文件删除失败，url: {}", url, e);
            return ResultContext.fail("文件删除失败: " + e.getMessage());
        }
    }
}
