package ru.yandex.jenkins.plugins.debuilder;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.plugins.git.GitChangeSet;
import hudson.plugins.git.GitSCM;
import hudson.scm.*;
import org.eclipse.jgit.lib.ObjectId;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static hudson.scm.SubversionSCM.ModuleLocation;
import static ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;

public class ChangesExtractor {

	public static List<Change> getChanges(AbstractBuild build, Runner runner, SCM scm, String remoteDebian, String ourMessage, VersionHelper helper) throws DebianizingException, InterruptedException {
		BuildListener listener = runner.getListener();
		if (scm instanceof SubversionSCM) {
			String oldRevision = helper.getRevision();
			helper.setRevision(getSVNRevision(build, runner, (SubversionSCM) scm, remoteDebian));
			if ("".equals(oldRevision)) {
				runner.announce("No last revision known, using changes since last successful build to populate debian/changelog");
				return getChangesSinceLastBuild(build, ourMessage);
			} else {
				runner.announce("Calculating changes since revision {0}.", oldRevision);
				return getChangesFromSubversion(build, runner, (SubversionSCM) scm, remoteDebian, oldRevision, helper.getRevision(), ourMessage);
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
		for (ModuleLocation location: scm.getLocations()) {
			String moduleDir;
			try {
				ModuleLocation expandedLocation = location.getExpandedLocation(build.getEnvironment(runner.getListener()));
				moduleDir = expandedLocation.getLocalDir();

				if (remoteDebian.startsWith(build.getWorkspace().child(moduleDir).getRemote())) {
					return expandedLocation;
				}
			} catch (IOException e) {
				throw new DebianizingException("IOException: " + e.getMessage(), e);
			} catch (InterruptedException e) {
				throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
			}
		}

		throw new DebianizingException("Can't find module location for remoteDebian " + remoteDebian);
	}

	static List<Change> getChangesFromSubversion(@SuppressWarnings("rawtypes") AbstractBuild build, final Runner runner, SubversionSCM scm, final String remoteDebian, String latestRevision, String currentRevision, final String ourMessage) throws DebianizingException {
		final List<Change> result = new ArrayList<Change>();

		SvnClientManager manager = SubversionSCM.createClientManager(build.getProject());
		try {
			ModuleLocation location = findOurLocation(build, scm, runner, remoteDebian);

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
				throw new DebianizingException("SVNException: " + e.getMessage(), e);
			}
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
			GitClient cli = scm.createClient(listener, null, build);
			FilePath workspace = build.getWorkspace();
			return getChangesFromGit(cli, workspace, remoteDebian);
		} catch (IOException e) {
			throw new DebianizingException("IOException: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
		}
	}

	static List<Change> getChangesFromGit(GitClient cli, FilePath workspace, String remoteDebian) throws InterruptedException {
		String changelogPath = remoteDebian + "/changelog";
		LinkedList<Change> changes = new LinkedList<Change>();

		for (ObjectId rev : cli.revListAll()) {
			GitChangeSet changeSet = new GitChangeSet(cli.showRevision(rev), true);
			for (GitChangeSet.Path path : changeSet.getPaths()) {
				String filePath = workspace.child(path.getPath()).getRemote();
				if (filePath.equals(changelogPath)) {
					return changes;
				}
			}
			changes.addFirst(new Change(changeSet.getAuthorName(), changeSet.getMsg()));
		}
		return changes;
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
