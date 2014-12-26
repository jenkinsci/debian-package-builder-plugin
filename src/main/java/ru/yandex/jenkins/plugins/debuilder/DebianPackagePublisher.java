package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildBadgeAction;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.model.Project;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.ComboBoxModel;
import hudson.util.DescribableList;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang3.text.StrSubstitutor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;

import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;


public class DebianPackagePublisher extends Recorder implements Serializable {
	private static final long serialVersionUID = 1L;
	private static final String PREFIX = "debian-package-publisher";

	private String repoId;
	private String commitMessage;
	private final boolean commitChanges;

	@DataBoundConstructor
	public DebianPackagePublisher(String repoId, String commitMessage, boolean commitChanges) {
		this.commitChanges = commitChanges;
		this.commitMessage = commitMessage;
		this.repoId = repoId;
	}

	private DebianPackageRepo getRepo(AbstractBuild<?, ?> build, Runner runner) throws IOException, InterruptedException {
		String expandedRepo = build.getEnvironment(runner.getListener()).expand(repoId);

		for(DebianPackageRepo repo: getDescriptor().getRepositories()) {
			if (repo.getName().equals(expandedRepo)) {
				return repo;
			}
		}

		throw new IllegalArgumentException(MessageFormat.format("Repo {0} is not found in global configuration", expandedRepo));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static String getUsedCommitMessage(AbstractBuild build) {
		DescribableList<Publisher, Descriptor<Publisher>> publishersList = ((Project)build.getProject()).getPublishersList();
		for (Publisher publisher: publishersList) {
			if (publisher instanceof DebianPackagePublisher) {
				return ((DebianPackagePublisher) publisher).commitMessage;
			}
		}

		return "";
	}

	private FilePath getRemoteKeyPath(AbstractBuild<?, ?> build, Runner runner) throws IOException, InterruptedException {
		String keysDir = "debian-package-builder-keys";

		String relativeKeyPath = new File(keysDir, getRepo(build, runner).getKeypath()).getPath();
		File absoluteKeyPath = new File (Jenkins.getInstance().getRootDir(), relativeKeyPath);
		FilePath localKey = new FilePath(absoluteKeyPath);

		FilePath remoteKey = build.getWorkspace().createTextTempFile("private", "key", localKey.readToString());
		remoteKey.chmod(0600);
		return remoteKey;
	}

	private FilePath[] generateDuploadConf(AbstractBuild<?, ?> build, Runner runner) throws IOException, InterruptedException, DebianizingException {
		String confTemplate =
				"package config;\n\n" +
				"$default_host = '${name}';\n\n" +
				"$cfg{'${name}'} = {\n" +
				"\tlogin => '${login}',\n" +
				"\tfqdn => '${fqdn}',\n" +
				"\tmethod => '${method}',\n" +
				"\tincoming => '${incoming}',\n" +
				"\tdinstall_runs => 0,\n" +
				"\toptions => '${options}',\n" +
				"};\n\n" +
				"1;\n";

		Map<String, String> values = new HashMap<String, String>();

		DebianPackageRepo repo = getRepo(build, runner);
		FilePath keyPath = getRemoteKeyPath(build, runner);

		values.put("name", repo.getName());
		values.put("method", repo.getMethod());
		values.put("fqdn", repo.getFqdn());
		values.put("incoming", repo.getIncoming());
		values.put("login", repo.getLogin());
		values.put("options", MessageFormat.format("-i {0} ", keyPath.getRemote()) + repo.getOptions());

		StrSubstitutor substitutor = new StrSubstitutor(values);
		String conf = substitutor.replace(confTemplate);

		FilePath duploadConf = build.getWorkspace().createTempFile("dupload", "conf");
		duploadConf.touch(System.currentTimeMillis()/1000);
		duploadConf.write(conf, "UTF-8");

		return new FilePath[] { duploadConf, keyPath };
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws IOException {
		PrintStream logger = listener.getLogger();

		if (build.getResult().isWorseThan(Result.SUCCESS)) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, "Build is not success, will not execute debrelease"));
			return true;
		}

		Runner runner = new DebUtils.Runner(build, launcher, listener, PREFIX);

		FilePath[] tempFiles = null;
		try {
			runner.runCommand("sudo apt-get -y install dupload devscripts");
			tempFiles = generateDuploadConf(build, runner);
			String duploadConf = tempFiles[0].getRemote();

			List<String> builtModules = new ArrayList<String>();

			for (BuildBadgeAction action: build.getBadgeActions()) {
				if (action instanceof DebianBadge) {
					builtModules.add(((DebianBadge) action).getModule());
				}
			}

			boolean wereBuilds = false;

			for (String module: DebianPackageBuilder.getRemoteModules(build, runner)) {
				if (! builtModules.contains(new FilePath(build.getWorkspace().getChannel(), module).child("debian").getRemote())) {
					runner.announce("Module in {0} was not built - not releasing", module);
					continue;
				}

				if (!runner.runCommandForResult("cd ''{0}'' && cp ''{1}'' dupload.conf && trap ''rm -f dupload.conf'' EXIT && debrelease -c", module, duploadConf)) {
					throw new DebianizingException("Debrelease failed");
				}

				wereBuilds = true;
			}

			if (wereBuilds && commitChanges) {
				String expandedCommitMessage = getExpandedCommitMessage(build, listener);
				commitChanges(build, runner, expandedCommitMessage);
			}
		} catch (InterruptedException e) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, e.getMessage()));
			build.setResult(Result.UNSTABLE);
		} catch (DebianizingException e) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, e.getMessage()));
			build.setResult(Result.UNSTABLE);
		} finally {
			if (tempFiles != null) {
				for (FilePath tempFile : tempFiles) {
					try {
						tempFile.delete();
					} catch (InterruptedException e) {
						logger.println(MessageFormat.format("[{0}] Error deleting {1}: {2}", PREFIX, tempFile.getRemote(), e.getMessage()));
					}
				}
			}
		}

		return true;
	}

	private String getExpandedCommitMessage(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
		EnvVars env = build.getEnvironment(listener);
		return env.expand(getCommitMessage());
	}

	private void commitChanges(AbstractBuild<?, ?> build, Runner runner, String commitMessage) throws DebianizingException, IOException, InterruptedException {
		SCM scm = build.getProject().getScm();

		if (scm instanceof SubversionSCM) {
			commitToSVN(build, runner, (SubversionSCM)scm, commitMessage);
		} else if (scm instanceof GitSCM) {
			commitToGitAndPush(build, runner, (GitSCM)scm, commitMessage);
		} else {
			throw new DebianizingException("SCM used is not a know one but " + scm.getType());
		}
	}

	private void commitToGitAndPush(final AbstractBuild<?, ?> build, final Runner runner, GitSCM scm, String commitMessage) throws DebianizingException {
		try {
			GitCommitHelper helper = new GitCommitHelper(build, scm, runner, commitMessage, DebianPackageBuilder.getRemoteModules(build, runner));

			if (build.getWorkspace().act(helper)) {
				runner.announce("Successfully commited to git");
			} else {
				throw new DebianizingException("Failed to commit to git");
			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void commitToSVN(final AbstractBuild<?, ?> build, final Runner runner, SubversionSCM svn, String commitMessage) throws DebianizingException {
		hudson.scm.SubversionSCM.DescriptorImpl descriptor = (hudson.scm.SubversionSCM.DescriptorImpl) Jenkins.getInstance().getDescriptor(hudson.scm.SubversionSCM.class);
		ISVNAuthenticationProvider authenticationProvider = descriptor.createAuthenticationProvider(build.getProject());

		try {
			for (String module: DebianPackageBuilder.getRemoteModules(build, runner)) {
				SVNCommitHelper helper = new SVNCommitHelper(authenticationProvider, module, commitMessage);
				runner.announce("Commited revision <{0}> of <{2}> with message <{1}>", runner.getChannel().call(helper), commitMessage, module);
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new DebianizingException("IOException: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			e.printStackTrace();
			throw new DebianizingException("Interrupted: " + e.getMessage(), e);
		}
	}

	@Override
	public boolean needsToRunAfterFinalized() {
		return false;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		private List<DebianPackageRepo> repos = new ArrayList<DebianPackageRepo>();

		public DescriptorImpl() {
			super();
			load();
		}

		public ArrayList<DebianPackageRepo> getRepositories() {
			return new ArrayList<DebianPackageRepo>(repos);
		}

		public ComboBoxModel doFillRepoIdItems() {
			ComboBoxModel model = new ComboBoxModel();

			for (DebianPackageRepo repo: repos) {
				model.add(repo.getName());
			}

			return model;
		}

		public FormValidation doCheckRepoId(@Nonnull @QueryParameter final String repoId) {
			if (repoId.contains("$")) {  // poor man's check that it has a parameter
				return FormValidation.warning("The actual repository will be determined at run-time, take care");
			} else if (
					Collections2.filter(
						getRepositories(),
						new Predicate<DebianPackageRepo>() {
							@Override
							public boolean apply(DebianPackageRepo repo) {
								return repoId.equals(repo.getName());
							}
						}
					).size() == 0) {
				return FormValidation.error("There is no such repository configured");
			} else {
				return FormValidation.ok();
			}
		}

		public FormValidation doCheckMethod(@QueryParameter String method) {
			if (method != "scpb") {
				return FormValidation.error("This method is not supported yet");
			} else {
				return FormValidation.ok();
			}
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			repos = req.bindJSONToList(DebianPackageRepo.class, formData.get("repositories"));
			save();

			return super.configure(req,formData);
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Publish debian packages";
		}
	}

	public boolean isCommitChanges() {
		return commitChanges;
	}

	public String getCommitMessage() {
		return commitMessage;
	}

	public String getRepoId() {
		return repoId;
	}
}
