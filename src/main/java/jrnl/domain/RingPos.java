package jrnl.domain;

/**
 * Physical coordinate of a journal block: absolute slot in the journal image and
 * slot index relative to the ring start. Both coordinate bases are carried.
 */
public record RingPos(int imageIndex, int ringSlot, int ringSlots) {

    /** Next position on the ring, wrapping from the last slot back to slot 0. */
    public RingPos next() {
        int slot = (ringSlot + 1) % ringSlots;
        return new RingPos(imageIndex - ringSlot + slot, slot, ringSlots);
    }

    public String coord() {
        return "image#" + imageIndex + "/ring#" + ringSlot;
    }
}
