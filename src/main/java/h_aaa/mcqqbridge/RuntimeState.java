package h_aaa.mcqqbridge;

public enum RuntimeState {
    STARTING,
    RUNNING,
    DEGRADED_DATABASE_LOCKED,
    DEGRADED_ONEBOT,
    CONFIGURATION_LOCKED,
    STOPPED
}
