package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
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
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
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

	private DebianPackageRepo getRepo(Run<?, ?> run, Runner runner) throws IOException, InterruptedException {
		String expandedRepo = run.getEnvironment(runner.getListener()).expand(repoId);

		for(DebianPackageRepo repo: getDescriptor().getRepositories()) {
			if (repo.getName().equals(expandedRepo)) {
				return repo;
			}
		}

		throw new IllegalArgumentException(MessageFormat.format("Repo {0} is not found in global configuration", expandedRepo));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static String getUsedCommitMessage(Run build) {
		((Project)build.getParent()).getPublishersList();
		DescribableList<Publisher, Descriptor<Publisher>> publishersList = ((Project)build.getParent()).getPublishersList();
		for (Publisher publisher: publishersList) {
			if (publisher instanceof DebianPackagePublisher) {
				return ((DebianPackagePublisher) publisher).commitMessage;
			}
		}

		return "";
	}

	private FilePath getRemoteKeyPath(Run<?, ?> run, FilePath workspace,  Runner runner) throws IOException, InterruptedException {
		String keysDir = "debian-package-builder-keys";

		String relativeKeyPath = new File(keysDir, getRepo(run, runner).getKeypath()).getPath();
		File absoluteKeyPath = new File (Jenkins.getInstance().getRootDir(), relativeKeyPath);
		FilePath localKey = new FilePath(absoluteKeyPath);

		FilePath remoteKey = workspace.createTextTempFile("private", "key", localKey.readToString());
		remoteKey.chmod(0600);
		return remoteKey;
	}

	private FilePath[] generateDuploadConf(Run<?, ?> build, FilePath workspace, Runner runner) throws IOException, InterruptedException, DebianizingException {
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
		FilePath keyPath = getRemoteKeyPath(build, workspace, runner);

		values.put("name", repo.getName());
		values.put("method", repo.getMethod());
		values.put("fqdn", repo.getFqdn());
		values.put("incoming", repo.getIncoming());
		values.put("login", repo.getLogin());
		values.put("options", MessageFormat.format("-i {0} ", keyPath.getRemote()) + repo.getOptions());

		StrSubstitutor substitutor = new StrSubstitutor(values);
		String conf = substitutor.replace(confTemplate);

		FilePath duploadConf = workspace.createTempFile("dupload", "conf");
		duploadConf.touch(System.currentTimeMillis()/1000);
		duploadConf.write(conf, "UTF-8");

		return new FilePath[] { duploadConf, keyPath };
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	public boolean perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws IOException {
		PrintStream logger = listener.getLogger();

		if (run.getResult() != null && run.getResult().isWorseThan(Result.SUCCESS)) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, "Build is not success, will not execute debrelease"));
			return true;
		}

		Runner runner = new DebUtils.Runner(run, workspace, launcher, listener, PREFIX);

		try {
			List<String> builtModules = getBuilds(run, workspace, runner);
			doDebrelease(builtModules, launcher, run, workspace, listener);

		} catch (InterruptedException e) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, e.getMessage()));
			run.setResult(Result.UNSTABLE);
		} catch (DebianizingException e) {
			logger.println(MessageFormat.format(DebianPackageBuilder.ABORT_MESSAGE, PREFIX, e.getMessage()));
			run.setResult(Result.UNSTABLE);
		}
		return true;
	}

	public void doDebrelease(List<String> builtModules, @Nonnull Launcher launcher, @Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull TaskListener listener) throws IOException, InterruptedException, DebianizingException {
		DebUtils.Runner runner = new DebUtils.Runner(run, workspace, launcher, listener, PREFIX);
		PrintStream logger = listener.getLogger();
		FilePath[] tempFiles = null;
		try {
			runner.runCommand("sudo apt-get -y install dupload devscripts");

			tempFiles = generateDuploadConf(run, workspace, runner);
			String duploadConf = tempFiles[0].getRemote();
			String command = MessageFormat.format("bash -c \"cp ''{0}'' dupload.conf && trap ''rm -f dupload.conf'' EXIT && debrelease -c\"", duploadConf);

			boolean wereBuilds = false;

			for (String module : builtModules) {
				String path = workspace + "/" + module;
				if (!runner.runCommandForResult(command, path, new HashMap<String, String>())) {
					throw new DebianizingException("Debrelease failed");
				}
				wereBuilds = true;
			}
			if (wereBuilds && commitChanges) {
				String expandedCommitMessage = getExpandedCommitMessage(run, listener);
				commitChanges(run, workspace, runner, expandedCommitMessage);
			}

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

		};
	}

	private List<String> getBuilds(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Runner runner) throws DebianizingException{
		List<String> modules = new ArrayList<String>();
		List<String> builtModules = new ArrayList<String>();
		for (BuildBadgeAction action: run.getBadgeActions()) {
            if (action instanceof DebianBadge) {
                modules.add(((DebianBadge) action).getModule());
            }
        }

		for (String module: DebianPackageBuilder.getRemoteModules(run, workspace, runner)) {
			if (!modules.contains(new FilePath(workspace.getChannel(), module).child("debian").getRemote())) {
				runner.announce("Module in {0} was not built - not releasing", module);
			}else{
				builtModules.add(module);
			}
		}

		return builtModules;
	}

	private String getExpandedCommitMessage(Run<?, ?> run, TaskListener listener) throws IOException, InterruptedException {
		EnvVars env = run.getEnvironment(listener);
		return env.expand(getCommitMessage());
	}

	private void commitChanges(Run<?, ?> run, FilePath workspace, Runner runner, String commitMessage) throws DebianizingException, IOException, InterruptedException {
		SCM scm;
		if (run.getParent() instanceof WorkflowJob) {
			scm = ((WorkflowJob) run.getParent()).getTypicalSCM();
		}else{
			scm = ((AbstractProject) run.getParent()).getScm();
		}

		if (scm instanceof SubversionSCM) {
			commitToSVN(run, workspace, runner, (SubversionSCM)scm, commitMessage);
		} else if (scm instanceof GitSCM) {
			commitToGitAndPush(run, workspace, runner, (GitSCM)scm, commitMessage);
		} else {
			throw new DebianizingException("SCM used is not a know one but " + scm.getType());
		}
	}

	private void commitToGitAndPush(final Run<?, ?> run, final FilePath workspace, final Runner runner, GitSCM scm, String commitMessage) throws DebianizingException {
		try {
			GitCommitHelper helper = new GitCommitHelper(run, scm, runner, commitMessage, DebianPackageBuilder.getRemoteModules(run, workspace, runner));

			if (workspace.act(helper)) {
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

	private void commitToSVN(final Run<?, ?> run, final FilePath workspace, final Runner runner, SubversionSCM svn, String commitMessage) throws DebianizingException {
		try {
			for (String module: DebianPackageBuilder.getRemoteModules(run, workspace, runner)) {
				ISVNAuthenticationProvider authenticationProvider = svn.createAuthenticationProvider(run.getParent(),
					ChangesExtractor.findOurLocation(run, workspace, svn, runner, module));

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
