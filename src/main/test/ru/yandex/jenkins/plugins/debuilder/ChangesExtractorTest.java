package ru.yandex.jenkins.plugins.debuilder;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import org.eclipse.jgit.lib.PersonIdent;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static ru.yandex.jenkins.plugins.debuilder.ChangesExtractor.Change;

/**
 * @author: tIGO
 *
 * Some tests are ignored because current implementation of JGitAPIImpl
 * can't parse first commit (https://issues.jenkins-ci.org/browse/JENKINS-22343)
 */
public class ChangesExtractorTest {
	public final PersonIdent alice = new PersonIdent("Alice", "alice@alice.com");
	public final PersonIdent jenkins = new PersonIdent("Jenkins", "jenkins@ci.com");

	private FilePath gitDirPath;
	private GitClient git;
	private File gitDir;

	@Rule
	public JenkinsRule jenkinsRule = new JenkinsRule();
	@Rule
	public TemporaryFolder tmpFolder = new TemporaryFolder();

	@Before
	public void setUp() throws Exception {
		TaskListener taskListener = jenkinsRule.createTaskListener();
		EnvVars envVars = new EnvVars();
		gitDir = tmpFolder.getRoot();
		gitDirPath = new FilePath(gitDir);
		git = Git.with(taskListener, envVars).in(gitDirPath).getClient();
		git.init();
	}

	public void commit(final File file, final String fileContent, final PersonIdent author, final String msg) throws GitException, InterruptedException {
		FilePath filePath = new FilePath(file);
		try {
			filePath.write(fileContent, null);
		} catch (Exception e) {
			throw new GitException("unable to write file", e);
		}
		String path = filePath.getRemote().replaceFirst(gitDir.getAbsolutePath() + "/", "");
		git.add(path);
		git.setAuthor(author);
		git.setCommitter(author);
		git.commit(msg);
	}

	@Ignore
	@WithoutJenkins
	@Test
	public void testNoChangelog() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 1")));
	}

	@WithoutJenkins
	@Test
	public void testNoCommitsSinceLastChangelogModificationByPlugin() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", jenkins, "init");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, empty());
	}

	@Ignore
	@WithoutJenkins
	@Test
	public void testNoCommitsSinceLastChangelogModificationByUser() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", alice, "init");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, empty());
	}

	@WithoutJenkins
	@Test
	public void testSomeCommitsAfterLastChangelogModificationByPlugin() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", jenkins, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(tmpFolder.newFile("3"), "3", jenkins, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 2"), new Change(jenkins.getName(), "add 3")));
	}

	@Ignore
	@WithoutJenkins
	@Test
	public void testSomeCommitsAfterLastChangelogModificationByUser() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", alice, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(tmpFolder.newFile("3"), "3", alice, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 2"), new Change(alice.getName(), "add 3")));
	}

	@WithoutJenkins
	@Test
	public void testSeveralChangelogModificationsByPlugin() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", jenkins, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(new File(debian, "changelog"), "modificate changelog", jenkins, "change");
		commit(tmpFolder.newFile("3"), "3", alice, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 3")));
	}

	@Ignore
	@WithoutJenkins
	@Test
	public void testSeveralChangelogModificationsByUser() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", alice, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(new File(debian, "changelog"), "modificate changelog", alice, "change");
		commit(tmpFolder.newFile("3"), "3", alice, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 3")));
	}

	@WithoutJenkins
	@Test
	public void testManualChangelogModificationAfterPlugin() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", jenkins, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(new File(debian, "changelog"), "modificate changelog", alice, "change");
		commit(tmpFolder.newFile("3"), "3", alice, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 2"), new Change(alice.getName(), "change"), new Change(alice.getName(), "add 3")));
	}

	@WithoutJenkins
	@Test
	public void testManualChangelogModificationBeforePlugin() throws IOException, InterruptedException {
		File debian = tmpFolder.newFolder("debian");
		commit(tmpFolder.newFile("1"), "1", alice, "add 1");
		commit(new File(debian, "changelog"), "changelog", alice, "init");
		commit(tmpFolder.newFile("2"), "2", alice, "add 2");
		commit(new File(debian, "changelog"), "modificate changelog", jenkins, "change");
		commit(tmpFolder.newFile("3"), "3", alice, "add 3");

		List<Change> changes = ChangesExtractor.getChangesFromGit(git, gitDirPath, debian.getAbsolutePath(), jenkins);
		assertThat(changes, contains(new Change(alice.getName(), "add 3")));
	}
}
