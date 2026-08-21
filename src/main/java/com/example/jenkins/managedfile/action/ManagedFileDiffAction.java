package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.VersionStore;
import com.example.jenkins.managedfile.util.DiffUtil;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.ModelObject;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.Collections;
import java.util.List;

/**
 * Renders a unified diff between two versions.
 */
@Extension
public class ManagedFileDiffAction implements Action, ModelObject {

    public ManagedFileDiffAction() {
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

    private int intParam(String name) {
        StaplerRequest2 req = Stapler.getCurrentRequest2();
        if (req == null) return 0;
        try {
            return Integer.parseInt(req.getParameter(name));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String getFileId() {
        return currentId();
    }

    public int getFromVersion() {
        return intParam("from");
    }

    public int getToVersion() {
        return intParam("to");
    }

    public ManagedFileVersion getFromMetadata() {
        String id = currentId();
        int v = getFromVersion();
        if (id == null || v <= 0) return null;
        return ManagedFileVersionService.getInstance().getVersion(id, v);
    }

    public ManagedFileVersion getToMetadata() {
        String id = currentId();
        int v = getToVersion();
        if (id == null || v <= 0) return null;
        return ManagedFileVersionService.getInstance().getVersion(id, v);
    }

    public String getFromContent() {
        String id = currentId();
        int v = getFromVersion();
        if (id == null || v <= 0) return "";
        String c = ManagedFileVersionService.getInstance().getVersionContent(id, v);
        return c == null ? "" : c;
    }

    public String getToContent() {
        String id = currentId();
        int v = getToVersion();
        if (id == null || v <= 0) return "";
        String c = ManagedFileVersionService.getInstance().getVersionContent(id, v);
        return c == null ? "" : c;
    }

    public List<DiffUtil.DiffLine> getDiffLines() {
        String a = getFromContent();
        String b = getToContent();
        return DiffUtil.diff(a, b);
    }

    public List<DiffUtil.SideBySideLine> getSideBySideLines() {
        String a = getFromContent();
        String b = getToContent();
        return DiffUtil.sideBySide(a, b);
    }

    public List<DiffUtil.DiffLine> getEmpty() {
        return Collections.emptyList();
    }

    public DiffStats getStats() {
        List<DiffUtil.DiffLine> lines = getDiffLines();
        int added = 0, removed = 0, unchanged = 0;
        for (DiffUtil.DiffLine line : lines) {
            switch (line.getKind()) {
                case ADD: added++; break;
                case REMOVE: removed++; break;
                case CONTEXT: unchanged++; break;
            }
        }
        return new DiffStats(added, removed, unchanged);
    }

    public static class DiffStats {
        public final int added, removed, unchanged;
        public DiffStats(int added, int removed, int unchanged) {
            this.added = added;
            this.removed = removed;
            this.unchanged = unchanged;
        }
    }
    @Override
    public String getDisplayName() {
        return "Diff: V" + getFromVersion() + " \u2192 V" + getToVersion();
    }

    @Override
    public String getIconFileName() {
        return "symbol-diff plugin-managed-file-version-manager";
    }

    @Override
    public String getUrlName() {
        return "diff";
    }

    public boolean getCanRollback() {
        return true;
    }


    public static void checkPermission() {
        // Permission checks removed per project decision; endpoint is open.
    }
}
