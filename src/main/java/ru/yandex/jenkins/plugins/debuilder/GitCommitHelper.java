package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.FilePath.FileCallable;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.plugins.git.GitSCM;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;

import jenkins.model.Jenkins;

import org.eclipse.jgit.lib.PersonIdent;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;
import ru.yandex.jenkins.plugins.debuilder.DebianPackageBuilder.DescriptorImpl;

/**
 * Performs git commiting actions in a remote WS, namely, commiting changelog to the current branch
 * Note to future self: all the fields should be serializable
 * 
 * @author pupssman
 *
 */
public class GitCommitHelper implements FileCallable<Boolean>{

	private static final long serialVersionUID = 1L;
	private final EnvVars environment;
	private final TaskListener listener;
	private final String gitExe;
	private final String accountName;
	private final String commitMessage;

	public GitCommitHelper(AbstractBuild<?, ?> build, GitSCM scm, Runner runner, String commitMessage) throws IOException, InterruptedException {
		this.commitMessage = commitMessage;
		this.environment = build.getEnvironment(runner.getListener());
		this.listener = runner.getListener();
		this.gitExe = scm.getGitExe(build.getBuiltOn(), listener);
		this.accountName = ((DescriptorImpl) Jenkins.getInstance().getDescriptor(DebianPackageBuilder.class)).getAccountName();
	}

	@Override
	public Boolean invoke(File localWorkspace, VirtualChannel channel) throws IOException,
			InterruptedException {
		GitClient git = Git.with(listener, environment)
				.in(localWorkspace).using(gitExe)
				.getClient();

		if (git.hasGitRepo()) {
			
			PersonIdent person = new PersonIdent("Jenkins", accountName);

			git.add("debian/changelog");
			git.commit(commitMessage, person, person);
			
			return true;
		} else {
			return false;
		}
	}

}
