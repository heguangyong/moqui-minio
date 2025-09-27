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

import io.minio.errors.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO异常处理工具类
 *
 * 提供统一的异常转换、分类和处理功能
 */
public class MinioExceptionUtils {
    private static final Logger logger = LoggerFactory.getLogger(MinioExceptionUtils.class);

    /**
     * 将MinIO SDK的异常转换为自定义MinioException
     *
     * @param operation 操作名称
     * @param cause 原始异常
     * @return 转换后的MinioException
     */
    public static MinioException convertException(String operation, Throwable cause) {
        if (cause == null) {
            return new MinioException(MinioException.ErrorType.UNKNOWN_ERROR, operation, "Unknown error occurred");
        }

        // 如果已经是MinioException，直接返回
        if (cause instanceof MinioException) {
            return (MinioException) cause;
        }

        // 根据异常类型进行分类转换
        if (cause instanceof ErrorResponseException) {
            return handleErrorResponseException(operation, (ErrorResponseException) cause);
        } else if (cause instanceof InsufficientDataException) {
            return MinioException.objectError(operation, "数据不完整: " + cause.getMessage());
        } else if (cause instanceof InternalException) {
            return new MinioException(MinioException.ErrorType.NETWORK_ERROR, operation,
                                    "内部错误: " + cause.getMessage(), cause);
        } else if (cause instanceof InvalidResponseException) {
            return new MinioException(MinioException.ErrorType.NETWORK_ERROR, operation,
                                    "无效响应: " + cause.getMessage(), cause);
        } else if (cause instanceof XmlParserException) {
            return new MinioException(MinioException.ErrorType.NETWORK_ERROR, operation,
                                    "XML解析错误: " + cause.getMessage(), cause);
        } else if (cause instanceof ServerException) {
            return new MinioException(MinioException.ErrorType.NETWORK_ERROR, operation,
                                    "服务器错误: " + cause.getMessage(), cause);
        } else if (cause instanceof java.net.ConnectException ||
                   cause instanceof java.net.SocketTimeoutException ||
                   cause instanceof java.net.UnknownHostException) {
            return MinioException.connectionError("网络连接失败: " + cause.getMessage(), cause);
        } else if (cause instanceof java.security.InvalidKeyException) {
            return MinioException.authenticationError("密钥验证失败: " + cause.getMessage());
        } else if (cause instanceof IllegalArgumentException) {
            return MinioException.configurationError("参数错误: " + cause.getMessage());
        } else {
            // 未知异常类型
            logger.warn("Unhandled exception type: {}", cause.getClass().getName());
            return new MinioException(MinioException.ErrorType.UNKNOWN_ERROR, operation,
                                    "未知错误: " + cause.getMessage(), cause);
        }
    }

    /**
     * 处理ErrorResponseException
     */
    private static MinioException handleErrorResponseException(String operation, ErrorResponseException e) {
        String errorCode = e.errorResponse().code();
        String message = e.errorResponse().message();

        switch (errorCode) {
            case "AccessDenied":
                return MinioException.permissionError(operation, "访问被拒绝: " + message);
            case "BucketAlreadyExists":
                return MinioException.bucketError(operation, "存储桶已存在: " + message);
            case "BucketNotEmpty":
                return MinioException.bucketError(operation, "存储桶不为空: " + message);
            case "NoSuchBucket":
                return MinioException.bucketError(operation, "存储桶不存在: " + message);
            case "NoSuchKey":
                return MinioException.objectError(operation, "对象不存在: " + message);
            case "InvalidAccessKeyId":
            case "SignatureDoesNotMatch":
                return MinioException.authenticationError("认证失败: " + message);
            case "InternalError":
                return new MinioException(MinioException.ErrorType.NETWORK_ERROR, operation,
                                        "服务器内部错误: " + message);
            case "SlowDown":
                return new MinioException(MinioException.ErrorType.TIMEOUT_ERROR, operation,
                                        "请求过于频繁，请稍后重试: " + message);
            default:
                logger.warn("Unhandled MinIO error code: {}", errorCode);
                return new MinioException(MinioException.ErrorType.UNKNOWN_ERROR, operation,
                                        String.format("MinIO错误 [%s]: %s", errorCode, message));
        }
    }

    /**
     * 检查异常是否为可重试的错误
     *
     * @param exception 异常
     * @return true如果可重试，false如果不可重试
     */
    public static boolean isRetryableException(Throwable exception) {
        if (exception instanceof MinioException) {
            MinioException.ErrorType errorType = ((MinioException) exception).getErrorType();
            return errorType == MinioException.ErrorType.NETWORK_ERROR ||
                   errorType == MinioException.ErrorType.TIMEOUT_ERROR ||
                   errorType == MinioException.ErrorType.CONNECTION_ERROR;
        }

        // 对于原始异常类型的判断
        return exception instanceof java.net.SocketTimeoutException ||
               exception instanceof java.net.ConnectException ||
               exception instanceof InternalException ||
               (exception instanceof ErrorResponseException &&
                "SlowDown".equals(((ErrorResponseException) exception).errorResponse().code()));
    }

    /**
     * 检查异常是否为临时性错误
     */
    public static boolean isTemporaryException(Throwable exception) {
        if (exception instanceof ErrorResponseException) {
            String errorCode = ((ErrorResponseException) exception).errorResponse().code();
            return "SlowDown".equals(errorCode) || "InternalError".equals(errorCode);
        }
        return exception instanceof java.net.SocketTimeoutException ||
               exception instanceof InternalException;
    }

    /**
     * 获取用户友好的错误消息
     */
    public static String getUserFriendlyMessage(Throwable exception) {
        if (exception instanceof MinioException) {
            return ((MinioException) exception).getDetailedMessage();
        }

        MinioException minioException = convertException("operation", exception);
        return minioException.getDetailedMessage();
    }
}