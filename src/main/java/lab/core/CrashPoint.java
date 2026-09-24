package lab.core;

/** 预期崩溃点：环上已写入块数（边界，不含）。 */
public record CrashPoint(int boundaryIndex) {
    public static CrashPoint endOfRing(int ringLength) { return new CrashPoint(ringLength); }
}
