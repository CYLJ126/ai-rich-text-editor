package com.arte.core.utils;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;

@SpringBootTest
public class QRCodeUtilTest {

    @Test
    public void generateQRCode() throws Exception {
        String logoPath = "D:\\temp\\Snipaste_2025-05-27_17-50-08.png";
        String codePath = "D:\\temp\\QrCode.png";
        String content = "https://www.baidu.com";
        QRCodeUtil.generateQrCode(new File(logoPath), new File(codePath), content);
        Assert.assertTrue(new File(codePath).exists());
    }
}