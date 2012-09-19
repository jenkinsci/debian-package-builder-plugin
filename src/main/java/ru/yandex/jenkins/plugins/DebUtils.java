package ru.yandex.jenkins.plugins;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Shell;

import java.io.IOException;
import java.io.InputStreamReader;
import java.text.MessageFormat;

import com.google.common.io.CharStreams;

public class DebUtils {
	public static class Runner {
		private final AbstractBuild<?, ?> build;
		private final Launcher launcher;
		private final BuildListener listener;
		private final String prefix;

		public Runner(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, String prefix) {
			this.build = build;
			this.launcher = launcher;
			this.listener = listener;
			this.prefix = prefix;
		}

		public void runCommand(String command) throws InterruptedException, DebianizingException {
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		public void runCommand(String commandTemplate, Object... arguments) throws InterruptedException, DebianizingException {
			String command = MessageFormat.format(commandTemplate, arguments);
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		public boolean runCommandForResult(String command) throws InterruptedException, DebianizingException {
			announce("running command <{0}>", command);
			return new Shell(command).perform(build, launcher, listener);
		}

		public boolean runCommandForResult(String commandTemplate, Object ... arguments) throws InterruptedException, DebianizingException {
			return this.runCommandForResult(MessageFormat.format(commandTemplate, arguments));
		}

		public String runCommandForOutput(String commandTemplate, Object... params) throws DebianizingException {
			return runCommandForOutput(MessageFormat.format(commandTemplate, params));
		}

		public String runCommandForOutput(String command) throws DebianizingException {
			try {
				String actualCommand = MessageFormat.format("bash -c ''{0}''", command);
				return CharStreams.toString(new InputStreamReader(launcher.launch().cmdAsSingleString(actualCommand).readStdout().start().getStdout()));
			} catch (IOException e) {
				e.printStackTrace(listener.getLogger());
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command), e);
			}
		}

		public void announce(String message) {
			listener.getLogger().println(MessageFormat.format("[{0}] {1}" , prefix, message));
		}

		public void announce(String messageTemplate, Object... params) {
			this.announce(MessageFormat.format(messageTemplate, params));
		}

		public VirtualChannel getChannel() {
			return launcher.getChannel();
		}

		public BuildListener getListener() {
			return listener;
		}
	}
}
