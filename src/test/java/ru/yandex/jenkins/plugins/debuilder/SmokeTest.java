package ru.yandex.jenkins.plugins.debuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;
import ru.yandex.jenkins.plugins.debuilder.ChangesExtractor.Change;
import ru.yandex.jenkins.plugins.debuilder.DebUtils.Runner;
import ru.yandex.jenkins.plugins.debuilder.DebianPackageBuilder.DescriptorImpl;

public class SmokeTest {
	@Rule
	public JenkinsRule j = new JenkinsRule();

	/**
	 * This test smokey-checks that the {@link DebianPackageBuilder} calls proper shell commands
	 *
	 * @throws Exception
	 */
	@Test
	public void smokeWithoutChangelog() throws Exception {
		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "", false, false, true, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		fire(builder);

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verifyIrrelevantAndBuild(runner);
		verifyNoMoreInteractions(runner);
	}


	/**
	 * This test smokey-checks that the {@link DebianPackageBuilder} calls proper shell commands
	 * when building package with changelog.
	 * Use forced ``nextVersion`` to ease the code.
	 * @throws Exception
	 */
	@Test
	public void smokeWithNewVersion() throws Exception {
		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "1.0", true, false, true, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		fire(builder);

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verify(runner).runCommand(contains("dch"), anyVararg());
		verifyIrrelevantAndBuild(runner);
		verifyNoMoreInteractions(runner);
	}


	/**
	 * This test smokey-checks that the {@link DebianPackageBuilder} calls proper shell commands
	 * when building package with changelog.
	 * Use forced ``nextVersion`` to ease the code.
	 * Check how the new version message is added
	 * @throws Exception
	 */
	@Test
	public void smokeWithChangesetVersion() throws Exception {
		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "1.0", true, false, true, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		Pair<VersionHelper, List<Change>> changes = new ImmutablePair<VersionHelper, List<Change>>(new VersionHelper("1.0"), Arrays.asList(new Change[] {new Change("ololo", "pewpew")}));

		doReturn(changes).when(builder).generateChangelog(anyString(), any(Runner.class), any(AbstractBuild.class), anyString(), anyBoolean());

		fire(builder);

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verify(runner).runCommand(contains("dch --check-dirname-level 0 -b --distribution ''{5}'' --newVersion {3} -- ''{4}''"), anyVararg());
		verify(runner).runCommand(contains("dch --check-dirname-level 0 --distribution ''{4}'' --append -- ''{3}''"),
											any(),
											any(),
											any(),
											eq("pewpew"),  // the version message
											any()
											);
		verifyIrrelevantAndBuild(runner);
		verifyNoMoreInteractions(runner);
	}



	/**
	 * Verify that actual ``debuild`` is called alongside not that interesting operations.
	 * @param runner
	 * @throws InterruptedException
	 * @throws DebianizingException
	 */
	private void verifyIrrelevantAndBuild(Runner runner) throws InterruptedException, DebianizingException {
		verify(runner, atLeast(0)).announce(anyString());
		verify(runner, atLeast(0)).getListener();
		verify(runner, atLeast(0)).announce(anyString(), anyVararg());
		verify(runner).runCommand(contains("debuild"));
	}


	/**
	 * Build this ``builder`` in a basic project and check that build results in success
	 *
	 * @param builder
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 */
	private void fire(DebianPackageBuilder builder) throws IOException, InterruptedException, ExecutionException {
		FreeStyleProject project = j.createFreeStyleProject();
		project.getBuildersList().add(builder);
		project.scheduleBuild2(0).get();
	}


	private Runner mockBasicRunner(DebianPackageBuilder builder) throws InterruptedException, DebianizingException {
		Runner runner = mock(Runner.class);
		doReturn(true).when(runner).runCommandForResult(any(String.class));
		doReturn("").when(runner).runCommandForOutput(any(String.class));
		doReturn("").when(runner).runCommandForOutput(any(String.class), anyVararg());

		doReturn(runner).when(builder).makeRunner(Mockito.any(AbstractBuild.class), Mockito.any(Launcher.class), Mockito.any(BuildListener.class));
		return runner;
	}

	public void verifyInstallAndKeyImport(Runner runner) throws InterruptedException, DebianizingException {
		verify(runner).runCommand("sudo apt-get -y update");
		verify(runner).runCommand("sudo apt-get -y install aptitude pbuilder");
		verify(runner).runCommandForResult("gpg --list-key {0}", "foo@bar.com");
		verify(runner).runCommandForResult("gpg --list-secret-key {0}", "foo@bar.com");
		verify(runner, times(2)).runCommand(contains("gpg --import"), anyVararg());
	}

	public void mockTestDescriptor(DebianPackageBuilder builder) {
		doReturn(getTestDescriptor()).when(builder).getDescriptor();
	}

	public DescriptorImpl getTestDescriptor() {
		DescriptorImpl descriptorImpl = new DebianPackageBuilder.DescriptorImpl();

		descriptorImpl.setAccountEmail("foo@bar.com");
		descriptorImpl.setAccountName("foo");
		descriptorImpl.setPublicKey("ololo not a key");
		descriptorImpl.setPrivateKey("ololo not a key");
		descriptorImpl.setPassphrase("a passphrase");

		return descriptorImpl;
	}
}

