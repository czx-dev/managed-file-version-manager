package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.VersionStore;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.ModelObject;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * Two-stage rollback flow:
 * <ol>
 *   <li>GET renders the confirmation page (target + current versions side
 *       by side),</li>
 *   <li>POST executes the rollback, writes the new content via
 *       {@link GlobalConfigFiles#save(Config)} and records a ROLLBACK
 *       version directly via the service.</li>
 * </ol>
 *
 * <p>Rollback deliberately creates a NEW version - history is append-only.</p>
 */
@Extension
public class ManagedFileRollbackAction implements Action, ModelObject {

    public ManagedFileRollbackAction() {
    }

    private String currentId() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) return null;
        String id = req.getParameter("id");
        if (id == null) return null;
        try {
            VersionStore.validateFileId(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return id;
    }

    private int currentVersion() {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) return 0;
        try {
            return Integer.parseInt(req.getParameter("version"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getFileId() {
        return currentId();
    }

    public int getTargetVersion() {
        return currentVersion();
    }

    public Config getConfig() {
        String id = currentId();
        if (id == null) return null;
        return GlobalConfigFiles.get().getById(id);
    }

    public ManagedFileVersion getTargetMetadata() {
        String id = currentId();
        int v = currentVersion();
        if (id == null || v <= 0) return null;
        return ManagedFileVersionService.getInstance().getVersion(id, v);
    }

    public String getConfigName() {
        Config c = getConfig();
        return c == null ? "" : (c.name == null ? "" : c.name);
    }

    public int getCurrentVersion() {
        String id = currentId();
        if (id == null) return 0;
        return VersionStore.getInstance().nextVersionNumber(id) - 1;
    }

    public String getTargetContent() {
        String id = currentId();
        int v = currentVersion();
        if (id == null || v <= 0) return "";
        String c = ManagedFileVersionService.getInstance().getVersionContent(id, v);
        return c == null ? "" : c;
    }
    @Override
    public String getDisplayName() {
        return "Rollback Managed File";
    }

    @Override
    public String getIconFileName() {
        return "symbol-rollback plugin-managed-file-version-manager";
    }

    @Override
    public String getUrlName() {
        return "rollback";
    }

    @POST
    @RequirePOST
    public HttpResponse doConfirm(StaplerRequest2 req,
                                  @QueryParameter("id") String id,
                                  @QueryParameter("version") int version) throws IOException {
        VersionStore.validateFileId(id);
        if (version <= 0) {
            throw new IllegalArgumentException("Invalid version: " + version);
        }

        String content = ManagedFileVersionService.getInstance().getVersionContent(id, version);
        if (content == null) {
            throw new IOException("Target version content missing");
        }
        Config current = GlobalConfigFiles.get().getById(id);
        if (current == null) {
            throw new IOException("Managed file no longer exists; cannot rollback");
        }

        Config rolledBack = withContent(current, content);
        ManagedFileVersionService.getInstance().recordRollback(id, version, content);
        GlobalConfigFiles.get().save(rolledBack);
        return new HttpRedirect("../history?id=" + id);
    }

    private Config withContent(Config current, String content) throws IOException {
        if (current instanceof com.example.jenkins.managedfile.model.SimpleConfig) {
            ConfigProvider provider = ConfigProvider.getByIdOrNull(current.getProviderId());
            if (provider == null) {
                throw new IOException("Config provider not found: " + current.getProviderId());
            }
            return provider.newConfig(current.id, current.name, current.comment, content);
        }

        try {
            Field contentField = Config.class.getDeclaredField("content");
            contentField.setAccessible(true);
            contentField.set(current, content);
            return current;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to update managed file content", e);
        }
    }
}
