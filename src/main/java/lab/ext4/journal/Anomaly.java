package lab.ext4.journal;

public record Anomaly(String id, String code, int ringPos, Long seq, String message, String originId) {
}
