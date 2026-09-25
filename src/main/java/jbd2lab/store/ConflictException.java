package jbd2lab.store;

/** Thrown when two editors save from the same base version (optimistic concurrency). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
