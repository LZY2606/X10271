package lab.journal;

import lab.Crc32c;
import lab.model.Model.JBlock;
import lab.model.Model.JournalSpec;
import lab.model.Model.Quantity;
import lab.model.Model.Txn;
import lab.model.Model.Write;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses an imported journal image into transaction groups.
 *
 * Ring order is derived from the physical slot distance to the ring start,
 * never from sorting sequence numbers as plain integers: sequence numbers
 * wrap at 2^32 and must be compared modularly against the ring window.
 */
public final class JournalParser {

    public static final class Parsed {
        public final JournalSpec spec;
        public final List<Txn> txns = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();
        public boolean blockSizeValid = true;
        public double blockSizeBytes = -1;

        Parsed(JournalSpec spec) { this.spec = spec; }
    }

    /** Normalize a block-size quantity; invalid values stay raw with a diagnostic. */
    public static double normalizeBlockSize(Quantity q, List<String> diagnostics) {
        if (q == null || q.raw() == null) {
            diagnostics.add("INVALID_BLOCK_SIZE: missing block size");
            return -1;
        }
        Double v = parseSizeWithUnit(q.raw());
        if (v == null || v < 1024 || v > 65536 || (v.longValue() & (v.longValue() - 1)) != 0) {
            diagnostics.add("INVALID_BLOCK_SIZE: raw value kept in place: '" + q.raw()
                    + "' (unit=" + q.unit() + ", basis=" + q.basis() + ")");
            return -1;
        }
        return v;
    }

    /** Accepts raw forms like "4096", "4KiB", "4K", "0x1000". */
    static Double parseSizeWithUnit(String raw) {
        String s = raw.trim().toLowerCase();
        long mult = 1;
        if (s.endsWith("kib")) { mult = 1024; s = s.substring(0, s.length() - 3); }
        else if (s.endsWith("k")) { mult = 1024; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("mib")) { mult = 1024 * 1024; s = s.substring(0, s.length() - 3); }
        s = s.trim();
        try {
            long v = s.startsWith("0x") ? Long.parseLong(s.substring(2), 16) : Long.parseLong(s);
            return (double) (v * mult);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Parsed parse(JournalSpec spec) {
        Parsed out = new Parsed(spec);
        double bs = normalizeBlockSize(spec.blockSize(), out.diagnostics);
        out.blockSizeBytes = bs;
        out.blockSizeValid = bs > 0;

        int ring = spec.ringBlocks();
        Double startD = spec.ringStart() == null ? null : parseSizeWithUnit(spec.ringStart().raw());
        if (startD == null) {
            out.diagnostics.add("INVALID_RING_START: raw value kept in place: '"
                    + (spec.ringStart() == null ? null : spec.ringStart().raw()) + "'");
            startD = 0.0;
        }
        int ringStart = startD.intValue();

        // Ring order: physical slot distance from ring start, modulo ring length.
        List<JBlock> ordered = new ArrayList<>(spec.blocks());
        ordered.sort(Comparator.comparingInt(b -> Math.floorMod(b.slot() - ringStart, ring)));

        Txn current = null;
        List<Long> pendingTags = new ArrayList<>();
        int ringPos = 0;
        for (JBlock b : ordered) {
            boolean ok = Crc32c.matches(b.canonical(), b.checksumStored());
            JBlock checked = new JBlock(b.slot(), b.type(), b.sequence(), b.tags(), b.revoke(),
                    b.payloadHex(), b.checksumStored(),
                    b.checksumVersion() == null ? spec.checksumVersion() : b.checksumVersion(), ok);
            if (current == null || checked.sequence() != current.sequence) {
                current = new Txn(checked.sequence(), ringPos, checked.slot());
                out.txns.add(current);
                pendingTags = new ArrayList<>();
            }
            current.blocks.add(checked);
            if (!ok) current.checksumOk = false;
            switch (checked.type()) {
                case "descriptor" -> pendingTags.addAll(checked.tags() == null ? List.of() : checked.tags());
                case "data" -> {
                    if (!pendingTags.isEmpty()) {
                        long fsblock = pendingTags.remove(0);
                        current.writes.add(new Write(fsblock, checked.payloadHex(), checked.sequence(), checked.slot()));
                    } else {
                        out.diagnostics.add("ORPHAN_DATA_BLOCK: slot " + checked.slot()
                                + " has no descriptor tag; kept unparsed");
                    }
                }
                case "revoke" -> current.revokes.addAll(checked.revoke() == null ? List.of() : checked.revoke());
                case "commit" -> current.hasCommit = true;
                default -> out.diagnostics.add("UNKNOWN_BLOCK_TYPE: " + checked.type() + " at slot " + checked.slot());
            }
            ringPos++;
        }
        return out;
    }
}
