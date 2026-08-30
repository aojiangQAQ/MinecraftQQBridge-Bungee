package h_aaa.mcqqbridge.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AdminPolicy {
    private final Set<String> administratorIds;
    private final boolean requireGroupRole;
    private final boolean allowMembersQueryOther;
    private final boolean allowMembersStatus;

    public AdminPolicy(Set<String> administratorIds, boolean requireGroupRole,
                       boolean allowMembersQueryOther, boolean allowMembersStatus) {
        this.administratorIds = Collections.unmodifiableSet(
                new HashSet<String>(administratorIds));
        this.requireGroupRole = requireGroupRole;
        this.allowMembersQueryOther = allowMembersQueryOther;
        this.allowMembersStatus = allowMembersStatus;
    }

    public boolean isAdministrator(String userId, String senderRole) {
        if (!administratorIds.contains(userId)) {
            return false;
        }
        if (!requireGroupRole) {
            return true;
        }
        return "admin".equals(senderRole) || "owner".equals(senderRole);
    }

    public boolean canQueryOther(String userId, String senderRole) {
        return allowMembersQueryOther
                || isAdministrator(userId, senderRole);
    }

    public boolean canViewStatus(String userId, String senderRole) {
        return allowMembersStatus
                || isAdministrator(userId, senderRole);
    }
}
