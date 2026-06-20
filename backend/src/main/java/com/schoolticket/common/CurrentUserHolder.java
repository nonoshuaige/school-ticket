package com.schoolticket.common;

/**
 * 当前用户上下文 —— 通过 ThreadLocal 持有 userId
 * 请求入口（Filter）写入，请求结束（finally）清除
 */
public class CurrentUserHolder {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }

    public static void remove() {
        USER_ID_HOLDER.remove();
    }
}
