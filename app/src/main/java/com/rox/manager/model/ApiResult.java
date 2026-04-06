package com.rox.manager.model;

/**
 * Wrapper for service call results. Either contains data or an error message.
 */
public final class ApiResult<T> {
    private final T data;
    private final String errorMessage;

    private ApiResult(T data, String errorMessage) {
        this.data = data;
        this.errorMessage = errorMessage;
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(data, null);
    }

    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(null, message);
    }

    public boolean isSuccess() { return errorMessage == null; }
    public T getData() { return data; }
    public String getErrorMessage() { return errorMessage; }
}
