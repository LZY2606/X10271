package jrnl.domain;

import java.util.List;

/** A block classified while scanning the ring from its ring start. */
public record ScanBlock(
        RingPos pos,
        BlockHeader header,
        List<DescriptorTag> tags,
        List<Long> revokedBlocks,
        Long expectedChecksum,
        Long actualChecksum,
        Boolean checksumOk,
        String diagnostic) {

    public boolean isDescriptor() {
        return header != null && header.type() == BlockHeader.DESCRIPTOR;
    }

    public boolean isCommit() {
        return header != null && header.type() == BlockHeader.COMMIT_BLOCK;
    }

    public boolean isRevoke() {
        return header != null && header.type() == BlockHeader.REVOKE_BLOCK;
    }
}
