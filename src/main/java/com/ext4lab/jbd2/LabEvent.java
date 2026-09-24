package com.ext4lab.jbd2;

import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-readable judgment event. Ids are stable: evt-<n> assigned in scan order. */
public final class LabEvent {
    public final String id;
    public final String type;
    public final Map<String, Object> payload = new LinkedHashMap<>();

    public LabEvent(String id, String type) {
        this.id = id;
        this.type = type;
    }

    public LabEvent put(String k, Object v) {
        payload.put(k, v);
        return this;
    }

    @Override public String toString() {
        return id + " " + type + " " + payload;
    }
}
