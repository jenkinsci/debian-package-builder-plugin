package ru.yandex.jenkins.plugins.debuilder;

import com.google.inject.Inject;
import hudson.*;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Computer;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Debian package builder step for Pipeline plugin
 *
 * @author mavlyutov
 */

public class DebianPackageStep extends AbstractStepImpl {

    private String pathToDebian;
    private String nextVersion;
    private boolean generateChangelog = false;
    private boolean signPackage = true;
    private boolean buildEvenWhenThereAreNoChanges = true;

    @DataBoundConstructor
    public DebianPackageStep(String pathToDebian, String nextVersion, Boolean generateChangelog, Boolean signPackage, Boolean buildEvenWhenThereAreNoChanges) {
        this.pathToDebian = pathToDebian;
        this.nextVersion = nextVersion;
        this.generateChangelog = generateChangelog;
        this.signPackage = signPackage;
        this.buildEvenWhenThereAreNoChanges = buildEvenWhenThereAreNoChanges;
    }

    @DataBoundSetter
    public void setPathToDebian(String pathToDebian) {
        this.pathToDebian = Util.fixEmptyAndTrim(pathToDebian);
    }

    @DataBoundSetter
    public void setNextVersion(String nextVersion) {
        this.nextVersion = Util.fixEmptyAndTrim(nextVersion);
    }

    @DataBoundSetter
    public void setGenerateChangelog(boolean generateChangelog) {
        this.generateChangelog = generateChangelog;
    }

    @DataBoundSetter
    public void setSignPackage(boolean signPackage) {
        this.signPackage = signPackage;
    }

    @DataBoundSetter
    public void SetBuildEvenWhenThereAreNoChanges(boolean buildEvenWhenThereAreNoChanges) {
        this.buildEvenWhenThereAreNoChanges = buildEvenWhenThereAreNoChanges;
    }

    public String getPathToDebian() {
        return pathToDebian;
    }

    public String getNextVersion() {
        return nextVersion;
    }

    public boolean isGenerateChangelog() {
        return generateChangelog;
    }

    public boolean isSignPackage() {
        return signPackage;
    }

    public boolean isBuildEvenWhenThereAreNoChanges() {
        return buildEvenWhenThereAreNoChanges;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override
        public String getFunctionName() {
            return "debianPackage";
        }

        @Override
        public String getDisplayName() {
            return "Build debian package";
        }
    }

    public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1;

        @Inject
        private transient DebianPackageStep step;

        @StepContextParameter
        private transient TaskListener listener;

        @StepContextParameter
        private transient Launcher launcher;

        @StepContextParameter
        private transient Run<?,?> run;

        @StepContextParameter
        private transient FilePath workspace;

        @StepContextParameter
        private transient EnvVars envVars;

        @StepContextParameter
        private transient Computer computer;

        @Override
        protected Void run() throws Exception {
            DebianPackageBuilder builder = new DebianPackageBuilder(
                    step.getPathToDebian(),
                    step.getNextVersion(),
                    step.isGenerateChangelog(),
                    step.isSignPackage(),
                    step.isBuildEvenWhenThereAreNoChanges());
            builder.perform(run, workspace, launcher, listener);
            return null;
        }
    }
}
