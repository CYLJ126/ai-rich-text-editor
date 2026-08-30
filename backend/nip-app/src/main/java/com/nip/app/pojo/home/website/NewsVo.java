package com.nip.app.pojo.home.website;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author zhangsc
 * @since 2025/4/2 15:27
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
public class NewsVo implements Serializable {
    @Serial
    private static final long serialVersionUID = -3826861008911777608L;

    private String id;

    private String title;

    private String url;

    private String summary;

    private String time;


}
