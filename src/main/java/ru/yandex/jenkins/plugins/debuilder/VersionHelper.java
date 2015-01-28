package ru.yandex.jenkins.plugins.debuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Encapsulates version information and helps with manipulation
 *
 * @author pupssman
 *
 */
public class VersionHelper {
	private String epoch;
	private String upstreamVersion;
	private String debianRevision;

	/**
	 * Constructs new helper with given version.
	 * Version is splitted by epoch:upstreamVersion-debianRevision
	 * As explain here: https://www.debian.org/doc/debian-policy/ch-controlfields.html#s-f-Version
	 * @param version
	 */
	public VersionHelper(String version) {
		int epochIndex = version.indexOf(':');
		if (epochIndex != -1) {
			this.epoch = version.substring(0, epochIndex);
			version = version.substring(epochIndex + 1);
		} else {
			this.epoch = null;
		}
		int debianRevisionIndex = version.lastIndexOf('-');
		if (debianRevisionIndex != -1) {
			this.debianRevision = version.substring(debianRevisionIndex + 1);
			version = version.substring(0,debianRevisionIndex);
		} else {
			this.debianRevision = null;
		}
		this.upstreamVersion = version;
	}

	/**
	 * Constructs new helper with given epoch, upstream version and debian revision.
	 * @param epoch
	 * @param upstreamVersion
	 * @param debianRevision
	 */
	public VersionHelper(String epoch, String upstreamVersion, String debianRevision) {
		this.epoch = epoch;
		this.upstreamVersion = upstreamVersion;
		this.debianRevision = debianRevision;
	}

  /**
	* @return upstreamVersion
	*/
	public String getUpstreamVersion() {
		return this.upstreamVersion;
	}

	/**
	* Replace upstreamVersion.
	* @param newUpstreamVersion
	*/
	public void setUpstreamVersion(String newUpstreamVersion) {
		this.upstreamVersion = newUpstreamVersion;
	}

	/**
	 * @return debian revision
	 */
	public String getDebianRevision() {
		return this.debianRevision;
	}

	/**
	 * Replace debianRevision.
	 * @param newDebianRevision
	 */
	public void setDebianRevision(String newDebianRevision) {
		this.debianRevision = newDebianRevision;
	}

	public String getNoEpoch() {
		StringBuilder s = new StringBuilder();
		s.append(this.upstreamVersion);
		if (this.debianRevision != null && !this.debianRevision.isEmpty()) {
			s.append('-');
			s.append(this.debianRevision);
		}
	return s.toString();
	}

	public String toString() {
		StringBuilder s = new StringBuilder();
		if (this.epoch != null && !this.epoch.isEmpty()) {
			s.append(this.epoch);
			s.append(':');
		}
		s.append(this.getNoEpoch());
		return s.toString();
	}

}
