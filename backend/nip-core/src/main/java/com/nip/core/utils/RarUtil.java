package com.nip.core.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.UUID;

/**
 * RarUtil
 * 对 Windows 或 Linux 下文件或文件夹进行加压或解压，支持密码，目标路径不存在则创建，存在目标文件则覆盖。
 * Windows下调用方法的第一个参数需指定 WinRAR.exe 的路径。
 * Linux下需安装 rarlinux。
 *
 * @author Zhangsc
 * @since 2019/11/11
 **/
@Slf4j
public class RarUtil {
    private static final String CD = "cd ";
    private static final String EXIT = "exit";
    /**
     * 密码，如 -pggg，ggg 为密码，中间不能有空格
     */
    private static final String PASSWORD_COMMAND = "-p";
    private static final String LINUX_PREFIX = "rar ";
    /**
     * a 是压缩
     * -ep1 是不在压缩文件包含文件的基目录
     * -o+ 是覆盖已存在文件
     */
    private static final String COMPRESS_ARGS = "a -ep1 -o+ ";
    /**
     * e 解压压缩文件到当前目录
     */
    private static final String DECOMPRESS_ARGS = "x -o+ ";

    private static final String FILE_ENCODING = System.getProperty("file.encoding");

    private static final String WIN_SYSTEM_FLAG = "windows";

    private static final String USER_DIR = System.getProperty("user.dir");

    public static final String RAR_SUFFIX = ".rar";

    /**
     * 压缩文件。
     *
     * @param sourceFile 源文件
     * @param destFile   压缩目标文件
     * @param password   密码
     * @param rarPath    WinRAR.exe 的路径
     */
    public static void rarCompress(File sourceFile, File destFile, String password, String rarPath) {
        compress(sourceFile.getAbsolutePath(), destFile.getAbsolutePath(), password, true, rarPath);
    }

    /***
     * 根据传入的字节流解压 rar，只支持压缩包中只有一个文件。
     * 1.将字节流写入文件 source.rar；
     * 2.将 source 解压出来得到“target/***.suffix”文件；
     * 3.将解压得到的文件转换成字节流；
     * 4.删除中间生成的rar文件和解压出来的文件；
     * 5.返回。
     * @param data 待解压的 rar 文件
     * @param password 密码
     * @param rarPath WinRAR.exe 的路径
     * @return 解压得到的文件
     * @throws IOException 读取或写入文件时出错
     */
    public static byte[] rarDecompress(byte[] data, String password, String rarPath) throws IOException {
        // 1.将字节流写入文件./source/uuid.rar；
        String source = USER_DIR + File.separator + UUID.randomUUID() + RAR_SUFFIX;
        try (FileOutputStream out = new FileOutputStream(new File(source))) {
            out.write(data);
        }

        // 2.将./source/uuid.rar解压出来得到./target/***.suffix文件；
        String target = USER_DIR + File.separator + UUID.randomUUID();
        compress(source, target, password, false, rarPath);

        // 3.将解压得到的文件转换成字节流；
        File[] files = new File(target).listFiles();
        assert files != null;
        if (files.length > 1) {
            log.error("压缩包中存在多个文件或路径！");
            return null;
        }
        byte[] outer;
        try (FileInputStream fin = new FileInputStream(files[0].getAbsolutePath());
             ByteArrayOutputStream bout = new ByteArrayOutputStream(fin.available())) {
            byte[] buffer = new byte[fin.available()];
            int n;
            while ((n = fin.read(buffer)) != -1) {
                bout.write(buffer, 0, n);
            }
            outer = bout.toByteArray();
        }

        // 4.删除中间生成的 rar 文件和解压出来的文件；
        try {
            new File(source).delete();
            files[0].delete();
            new File(target).delete();
        } catch (Exception e) {
            log.error("解压时删除中间文件出错，但不影响结果", e);
        }
        return outer;
    }

    public static void compress(String source, String target, String password, boolean flag, String rarPath) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains(WIN_SYSTEM_FLAG)) {
            winCompress(rarPath, source, target, password, flag);
        } else {
            linuxCompress(source, target, password, flag);
        }
    }

    /**
     * Windows压缩或解压文件到当前路径
     *
     * @param rarPath WinRAR.exe 的路径
     * @param source  源文件绝对路径全名
     * @param flag    为 true 时压缩，为 false 时解压
     */
    public static void winCompress(String rarPath, String source, boolean flag) {
        winCompress(rarPath, source, null, flag);
    }

    /**
     * Linux 压缩或解压文件到当前路径
     *
     * @param source 源文件绝对路径全名
     * @param flag   为 true 时压缩，为 false 时解压
     */
    public static void linuxCompress(String source, boolean flag) {
        linuxCompress(source, null, flag);
    }

    /**
     * Windows压缩或解压文件到当前路径
     *
     * @param rarPath  WinRAR.exe 的路径
     * @param source   源文件绝对路径全名
     * @param password 密码
     * @param flag     为 true 时压缩，为 false 时解压
     */
    public static void winCompress(String rarPath, String source, String password, boolean flag) {
        winCompress(rarPath, source, getTargetName(source, flag), password, flag);
    }

    private static String getTargetName(String source, boolean flag) {
        String target;
        if (flag) {
            // 目标压缩文件名
            int suffix = source.lastIndexOf(".") >= 0 ? source.lastIndexOf(".") : source.length();
            target = source.substring(0, suffix) + RAR_SUFFIX;
        } else {
            target = source.substring(0, source.lastIndexOf(File.separator));
        }
        return target;
    }

    /**
     * Linux压缩或解压文件到当前路径
     *
     * @param source   源文件绝对路径全名
     * @param password 密码
     * @param flag     为true时压缩，为false时解压
     */
    public static void linuxCompress(String source, String password, boolean flag) {
        linuxCompress(source, getTargetName(source, flag), password, flag);
    }

    /**
     * Windows压缩或解压文件
     *
     * @param rarPath  WinRAR.exe的路径
     * @param source   源文件绝对路径全名
     * @param target   目标文件绝对路径全名
     * @param password 密码，没有密码则传入null
     * @param flag     为true时压缩，为false时解压
     */
    public static void winCompress(String rarPath, String source, String target, String password, boolean flag) {
        // 校验源文件合法性
        if (validateSourceAndTarget(source, target, flag)) {
            log.warn("源文件或目标文件检查非法，不予压缩");
            return;
        }
        String absolutePath = source.substring(0, source.lastIndexOf(File.separator));

        String command;
        String pwdParam = password == null ? " " : PASSWORD_COMMAND + password + " ";
        if (flag) {
            command = rarPath + " " + COMPRESS_ARGS + pwdParam + " \"" + target + "\"" + " " + "\"" + source + "\" ";
            log.info("压缩命令: {}", command);
        } else {
            command = rarPath + " " + DECOMPRESS_ARGS + pwdParam + " \"" + source + "\"" + " " + "\"" + target + "\" ";
            log.info("解压命令: {}", command);
        }

        Runtime runtime = Runtime.getRuntime();
        Process process = null;
        try {
            process = runtime.exec(command);
        } catch (IOException e) {
            log.error("出错！", e);
        }

        if (process != null) {
            BufferedReader in;
            PrintWriter out;
            try {
                in = new BufferedReader(new InputStreamReader(process.getInputStream(), FILE_ENCODING));
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), FILE_ENCODING)), true);
            } catch (UnsupportedEncodingException e) {
                log.info("压缩解压出错，不支持的系统编码[{}]", FILE_ENCODING);
                return;
            }
            out.println(CD + absolutePath.trim());
            executeCommand(command, process, in, out);
        }
    }

    private static boolean validateSourceAndTarget(String source, String target, boolean flag) {
        File file = new File(source);
        if (!file.exists()) {
            log.info("源文件或路径[{}]有误或不存在！", source);
            return true;
        }

        file = new File(target);

        if (!flag) {
            //解压时不存在目标路径则新建路径
            if (!file.exists()) {
                return file.mkdirs();
            }
        } else {
            //压缩时不存在目标路径则新建路径
            if (!file.getParentFile().exists()) {
                return file.getParentFile().mkdirs();
            }
        }
        return false;
    }

    /**
     * Linux压缩或解压文件
     *
     * @param source   源文件绝对路径全名
     * @param target   目标文件绝对路径全名
     * @param password 密码，没有密码则传入null
     * @param flag     为true时压缩，为false时解压
     */
    public static void linuxCompress(String source, String target, String password, boolean flag) {
        // 校验源文件合法性
        if (validateSourceAndTarget(source, target, flag)) {
            log.warn("源文件或目标文件检查非法，不予压缩");
            return;
        }
        // 源文件路径
        String sourcePath = new File(source).getParent();
        // 源文件名
        String sourceFileName = source.substring(source.lastIndexOf(File.separator) + 1);
        String command;
        String pwdParam = (password == null || password.isEmpty()) ? " " : PASSWORD_COMMAND + password + " ";
        if (flag) {
            command = LINUX_PREFIX + COMPRESS_ARGS + pwdParam + target + " " + sourceFileName;
            log.info("压缩命令: {}", command);
        } else {
            command = LINUX_PREFIX + DECOMPRESS_ARGS + pwdParam + sourceFileName + " " + target;
            log.info("解压命令: {}", command);
        }
        Runtime runtime = Runtime.getRuntime();
        File bashFile = new File("/bin");
        Process process = null;
        try {
            process = runtime.exec("/bin/bash", null, bashFile);
        } catch (IOException e) {
            log.error("出错！", e);
        }
        if (process != null) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), FILE_ENCODING));
                 PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), FILE_ENCODING)), true)) {
                out.println(CD + sourcePath);
                executeCommand(command, process, in, out);
            } catch (IOException e) {
                log.info("压缩解压出错，不支持的系统编码[{}]", FILE_ENCODING);
            }
        }
    }

    private static void executeCommand(String command, Process process, BufferedReader in, PrintWriter out) {
        out.println(command);
        out.println(EXIT);
        try {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
            }
            process.waitFor();
            in.close();
            out.close();
            process.destroy();
        } catch (Exception e) {
            log.error("出错！", e);
        }
    }
}
