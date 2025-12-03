package org.starexec.data.to;

/**
 * Represents a row in the migration_stats view.
 */
public class MigrationStat {
    private String passwordAlgorithm;
    private String lastLoginAlgorithm;
    private int userCount;

    public MigrationStat() {
    }

    public MigrationStat(String passwordAlgorithm, String lastLoginAlgorithm, int userCount) {
        this.passwordAlgorithm = passwordAlgorithm;
        this.lastLoginAlgorithm = lastLoginAlgorithm;
        this.userCount = userCount;
    }

    public String getPasswordAlgorithm() {
        return passwordAlgorithm;
    }

    public void setPasswordAlgorithm(String passwordAlgorithm) {
        this.passwordAlgorithm = passwordAlgorithm;
    }

    public String getLastLoginAlgorithm() {
        return lastLoginAlgorithm;
    }

    public void setLastLoginAlgorithm(String lastLoginAlgorithm) {
        this.lastLoginAlgorithm = lastLoginAlgorithm;
    }

    public int getUserCount() {
        return userCount;
    }

    public void setUserCount(int userCount) {
        this.userCount = userCount;
    }
}
