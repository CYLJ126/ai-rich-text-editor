package com.arte.core.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * @author zhangsc
 * @since 2025/5/26 17:56
 */
@Slf4j
public class PdfUtil {

    private PdfUtil() {
    }

    /**
     * PDF 转图片，以 前缀 + 页码 命名
     *
     * @param pdfPath    PDF 文件路径
     * @param outputDir  输出目录
     * @param namePrefix 图片名称前缀
     */
    public static void convertPdfToImages(String pdfPath, String outputDir, String namePrefix) {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            PDFRenderer renderer = new PDFRenderer(document);
            // 创建输出目录（如果不存在）
            File outputFolder = new File(outputDir);
            if (!outputFolder.exists()) {
                outputFolder.mkdirs();
            }
            // 逐页渲染为图片
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                // 300 DPI
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300);
                String fileName = String.format("%s-%d.png", namePrefix, pageIndex + 1);
                // 保存图片
                File outputFile = new File(outputDir, fileName);
                ImageIO.write(image, "PNG", outputFile);
            }
        } catch (Exception e) {
            log.error("PDF 转图片失败", e);
        }
    }
}
