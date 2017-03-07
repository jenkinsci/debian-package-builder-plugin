package ru.yandex.jenkins.plugins.debuilder;

import com.google.inject.Inject;
import hudson.*;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Debian package builder step for Pipeline plugin
 *
 * @author mavlyutov
 */

public class DebianPackagePublisherStep extends AbstractStepImpl {
    private String repoId;
    private String commitMessage;
    private Boolean commitChanges;

    private List<String> packages;

    public void setPackages(String[] packages) {
        this.packages = new ArrayList<String>(Arrays.asList(packages));
    }

    @DataBoundSetter
    public void setCommitChanges(Boolean commitChanges) {
        this.commitChanges = commitChanges;
    }

    @DataBoundSetter
    public void setRepoId(String repoId) {
        this.repoId = repoId;
    }

    @DataBoundSetter
    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    @DataBoundConstructor
    public DebianPackagePublisherStep(String repoId, String commitMessage, String[] packages) {
        this.repoId = repoId;
        this.commitMessage = commitMessage;
        this.setPackages(packages);
    }

    public String getRepoId() {
        return repoId;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public Boolean getCommitChanges() {
        return commitChanges;
    }

    public List<String> getPackages() {
        return packages;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override
        public String getFunctionName() {
            return "debianPackagePublish";
        }

        @Override
        public String getDisplayName() {
            return "Publish debian package";
        }
    }

    public static final class Execution extends AbstractSynchronousNonBlockingStepExecution<Void> {

        private static final long serialVersionUID = 1;

        @Inject
        private transient DebianPackagePublisherStep step;

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
            DebianPackagePublisher publisher = new DebianPackagePublisher(
                    step.getRepoId(),
                    step.getCommitMessage(), step.getCommitChanges());
            publisher.doDebrelease(step.getPackages(), launcher, run, workspace, listener);
            return null;
        }
    }
}
