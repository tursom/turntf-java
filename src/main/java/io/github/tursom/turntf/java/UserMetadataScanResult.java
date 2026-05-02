package io.github.tursom.turntf.java;

import java.util.List;

/**
 * 用户元数据扫描结果的分页记录。
 * <p>
 * 由用户元数据扫描 API 返回，包含当前页的元数据条目列表以及用于下一页查询的游标。
 * <p>
 * {@code nextAfter} 值可以作为下一次扫描请求的参数传入，
 * 从而在有序的键空间中继续迭代，无需重新读取当前页。
 * 如果 {@code nextAfter} 为空字符串，则表示已到达数据末尾。
 *
 * @param items     当前页的元数据条目列表
 * @param count     当前页返回的条目数量，通常等于 {@code items.size()}
 * @param nextAfter 下一页的起始游标，将此值传入下一次扫描可获取后续数据；空字符串表示无更多数据
 */
public record UserMetadataScanResult(
    List<UserMetadata> items,
    int count,
    String nextAfter
) {
}
