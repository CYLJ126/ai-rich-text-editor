package com.nip.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;

@Slf4j
public class LogUtilTest {

    @Test
    public void info() {
        LogUtil.info(log, "prefix", "arg");
        Assert.assertTrue(true);
    }
}