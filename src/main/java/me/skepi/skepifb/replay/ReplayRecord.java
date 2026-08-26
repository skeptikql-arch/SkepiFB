package me.skepi.skepifb.replay;

import java.util.List;
import java.util.UUID;

public final class ReplayRecord {
    private final ReplayMetadata metadata;
    private final List<ReplayBlockEvent> events;

    public ReplayRecord(ReplayMetadata metadata, List<ReplayBlockEvent> events) {
        this.metadata = metadata;
        this.events = events;
    }

    public ReplayMetadata getMetadata() {
        return metadata;
    }

    public List<ReplayBlockEvent> getEvents() {
        return events;
    }
}
