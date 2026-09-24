package com.fslab.jbd2.model;

/** A descriptor tag bound to the data block immediately following it in the run. */
public record DataBinding(
        long tagIndex,
        long fsBlockNr,
        long descriptorRingPos,
        long dataRingPos,
        int flags,
        String dataBytesHex) {

    public String id() {
        return DescriptorTag.id(descriptorRingPos, tagIndex) + "->data@ring" + dataRingPos;
    }
}
