package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.model.*;
import hudson.plugins.git.GitSCM;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

import jenkins.model.Jenkins;
import jenkins.SlaveToMasterFileCallable;

import org.eclipse.jgit.lib.PersonIdent;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;
import ru.yandex.jenkins.plugins.debuilder.DebianPackageBuilder.DescriptorImpl;

/**
 * Performs git commiting actions in a remote WS, namely, commiting changelog to the current branch
 * Note to future self: all the fields should be serializable
 * 
 * @author pupssman
 *
 */
public class GitCommitHelper extends SlaveToMasterFileCallable<Boolean> {

	private static final long serialVersionUID = 1L;
	private final EnvVars environment;
	private final TaskListener listener;
	private final String gitExe;
	private final String gitPrefix;
	private final String accountName;
	private final String accountEmail;
	private final String commitMessage;
	private Collection<String> modules;

	public GitCommitHelper(Run<?, ?> run, GitSCM scm, Runner runner, String commitMessage, Collection<String> modules) throws IOException, InterruptedException {
		this.commitMessage = commitMessage;
		this.modules = modules;
		this.environment = run.getEnvironment(runner.getListener());
		this.listener = runner.getListener();
		Computer c = Computer.currentComputer();
		Node n = c == null?null:c.getNode();
		this.gitExe = scm.getGitExe(n, listener);
		this.gitPrefix = scm.getRelativeTargetDir();
		DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(DebianPackageBuilder.class);
		this.accountName = descriptor.getAccountName();
		this.accountEmail = descriptor.getAccountEmail();
	}

	@Override
	public Boolean invoke(File localWorkspace, VirtualChannel channel) throws IOException,
			InterruptedException {

		File gitClonePath = localWorkspace;
		if (gitPrefix != null) {
			gitClonePath = new File(localWorkspace, gitPrefix);
		}

		GitClient git = Git.with(listener, environment)
				.in(gitClonePath).using(gitExe)
				.getClient();

		if (git.hasGitRepo()) {
			
			PersonIdent person = new PersonIdent(accountName, accountEmail);
			for (String module: modules) {
				git.add(new File(module, "debian/changelog").getCanonicalPath());
			}
			git.setAuthor(person);
			git.setCommitter(person);
			git.commit(commitMessage);
			return true;
		} else {
			return false;
		}
	}

}
