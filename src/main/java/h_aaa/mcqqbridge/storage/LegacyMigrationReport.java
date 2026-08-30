package h_aaa.mcqqbridge.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LegacyMigrationReport {
    public enum Status {
        NO_SOURCE,
        MIGRATED,
        MIGRATED_WITH_ISSUES,
        ALREADY_MIGRATED
    }

    private final Status status;
    private final String runId;
    private final Path source;
    private final Path backupDirectory;
    private final String sourceSha256;
    private final String sourceFingerprint;
    private final List<LegacyRowDisposition> rows;

    public LegacyMigrationReport(Status status, String runId, Path source, Path backupDirectory,
                                 String sourceSha256, String sourceFingerprint,
                                 List<LegacyRowDisposition> rows) {
        this.status = status;
        this.runId = runId;
        this.source = source;
        this.backupDirectory = backupDirectory;
        this.sourceSha256 = sourceSha256 == null ? "" : sourceSha256;
        this.sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        this.rows = Collections.unmodifiableList(new ArrayList<LegacyRowDisposition>(rows));
    }

    public static LegacyMigrationReport noSource() {
        return new LegacyMigrationReport(Status.NO_SOURCE, "", null, null, "", "",
                Collections.<LegacyRowDisposition>emptyList());
    }

    public Status getStatus() { return status; }
    public String getRunId() { return runId; }
    public Path getSource() { return source; }
    public Path getBackupDirectory() { return backupDirectory; }
    public String getSourceSha256() { return sourceSha256; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public List<LegacyRowDisposition> getRows() { return rows; }

    public int getTotalRows() { return rows.size(); }

    public int count(LegacyRowDisposition.Outcome outcome) {
        int count = 0;
        for (LegacyRowDisposition row : rows) {
            if (row.getOutcome() == outcome) {
                count++;
            }
        }
        return count;
    }

    public int getImportedRows() { return count(LegacyRowDisposition.Outcome.IMPORTED); }

    public int getIssueRows() {
        int count = 0;
        for (LegacyRowDisposition row : rows) {
            if (row.getOutcome() != LegacyRowDisposition.Outcome.IMPORTED
                    && row.getOutcome() != LegacyRowDisposition.Outcome.ALREADY_PRESENT
                    && row.getOutcome() != LegacyRowDisposition.Outcome.DUPLICATE_IDENTICAL) {
                count++;
            }
        }
        return count;
    }
}
