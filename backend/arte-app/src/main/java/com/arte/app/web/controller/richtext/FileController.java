package com.arte.app.web.controller.richtext;

import com.arte.core.i18n.MessageUtils;

import com.arte.app.service.richtext.RichTextFileStorageService;
import com.arte.app.service.richtext.RemoteImageDownloadService;
import com.arte.core.annotations.AnonymousAccess;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import com.arte.ai.api.ConversationService;
import com.arte.ai.api.MessageAttachmentService;
import com.arte.ai.pojo.message.MessageAttachmentDto;
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

    @Resource
    private MessageAttachmentService messageAttachmentService;

    @Resource
    private ConversationService conversationService;

    @PostMapping("/uploadChatImage")
    @PreAuthorize("isAuthenticated()")
    public ResultContext<MessageAttachmentDto> uploadChatImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam("convId") String convId,
            @RequestParam("messageId") String messageId) {
        var conversation = conversationService.getAndValidate(convId);
        if (!java.util.Objects.equals(conversation.getCreateBy(), UserContext.getUserName())) {
            return ResultContext.fail(MessageUtils.get("error.ai.conversationNotFound", convId));
        }
        if (file.isEmpty()) return ResultContext.fail("error.file.uploadEmpty");
        if (file.getContentType() == null || !java.util.Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(file.getContentType())) {
            return ResultContext.fail("error.file.onlyImage");
        }
        try {
            String url = richTextFileStorageService.uploadImage(file, "images/article/canvas-ai");
            richTextFileStorageService.makePermanentByUrl(url);
            MessageAttachmentDto attachment = new MessageAttachmentDto();
            attachment.setMessageId(messageId);
            attachment.setConvId(convId);
            attachment.setFileName(file.getOriginalFilename());
            attachment.setFileSize(file.getSize());
            attachment.setFileType(file.getContentType());
            attachment.setAttachType("IMAGE");
            attachment.setAccessUrl(url);
            attachment.setStatus("UPLOADED");
            attachment.setCreateBy(UserContext.getUserName());
            messageAttachmentService.save(attachment);
            return ResultContext.success(attachment);
        } catch (Exception e) {
            log.error("Chat image upload failed", e);
            return ResultContext.fail(MessageUtils.get("error.file.imageUploadFailed", e.getMessage()));
        }
    }

    /**
     * Upload an image and return a browser-accessible URL.
     */
    @PostMapping("/uploadImage")
    @AnonymousAccess
    public ResultContext<String> uploadImage(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "folder", required = false) String folder) {
        if (file == null || file.isEmpty()) {
            return ResultContext.fail("error.file.uploadEmpty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResultContext.fail("error.file.onlyImage");
        }

        try {
            String url = richTextFileStorageService.uploadImage(file, folder);
            log.info("图片上传成功，链接: {}", url);
            return ResultContext.success(url, "error.file.uploadSuccess");
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return ResultContext.fail(MessageUtils.get("error.file.imageUploadFailed", e.getMessage()));
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
            return ResultContext.fail("error.file.uploadEmpty");
        }

        try {
            String url = richTextFileStorageService.uploadFile(file, folder);
            log.info("文件上传成功，链接: {}", url);
            return ResultContext.success(url, "error.file.uploadSuccess");
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return ResultContext.fail(MessageUtils.get("error.file.uploadFailed", e.getMessage()));
        }
    }

    @PostMapping("/importImage")
    @PreAuthorize("isAuthenticated()")
    public ResultContext<String> importImage(@RequestParam("url") String url) {
        try {
            MultipartFile image = remoteImageDownloadService.download(url);
            String storedUrl = richTextFileStorageService.uploadImage(image, "images/article/remote");
            return ResultContext.success(storedUrl, "error.file.transferSuccess");
        } catch (Exception e) {
            log.error("远程图片转存失败，url: {}", url, e);
            return ResultContext.fail(MessageUtils.get("error.image.transferFailed", e.getMessage()));
        }
    }

    @GetMapping("/readText")
    @AnonymousAccess
    public ResultContext<String> readText(@RequestParam("url") String url) {
        try {
            return ResultContext.success(richTextFileStorageService.readTextByUrl(url));
        } catch (Exception e) {
            log.error("富文本文件读取失败，url: {}", url, e);
            return ResultContext.fail(MessageUtils.get("error.file.readFailed", e.getMessage()));
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
            return ResultContext.fail(MessageUtils.get("error.file.deleteFailed", e.getMessage()));
        }
    }
}
