package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry-point action reachable from "Manage Jenkins" -> "Managed File Versions".
 *
 * <p>Lists every Managed File known to {@link GlobalConfigFiles} alongside the
 * latest recorded version (or a note that there is no history yet).</p>
 *
 * <p>Child URLs ({@code /manage/managed-file-versions/history}, {@code view},
 * {@code diff}, {@code rollback}) are terminal endpoints that forward directly
 * to their Jelly views.</p>
 */
@Extension
public class ManagedFileVersionsAction extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(ManagedFileVersionsAction.class.getName());

    @Override
    public String getDisplayName() {
        return "Managed File Versions";
    }

    @Override
    public String getDescription() {
        return "View, diff and rollback version history for Managed Files.";
    }

    @Override
    public String getIconFileName() {
        return "symbol-history plugin-managed-file-version-manager";
    }

    @Override
    public String getUrlName() {
        return "managed-file-versions";
    }

    @Override
    public String getCategoryName() {
        return "CONFIGURATION";
    }

    @Override
    public Permission getRequiredPermission() {
        return Jenkins.READ;
    }

    /**
     * Renders the index jelly view for {@code /manage/managed-file-versions}.
     */
    @WebMethod(name = "index")
    public void doIndex(StaplerRequest2 req) throws Exception {
        throw HttpResponses.forwardToView(this, "index.jelly");
    }

    /**
     * Dispatcher that routes {@code /manage/managed-file-versions/<segment>}
     * to the appropriate child action.
     */
    @WebMethod(name = "history")
    public void history(StaplerRequest2 req) throws Exception {
        String id = req.getParameter("id");
        throw HttpResponses.forwardToView(new ManagedFileHistoryAction(id), "index.jelly");
    }

    @WebMethod(name = "view")
    public void view(StaplerRequest2 req) throws Exception {
        throw HttpResponses.forwardToView(new ManagedFileViewVersionAction(), "index.jelly");
    }

    @WebMethod(name = "diff")
    public void diff(StaplerRequest2 req) throws Exception {
        throw HttpResponses.forwardToView(new ManagedFileDiffAction(), "index.jelly");
    }

    @WebMethod(name = "rollback")
    public void rollback(StaplerRequest2 req) throws Exception {
        throw HttpResponses.forwardToView(new ManagedFileRollbackAction(), "index.jelly");
    }

    @POST
    @RequirePOST
    public HttpResponse doConfirm(StaplerRequest2 req,
                                  @QueryParameter("id") String id,
                                  @QueryParameter("version") int version) throws IOException {
        return new ManagedFileRollbackAction().doConfirm(req, id, version);
    }

    public List<ManagedFileRow> getRows() {
        LOGGER.log(Level.INFO, "getRows called - starting");
        Collection<Config> configs = GlobalConfigFiles.get().getConfigs();
        LOGGER.log(Level.INFO, "GlobalConfigFiles has " + configs.size() + " configs");
        List<ManagedFileRow> rows = new ArrayList<>();
        for (Config c : configs) {
            List<ManagedFileVersion> history = ManagedFileVersionService.getInstance().getHistory(c.id);
            rows.add(new ManagedFileRow(c.id, c.name, history));
        }
        rows.sort(Comparator.comparing(r -> r.name == null ? r.id : r.name, String.CASE_INSENSITIVE_ORDER));
        LOGGER.log(Level.INFO, "Returning " + rows.size() + " rows");
        return rows;
    }

    public static final class ManagedFileRow {
        public final String id;
        public final String name;
        public final List<ManagedFileVersion> history;

        public ManagedFileRow(String id, String name, List<ManagedFileVersion> history) {
            this.id = id;
            this.name = name;
            this.history = history == null ? Collections.emptyList() : history;
        }

        public int getVersionCount() {
            return history.size();
        }

        public ManagedFileVersion getLatestVersion() {
            return history.isEmpty() ? null : history.get(0);
        }
    }
}
