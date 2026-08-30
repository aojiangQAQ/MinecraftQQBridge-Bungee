package h_aaa.mcqqbridge.domain;

public final class BindResult {
    public enum Status {
        CREATED,
        SAME_BINDING,
        QQ_ALREADY_BOUND,
        PLAYER_ALREADY_BOUND
    }

    private final Status status;
    private final Binding binding;

    public BindResult(Status status, Binding binding) {
        this.status = status;
        this.binding = binding;
    }

    public Status getStatus() { return status; }
    public Binding getBinding() { return binding; }
}
