package ru.yandex.jenkins.plugins.debuilder;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;
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
		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "", false, false, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		fire(builder);

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verify(runner, atLeast(0)).announce(anyString());
		verify(runner, atLeast(0)).announce(anyString(), anyVararg());
		verify(runner).runCommand(contains("debuild"));
		verifyNoMoreInteractions(runner);

	}


	/**
	 * This test smokey-checks that the {@link DebianPackageBuilder} calls proper shell commands
	 * when building package with changelog
	 *
	 * @throws Exception
	 */
	@Test
	public void smokeWithChangelog() throws Exception {
		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "", true, false, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		fire(builder);

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verify(runner, atLeast(0)).announce(anyString());
		verify(runner, atLeast(0)).announce(anyString(), anyVararg());
		verify(runner).runCommand(contains("debuild"));
		verifyNoMoreInteractions(runner);
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
		FreeStyleBuild build = project.scheduleBuild2(0).get();

		assert build.getResult() == Result.SUCCESS;
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

