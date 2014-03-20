package ru.yandex.jenkins.plugins.debuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jedi.functional.FunctionalPrimitives;

/**
 * Encapsulates version information and helps with manipulation
 * 
 * @author pupssman
 * 
 */
public class VersionHelper {
	private final String separator;
	private final List<String> versionElements;
	private final int minorEntry;
	private final int revisionEntry;

	/**
	 * As {@link VersionHelper#VersionHelper(String, String)} with "." separator
	 * @param version
	 */
	public VersionHelper(String version) {
		this(version, '.');
	}

	/**
	 * As {@link VersionHelper#VersionHelper(String[])} with elements got by splitting string with given character.
	 * @param version
	 * @param separatorCharacter
	 */
	public VersionHelper(String version, char separatorCharacter) {
		this(version.split("\\" + separatorCharacter), "" + separatorCharacter);

	}

	/**
	 * Constructs new helper with given version elements.
	 * They are analyzed to know which are responsible for the minor version and for the revision.
	 * @param versionElements
	 */
	public VersionHelper(String[] versionElements, String separator) {
		this.versionElements = new ArrayList<String>(Arrays.asList(versionElements));
		this.separator = separator;
		this.minorEntry = getMinorEntry();
		this.revisionEntry = getRevisionEntry();
	}

	private int getMinorEntry() {
		int lastNumeric = -1;

		for (int i = versionElements.size() - 1; i >= 0; i--) {
			try {
				Integer.parseInt(versionElements.get(i));
				lastNumeric = i;
				break;
			} catch (NumberFormatException e) {
				// pass
			}
		}

		return lastNumeric;
	}

	private int getRevisionEntry() {
		for (int i = 0; i < versionElements.size(); i++) {
			if (versionElements.get(i).startsWith("r")) {
				return i;
			}
		}

		return -1;
	}

	public void setRevision(String revision) {
		String revisionElement = "r" + revision;
		if (revisionEntry >= 0) {
			versionElements.set(revisionEntry, revisionElement);
		} else {
			versionElements.add(revisionElement);
		}
	}

	/**
	 * @return last revision imprinted in this version or <b>empty string</b> if no revision known
	 */
	public String getRevision() {
		if (revisionEntry >= 0) {
			return versionElements.get(revisionEntry).substring(1);
		} else {
			return "";
		}
	}

	/**
	 * @return last minor version imprinted in this version or <b>0</b> if no minor version known
	 */
	public int getMinorVersion() {
		if (minorEntry >= 0) {
			return Integer.parseInt(versionElements.get(minorEntry));
		} else {
			return 0;
		}
	}

	/**
	 * Replace the last numeric element.
	 * If there is no numeric element, add the new version at the end.
	 * @param newVersion
	 *            The new minor version
	 */
	public void setMinorVersion(int newVersion) {
		String versionElement = Integer.toString(newVersion);
		if (minorEntry >= 0) {
			versionElements.set(minorEntry, versionElement);
		} else {
			versionElements.add(versionElement);
		}
	}

	public String toString() {
		return FunctionalPrimitives.join(versionElements, separator);
	}

}
