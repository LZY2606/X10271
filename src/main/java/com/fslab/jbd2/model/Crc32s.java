package com.fslab.jbd2.model;

import java.util.zip.CRC32;

public final class Crc32s {
    private Crc32s() {}

    public static long of(byte[]... parts) {
        CRC32 crc = new CRC32();
        for (byte[] p : parts) crc.update(p);
        return crc.getValue();
    }
}
