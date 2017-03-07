package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.*;
import hudson.model.Cause.UserIdCause;
import hudson.scm.SCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.DescribableList;
import hudson.util.VariableResolver;

import java.io.IOException;
import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jedi.functional.FunctionalPrimitives;
import jedi.functional.Functor;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;

import javax.annotation.Nonnull;

import static ru.yandex.jenkins.plugins.debuilder.ChangesExtractor.Change;

public class DebianPackageBuilder extends Builder implements SimpleBuildStep {
	public static final String DEBIAN_SOURCE_PACKAGE = "DEBIAN_SOURCE_PACKAGE";
	public static final String DEBIAN_PACKAGE_VERSION = "DEBIAN_PACKAGE_VERSION";
	public static final String ABORT_MESSAGE = "[{0}] Aborting: {1} ";
	private static final String PREFIX = "debian-package-builder";

	// location of debian catalog relative to the workspace root
	private final String pathToDebian;
	private final String nextVersion;
	private final boolean generateChangelog;
	private final boolean signPackage;
	private final boolean buildEvenWhenThereAreNoChanges;

	@DataBoundConstructor
	public DebianPackageBuilder(String pathToDebian, String nextVersion, Boolean generateChangelog, Boolean signPackage, Boolean buildEvenWhenThereAreNoChanges) {
		this.pathToDebian = pathToDebian;
		this.nextVersion = nextVersion;
		this.generateChangelog = generateChangelog;
		this.signPackage = signPackage;
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

	@Override
	public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) {
		PrintStream logger = listener.getLogger();

		Runner runner = makeRunner(build, workspace, launcher, listener);

		try {
			String remoteDebian = getRemoteDebian(build, workspace, runner);

			runner.runCommand("sudo apt-get -y update");
			runner.runCommand("sudo apt-get -y install aptitude pbuilder");

			if (signPackage) {
				importKeys(workspace, runner);
			}

			Map<String, String> changelog = parseChangelog(runner, remoteDebian);

			String source = changelog.get("Source");
			String latestVersion = changelog.get("Version");
			String distribution = changelog.get("Distribution");
			runner.announce("Determined latest version to be {0}", latestVersion);

			if (generateChangelog) {
				Pair<VersionHelper, List<Change>> changes = generateChangelog(latestVersion, runner, build, remoteDebian);

				if (isTriggeredAutomatically(build) && changes.getRight().isEmpty() && !buildEvenWhenThereAreNoChanges) {
					runner.announce("There are no creditable changes for this build - not building package.");
				}

				latestVersion = changes.getLeft().toString();
				writeChangelog(build, listener, remoteDebian, runner, changes, distribution);
			}

			runner.runCommand(remoteDebian, new HashMap<String, String>(),"sudo /usr/lib/pbuilder/pbuilder-satisfydepends --control control");
			String package_command = "debuild --check-dirname-level 0 --no-tgz-check ";
			if (signPackage) {
				package_command += String.format("-k%1$s -p''gpg --no-tty --passphrase %2$s''", getDescriptor().getAccountEmail(), getDescriptor().getPassphrase());
			}
			else
			{
				package_command += "-us -uc";
			}
			runner.runCommand(remoteDebian, new HashMap<String, String>(), package_command);

			archiveArtifacts(build, workspace, runner, latestVersion);

			build.addAction(new DebianBadge(latestVersion, remoteDebian));
			build.getEnvironment(listener).put(DEBIAN_SOURCE_PACKAGE, source);
			build.getEnvironment(listener).put(DEBIAN_PACKAGE_VERSION, latestVersion);
		} catch (InterruptedException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
		} catch (DebianizingException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
		} catch (IOException e) {
			logger.println(MessageFormat.format(ABORT_MESSAGE, PREFIX, e.getMessage()));
		}
	}

	@SuppressWarnings("rawtypes") Runner makeRunner(Run build, FilePath workspace, Launcher launcher, TaskListener listener) {
		return new Runner(build, workspace, launcher, listener, PREFIX);
	}

	@SuppressWarnings("rawtypes")
	private void archiveArtifacts(Run build, FilePath workspace, Runner runner, String latestVersion) throws IOException, InterruptedException {
		FilePath path = workspace.child(pathToDebian).child("..");
		String mask = "*" + latestVersion + "*.deb";
		for (FilePath file:path.list(mask)) {
			runner.announce("Archiving file <{0}> as a build artifact", file.getName());
		}
		path.copyRecursiveTo(mask, new FilePath(build.getArtifactsDir()));
	}

	@SuppressWarnings("rawtypes")
	public String getRemoteDebian(Run<?,?> build, FilePath workspace, Runner runner) throws DebianizingException {
		String expanded;
		try {
			expanded = build.getEnvironment(runner.getListener()).expand(pathToDebian);

			if (expanded.endsWith("debian") || expanded.endsWith("debian/")) {
				return workspace.child(expanded).getRemote();
			} else {
				return workspace.child(expanded).child("debian").getRemote();
			}

		} catch (IOException cause) {
			throw new DebianizingException("Failed to get build environment", cause);
		} catch (InterruptedException cause) {
			throw new DebianizingException("Failed to get build environment", cause);
		}
	}

	/**
	 * Parses changelog and updates it with next version and it's changes
	 *
	 * @param latestVersion
	 * @param runner
	 * @param build
	 * @param remoteDebian
	 * @return
	 * @throws DebianizingException
	 * @throws InterruptedException
	 * @throws IOException
	 */
	@SuppressWarnings({ "rawtypes" }) Pair<VersionHelper, List<Change>> generateChangelog(String latestVersion, Runner runner, Run<?, ?> build, String remoteDebian)
			throws DebianizingException, InterruptedException, IOException {
		VersionHelper helper;
		EnvVars env = build.getEnvironment(runner.getListener());
		String nextVersion = env.expand(this.nextVersion);

		if (nextVersion == null || nextVersion.trim().isEmpty()) {
			helper = new VersionHelper(latestVersion);
			runner.announce("Determined latest revision to be {0}", helper.getRevision());
			helper.setMinorVersion(helper.getMinorVersion() + 1);
		} else {
			helper = new VersionHelper(nextVersion);
		}
		SCM scm;
		List<Change> changes;
		if (build.getParent() instanceof WorkflowJob) {
			scm = ((WorkflowJob) build.getParent()).getTypicalSCM();
			changes = new ArrayList<Change>();
		}else{
			scm = ((AbstractProject) build.getParent()).getScm();
			String ourMessage = DebianPackagePublisher.getUsedCommitMessage(build);
			changes = ChangesExtractor.getChanges(build, runner, scm, remoteDebian, ourMessage, helper);
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
	private void writeChangelog(Run build, TaskListener listener, String remoteDebian, Runner runner, Pair<VersionHelper, List<Change>> changes, String distribution)
			throws IOException, InterruptedException, DebianizingException {

		String versionMessage = getCausedMessage(build);

		String newVersionMessage = Util.replaceMacro(versionMessage, new VariableResolver.ByMap<String>(build.getEnvironment(listener)));
		startVersion(runner, remoteDebian, changes.getLeft(), newVersionMessage, distribution);

		for (Change change: changes.getRight()) {
			addChange(runner, remoteDebian, change, distribution);
		}
	}

	@SuppressWarnings("rawtypes")
	private boolean isTriggeredAutomatically (Run build) {
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
	private String getCausedMessage(Run build) {
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

	private String clearMessage(String message) {
		return message.replaceAll("\\'", "");
	}

	private Map<String, String> getDchEnv(String mail, String name){
		Map<String, String> r = new HashMap<String, String>();
		r.put("DEBEMAIL", mail);
		r.put("DEBFULLNAME", name);
		return r;
	}

	private void addChange(Runner runner, String remoteDebian, Change change, String distribution)
			throws IOException, InterruptedException, DebianizingException {
		runner.announce("Got changeset entry: {0} by {1}", clearMessage(change.getMessage()), change.getAuthor());
		runner.runCommand(remoteDebian, getDchEnv(getDescriptor().getAccountEmail(), getDescriptor().getAccountName()), "dch --check-dirname-level 0 --distribution ''{0}'' --append -- ''{1}''", clearMessage(change.getMessage()), distribution);
	}

	private void startVersion(Runner runner, String remoteDebian, VersionHelper helper, String message, String distribution)
			throws IOException, InterruptedException, DebianizingException {
		runner.announce("Starting version <{0}> with message <{1}>", helper, clearMessage(message));
		runner.runCommand(remoteDebian, getDchEnv(getDescriptor().getAccountEmail(), getDescriptor().getAccountName()), "dch --check-dirname-level 0 -b --distribution ''{2}'' --newVersion {0} -- ''{1}''", helper, clearMessage(message), distribution);
	}

	/**
	 * FIXME Doesn't work with multi-line entries
	 */
	Map<String, String> parseChangelog(Runner runner, String remoteDebian) throws DebianizingException {
		String changelogOutput = runner.runCommandForOutput("cd \"{0}\" && dpkg-parsechangelog -lchangelog", remoteDebian);
		Map<String, String> changelog = new HashMap<String, String>();
		Pattern changelogFormat = Pattern.compile("(\\w+):\\s*(.*)");

		for(String row: changelogOutput.split("\n")) {
			Matcher matcher = changelogFormat.matcher(row.trim());
			if (matcher.matches()) {
				 changelog.put(matcher.group(1), matcher.group(2));
			}
		}

		return changelog;
	}

	private void importKeys(FilePath workspace, Runner runner)
			throws InterruptedException, DebianizingException, IOException {
		if (!runner.runCommandForResult("gpg --list-key {0}", getDescriptor().getAccountEmail())) {
			FilePath publicKey = workspace.createTextTempFile("public", "key", getDescriptor().getPublicKey());
			runner.runCommand("gpg --import ''{0}''", publicKey.getRemote());
			publicKey.delete();
		}

		if (!runner.runCommandForResult("gpg --list-secret-key {0}", getDescriptor().getAccountEmail())) {
			FilePath privateKey = workspace.createTextTempFile("private", "key", getDescriptor().getPrivateKey());
			runner.runCommand("gpg --import ''{0}''", privateKey.getRemote());
			privateKey.delete();
		}
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
		private String accountEmail;
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
			setAccountName("Jenkins");
			setAccountEmail(json.getString("accountEmail"));
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

		public String getAccountEmail() {
			return accountEmail;
		}

		public void setAccountEmail(String accountEmail) {
			this.accountEmail = accountEmail;
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
	 * @param runner
	 * @return all the paths to remote module roots declared in given build by {@link DebianPackageBuilder}s
	 * @throws DebianizingException
	 */
	public static Collection<String> getRemoteModules(Run<?, ?> build, FilePath workspace, Runner runner) throws DebianizingException {
		ArrayList<String> result = new ArrayList<String>();

		for (DebianPackageBuilder builder: getDPBuilders(build)) {
			result.add(new FilePath(workspace.getChannel(), builder.getRemoteDebian(build, workspace, runner)).child("..").getRemote());
		}

		return result;
	}

	/**
	 * @param build
	 * @return all the {@link DebianPackageBuilder}s participating in this build
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static Collection<DebianPackageBuilder> getDPBuilders(Run<?, ?> build) {
		ArrayList<DebianPackageBuilder> result = new ArrayList<DebianPackageBuilder>();

		if (build.getParent() instanceof Project) {
			DescribableList<Builder, Descriptor<Builder>> builders = ((Project)build.getParent()).getBuildersList();
			for (Builder builder: builders) {
				if (builder instanceof DebianPackageBuilder) {
					result.add((DebianPackageBuilder) builder);
				}
			}
		}

		return result;
	}

}
