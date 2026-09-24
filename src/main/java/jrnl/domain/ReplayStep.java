package jrnl.domain;

/** One ordered block overwrite in a replay plan. */
public record ReplayStep(
        int order,
        Seq sequence,
        long fsBlock,
        RingPos sourcePos,
        String dataHex,
        String status,
        String reason) {

    public String stableKey() {
        return "step#" + order + ":seq" + sequence.hex() + ":fs" + fsBlock;
    }
}
