package ru.yandex.jenkins.plugins.debuilder;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;

import java.io.IOException;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.Map;

import com.google.common.io.CharStreams;

public class DebUtils {
	public static class Runner {
		private final Run<?, ?> build;
		private final FilePath workspace;
		private final Launcher launcher;
		private final TaskListener listener;
		private final String prefix;

		public Runner(Run<?,?> build, FilePath workspace, Launcher launcher, TaskListener listener, String prefix) {
			this.build = build;
			this.workspace = workspace;
			this.launcher = launcher;
			this.listener = listener;
			this.prefix = prefix;
		}

		public void runCommand(String command) throws IOException, InterruptedException, DebianizingException {
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		public void runCommand(String cwd, Map<String, String> envs, String commandTemplate, Object... arguments) throws IOException, InterruptedException, DebianizingException {
			String command = MessageFormat.format(commandTemplate, arguments);
			if (!this.runCommandForResult(command, cwd, envs)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		public void runCommand(String commandTemplate, Object... arguments) throws IOException, InterruptedException, DebianizingException {
			String command = MessageFormat.format(commandTemplate, arguments);
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		public boolean runCommandForResult(String command) throws IOException, InterruptedException, DebianizingException {
			announce("running command <{0}>", command);
			return launcher.launch().pwd(workspace).cmdAsSingleString(command).stdout(listener).join() == 0;
		}

		public boolean runCommandForResult(String command, String cwd, Map<String, String> envs) throws IOException, InterruptedException, DebianizingException {
			announce("running command <{0}>", command);
			return launcher.launch().pwd(cwd).envs(envs).cmdAsSingleString(command).stdout(listener).join() == 0;
		}

		public boolean runCommandForResult(String commandTemplate, Object ... arguments) throws IOException, InterruptedException, DebianizingException {
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

		public TaskListener getListener() {
			return listener;
		}
	}
}
