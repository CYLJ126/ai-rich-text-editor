package com.arte.core.utils.media.pic;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 重置图片大小
 *
 * @author zhangsc ≧◔◡◔≦
 * @version 1.0.0 ✵
 * @since 2023/6/11 16:19 ✾
 **/
public class ResizeImage {

    /**
     * 通过BufferedImage图片流调整图片大小
     */
    public static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);
        return outputImage;
    }

    public static BufferedImage resizeImageByWidth(BufferedImage originalImage, int targetWidth) {
        int targetHeight = (int) (((double) targetWidth / originalImage.getWidth()) * originalImage.getHeight());
        Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_AREA_AVERAGING);
        BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);
        return outputImage;
    }

    public static BufferedImage resizeImageByWidthIgnoreBigOrSmall(BufferedImage image, int size) {
        BufferedImage result = new BufferedImage(size, size, image.getType());
        Graphics2D g2 = result.createGraphics();
        g2.drawImage(image, 0, 0, size, size, null);
        g2.dispose();
        return result;
    }

    public static BufferedImage cutHalfImg(BufferedImage originalImage, boolean isLeft) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        if (isLeft) {
            return originalImage.getSubimage(0, 0, width / 2, height);
        } else {
            return originalImage.getSubimage(width / 2, 0, width / 2, height);
        }
    }

    public static void main(String[] args) throws IOException {
        String path = "D:\\home\\data\\eladmin\\pantone\\1 - 副本.JPG";
        BufferedImage bufferedImage = ImageIO.read(new File(path));
        BufferedImage resizeImage = resizeImageByWidth(bufferedImage, 1000);
        ImageIO.write(resizeImage, "png", new File("D:\\home\\data\\eladmin\\pantone\\1 - 调整.JPG"));
    }
}
