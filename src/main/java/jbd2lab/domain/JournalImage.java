package jbd2lab.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Imported journal image: geometry plus raw blocks keyed by physical coordinate. */
public record JournalImage(RingGeom geom, Map<Integer, RawBlock> blocks) {

    public static JournalImage of(RingGeom geom, Map<Integer, RawBlock> blocks) {
        return new JournalImage(geom, new LinkedHashMap<>(blocks));
    }

    public RawBlock at(int physical) {
        return blocks.get(physical);
    }
}
