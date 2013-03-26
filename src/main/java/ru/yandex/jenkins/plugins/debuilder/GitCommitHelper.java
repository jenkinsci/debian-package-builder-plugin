package ru.yandex.jenkins.plugins.debuilder;

import java.io.IOException;

import org.eclipse.jgit.lib.PersonIdent;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;

import hudson.EnvVars;
import hudson.remoting.Callable;

public class GitCommitHelper implements Callable<String, DebianizingException>{

	private static final long serialVersionUID = -7582688022977623274L;
	private final EnvVars environment;

	public GitCommitHelper(EnvVars environemnt) {
		this.environment = environemnt;
	}

	@Override
	public String call() throws DebianizingException {
		GitClient client = Git.with(null, this.environment).getClient();
		if (!client.hasGitRepo()) {
			throw new DebianizingException("Workspace contains no git repository");
		}
		PersonIdent person = new PersonIdent("ololo", "foo@somewhere.com");

		client.add("debian/**");
		client.commit("test", person, person);
		client.push("origin", null);

		return "OK";
	}

}
