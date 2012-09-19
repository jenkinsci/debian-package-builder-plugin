package ru.yandex.jenkins.plugins.debuilder;

import hudson.remoting.Callable;
import hudson.scm.SubversionSCM;
import hudson.scm.SvnClientManager;

import java.io.File;
import java.io.Serializable;

import javax.naming.ldap.ManageReferralControl;

import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationProvider;
import org.tmatesoft.svn.core.wc.SVNCommitPacket;

public class SVNCommitHelper implements Serializable , Callable<String, DebianizingException>{
	private static final long serialVersionUID = 1L;

	private final ISVNAuthenticationProvider provider;
	private final String path;
	private final String commitMessage;

	public SVNCommitHelper(ISVNAuthenticationProvider provider, String path, String commitMessage) {
		this.provider = provider;
		this.path = path;
		this.commitMessage = commitMessage;
	}

	@Override
	public String call() throws DebianizingException {
		SvnClientManager clientManager = SubversionSCM.createClientManager(provider);
		try {
			SVNCommitPacket changeset = clientManager.getCommitClient().doCollectCommitItems(new File[] {new File(path)}, false, true, SVNDepth.INFINITY, null);
			if (changeset != SVNCommitPacket.EMPTY) {
				SVNCommitInfo commitInfo = clientManager.getCommitClient().doCommit(changeset, false, commitMessage);
				return Long.toString(commitInfo.getNewRevision());
			} else {
				throw new DebianizingException("There was nothing to commit.");
			}
		} catch (SVNException e) {
			throw new DebianizingException("SVNException: " + e.getMessage(), e);
		} finally {
			clientManager.dispose();
		}
	}
}
