package com.example.jenkins.managedfile.listener;

import com.example.jenkins.managedfile.service.ManagedFileVersionService;
import com.example.jenkins.managedfile.store.VersionStore;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import jakarta.annotation.PostConstruct;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures save events from {@link GlobalConfigFiles} and asks the version
 * service to compute the delta.
 *
 * <p>The listener is intentionally a thin shim - all logic lives in the
 * service so that it can also be exercised from unit tests.</p>
 */
@Extension
public class ManagedFileSaveListener extends SaveableListener {

    private static final Logger LOGGER = Logger.getLogger(ManagedFileSaveListener.class.getName());

    @PostConstruct
    public void init() {
        try {
            VersionStore.getInstance().init();
            ManagedFileVersionService.getInstance().initialiseSnapshot();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Managed File Version Manager failed to initialise", t);
        }
    }

    @Override
    public void onChange(Saveable o, XmlFile file) {
        try {
            if (o instanceof GlobalConfigFiles) {
                ManagedFileVersionService.getInstance().recordSnapshot();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "ManagedFileSaveListener.onChange failed", t);
        }
    }

    @Override
    public void onDeleted(Saveable o, XmlFile file) {
        try {
            if (o instanceof GlobalConfigFiles) {
                ManagedFileVersionService.getInstance().recordSnapshot();
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "ManagedFileSaveListener.onDeleted failed", t);
        }
    }

    /**
     * Test-only entry point that lets a unit test trigger the listener
     * logic without an actual Jenkins Saveable pipeline.
     */
    public static void triggerForTesting(GlobalConfigFiles store) {
        ManagedFileVersionService.getInstance().recordSnapshot();
    }

    /**
     * Visible for testing only.
     */
    public static boolean isInitialised() {
        try {
            return Jenkins.get() != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
