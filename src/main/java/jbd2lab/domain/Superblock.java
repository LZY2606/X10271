package jbd2lab.domain;

import java.util.Map;

/**
 * Journal superblock values with their original textual representation preserved.
 * {@code extras} keeps every unknown field so import never drops information.
 */
public record Superblock(
        Quantity blockSize,
        Quantity ringStart,
        Quantity ringBlocks,
        int checksumVersion,
        String uuid,
        Map<String, String> extras) {
}
