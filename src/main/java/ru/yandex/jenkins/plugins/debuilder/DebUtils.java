package ru.yandex.jenkins.plugins.debuilder;

import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Shell;

import java.io.IOException;
import java.io.InputStreamReader;
import java.text.MessageFormat;

import com.google.common.io.CharStreams;

/**
 * A collection of tools to help the build and publish process
 * 
 */
public class DebUtils {
	/**
	 * Provides same tools to interact with the user console and the build
	 * environment
	 * 
	 */
	public static class Runner {
		private final AbstractBuild<?, ?> build;
		private final Launcher launcher;
		private final BuildListener listener;
		private final String prefix;

		/**
		 * Create a new tool for the specific build process
		 * 
		 * @param build
		 *            The targeted build
		 * @param launcher
		 *            The launcher with the necessary envs
		 * @param listener
		 *            The build listener to get the events
		 * @param prefix
		 *            The message prefix used when messages are printed
		 */
		public Runner(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, String prefix) {
			this.build = build;
			this.launcher = launcher;
			this.listener = listener;
			this.prefix = prefix;
		}

		/**
		 * Same as {@link Runner#runCommandForResult(String)}
		 * 
		 * @param command
		 *            The command
		 * @throws InterruptedException
		 *             Same as {@link Runner#runCommandForResult(String)}
		 * @throws DebianizingException
		 *             If the {@link Runner#runCommandForResult(String)} returns
		 *             false
		 */
		public void runCommand(String command) throws InterruptedException, DebianizingException {
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		/**
		 * Same as {@link Runner#runCommand(String)} but with using a {@link MessageFormat}
		 * 
		 * @param commandTemplate
		 *            The message template
		 * @param arguments
		 *            The template arguments
		 * @throws InterruptedException
		 *             Same as {@link Runner#runCommandForResult(String)}
		 * @throws DebianizingException
		 *             If the {@link Runner#runCommandForResult(String)} returns
		 *             false
		 */
		public void runCommand(String commandTemplate, Object... arguments) throws InterruptedException, DebianizingException {
			String command = MessageFormat.format(commandTemplate, arguments);
			if (!this.runCommandForResult(command)) {
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command));
			}
		}

		/**
		 * Execute the {@link Shell#perform(AbstractBuild, Launcher, BuildListener)}
		 * 
		 * @param command
		 *            The command
		 * @return Same as {@link Shell#perform(AbstractBuild, Launcher, BuildListener)}
		 * @throws InterruptedException
		 *             Same as {@link Shell#perform(AbstractBuild, Launcher, BuildListener)}
		 */
		public boolean runCommandForResult(String command) throws InterruptedException {
			announce("running command <{0}>", command);
			return new Shell(command).perform(build, launcher, listener);
		}

		public boolean runCommandForResult(String commandTemplate, Object... arguments) throws InterruptedException, DebianizingException {
			return this.runCommandForResult(MessageFormat.format(commandTemplate, arguments));
		}

		/**
		 * Same as {@link Runner#runCommandForOutput(String)} but with a {@link MessageFormat}
		 * 
		 * @param commandTemplate
		 *            The command template
		 * @param params
		 *            The template parameters
		 * @return Same as {@link Runner#runCommandForOutput(String)}
		 * @throws DebianizingException
		 *             Same as {@link Runner#runCommandForOutput(String)}
		 */
		public String runCommandForOutput(String commandTemplate, Object... params) throws DebianizingException {
			return runCommandForOutput(MessageFormat.format(commandTemplate, params));
		}

		/**
		 * Run the command inside a bash using {@link ProcStarter#cmdAsSingleString(String)} and return the output
		 * 
		 * @param command
		 *            The command
		 * @return The command output
		 * @throws DebianizingException
		 *             If there is some problem running the command
		 */
		public String runCommandForOutput(String command) throws DebianizingException {
			try {
				String actualCommand = MessageFormat.format("bash -c ''{0}''", command);
				return CharStreams.toString(new InputStreamReader(launcher.launch().cmdAsSingleString(actualCommand).readStdout().start().getStdout()));
			} catch (IOException e) {
				e.printStackTrace(listener.getLogger());
				throw new DebianizingException(MessageFormat.format("Command <{0}> failed", command), e);
			}
		}

		/**
		 * Print a new message line to user console with a prefix identification
		 * 
		 * @param message
		 *            The message
		 */
		public void announce(String message) {
			listener.getLogger().println(MessageFormat.format("[{0}] {1}", prefix, message));
		}

		/**
		 * Same as {@link Runner#announce(String)} but with a {@link MessageFormat}
		 * 
		 * @param messageTemplate
		 *            The message template
		 * @param params
		 *            The parameters to the message template
		 */
		public void announce(String messageTemplate, Object... params) {
			this.announce(MessageFormat.format(messageTemplate, params));
		}

		/**
		 * Same as {@link Launcher#getChannel()}
		 * 
		 * @return Same as {@link Launcher#getChannel()}
		 */
		public VirtualChannel getChannel() {
			return launcher.getChannel();
		}

		/**
		 * Get the {@link BuildListener}
		 * 
		 * @return The {@link BuildListener}
		 */
		public BuildListener getListener() {
			return listener;
		}
	}
}
