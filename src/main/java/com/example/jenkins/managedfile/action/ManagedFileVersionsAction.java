package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.Group;
import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.GroupStore;
import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.security.Permission;
import jenkins.model.Jenkins;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.kohsuke.stapler.HttpRedirect;
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
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry-point action reachable from "Manage Jenkins" -> "Managed File Versions".
 *
 * <p>Lists every Managed File known to {@link GlobalConfigFiles} alongside the
 * latest recorded version (or a note that there is no history yet).</p>
 *
 * <p>This page additionally surfaces <em>groups</em>, <em>search</em> and
 * <em>sort</em> features. Editing the underlying Managed File (create / edit /
 * delete) is intentionally delegated to Config File Provider's own UI; we
 * only manage grouping and listing on top.</p>
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

    // ---------------- POST: group management ----------------

    @POST
    @RequirePOST
    public HttpResponse doCreateGroup(@QueryParameter("groupId") String groupId,
                                      @QueryParameter("groupName") String groupName,
                                      @QueryParameter("description") String description,
                                      @QueryParameter("q") String q,
                                      @QueryParameter("group") String groupFilter,
                                      @QueryParameter("sort") String sort,
                                      @QueryParameter("dir") String dir) throws IOException {
        try {
            GroupStore.getInstance().createGroup(groupId, groupName, description);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Failed to create group: " + groupId, e);
        }
        return renderInPlace();
    }

    @POST
    @RequirePOST
    public HttpResponse doDeleteGroup(@QueryParameter("groupId") String groupId,
                                      @QueryParameter("q") String q,
                                      @QueryParameter("group") String groupFilter,
                                      @QueryParameter("sort") String sort,
                                      @QueryParameter("dir") String dir) throws IOException {
        GroupStore.getInstance().deleteGroup(groupId);
        // If we were filtering by the deleted group, clear the filter so the
        // user is not stranded on an empty result set.
        if (groupId != null && groupId.equals(groupFilter)) {
            groupFilter = null;
        }
        return renderInPlace();
    }

    @POST
    @RequirePOST
    public HttpResponse doAssign(@QueryParameter("fileId") String fileId,
                                 @QueryParameter("groupId") String groupId,
                                 @QueryParameter("q") String q,
                                 @QueryParameter("group") String groupFilter,
                                 @QueryParameter("sort") String sort,
                                 @QueryParameter("dir") String dir) throws IOException {
        try {
            GroupStore.getInstance().assign(fileId, groupId);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Failed to assign " + fileId + " -> " + groupId, e);
        }
        return renderInPlace();
    }

    /**
     * Delete a managed file by delegating to {@link GlobalConfigFiles#remove(String)}.
     * Also drops the file from our group assignments so that a stale groupId
     * does not linger after the file itself has been removed.
     */
    @POST
    @RequirePOST
    public HttpResponse doDeleteConfig(@QueryParameter("fileId") String fileId,
                                       @QueryParameter("q") String q,
                                       @QueryParameter("group") String groupFilter,
                                       @QueryParameter("sort") String sort,
                                       @QueryParameter("dir") String dir) throws IOException {
        if (fileId != null && !fileId.isEmpty()) {
            try {
                GlobalConfigFiles.get().remove(fileId);
                GroupStore.getInstance().unassign(fileId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to delete config " + fileId, e);
            }
        }
        return renderInPlace();
    }

    private static HttpResponse redirectBack(String q, String group, String sort, String dir) {
        StringBuilder url = new StringBuilder("../");
        boolean first = true;
        if (q != null && !q.isEmpty()) { url.append(first ? "?" : "&").append("q=").append(encode(q)); first = false; }
        if (group != null && !group.isEmpty()) { url.append(first ? "?" : "&").append("group=").append(encode(group)); first = false; }
        if (sort != null && !sort.isEmpty()) { url.append(first ? "?" : "&").append("sort=").append(encode(sort)); first = false; }
        if (dir != null && !dir.isEmpty()) { url.append(first ? "?" : "&").append("dir=").append(encode(dir)); }
        return new HttpRedirect(url.toString());
    }

    /**
     * Re-render the current Jelly view in place. Used for "create" and
     * "assign" actions where bouncing the user through a redirect is just
     * noise - the data already changed, and a same-request render keeps the
     * scroll position, URL and selected filter intact.
     */
    private static HttpResponse renderInPlace() {
        return HttpResponses.forwardToView(
                org.kohsuke.stapler.Stapler.getCurrentRequest().findAncestorObject(ManagedFileVersionsAction.class),
                "index.jelly");
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------------- view helpers ----------------

    /** All rows before filtering - used by the unfiltered full view. */
    public List<ManagedFileRow> getRows() {
        return buildRows(null, null, "name", "asc");
    }

    /** Rows after applying query / group / sort parameters from the request. */
    public List<ManagedFileRow> getFilteredRows() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String q = req == null ? null : trim(req.getParameter("q"));
        String group = req == null ? null : trim(req.getParameter("group"));
        String sort = req == null ? "name" : trim(req.getParameter("sort"));
        String dir = req == null ? "asc" : trim(req.getParameter("dir"));
        if (sort == null || sort.isEmpty()) sort = "name";
        if (dir == null || dir.isEmpty()) dir = "asc";
        return buildRows(q, group, sort, dir);
    }

    public List<Group> getGroups() {
        return GroupStore.getInstance().listGroups();
    }

    /** Absolute on-disk path of the storage root, for display only. */
    public String getStoragePath() {
        return GroupStore.getInstance().getStoragePath();
    }

    /** Current search keyword, or empty. */
    public String getQuery() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String q = req == null ? "" : trim(req.getParameter("q"));
        return q == null ? "" : q;
    }

    /** Currently selected group filter id, or empty. */
    public String getGroupFilter() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String g = req == null ? "" : trim(req.getParameter("group"));
        return g == null ? "" : g;
    }

    /** Current sort column key. */
    public String getSortKey() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String s = req == null ? null : trim(req.getParameter("sort"));
        return (s == null || s.isEmpty()) ? "name" : s;
    }

    public String getSortDir() {
        StaplerRequest2 req = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String d = req == null ? null : trim(req.getParameter("dir"));
        return (d == null || d.isEmpty()) ? "asc" : d;
    }

    /**
     * Returns the URL that sorts by {@code column}, toggling direction if
     * the column is already the active sort key.
     */
    public String sortUrl(String column) {
        String current = getSortKey();
        String currentDir = getSortDir();
        String nextDir = column.equals(current) && "asc".equalsIgnoreCase(currentDir) ? "desc" : "asc";
        StringBuilder sb = new StringBuilder("?sort=").append(column).append("&dir=").append(nextDir);
        String q = getQuery();
        if (!q.isEmpty()) sb.append("&q=").append(encode(q));
        String g = getGroupFilter();
        if (!g.isEmpty()) sb.append("&group=").append(encode(g));
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private List<ManagedFileRow> buildRows(String query, String groupFilter, String sortKey, String sortDir) {
        LOGGER.log(Level.FINE, "buildRows query={0}, group={1}, sort={2}/{3}",
                new Object[]{query, groupFilter, sortKey, sortDir});
        Collection<Config> configs = GlobalConfigFiles.get().getConfigs();
        Map<String, String> assignments = GroupStore.getInstance().snapshotAssignments();

        List<ManagedFileRow> rows = new ArrayList<>();
        for (Config c : configs) {
            String groupId = assignments.get(c.id);
            if (groupFilter != null && !groupFilter.isEmpty()) {
                if (!groupFilter.equals(groupId)) continue;
            }
            if (query != null && !query.isEmpty()) {
                String name = c.name == null ? "" : c.name;
                if (!name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                    continue;
                }
            }
            List<ManagedFileVersion> history = ManagedFileVersionService.getInstance().getHistory(c.id);
            rows.add(new ManagedFileRow(c.id, c.name, history, groupId));
        }

        boolean descending = "desc".equalsIgnoreCase(sortDir);
        Comparator<ManagedFileRow> cmp;
        switch (sortKey == null ? "name" : sortKey) {
            case "id":
                cmp = Comparator.comparing(r -> r.id == null ? "" : r.id, String.CASE_INSENSITIVE_ORDER);
                break;
            case "versions":
                cmp = Comparator.comparingInt(ManagedFileRow::getVersionCount);
                break;
            case "time":
                cmp = Comparator.comparing(
                        (ManagedFileRow r) -> r.getLatestVersion() == null || r.getLatestVersion().getTimestamp() == null
                                ? java.time.Instant.EPOCH
                                : r.getLatestVersion().getTimestamp());
                break;
            case "name":
            default:
                cmp = Comparator.comparing(r -> r.name == null ? r.id : r.name, String.CASE_INSENSITIVE_ORDER);
                break;
        }
        if (descending) cmp = cmp.reversed();
        rows.sort(cmp);
        return rows;
    }

    // ---------------- row model ----------------

    public static final class ManagedFileRow {
        public final String id;
        public final String name;
        public final List<ManagedFileVersion> history;
        public final String groupId;

        public ManagedFileRow(String id, String name, List<ManagedFileVersion> history, String groupId) {
            this.id = id;
            this.name = name;
            this.history = history == null ? Collections.emptyList() : history;
            this.groupId = groupId == null ? "" : groupId;
        }

        public int getVersionCount() {
            return history.size();
        }

        public ManagedFileVersion getLatestVersion() {
            return history.isEmpty() ? null : history.get(0);
        }

        public String getGroupDisplayName() {
            if (groupId.isEmpty()) return "";
            Group g = GroupStore.getInstance().getGroup(groupId);
            return g == null ? groupId : g.getName();
        }
    }
}
