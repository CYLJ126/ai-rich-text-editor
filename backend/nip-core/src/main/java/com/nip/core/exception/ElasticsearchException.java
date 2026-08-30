package com.nip.core.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * Elasticsearch 交互异常
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:15 ✾
 **/
public class ElasticsearchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 2526332522664754058L;

    @Getter
    private final String operation;

    @Getter
    private final String index;

    public ElasticsearchException(String message, String operation, String index) {
        super(message);
        this.operation = operation;
        this.index = index;
    }

    public ElasticsearchException(String message, String operation, String index, Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.index = index;
    }

    @Override
    public String toString() {
        return "ElasticsearchException{operation='%s', index='%s', message='%s'}"
                .formatted(operation, index, getMessage());
    }
}
