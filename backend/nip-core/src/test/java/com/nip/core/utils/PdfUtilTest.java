package com.nip.core.utils;

import org.junit.Assert;
import org.junit.Test;

public class PdfUtilTest {

    @Test
    public void convertPdfToImages() {
        // 输入PDF路径
        String pdfPath = "C:\\Users\\FG\\Desktop\\DeepSeek从入门到精通（清华大学版）.pdf";
        // 输出图片目录
        String outputDir = "D:\\temp\\deepseek\\pic";
        PdfUtil.convertPdfToImages(pdfPath, outputDir, "DeepSeek从入门到精通（清华大学版）");
        Assert.assertTrue(true);
    }
}