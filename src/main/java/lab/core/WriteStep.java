package lab.core;

/** 覆盖顺序中的一步：某 fs 块在 ringIndex 处被 sequence 写入。 */
public record WriteStep(long fsBlock, long sequence, int ringIndex, String payloadHash,
                        boolean superseded, Long cancelledByRevokeSeq) {}
