package com.example.jenkins.managedfile.action;

import com.example.jenkins.managedfile.model.ManagedFileVersion;
import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.VersionStore;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.ModelObject;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Shows the raw content of a single historical version.
 */
@Extension
public class ManagedFileViewVersionAction implements Action, ModelObject {

    public ManagedFileViewVersionAction() {
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

    public int getVersion() {
        return currentVersion();
    }

    public ManagedFileVersion getMetadata() {
        String id = currentId();
        int v = currentVersion();
        if (id == null || v <= 0) return null;
        return ManagedFileVersionService.getInstance().getVersion(id, v);
    }

    public String getContent() {
        String id = currentId();
        int v = currentVersion();
        if (id == null || v <= 0) return "";
        String c = ManagedFileVersionService.getInstance().getVersionContent(id, v);
        return c == null ? "" : c;
    }

    public int getPreviousVersion() {
        String id = currentId();
        int v = currentVersion();
        if (id == null || v <= 1) return 0;
        return v - 1;
    }
    @Override
    public String getDisplayName() {
        return "View Version: V" + currentVersion() + " (" + currentId() + ")";
    }

    @Override
    public String getIconFileName() {
        return "symbol-file plugin-managed-file-version-manager";
    }

    @Override
    public String getUrlName() {
        return "view";
    }

    public static void checkPermission() {
        // Permission checks removed per project decision; endpoint is open.
    }
}
