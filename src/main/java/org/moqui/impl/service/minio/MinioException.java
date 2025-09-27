/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.service.minio;

/**
 * MinIO组件自定义异常类
 *
 * 提供统一的MinIO相关错误处理，包含错误分类和详细信息
 */
public class MinioException extends RuntimeException {

    /**
     * 错误类型枚举
     */
    public enum ErrorType {
        CONFIGURATION_ERROR("配置错误"),
        CONNECTION_ERROR("连接错误"),
        AUTHENTICATION_ERROR("认证错误"),
        BUCKET_ERROR("存储桶错误"),
        OBJECT_ERROR("对象错误"),
        PERMISSION_ERROR("权限错误"),
        NETWORK_ERROR("网络错误"),
        TIMEOUT_ERROR("超时错误"),
        UNKNOWN_ERROR("未知错误");

        private final String description;

        ErrorType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final ErrorType errorType;
    private final String operation;

    /**
     * 构造基本异常
     *
     * @param message 错误消息
     */
    public MinioException(String message) {
        super(message);
        this.errorType = ErrorType.UNKNOWN_ERROR;
        this.operation = null;
    }

    /**
     * 构造带错误类型的异常
     *
     * @param errorType 错误类型
     * @param message 错误消息
     */
    public MinioException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
        this.operation = null;
    }

    /**
     * 构造带错误类型和操作的异常
     *
     * @param errorType 错误类型
     * @param operation 操作名称
     * @param message 错误消息
     */
    public MinioException(ErrorType errorType, String operation, String message) {
        super(message);
        this.errorType = errorType;
        this.operation = operation;
    }

    /**
     * 构造带原因的异常
     *
     * @param errorType 错误类型
     * @param operation 操作名称
     * @param message 错误消息
     * @param cause 原始异常
     */
    public MinioException(ErrorType errorType, String operation, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.operation = operation;
    }

    /**
     * 获取错误类型
     *
     * @return 错误类型
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * 获取操作名称
     *
     * @return 操作名称，可能为null
     */
    public String getOperation() {
        return operation;
    }

    /**
     * 获取详细的错误描述
     *
     * @return 包含错误类型和操作的详细描述
     */
    public String getDetailedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(errorType.getDescription());
        if (operation != null) {
            sb.append(" (操作: ").append(operation).append(")");
        }
        sb.append(": ").append(getMessage());
        return sb.toString();
    }

    @Override
    public String toString() {
        return "MinioException{" +
                "errorType=" + errorType +
                ", operation='" + operation + '\'' +
                ", message='" + getMessage() + '\'' +
                '}';
    }

    /**
     * 创建配置错误异常
     */
    public static MinioException configurationError(String message) {
        return new MinioException(ErrorType.CONFIGURATION_ERROR, message);
    }

    /**
     * 创建连接错误异常
     */
    public static MinioException connectionError(String message, Throwable cause) {
        return new MinioException(ErrorType.CONNECTION_ERROR, "connect", message, cause);
    }

    /**
     * 创建认证错误异常
     */
    public static MinioException authenticationError(String message) {
        return new MinioException(ErrorType.AUTHENTICATION_ERROR, "authenticate", message);
    }

    /**
     * 创建存储桶错误异常
     */
    public static MinioException bucketError(String operation, String message) {
        return new MinioException(ErrorType.BUCKET_ERROR, operation, message);
    }

    /**
     * 创建对象错误异常
     */
    public static MinioException objectError(String operation, String message) {
        return new MinioException(ErrorType.OBJECT_ERROR, operation, message);
    }

    /**
     * 创建权限错误异常
     */
    public static MinioException permissionError(String operation, String message) {
        return new MinioException(ErrorType.PERMISSION_ERROR, operation, message);
    }
}