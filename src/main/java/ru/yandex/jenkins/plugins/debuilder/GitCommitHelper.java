package ru.yandex.jenkins.plugins.debuilder;

import hudson.remoting.Callable;

public class GitCommitHelper implements Callable<String, DebianizingException>{

	private static final long serialVersionUID = -7582688022977623274L;

	@Override
	public String call() throws DebianizingException {
		return null;
	}

}
