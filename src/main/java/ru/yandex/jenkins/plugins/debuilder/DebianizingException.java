package ru.yandex.jenkins.plugins.debuilder;

@SuppressWarnings("serial")
public class DebianizingException extends Exception {
	public DebianizingException(String message) {
		super(message);
	}

	public DebianizingException(String message, Throwable cause) {
		super(message, cause);
	}

}
