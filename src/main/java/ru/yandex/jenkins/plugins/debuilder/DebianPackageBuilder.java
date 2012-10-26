package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.Cause.UserIdCause;
import hudson.model.Descriptor;
import hudson.model.Project;
import hudson.model.Run;
import hudson.scm.SubversionHack;
import hudson.scm.SvnClientManager;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import hudson.scm.SubversionSCM.ModuleLocation;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.VariableResolver;

import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jedi.functional.FunctionalPrimitives;
import jedi.functional.Functor;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.tmatesoft.svn.core.ISVNLogEntryHandler;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLogEntry;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNRevision;

import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;


public class DebianPackageBuilder extends Builder {
	public static final String DEBIAN_PACKAGE_VERSION = "DEBIAN_PACKAGE_VERSION";
	public static final String ABORT_MESSAGE = "[{0}] Aborting: {1} ";
	private static final String PREFIX = "debian-package-builder";

	// location of debian catalog relative to the workspace root
	private final String pathToDebian;
	private final boolean generateChangelog;
	private final boolean buildEvenWhenThereAreNoChanges;

	@DataBoundConstructor
	public DebianPackageBuilder(String pathToDebian, Boolean generateChangelog, Boolean buildEvenWhenThereAreNoChanges) {
		this.pathToDebian = pathToDebian;
		this.generateChangelog = generateChangelog;
		this.buildEvenWhenThereAreNoChanges = buildEvenWhenThereAreNoChanges;
	}


	@Override
	public boolean perform(@SuppressWarnings("rawtypes") AbstractBuild build, Launcher launcher, BuildListener listener) {
		PrintStream logger = listener.getLogger();

		FilePath workspace = build.getWorkspace();
		String remoteDebian = getRemoteDebian(workspace);

		Runner runner = new DebUtils.Runner(build, launcher, listener, PREFIX);

		try {
			runner.runCommand("sudo apt-get update");
			runner.runCommand("sudo apt-get install aptitude pbuilder");

			importKeys(workspace, runner);

			String latestVersion = determineVersion(runner, remoteDebian);

			if (generateChangelog) {
				Pair<VersionHelper, List<Change>> changes = generateChangelog(latestVersion, runner, build, launcher, listener, remoteDebian);

				if (isTriggeredAutomatically(build) && changes.getRight().isEmpty() && !buildEvenWhenThereAreNoChanges) {
					runner.announce("There are no creditable changes for this build - not building package.");
					return true;
				}

				latestVersion = changes.getLeft().toString();
				writeChangelog(build, listener, remoteDebian, runner, changes);
			}

			runner.runCommand("cd ''{0}'' && sudo /usr/lib/pbuilder/pbuilder-satisfydepends --control control", remoteDebian);
			runner.runCommand("cd ''{0}'' && debuild --check-dirname-level 0 -k{1} -p''gpg --no-tty --passphrase {2}''", remoteDebian, getDescriptor().getAccountName(), getDescriptor().getPassphrase());

			archiveArtifacts(build, runner, latestVersion);

			build.addAction(new DebianBadge(latestVersion, pathToDebian));
			build.getEnvironments().add(Environment.create(new EnvVars(DEBIAN_PACKAGE_VERSION, latestVersion)));
		} catch (InterruptedException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		} catch (DebianizingException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		} catch (IOException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
			return false;
		}

		return true;
	}

	@SuppressWarnings("rawtypes")
	private void archiveArtifacts(AbstractBuild build, Runner runner, String latestVersion) throws IOException, InterruptedException {
		FilePath path = build.getWorkspace().child(pathToDebian).child("..");
		String mask = "*" + latestVersion + "*.deb";
		for (FilePath file:path.list(mask)) {
			runner.announce("Archiving file <{0}> as a build artifact", file.getName());
		}
		path.copyRecursiveTo(mask, new FilePath(build.getArtifactsDir()));
	}

	private String getRemoteDebian(FilePath workspace) {
		if (pathToDebian.endsWith("debian") || pathToDebian.endsWith("debian/")) {
			return workspace.child(pathToDebian).getRemote();
		} else {
			return workspace.child(pathToDebian).child("debian").getRemote();
		}
	}

	/**
	 * Parses changelog and updates it with next version and it's changes
	 *
	 * @param latestVersion
	 * @param runner
	 * @param build
	 * @param listener
	 * @param remoteDebian
	 * @param launcher
	 * @return
	 * @throws DebianizingException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	@SuppressWarnings({ "rawtypes" })
	private Pair<VersionHelper, List<Change>> generateChangelog(String latestVersion, Runner runner, AbstractBuild build, Launcher launcher, BuildListener listener, String remoteDebian) throws DebianizingException, InterruptedException, IOException {
		VersionHelper helper = new VersionHelper(latestVersion);

		runner.announce("Determined latest revision to be {0}", helper.getRevision());

		SCM scm = build.getProject().getScm();

		helper.setMinorVersion(helper.getMinorVersion() + 1);
		String oldRevision = helper.getRevision();

		List<Change> changes;

		if (! (scm instanceof SubversionSCM)) {
			runner.announce("SCM in use is not Subversion (but <{0}> instead), defaulting to changes since last build", scm.getClass().getName());
			changes = getChangesSinceLastBuild(runner, build);
		} else {
			helper.setRevision(getSVNRevision(build, (SubversionSCM) scm, runner, remoteDebian));
			if ("".equals(oldRevision)) {
				runner.announce("No last revision known, using changes since last successful build to populate debian/changelog");
				changes = getChangesSinceLastBuild(runner, build);
			} else {
				runner.announce("Calculating changes since revision {0}.", oldRevision);
				String ourMessage = DebianPackagePublisher.getUsedCommitMessage(build);
				changes = getChangesFromSubversion(runner, (SubversionSCM) scm, build, remoteDebian, oldRevision, helper.getRevision(), ourMessage);
			}
		}

		return new ImmutablePair<VersionHelper, List<Change>>(helper, changes);
	}

	/**
	 * Writes down changelog contained in <b>changes</b>
	 *
	 * @param build
	 * @param listener
	 * @param remoteDebian
	 * @param runner
	 * @param changes
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws DebianizingException
	 */
	@SuppressWarnings("rawtypes")
	private void writeChangelog(AbstractBuild build, BuildListener listener, String remoteDebian, Runner runner, Pair<VersionHelper, List<Change>> changes) throws IOException,
			InterruptedException, DebianizingException {

		String versionMessage = getCausedMessage(build);

		String newVersionMessage = Util.replaceMacro(versionMessage, new VariableResolver.ByMap<String>(build.getEnvironment(listener)));
		startVersion(runner, remoteDebian, changes.getLeft(), newVersionMessage);

		for (Change change: changes.getRight()) {
			addChange(runner, remoteDebian, change);
		}
	}

	@SuppressWarnings("rawtypes")
	private boolean isTriggeredAutomatically (AbstractBuild build) {
		for (Object cause: build.getCauses()) {
			if (cause instanceof UserIdCause) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Returns message based on causes of the build
	 * @param build
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	private String getCausedMessage(AbstractBuild build) {
		String firstPart = "Build #${BUILD_NUMBER}. ";

		@SuppressWarnings("unchecked")
		List<Cause> causes = build.getCauses();

		List<String> causeMessages = FunctionalPrimitives.map(causes, new Functor<Cause, String>() {

			@Override
			public String execute(Cause value) {
				return value.getShortDescription();
			}
		});

		Set<String> uniqueCauses = new HashSet<String>(causeMessages);

		return firstPart + FunctionalPrimitives.join(uniqueCauses, ". ") + ".";

	}

	private String getSVNRevision(@SuppressWarnings("rawtypes") AbstractBuild build, SubversionSCM scm, Runner runner, String remoteDebian) throws DebianizingException {
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

	private ModuleLocation findOurLocation(@SuppressWarnings("rawtypes") AbstractBuild build, SubversionSCM scm, Runner runner, String remoteDebian) throws DebianizingException {
		for (ModuleLocation location: scm.getLocations()) {
			String moduleDir;
			try {
				moduleDir = location.getExpandedLocation(build.getEnvironment(runner.getListener())).getLocalDir();
			} catch (IOException e) {
				throw new DebianizingException("IOException: " + e.getMessage(), e);
			} catch (InterruptedException e) {
				throw new DebianizingException("InterruptedException: " + e.getMessage(), e);
			}
			if (remoteDebian.startsWith(build.getWorkspace().child(moduleDir).getRemote())) {
				return location;
			}
		}

		throw new DebianizingException("Can't find module location for remoteDebian " + remoteDebian);
	}

	private List<Change> getChangesFromSubversion(final Runner runner, SubversionSCM scm, @SuppressWarnings("rawtypes") AbstractBuild build, final String remoteDebian, String latestRevision, String currentRevision, final String ourMessage) throws DebianizingException {
		final List<Change> result = new ArrayList<DebianPackageBuilder.Change>();

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

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private List<Change> getChangesSinceLastBuild(Runner runner, AbstractBuild build) throws InterruptedException, DebianizingException {
		List<Change> result = new ArrayList<DebianPackageBuilder.Change>();
		Run lastSuccessfulBuild = build.getProject().getLastSuccessfulBuild();

		int lastSuccessNumber = lastSuccessfulBuild == null ? 0 : lastSuccessfulBuild.number;

		for (int num = lastSuccessNumber + 1; num < build.number; num ++) {
			AbstractBuild run = (AbstractBuild) build.getProject().getBuildByNumber(num);

			if (run == null) {
				continue;
			}

			ChangeLogSet<? extends Entry> changeSet = run.getChangeSet();

			for (Entry entry : changeSet) {
				result.add(new Change(entry.getAuthor().getFullName(), entry.getMsg()));
			}
		}

		return result;
	}

	/**
	 * Pojo to store change
	 *
	 * @author pupssman
	 */
	private static final class Change {
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
	}

	private String clearMessage(String message) {
		return message.replaceAll("\\'", "");
	}

	private void addChange(Runner runner, String remoteDebian, Change change) throws InterruptedException, DebianizingException {
		runner.announce("Got changeset entry: {0} by {1}", clearMessage(change.getMessage()), change.getAuthor());
		runner.runCommand("export DEBEMAIL={0} && export DEBFULLNAME={1} && cd {2} && dch --check-dirname-level 0 --distributor debian --append ''{3}''", getDescriptor().getAccountName(), change.getAuthor(), remoteDebian, clearMessage(change.getMessage()));
	}

	private void startVersion(Runner runner, String remoteDebian, VersionHelper helper, String message) throws InterruptedException, DebianizingException {
		runner.announce("Starting version <{0}> with message <{1}>", helper, clearMessage(message));
		runner.runCommand("export DEBEMAIL={0} && export DEBFULLNAME={1} && cd {2} && dch --check-dirname-level 0 -b --distributor debian --newVersion {3} ''{4}''", getDescriptor().getAccountName(), "Jenkins", remoteDebian, helper, clearMessage(message));
	}

	private String determineVersion(Runner runner, String remoteDebian) throws DebianizingException {
		String changelogOutput = runner.runCommandForOutput("cd ''{0}'' && dpkg-parsechangelog -lchangelog", remoteDebian);

		String latestVersion = "";

		for(String row: changelogOutput.split("\n")) {
			if (row.startsWith("Version:")) {
				latestVersion = row.split(":")[1].trim();
			}
		}

		runner.announce("Determined latest version to be {0}", latestVersion);
		return latestVersion;
	}

	private void importKeys(FilePath workspace, Runner runner)
			throws InterruptedException, DebianizingException, IOException {
		if (!runner.runCommandForResult("gpg --list-key {0}", getDescriptor().getAccountName())) {
			FilePath publicKey = workspace.createTextTempFile("public", "key", getDescriptor().getPublicKey());
			runner.runCommand("gpg --import ''{0}''", publicKey.getRemote());
			publicKey.delete();
		}

		if (!runner.runCommandForResult("gpg --list-secret-key {0}", getDescriptor().getAccountName())) {
			FilePath privateKey = workspace.createTextTempFile("private", "key", getDescriptor().getPrivateKey());
			runner.runCommand("gpg --import ''{0}''", privateKey.getRemote());
			privateKey.delete();
		}
	}

	public boolean isGenerateChangelog() {
		return generateChangelog;
	}

	public String getPathToDebian() {
		return pathToDebian;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		private String publicKey;
		private String privateKey;
		private String accountName;
		private String passphrase;

		public DescriptorImpl() {
			load();
		}

		@Override
		public String getDisplayName() {
			return "Build debian package";
		}

		public String getPublicKey() {
			return publicKey;
		}

		@Override
		public boolean isApplicable(@SuppressWarnings("rawtypes") Class type) {
			return true;
		}

		@Override
		public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException {
			setPrivateKey(json.getString("privateKey"));
			setPublicKey(json.getString("publicKey"));
			setAccountName(json.getString("accountName"));
			setPassphrase(json.getString("passphrase"));

			save();
			return true; // indicate that everything is good so far
		}

		public String getPrivateKey() {
			return privateKey;
		}

		public void setPrivateKey(String privateKey) {
			this.privateKey = privateKey;
		}

		public void setPublicKey(String publicKey) {
			this.publicKey = publicKey;
		}

		public String getAccountName() {
			return accountName;
		}

		public void setAccountName(String accountName) {
			this.accountName = accountName;
		}

		public String getPassphrase() {
			return passphrase;
		}

		public void setPassphrase(String passphrase) {
			this.passphrase = passphrase;
		}

	}

	/**
	 * @param build
	 * @return all the module locations declared in given build by {@link DebianPackageBuilder}s or "." if there are none
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Collection<String> getAllModules(AbstractBuild<?, ?> build) {
		ArrayList<String> result = new ArrayList<String>();

		if (build.getProject() instanceof Project) {
			DescribableList<Builder, Descriptor<Builder>> builders = ((Project)build.getProject()).getBuildersList();
			for (Builder builder: builders) {
				if (builder instanceof DebianPackageBuilder) {
					result.add(((DebianPackageBuilder) builder).pathToDebian);
				}
			}
		} else {
			result.add(".");
		}
		return result;
	}

	public boolean isBuildEvenWhenThereAreNoChanges() {
		return buildEvenWhenThereAreNoChanges;
	}

}
