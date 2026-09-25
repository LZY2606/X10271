package jbd2lab.domain;

/**
 * One raw journal block exactly as imported: physical coordinate inside the image,
 * original bytes and their SHA-256 content identity. Bytes are never normalised
 * away; the derivation layer parses copies.
 */
public record RawBlock(int physical, byte[] bytes, String sha256) {

    public String byteHex() {
        return JournalFormat.hex(bytes);
    }
}
