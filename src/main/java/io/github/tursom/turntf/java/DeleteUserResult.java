package io.github.tursom.turntf.java;

/**
 * 用户删除操作的结果记录。
 * <p>
 * 该记录封装了删除用户操作的返回信息，包含操作状态和被删除用户的引用。
 * 状态字符串由服务器返回，通常为 "deleted" 或其他表示操作结果的标识。
 *
 * @param status 操作状态字符串，由服务器定义和返回
 * @param user   被删除用户的引用
 */
public record DeleteUserResult(String status, UserRef user) {
}
