package com.yx.uavfire.fc100.event.service;

/** Resolves an Agent flightId to its authoritative workspace and assigned aircraft. */
public interface AgentFireTaskBindingResolver {
    Binding resolveForInitialReport(String flightId, String droneSn, long eventTimestamp);

    final class Binding {
        private final String workspaceId;
        private final String flightId;
        private final String droneSn;

        public Binding(String workspaceId, String flightId, String droneSn) {
            this.workspaceId = workspaceId;
            this.flightId = flightId;
            this.droneSn = droneSn;
        }
        public String getWorkspaceId() { return workspaceId; }
        public String getFlightId() { return flightId; }
        public String getDroneSn() { return droneSn; }
    }
}
