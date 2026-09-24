package lab.jbd2.model;

/**
 * 指定崩溃点派生块图中的一个派生文件系统块。
 * 来源链：dataPhys（日志块） -> sequence -> tag ordinal；baseHex 为重放前原块字节。
 */
public record DerivedBlock(long fsBlocknr, String beforeHex, String afterHex,
                           String status, long writtenBySeq, long dataPhys,
                           List<String> sourceChain) {
    public String stableKey() {
        return "derived:" + fsBlocknr;
    }
}
