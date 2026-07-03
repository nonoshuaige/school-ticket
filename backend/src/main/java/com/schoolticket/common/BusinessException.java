package com.schoolticket.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    // 常用业务错误码
    public static final int TICKET_SOLD_OUT = 4001;
    public static final int ORDER_EXPIRED = 4002;
    public static final int ORDER_STATUS_ERROR = 4003;
    public static final int USER_NOT_FOUND = 4004;
    public static final int INVALID_PARAM = 4005;
    public static final int ALREADY_FOLLOWING = 4006;
    public static final int CANNOT_FOLLOW_SELF = 4007;
    public static final int DUPLICATE_REQUEST = 4008;
}
