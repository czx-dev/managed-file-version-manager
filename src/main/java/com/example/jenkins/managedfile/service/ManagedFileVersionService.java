package com.example.jenkins.managedfile.service;

import com.example.jenkins.managedfile.model.ConfigSnapshot;
import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.model.Operation;
import com.example.jenkins.managedfile.store.VersionStore;
import com.example.jenkins.managedfile.util.Sha256Util;
import hudson.model.User;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service facade that:
 * <ul>
 *   <li>decides CREATE / UPDATE / DELETE based on the in-memory snapshot map,</li>
 *   <li>delegates persistence to {@link VersionStore},</li>
 *   <li>exposes query APIs used by the UI.</li>
 * </ul>
 *
 * <p>All version-creation paths go through {@link #recordSnapshot()}. The
 * rollback path bypasses the snapshot logic and writes a new version
 * directly.</p>
 */
public class ManagedFileVersionService {

    private static final Logger LOGGER = Logger.getLogger(ManagedFileVersionService.class.getName());

    private static final ManagedFileVersionService INSTANCE = new ManagedFileVersionService();

    public static ManagedFileVersionService getInstance() {
        return INSTANCE;
    }

    /** fileId -> last seen snapshot */
    private final Map<String, ConfigSnapshot> snapshots = new HashMap<>();

    private ManagedFileVersionService() {
    }

    /**
     * Bootstrap: rebuild the in-memory snapshot map from the current
     * GlobalConfigFiles state. Called once at plugin start-up.
     *
     * <p>This avoids spurious "CREATE" entries after a restart where the
     * Managed Files already exist on disk.</p>
     */
    public void initialiseSnapshot() {
        try {
            Collection<Config> configs = GlobalConfigFiles.get().getConfigs();
            Map<String, ConfigSnapshot> fresh = new HashMap<>();
            for (Config c : configs) {
                fresh.put(c.id, new ConfigSnapshot(c.id, c.name, c.content, Sha256Util.hash(c.content)));
            }
            synchronized (snapshots) {
                snapshots.clear();
                snapshots.putAll(fresh);
            }
            LOGGER.log(Level.INFO, "Managed File Version Manager: initialised snapshot of {0} configs", fresh.size());
        } catch (Throwable t) {
            // Do not let plugin failures take Jenkins down.
            LOGGER.log(Level.WARNING, "Failed to initialise managed-file snapshot", t);
        }
    }

    /**
     * Inspect the current GlobalConfigFiles state, compare against the
     * snapshot map, and persist new versions for any delta.
     */
    public void recordSnapshot() {
        try {
            Collection<Config> configs = GlobalConfigFiles.get().getConfigs();
            Map<String, ConfigSnapshot> current = new HashMap<>();
            for (Config c : configs) {
                current.put(c.id, new ConfigSnapshot(c.id, c.name, c.content, Sha256Util.hash(c.content)));
            }

            UserAndId user = currentUser();

            synchronized (snapshots) {
                // CREATE / UPDATE
                for (Map.Entry<String, ConfigSnapshot> entry : current.entrySet()) {
                    ConfigSnapshot now = entry.getValue();
                    ConfigSnapshot was = snapshots.get(entry.getKey());
                    if (was == null) {
                        VersionStore.getInstance().saveVersion(
                                now.getId(), now.getName(),
                                user.user, user.userId,
                                Operation.CREATE, null,
                                now.getContent(),
                                null);
                    } else if (!was.getSha256().equals(now.getSha256())) {
                        VersionStore.getInstance().saveVersion(
                                now.getId(), now.getName(),
                                user.user, user.userId,
                                Operation.UPDATE, null,
                                now.getContent(),
                                null);
                    }
                }
                // DELETE
                for (Map.Entry<String, ConfigSnapshot> entry : snapshots.entrySet()) {
                    if (!current.containsKey(entry.getKey())) {
                        // We still want to record what the content WAS at delete time.
                        VersionStore.getInstance().saveVersion(
                                entry.getValue().getId(), entry.getValue().getName(),
                                user.user, user.userId,
                                Operation.DELETE, null,
                                entry.getValue().getContent(),
                                "Managed file deleted");
                    }
                }
                snapshots.clear();
                snapshots.putAll(current);
            }
        } catch (Throwable t) {
            // Plugin must never affect Jenkins core behaviour.
            LOGGER.log(Level.WARNING, "recordSnapshot failed", t);
        }
    }

    /**
     * Record a rollback as a new version. The caller is expected to have
     * already mutated the underlying GlobalConfigFiles.
     *
     * <p>The rollback action calls {@link #rememberSnapshot(String, String, String)}
     * before saving the Jenkins config, then calls this method after the save
     * succeeds to append the explicit ROLLBACK entry.</p>
     */
    public ManagedFileVersion recordRollback(String fileId, int targetVersion, String newContent) {
        UserAndId user = currentUser();
        Config config = GlobalConfigFiles.get().getById(fileId);
        String fileName = config == null ? fileId : config.name;
        ManagedFileVersion v = VersionStore.getInstance().saveVersion(
                fileId, fileName,
                user.user, user.userId,
                Operation.ROLLBACK, targetVersion,
                newContent,
                "Rollback to version " + targetVersion);

        synchronized (snapshots) {
            snapshots.put(fileId, new ConfigSnapshot(fileId, fileName, newContent, Sha256Util.hash(newContent)));
        }
        return v;
    }

    public void rememberSnapshot(String fileId, String fileName, String content) {
        VersionStore.validateFileId(fileId);
        synchronized (snapshots) {
            snapshots.put(fileId, new ConfigSnapshot(fileId, fileName, content, Sha256Util.hash(content)));
        }
    }

    public List<ManagedFileVersion> getHistory(String fileId) {
        return VersionStore.getInstance().listVersions(fileId);
    }

    public ManagedFileVersion getVersion(String fileId, int version) {
        return VersionStore.getInstance().getVersion(fileId, version);
    }

    public String getVersionContent(String fileId, int version) {
        return VersionStore.getInstance().getContent(fileId, version);
    }

    private UserAndId currentUser() {
        try {
            User u = User.current();
            String userId = u != null ? u.getId() : "anonymous";
            String display = u != null && u.getFullName() != null && !u.getFullName().isEmpty()
                    ? u.getFullName() : userId;
            return new UserAndId(display, userId);
        } catch (Throwable t) {
            return new UserAndId("system", "system");
        }
    }

    private static final class UserAndId {
        final String user;
        final String userId;

        UserAndId(String user, String userId) {
            this.user = user;
            this.userId = userId;
        }
    }
}
