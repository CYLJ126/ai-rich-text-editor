package com.arte.app.web.controller.tools;

import com.arte.core.pojo.ResultContext;
import com.arte.core.utils.PdfUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF 工具接口。文件处理结果写入运行后端的本机目录。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/11 11:47 ✾
 */
@Slf4j
@RestController
@RequestMapping("/tools/pdf")
public class PdfController {

    private static final String DEFAULT_FOLDER = "arte-pdf";

    /**
     * 读取页数和书签，供前端构建可多选的页面大纲。
     */
    @PreAuthorize("@pcs.check('pdf:handle')")
    @PostMapping("/metadata")
    public ResultContext<PdfMetadataResponse> metadata(@RequestParam("file") MultipartFile file) {
        return withPdf(file, pdf -> {
            PdfUtil.PdfInfo info = PdfUtil.readInfo(pdf);
            return new PdfMetadataResponse(info.pageCount(), info.title(), info.outlines(),
                    defaultOutputDir().toString());
        });
    }

    /**
     * 将全部或指定页面转为图片。
     */
    @PostMapping("/to-pic")
    @PreAuthorize("@pcs.check('pdf:handle')")
    public ResultContext<PdfOperationResponse> toPic(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pages", defaultValue = "") String pages,
            @RequestParam(value = "exportAll", defaultValue = "false") boolean exportAll,
            @RequestParam(value = "format", defaultValue = "PNG") String format,
            @RequestParam(value = "quality", defaultValue = "92") int quality,
            @RequestParam(value = "dpi", defaultValue = "300") float dpi,
            @RequestParam(value = "stitch", defaultValue = "false") boolean stitch,
            @RequestParam(value = "colorMode", defaultValue = "COLOR") String colorMode,
            @RequestParam(value = "outputDir", defaultValue = "") String outputDir) {
        return withPdf(file, pdf -> {
            PdfUtil.PdfInfo info = PdfUtil.readInfo(pdf);
            List<Integer> selectedPages = resolvePages(pages, exportAll, info.pageCount());
            Path targetDir = resolveOutputDir(outputDir);
            PdfUtil.ImageOptions options = new PdfUtil.ImageOptions(
                    PdfUtil.ImageFormat.from(format), quality / 100F, dpi, stitch,
                    PdfUtil.ColorMode.from(colorMode));
            List<Path> outputs = PdfUtil.convertPagesToImages(pdf, targetDir,
                    sourceBaseName(file), selectedPages, options);
            return response(targetDir, outputs, selectedPages);
        });
    }

    /**
     * 抽取选定页面组成一个新 PDF。split 是当前页面功能名称，extract 为语义化别名。
     */
    @PostMapping({"/split", "/extract"})
    @PreAuthorize("@pcs.check('pdf:handle')")
    public ResultContext<PdfOperationResponse> extract(
            @RequestParam("file") MultipartFile file,
            @RequestParam("pages") String pages,
            @RequestParam(value = "outputDir", defaultValue = "") String outputDir,
            @RequestParam(value = "outputName", defaultValue = "") String outputName) {
        return withPdf(file, pdf -> {
            PdfUtil.PdfInfo info = PdfUtil.readInfo(pdf);
            List<Integer> selectedPages = resolvePages(pages, false, info.pageCount());
            Path targetDir = resolveOutputDir(outputDir);
            Path output = targetDir.resolve(pdfFileName(outputName, sourceBaseName(file) + "-extract"));
            PdfUtil.extractPages(pdf, output, selectedPages);
            return response(targetDir, List.of(output), selectedPages);
        });
    }

    /**
     * 将选定页面各自拆成单页 PDF，供后续页面功能直接复用。
     */
    @PostMapping("/split-pages")
    @PreAuthorize("@pcs.check('pdf:handle')")
    public ResultContext<PdfOperationResponse> splitPages(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "pages", defaultValue = "") String pages,
            @RequestParam(value = "exportAll", defaultValue = "true") boolean exportAll,
            @RequestParam(value = "outputDir", defaultValue = "") String outputDir) {
        return withPdf(file, pdf -> {
            PdfUtil.PdfInfo info = PdfUtil.readInfo(pdf);
            List<Integer> selectedPages = resolvePages(pages, exportAll, info.pageCount());
            Path targetDir = resolveOutputDir(outputDir);
            List<Path> outputs = PdfUtil.splitToSinglePageDocuments(pdf, targetDir,
                    sourceBaseName(file), selectedPages);
            return response(targetDir, outputs, selectedPages);
        });
    }

    /**
     * 按上传顺序合并 PDF。
     */
    @PostMapping("/merge")
    @PreAuthorize("@pcs.check('pdf:handle')")
    public ResultContext<PdfOperationResponse> merge(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "outputDir", defaultValue = "") String outputDir,
            @RequestParam(value = "outputName", defaultValue = "merged") String outputName) {
        if (files == null || files.isEmpty()) {
            return ResultContext.fail("至少上传一个 PDF 文件");
        }

        List<Path> temporaryFiles = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                validatePdf(file);
                temporaryFiles.add(saveTemporary(file));
            }
            Path targetDir = resolveOutputDir(outputDir);
            Path output = targetDir.resolve(pdfFileName(outputName, "merged"));
            PdfUtil.mergeDocuments(temporaryFiles, output);
            return ResultContext.success(response(targetDir, List.of(output), List.of()));
        } catch (Exception e) {
            log.error("PDF 合并失败", e);
            return ResultContext.fail("PDF 合并失败: " + safeMessage(e));
        } finally {
            temporaryFiles.forEach(PdfController::deleteQuietly);
        }
    }

    private <T> ResultContext<T> withPdf(MultipartFile file, PdfTask<T> task) {
        Path temporaryFile = null;
        try {
            validatePdf(file);
            temporaryFile = saveTemporary(file);
            return ResultContext.success(task.apply(temporaryFile));
        } catch (Exception e) {
            log.error("PDF 处理失败", e);
            return ResultContext.fail("PDF 处理失败: " + safeMessage(e));
        } finally {
            deleteQuietly(temporaryFile);
        }
    }

    private static void validatePdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 PDF 文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("仅支持 PDF 文件");
        }
    }

    private static Path saveTemporary(MultipartFile file) throws IOException {
        Path temporaryFile = Files.createTempFile("arte-pdf-", ".pdf");
        file.transferTo(temporaryFile);
        return temporaryFile;
    }

    private static List<Integer> resolvePages(String expression, boolean exportAll, int pageCount) {
        List<Integer> pages = exportAll
                ? PdfUtil.allPages(pageCount) : PdfUtil.parsePageSelection(expression, pageCount);
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("请在页面大纲中选择页面，或输入页码范围");
        }
        return pages;
    }

    private static Path resolveOutputDir(String outputDir) {
        if (outputDir == null || outputDir.isBlank()) {
            return defaultOutputDir();
        }
        String path = outputDir.trim();
        if (path.equals("~")) {
            path = System.getProperty("user.home");
        } else if (path.startsWith("~/") || path.startsWith("~\\")) {
            path = Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    private static Path defaultOutputDir() {
        return Path.of(System.getProperty("user.home"), "Downloads", DEFAULT_FOLDER)
                .toAbsolutePath().normalize();
    }

    private static String sourceBaseName(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }
        int dot = fileName.lastIndexOf('.');
        return sanitizeFileName(dot > 0 ? fileName.substring(0, dot) : fileName, "document");
    }

    private static String pdfFileName(String requestedName, String fallback) {
        String baseName = requestedName == null ? "" : requestedName.trim();
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        return sanitizeFileName(baseName, fallback) + ".pdf";
    }

    private static String sanitizeFileName(String value, String fallback) {
        String sanitized = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private static PdfOperationResponse response(Path outputDir, List<Path> files, List<Integer> pages) {
        List<String> paths = files.stream()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
        return new PdfOperationResponse(outputDir.toAbsolutePath().normalize().toString(), paths, pages);
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("临时 PDF 删除失败: {}", path, e);
        }
    }

    @FunctionalInterface
    private interface PdfTask<T> {
        T apply(Path pdf) throws Exception;
    }

    public record PdfMetadataResponse(int pageCount, String title, List<PdfUtil.OutlineEntry> outlines,
                                      String defaultOutputDir) {
    }

    public record PdfOperationResponse(String outputDir, List<String> files, List<Integer> pages) {
    }
}
