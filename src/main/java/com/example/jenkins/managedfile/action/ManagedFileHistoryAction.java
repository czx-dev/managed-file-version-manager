package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.VersionStore;
import hudson.model.Action;
import hudson.model.ModelObject;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.WebMethod;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists the version history of one specific managed file.
 *
 * <p>NOT an @Extension — this class is instantiated dynamically by
 * {@link ManagedFileVersionsAction#history(StaplerRequest2)}.</p>
 */
public class ManagedFileHistoryAction implements Action, ModelObject {

    private static final Logger LOGGER = Logger.getLogger(ManagedFileHistoryAction.class.getName());

    private final String fileId;

    public ManagedFileHistoryAction(String fileId) {
        this.fileId = fileId;
        LOGGER.info("ManagedFileHistoryAction created with fileId=" + fileId);
    }

    public ManagedFileHistoryAction() {
        this.fileId = null;
        LOGGER.warning("ManagedFileHistoryAction created with no-arg constructor");
    }

    public String getFileId() {
        return fileId;
    }

    public String getDisplayName() {
        return "Managed File Version History: " + getConfigName();
    }

    public String getConfigName() {
        Config c = getConfig();
        return c == null ? "" : (c.name == null ? "" : c.name);
    }

    public Config getConfig() {
        if (fileId == null) {
            return null;
        }
        return GlobalConfigFiles.get().getById(fileId);
    }

    public List<ManagedFileVersion> getVersions() {
        if (fileId == null) {
            return Collections.emptyList();
        }
        List<ManagedFileVersion> versions = ManagedFileVersionService.getInstance().getHistory(fileId);
        LOGGER.info("getVersions: got " + (versions == null ? 0 : versions.size()) + " versions");
        return versions == null ? Collections.emptyList() : versions;
    }

    public boolean getCanRollback() {
        return true;
    }

    @WebMethod(name = "index")
    public void doIndex(StaplerRequest2 req) throws Exception {
        LOGGER.info("doIndex called");
        throw HttpResponses.forwardToView(this, "index.jelly");
    }

    @Override
    public String getIconFileName() {
        return "symbol-history plugin-managed-file-version-manager";
    }

    @Override
    public String getUrlName() {
        return "history";
    }

}
