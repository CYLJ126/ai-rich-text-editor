/*
 * Copyright 2019-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version loginCode.length.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-loginCode.length.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arte.app.config.bean;

import cn.hutool.core.text.CharSequenceUtil;
import com.arte.app.common.enums.LoginCodeEnum;
import com.arte.app.pojo.rbac.LoginCodeDto;
import com.arte.core.exception.BusinessException;
import com.wf.captcha.*;
import com.wf.captcha.base.Captcha;
import lombok.Data;

import java.awt.*;
import java.util.Objects;

/**
 * 登录控制属性
 *
 * @author zhangsc
 */
@Data
public class LoginProperties {

    /**
     * 账号单用户 登录
     */
    private boolean singleLogin = false;

    private LoginCodeDto loginCode;
    /**
     * 用户登录信息缓存
     */
    private boolean cacheEnable;

    /**
     * 获取验证码生产类
     *
     * @return /
     */
    public Captcha getCaptcha() {
        if (Objects.isNull(loginCode)) {
            loginCode = new LoginCodeDto();
            if (Objects.isNull(loginCode.getCodeType())) {
                loginCode.setCodeType(LoginCodeEnum.arithmetic);
            }
        }
        return switchCaptcha(loginCode);
    }

    /**
     * 依据配置信息生产验证码
     *
     * @param loginCode 验证码配置信息
     * @return /
     */
    private Captcha switchCaptcha(LoginCodeDto loginCode) {
        Captcha captcha;
        synchronized (this) {
            switch (loginCode.getCodeType()) {
                case arithmetic:
                    // 算术类型 https://gitee.com/whvse/EasyCaptcha
                    captcha = new ArithmeticCaptcha(loginCode.getWidth(), loginCode.getHeight());
                    // 几位数运算，默认是两位
                    captcha.setLen(loginCode.getLength());
                    break;
                case chinese:
                    captcha = new ChineseCaptcha(loginCode.getWidth(), loginCode.getHeight());
                    captcha.setLen(loginCode.getLength());
                    break;
                case chinese_gif:
                    captcha = new ChineseGifCaptcha(loginCode.getWidth(), loginCode.getHeight());
                    captcha.setLen(loginCode.getLength());
                    break;
                case gif:
                    captcha = new GifCaptcha(loginCode.getWidth(), loginCode.getHeight());
                    captcha.setLen(loginCode.getLength());
                    break;
                case spec:
                    captcha = new SpecCaptcha(loginCode.getWidth(), loginCode.getHeight());
                    captcha.setLen(loginCode.getLength());
                    break;
                default:
                    throw new BusinessException("验证码配置信息错误！正确配置查看 LoginCodeEnum ");
            }
        }
        if (CharSequenceUtil.isNotBlank(loginCode.getFontName())) {
            captcha.setFont(new Font(loginCode.getFontName(), Font.PLAIN, loginCode.getFontSize()));
        }
        return captcha;
    }
}
