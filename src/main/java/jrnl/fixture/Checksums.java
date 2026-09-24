package jrnl.fixture;

import jrnl.domain.ChecksumVersion;

/** Public bridge for fixture generation over the jbd2 checksum coverage rules. */
public final class Checksums {

    public static final Checksums INSTANCE = new Checksums();

    private Checksums() {
    }

    public long compute(ChecksumVersion v, byte[] covered) {
        return jrnl.jbd2.Checksums.compute(v, covered);
    }

    public byte[] descriptorCoverage(byte[] block) {
        return jrnl.jbd2.Checksums.descriptorCoverage(block);
    }

    public byte[] commitCoverage(byte[] block) {
        return jrnl.jbd2.Checksums.commitCoverage(block);
    }
}
