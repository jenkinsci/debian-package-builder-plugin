package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.extensions.impl.RelativeTargetDirectory;
import hudson.scm.*;
import jenkins.model.Jenkins;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hudson.scm.SubversionSCM.ModuleLocation;
import static ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;
import static ru.yandex.jenkins.plugins.debuilder.DebianPackageBuilder.DescriptorImpl;

public class ChangesExtractor {

	public static List<Change> getChanges(AbstractBuild build, Runner runner, SCM scm, String remoteDebian, String ourMessage, VersionHelper helper) throws DebianizingException, InterruptedException {
		BuildListener listener = runner.getListener();
		if (scm instanceof SubversionSCM) {
			String oldUpstreamVersion = helper.getUpstreamVersion();
			helper.setUpstreamVersion(getSVNRevision(build, runner, (SubversionSCM) scm, remoteDebian));
			if ("".equals(oldUpstreamVersion)) {
				runner.announce("No last revision known, using changes since last successful build to populate debian/changelog");
				return getChangesSinceLastBuild(build, ourMessage);
			} else {
				runner.announce("Calculating changes since revision {0}.", oldUpstreamVersion);
				return getChangesFromSubversion(build, runner, (SubversionSCM) scm, remoteDebian, oldUpstreamVersion, helper.getUpstreamVersion(), ourMessage);
			}
		} else if (scm instanceof GitSCM) {
			runner.announce("Calculating changes from git log");
			return getChangesFromGit(build, listener, (GitSCM) scm, remoteDebian);
		} else {
			runner.announce("SCM in use is not Subversion nor Git (but <{0}> instead), defaulting to changes since last build", scm.getClass().getName());
			return getChangesSinceLastBuild(build, ourMessage);
		}
	}

	static String getSVNRevision(@SuppressWarnings("rawtypes") AbstractBuild build, Runner runner, SubversionSCM scm, String remoteDebian) throws DebianizingException {
		ModuleLocation location = findOurLocation(build, scm, runner, remoteDebian);
		try {
			Map<String, Long> revisionsForBuild = SubversionHack.getRevisionsForBuild(scm, build);

			return Long.toString(revisionsForBuild.get(location.getSVNURL().toString()));
		} catch (IOException e) {
			throw new DebianizingException("IOException: " + e.getMessage(), e);
		} catch (SVNException e) {
			throw new DebianizingException("SVNException: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
		} catch (IllegalArgumentException e) {
			throw new DebianizingException("IllegalArgumentException: " + e.getMessage(), e);
		} catch (IllegalAccessException e) {
			throw new DebianizingException("IllegalAccessException: " + e.getMessage(), e);
		}
	}

	static ModuleLocation findOurLocation(@SuppressWarnings("rawtypes") AbstractBuild build, SubversionSCM scm, Runner runner, String remoteDebian) throws DebianizingException {
		EnvVars environment;
		try {
			environment = build.getEnvironment(runner.getListener());
		} catch (IOException e) {
			throw new DebianizingException("IOException: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
		}

		for (ModuleLocation location: scm.getLocations(environment, build)) {
			if (remoteDebian.startsWith(build.getWorkspace().child(location.getLocalDir()).getRemote())) {
				return location;
			}
		}

		throw new DebianizingException("Can't find module location for remoteDebian " + remoteDebian);
	}

	static List<Change> getChangesFromSubversion(@SuppressWarnings("rawtypes") AbstractBuild build, final Runner runner, SubversionSCM scm, final String remoteDebian, String latestRevision, String currentRevision, final String ourMessage) throws DebianizingException {
		final List<Change> result = new ArrayList<Change>();
		
		ModuleLocation location = findOurLocation(build, scm, runner, remoteDebian);

		ISVNAuthenticationProvider authenticationProvider = ((SubversionSCM)build.getProject().getScm()).createAuthenticationProvider(build.getProject(), location);
		
		SvnClientManager manager = SubversionSCM.createClientManager(authenticationProvider);

		try {
			SVNURL svnurl = location.getSVNURL();
			
			manager.getLogClient().doLog(svnurl, null, SVNRevision.UNDEFINED, SVNRevision.create(Long.parseLong(latestRevision) + 1), SVNRevision.parse(currentRevision), false, true, 0, new ISVNLogEntryHandler() {

				@Override
				public void handleLogEntry(SVNLogEntry logEntry) throws SVNException {
					if (!logEntry.getMessage().equals(ourMessage)) {
						result.add(new Change(logEntry.getAuthor(), logEntry.getMessage()));
					}
				}
			});
		} catch (SVNException e) {
			e.printStackTrace();
			throw new DebianizingException("SVNException: " + e.getMessage(), e);
		} finally {
			manager.dispose();
		}

		return result;
	}

	/**
	 * Extract all commits from git log since last debian/changelog change
	 * @param build
	 * @param listener
	 * @param scm
	 * @param remoteDebian
	 * @return
	 * @throws DebianizingException
	 */
	static List<Change> getChangesFromGit(AbstractBuild build, BuildListener listener, GitSCM scm, String remoteDebian) throws DebianizingException {
		try {
			EnvVars environment = build.getEnvironment(listener);
			FilePath workspace = build.getWorkspace();
//			method signature changed in latest Git plugin, @since 2.3.4
			GitClient cli = scm.createClient(listener, environment, build, workspace);

			String relativeTargetDirectory = "";
			if(scm.getExtensions().get(RelativeTargetDirectory.class) != null) {
				relativeTargetDirectory = scm.getExtensions().get(RelativeTargetDirectory.class).getRelativeTargetDir();
			}

			DescriptorImpl descriptor = (DescriptorImpl) Jenkins.getInstance().getDescriptor(DebianPackageBuilder.class);
			PersonIdent account = new PersonIdent(descriptor.getAccountName(), descriptor.getAccountEmail());
			return getChangesFromGit(cli, workspace, relativeTargetDirectory, remoteDebian, account);
		} catch (IOException e) {
			throw new DebianizingException("IOException: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
		}
	}

	static List<Change> getChangesFromGit(GitClient cli, FilePath workspace, String relativeTargetDirectory, String remoteDebian, PersonIdent account) throws InterruptedException {
		String changelogPath = remoteDebian + "/changelog";
		LinkedList<Change> changesSinceLastChangelogModification = new LinkedList<Change>();
		LinkedList<Change> changesSinceLastChangelogModificationByPlugin = new LinkedList<Change>();
		boolean firstChangelogModificationFound = false;

		for (ObjectId rev : cli.revListAll()) {
			List<String> lines = cli.showRevision(rev);
			GitChangeSet changeSet = new GitChangeSet(lines, true);
			String email = getAuthorEmailFromGitRevision(lines);
			Change change = new Change(changeSet.getAuthorName(), changeSet.getMsg());

			for (GitChangeSet.Path path : changeSet.getPaths()) {
				String filePath = workspace.child(relativeTargetDirectory).child(path.getPath()).getRemote();
				if (filePath.equals(changelogPath)) {
					if (changeSet.getAuthorName().equals(account.getName())
						& email.equals(account.getEmailAddress())) {
						return changesSinceLastChangelogModificationByPlugin;
					} else {
						firstChangelogModificationFound = true;
					}
				}
			}
			if (!firstChangelogModificationFound) {
				changesSinceLastChangelogModification.addFirst(change);
			}
			changesSinceLastChangelogModificationByPlugin.addFirst(change);
		}
		return changesSinceLastChangelogModification;
	}

	/*
		This is temporary solution. GitChangeSet doesn't provide any method to extract email of commit author now.
	 */
	static String getAuthorEmailFromGitRevision(List<String> lines) {
		Pattern pattern = Pattern.compile("^author [^<]*<(.*)> .*$");

		for (String line: lines) {
			Matcher matcher = pattern.matcher(line);
			if (matcher.matches()) {
				return matcher.group(1);
			}
		}

		return "";
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static List<Change> getChangesSinceLastBuild(AbstractBuild build, String ourMessage) throws InterruptedException, DebianizingException {
		List<Change> result = new ArrayList<Change>();
		Run lastSuccessfulBuild = build.getProject().getLastSuccessfulBuild();

		int lastSuccessNumber = lastSuccessfulBuild == null ? 0 : lastSuccessfulBuild.number;

		for (int num = lastSuccessNumber + 1; num <= build.number; num ++) {
			AbstractBuild run = build.getProject().getBuildByNumber(num);

			if (run == null) {
				continue;
			}

			ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = run.getChangeSet();

			for (ChangeLogSet.Entry entry : changeSet) {
				if (!entry.getMsg().equals(ourMessage)) {
					result.add(new Change(entry.getAuthor().getFullName(), entry.getMsg()));
				}
			}
		}

		return result;
	}

	/**
	 * Pojo to store change
	 *
	 * @author pupssman
	 */
	public static final class Change {
		private final String author;
		private final String message;

		public Change(String author, String message) {
			this.author = author;
			this.message = message;}

		public String getAuthor() {
			return author;
		}
		public String getMessage() {
			return message;
		}

		@Override
		public String toString() {
			return author + ": " + message;
		}

		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Change)) {
				return false;
			}
			Change that = (Change) obj;
			return this.author.equals(that.author) && this.message.equals(that.message);
		}
	}
}
