package jrnl.domain;

import java.util.HexFormat;

/**
 * A raw imported block: original bytes plus their coordinate. Bytes are never
 * normalised away; all derived nodes point back to a {@code RawBlock}.
 */
public record RawBlock(int imageIndex, String hexBytes, String sourceRef) {

    public byte[] bytes() {
        return HexFormat.of().parseHex(hexBytes);
    }

    public int length() {
        return hexBytes.length() / 2;
    }
}
