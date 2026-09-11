package com.arte.core.utils;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PDF 文件处理工具。对外页码均从 1 开始，PDFBox 的 0 起始页码只在类内部使用。
 *
 * @author zhangsc
 * @since 2025/5/26 17:56
 */
public final class PdfUtil {

    private static final float DEFAULT_DPI = 300F;

    private PdfUtil() {
    }

    /**
     * 兼容原有的全量 PNG 导出方法。
     */
    public static void convertPdfToImages(String pdfPath, String outputDir, String namePrefix) {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            convertPagesToImages(document, Path.of(outputDir), namePrefix,
                    allPages(document.getNumberOfPages()), ImageOptions.defaults());
        } catch (IOException e) {
            throw new IllegalStateException("PDF 转图片失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将指定页面渲染为图片。stitch=true 时按选择顺序纵向拼接为一张图片。
     */
    public static List<Path> convertPagesToImages(Path pdfPath, Path outputDir, String namePrefix,
                                                   Collection<Integer> pages, ImageOptions options)
            throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return convertPagesToImages(document, outputDir, namePrefix, pages, options);
        }
    }

    private static List<Path> convertPagesToImages(PDDocument document, Path outputDir, String namePrefix,
                                                    Collection<Integer> pages, ImageOptions options)
            throws IOException {
        ImageOptions actualOptions = options == null ? ImageOptions.defaults() : options;
        List<Integer> selectedPages = validatePages(pages, document.getNumberOfPages());
        Files.createDirectories(outputDir);

        PDFRenderer renderer = new PDFRenderer(document);
        String prefix = sanitizeFileName(namePrefix, "page");
        if (actualOptions.stitch()) {
            List<BufferedImage> images = new ArrayList<>(selectedPages.size());
            for (Integer page : selectedPages) {
                images.add(renderer.renderImageWithDPI(page - 1, actualOptions.dpi(),
                        actualOptions.colorMode().imageType));
            }
            BufferedImage stitched = stitchVertically(images, actualOptions.colorMode());
            Path output = outputDir.resolve(prefix + "-pages." + actualOptions.format().extension);
            writeImage(stitched, output, actualOptions);
            return List.of(output);
        }

        // 非拼接模式逐页渲染并立即写盘，避免全量导出时长期占用大量内存。
        List<Path> outputs = new ArrayList<>(selectedPages.size());
        for (Integer page : selectedPages) {
            BufferedImage image = renderer.renderImageWithDPI(page - 1, actualOptions.dpi(),
                    actualOptions.colorMode().imageType);
            Path output = outputDir.resolve(String.format(Locale.ROOT, "%s-%d.%s",
                    prefix, page, actualOptions.format().extension));
            writeImage(image, output, actualOptions);
            outputs.add(output);
        }
        return List.copyOf(outputs);
    }

    /**
     * 按传入顺序抽取页面组成新 PDF。
     */
    public static Path extractPages(Path source, Path output, Collection<Integer> pages) throws IOException {
        createParentDirectories(output);
        try (PDDocument sourceDocument = Loader.loadPDF(source.toFile());
             PDDocument targetDocument = new PDDocument()) {
            for (Integer page : validatePages(pages, sourceDocument.getNumberOfPages())) {
                targetDocument.importPage(sourceDocument.getPage(page - 1));
            }
            targetDocument.save(output.toFile());
        }
        return output;
    }

    /**
     * 将选定页面分别拆分为单页 PDF。
     */
    public static List<Path> splitToSinglePageDocuments(Path source, Path outputDir, String namePrefix,
                                                         Collection<Integer> pages) throws IOException {
        Files.createDirectories(outputDir);
        List<Path> outputs = new ArrayList<>();
        try (PDDocument sourceDocument = Loader.loadPDF(source.toFile())) {
            List<Integer> selectedPages = validatePages(pages, sourceDocument.getNumberOfPages());
            String prefix = sanitizeFileName(namePrefix, "page");
            for (Integer page : selectedPages) {
                Path output = outputDir.resolve(prefix + "-" + page + ".pdf");
                try (PDDocument targetDocument = new PDDocument()) {
                    targetDocument.importPage(sourceDocument.getPage(page - 1));
                    targetDocument.save(output.toFile());
                }
                outputs.add(output);
            }
        }
        return List.copyOf(outputs);
    }

    /**
     * 合并多个 PDF，顺序与 sources 一致。
     */
    public static Path mergeDocuments(List<Path> sources, Path output) throws IOException {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 PDF 文件");
        }
        createParentDirectories(output);
        try (PDDocument targetDocument = new PDDocument()) {
            for (Path source : sources) {
                try (PDDocument sourceDocument = Loader.loadPDF(source.toFile())) {
                    for (PDPage page : sourceDocument.getPages()) {
                        targetDocument.importPage(page);
                    }
                }
            }
            targetDocument.save(output.toFile());
        }
        return output;
    }

    /**
     * 读取页数、标题和书签。无法定位到页面的书签 page 为 null。
     */
    public static PdfInfo readInfo(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            List<OutlineEntry> outlines = new ArrayList<>();
            PDDocumentOutline documentOutline = document.getDocumentCatalog().getDocumentOutline();
            if (documentOutline != null) {
                collectOutlines(document, documentOutline, 0, "", outlines);
            }
            return new PdfInfo(document.getNumberOfPages(), document.getDocumentInformation().getTitle(),
                    List.copyOf(outlines));
        }
    }

    /**
     * 解析 1,3-7,20、-5（前 5 页）、5-（第 5 页至末页）等页码表达式。
     */
    public static List<Integer> parsePageSelection(String expression, int pageCount) {
        if (pageCount < 1) {
            throw new IllegalArgumentException("PDF 页数必须大于 0");
        }
        if (expression == null || expression.isBlank()) {
            return List.of();
        }

        Set<Integer> pages = new LinkedHashSet<>();
        String normalized = expression.replace('，', ',').replaceAll("\\s+", "");
        for (String part : normalized.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int hyphen = part.indexOf('-');
            if (hyphen < 0) {
                pages.add(parsePageNumber(part, pageCount));
                continue;
            }
            if (hyphen != part.lastIndexOf('-')) {
                throw new IllegalArgumentException("无效页码范围: " + part);
            }
            int start = hyphen == 0 ? 1 : parsePageNumber(part.substring(0, hyphen), pageCount);
            int end = hyphen == part.length() - 1
                    ? pageCount : parsePageNumber(part.substring(hyphen + 1), pageCount);
            if (start > end) {
                throw new IllegalArgumentException("页码范围起始值不能大于结束值: " + part);
            }
            for (int page = start; page <= end; page++) {
                pages.add(page);
            }
        }
        return List.copyOf(pages);
    }

    public static List<Integer> allPages(int pageCount) {
        if (pageCount < 1) {
            return List.of();
        }
        List<Integer> pages = new ArrayList<>(pageCount);
        for (int page = 1; page <= pageCount; page++) {
            pages.add(page);
        }
        return List.copyOf(pages);
    }

    private static int parsePageNumber(String value, int pageCount) {
        try {
            int page = Integer.parseInt(value);
            if (page < 1 || page > pageCount) {
                throw new IllegalArgumentException("页码必须在 1-" + pageCount + " 之间: " + page);
            }
            return page;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效页码: " + value, e);
        }
    }

    private static List<Integer> validatePages(Collection<Integer> pages, int pageCount) {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("至少选择一页");
        }
        Set<Integer> uniquePages = new LinkedHashSet<>();
        for (Integer page : pages) {
            if (page == null || page < 1 || page > pageCount) {
                throw new IllegalArgumentException("页码必须在 1-" + pageCount + " 之间: " + page);
            }
            uniquePages.add(page);
        }
        return List.copyOf(uniquePages);
    }

    private static void collectOutlines(PDDocument document, PDOutlineNode parent, int depth, String parentId,
                                        List<OutlineEntry> result) throws IOException {
        PDOutlineItem item = parent.getFirstChild();
        int siblingIndex = 0;
        while (item != null) {
            String id = parentId.isEmpty() ? String.valueOf(siblingIndex) : parentId + "." + siblingIndex;
            PDPage destinationPage = item.findDestinationPage(document);
            Integer page = destinationPage == null ? null : document.getPages().indexOf(destinationPage) + 1;
            result.add(new OutlineEntry(id, item.getTitle(), page, depth));
            collectOutlines(document, item, depth + 1, id, result);
            item = item.getNextSibling();
            siblingIndex++;
        }
    }

    private static BufferedImage stitchVertically(List<BufferedImage> images, ColorMode colorMode) {
        int width = images.stream().mapToInt(BufferedImage::getWidth).max().orElseThrow();
        long totalHeight = images.stream().mapToLong(BufferedImage::getHeight).sum();
        if (totalHeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("拼接图片高度过大，请减少页数或 DPI");
        }
        int imageType = colorMode == ColorMode.GRAY
                ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;
        BufferedImage stitched = new BufferedImage(width, (int) totalHeight, imageType);
        Graphics2D graphics = stitched.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, (int) totalHeight);
            int y = 0;
            for (BufferedImage image : images) {
                graphics.drawImage(image, 0, y, null);
                y += image.getHeight();
            }
        } finally {
            graphics.dispose();
        }
        return stitched;
    }

    private static void writeImage(BufferedImage image, Path output, ImageOptions options) throws IOException {
        BufferedImage writableImage = ensureJpegCompatible(image, options.format());
        ImageWriter writer = ImageIO.getImageWritersByFormatName(options.format().writerName).next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(options.quality());
            }
            writer.write(null, new IIOImage(writableImage, null, null), writeParam);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage ensureJpegCompatible(BufferedImage image, ImageFormat format) {
        if (format != ImageFormat.JPEG
                || image.getType() == BufferedImage.TYPE_INT_RGB
                || image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return image;
        }
        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }

    private static void createParentDirectories(Path output) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String sanitizeFileName(String value, String fallback) {
        String candidate = value == null ? "" : value.trim();
        candidate = candidate.replaceAll("[\\\\/:*?\"<>|]", "_");
        return candidate.isBlank() ? fallback : candidate;
    }

    public enum ImageFormat {
        PNG("png", "png"),
        JPEG("jpg", "jpeg"),
        BMP("bmp", "bmp");

        private final String extension;
        private final String writerName;

        ImageFormat(String extension, String writerName) {
            this.extension = extension;
            this.writerName = writerName;
        }

        public static ImageFormat from(String value) {
            if (value == null || value.isBlank()) {
                return PNG;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            return "JPG".equals(normalized) ? JPEG : ImageFormat.valueOf(normalized);
        }
    }

    public enum ColorMode {
        COLOR(ImageType.RGB),
        GRAY(ImageType.GRAY),
        BINARY(ImageType.BINARY);

        private final ImageType imageType;

        ColorMode(ImageType imageType) {
            this.imageType = imageType;
        }

        public static ColorMode from(String value) {
            return value == null || value.isBlank()
                    ? COLOR : ColorMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record ImageOptions(ImageFormat format, float quality, float dpi, boolean stitch,
                               ColorMode colorMode) {
        public ImageOptions {
            format = format == null ? ImageFormat.PNG : format;
            colorMode = colorMode == null ? ColorMode.COLOR : colorMode;
            if (quality <= 0 || quality > 1) {
                throw new IllegalArgumentException("图片品质必须在 0-1 之间");
            }
            if (dpi < 36 || dpi > 600) {
                throw new IllegalArgumentException("DPI 必须在 36-600 之间");
            }
        }

        public static ImageOptions defaults() {
            return new ImageOptions(ImageFormat.PNG, 0.92F, DEFAULT_DPI, false, ColorMode.COLOR);
        }
    }

    public record OutlineEntry(String id, String title, Integer page, int depth) {
    }

    public record PdfInfo(int pageCount, String title, List<OutlineEntry> outlines) {
    }
}
