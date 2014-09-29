package ru.yandex.jenkins.plugins.debuilder;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;

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
		FreeStyleProject project = j.createFreeStyleProject();

		DebianPackageBuilder builder = spy(new DebianPackageBuilder(".", "", false, false, true));

		mockTestDescriptor(builder);
		Runner runner = mockBasicRunner(builder);

		project.getBuildersList().add(builder);
		project.scheduleBuild2(0).get();

		verifyInstallAndKeyImport(runner);
		verify(runner).runCommandForOutput(contains("dpkg-parsechangelog"), contains("debian"));
		verify(runner).runCommand(contains("pbuilder-satisfydepends"), anyVararg());
		verify(runner, atLeast(0)).announce(anyString());
		verify(runner, atLeast(0)).announce(anyString(), anyVararg());
		verify(runner).runCommand(contains("debuild"));
		verifyNoMoreInteractions(runner);
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

