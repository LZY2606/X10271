package jrnl.derive;

import jrnl.domain.ChecksumVersion;
import jrnl.jbd2.Checksums;

final class ChecksumsShim {
    private ChecksumsShim() {
    }

    static long compute(ChecksumVersion version, byte[] block, boolean descriptor) {
        if (version == ChecksumVersion.NONE) {
            return 0L;
        }
        return Checksums.compute(version,
                descriptor ? Checksums.descriptorCoverage(block) : Checksums.commitCoverage(block));
    }
}
