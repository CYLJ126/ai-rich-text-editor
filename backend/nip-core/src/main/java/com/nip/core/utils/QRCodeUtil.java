package com.nip.core.utils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成工具类
 *
 * @author zhangsc
 * @since 2025/5/27 17:37
 */
@Slf4j
public class QRCodeUtil {
    private static final int QRCODE_WIDTH = 400;
    private static final int QRCODE_HEIGHT = 400;
    private static final int LOGO_WIDTH = QRCODE_WIDTH / 5;  // Logo宽度为二维码的1/5
    private static final int LOGO_HEIGHT = QRCODE_HEIGHT / 5; // Logo高度为二维码的1/5

    private QRCodeUtil() {
    }

    /**
     * 生成二维码图片，可选添加 Logo
     *
     * @param logoFile Logo文件
     * @param codeFile 二维码文件
     * @param content  二维码内容
     * @throws WriterException \
     * @throws IOException     \
     */
    public static void generateQrCode(File logoFile, File codeFile, String content) throws WriterException, IOException {
        // 设置二维码参数
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // 设置纠错级别为H，最高级别，容错率约30%
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        // 设置白边
        hints.put(EncodeHintType.MARGIN, 1);

        // 生成二维码矩阵
        BitMatrix bitMatrix = new MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                QRCODE_WIDTH,
                QRCODE_HEIGHT,
                hints);

        // 创建BufferedImage对象
        BufferedImage qrCodeImage = new BufferedImage(
                QRCODE_WIDTH,
                QRCODE_HEIGHT,
                BufferedImage.TYPE_INT_RGB);

        // 将BitMatrix绘制到BufferedImage
        for (int x = 0; x < QRCODE_WIDTH; x++) {
            for (int y = 0; y < QRCODE_HEIGHT; y++) {
                qrCodeImage.setRGB(x, y, bitMatrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }

        // 如果提供了Logo文件，则将Logo添加到二维码中心
        if (logoFile != null && logoFile.exists()) {
            insertLogo(qrCodeImage, logoFile);
        }

        // 确保输出目录存在
        if (!codeFile.getParentFile().exists()) {
            codeFile.getParentFile().mkdirs();
        }

        // 输出二维码图片
        ImageIO.write(qrCodeImage, "png", codeFile);
    }

    /**
     * 在二维码中心插入Logo
     */
    private static void insertLogo(BufferedImage qrCodeImage, File logoFile) throws IOException {
        // 读取Logo图片
        BufferedImage logoImage = ImageIO.read(logoFile);
        // 计算Logo的位置
        int x = (QRCODE_WIDTH - LOGO_WIDTH) / 2;
        int y = (QRCODE_HEIGHT - LOGO_HEIGHT) / 2;
        // 开始绘制Logo
        Graphics2D graphics = qrCodeImage.createGraphics();
        graphics.drawImage(logoImage, x, y, LOGO_WIDTH, LOGO_HEIGHT, null);
        // 绘制Logo边框，可选
        graphics.setStroke(new BasicStroke(3));
        graphics.setColor(Color.WHITE);
        graphics.draw(new RoundRectangle2D.Float(x, y, LOGO_WIDTH, LOGO_HEIGHT, 10, 10));
        graphics.dispose();
    }

}
