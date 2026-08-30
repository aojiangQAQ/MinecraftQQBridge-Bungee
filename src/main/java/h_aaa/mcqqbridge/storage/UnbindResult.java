package h_aaa.mcqqbridge.storage;

import h_aaa.mcqqbridge.domain.Binding;

public final class UnbindResult {
    public enum Status {
        REMOVED,
        NOT_FOUND,
        DUPLICATE_EVENT
    }

    private final Status status;
    private final Binding binding;

    public UnbindResult(Status status, Binding binding) {
        this.status = status;
        this.binding = binding;
    }

    public Status getStatus() { return status; }
    public Binding getBinding() { return binding; }
}
