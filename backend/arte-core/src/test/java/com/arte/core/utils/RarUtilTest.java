package com.arte.core.utils;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.io.File;
import java.io.IOException;


@Slf4j
public class RarUtilTest {
    final String rarPath = "C:\\Program Files\\WinRAR\\WinRAR.exe";

    @Test
    public void compressInPath() {
        String path = "D:\\CYLJ126\\Media\\S\\000\\2023 - 副本";
        String donePath = path + "\\已完成\\";
        String compressPath = path + "\\已压缩\\";
        FileUtil.mkdir(donePath);
        FileUtil.mkdir(compressPath);
        FileUtil.loopFiles(path).forEach((file) -> {
            if (file.isFile() && endWith(file.getName())) {
                String fileName = compressPath + file.getName() + "-密码[Aa1-6]" + ".rar";
                log.info("文件：{}", fileName);
                RarUtil.rarCompress(file, new File(fileName), "Aa111111", rarPath);
                FileUtil.move(file, new File(donePath), true);
            }
        });
    }

    private boolean endWith(String name) {
        return CharSequenceUtil.endWithAny(name, "mp4");
    }

    @Test
    public void deCompressInPath() {
        String path = "D:\\CYLJ126\\Media\\S\\000\\超短已传";
        FileUtil.loopFiles(path).forEach((file) -> {
            if (file.isFile() && StrUtil.endWith(file.getName(), ".rar")) {
                try {
                    byte[] bytes = RarUtil.rarDecompress(FileUtil.readBytes(file), "Aa111111", rarPath);
                    String name = path + "\\" + StrUtil.replace(file.getName(), " -密码[Aa1-6].rar", "") + ".mp4";
                    assert bytes != null;
                    FileUtil.writeBytes(bytes, name);
                    file.delete();
                } catch (IOException e) {
                    log.error("文件【{}】解压失败", file.getName());
                }
            }
        });
    }

}