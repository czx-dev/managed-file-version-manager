package com.example.jenkins.managedfile.model;

import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Concrete {@link Config} used for rollback operations.
 */
public class SimpleConfig extends Config {

    @DataBoundConstructor
    public SimpleConfig(@NonNull String id, String name, String comment, String content,
                        @NonNull String providerId) {
        super(id, name, comment, content, providerId);
    }

    @Override
    public String toString() {
        return "SimpleConfig{id='" + id + "', name='" + name + "'}";
    }
}
