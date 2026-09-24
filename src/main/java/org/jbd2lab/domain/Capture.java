package org.jbd2lab.domain;

import org.jbd2lab.util.Units;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imported baseline material. Nothing is normalized away: the declared block size keeps its
 * original textual representation, ring start / sequences keep their unit (journal-block /
 * u32-circular), and every block keeps exact bytes.
 *
 * <p>{@code strideBytes} is the capture stride observed from byte offsets; when the declared
 * block size is invalid the derivation layer flags it and falls back to the stride so the
 * material stays visible instead of being dropped for drawing.
 */
public record Capture(
        String name,
        String blockSizeRaw,
        int strideBytes,
        long journalStart,
        long ringSlots,
        String checksumVersionRaw,
        List<RawBlock> blocks,
        String superblockSource,
        List<String> diagnostics) {

    public Capture {
        List<RawBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(Comparator.comparingLong(RawBlock::physicalRingIndex));
        blocks = List.copyOf(sorted);
        diagnostics = List.copyOf(diagnostics);
    }

    public Units.RawLong declaredBlockSize() {
        return Units.parsePositivePowerOfTwo(blockSizeRaw, "declared block size");
    }

    /** Block size used structurally: declared value when valid, else capture stride with a diagnostic. */
    public int effectiveBlockSize() {
        Units.RawLong d = declaredBlockSize();
        if (d.valid()) {
            return (int) d.require();
        }
        return strideBytes;
    }

    public Map<Long, RawBlock> ringMap() {
        Map<Long, RawBlock> map = new LinkedHashMap<>();
        for (RawBlock block : blocks) {
            map.put(block.physicalRingIndex(), block);
        }
        return map;
    }
}
