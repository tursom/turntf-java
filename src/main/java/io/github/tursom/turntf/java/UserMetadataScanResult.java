package io.github.tursom.turntf.java;

import java.util.List;

/**
 * Page returned by user metadata scan APIs.
 *
 * <p>{@code nextAfter} can be fed back into the next scan request to continue iterating the same
 * ordered key space without re-reading the current page.
 */
public record UserMetadataScanResult(
    List<UserMetadata> items,
    int count,
    String nextAfter
) {
}
