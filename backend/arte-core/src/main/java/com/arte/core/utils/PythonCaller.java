package com.arte.core.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * python 调用
 *
 * @author zhangsc
 * @version 2023/5/25 16:28
 */
@Slf4j
public class PythonCaller {

    /**
     * 首先一定要设置好你所使用的python的位置，切记不要直接使用python，因为系统会默认使用自带的python，所以一定要设置好你所使用的python的位置，
     * 否则可能会出现意想不到的问题。还有就是如果调用文件，要设置好py文件的位置，使用绝对路径。
     *
     * @param python  机器上可能装了多个python版本，指定使用的python版本
     * @param command 调用的命令
     * @return 调用python命令后的响应
     */
    public static String callByRuntime(String python, String command) {
        Process proc;
        String result = "";
        try {
            String formatCommand = String.format("%s %s", python, command);
            log.info("命令：{}", formatCommand);
            proc = Runtime.getRuntime().exec(formatCommand);
            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String temp;
            while ((temp = in.readLine()) != null) {
                sb.append(temp);
            }
            result = sb.toString();
            in.close();
            proc.waitFor();
        } catch (Exception e) {
            log.error("脚本调用出错", e);
        }
        return result;
    }
}
