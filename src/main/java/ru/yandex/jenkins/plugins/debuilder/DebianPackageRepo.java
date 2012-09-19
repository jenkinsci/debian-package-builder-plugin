package ru.yandex.jenkins.plugins.debuilder;

import org.kohsuke.stapler.DataBoundConstructor;

public final class DebianPackageRepo {

	private String name;
	private String method;
	private String fqdn;
	private String incoming;
	private String login;
	private String options;
	private String keypath;

	@DataBoundConstructor
	public DebianPackageRepo(String name, String method, String fqdn, String incoming, String login, String options, String keypath) {
		this.name = name;
		this.method = method;
		this.fqdn = fqdn;
		this.incoming = incoming;
		this.login = login;
		this.options = options;
		this.keypath = keypath;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getFqdn() {
		return fqdn;
	}

	public void setFqdn(String fqdn) {
		this.fqdn = fqdn;
	}

	public String getIncoming() {
		return incoming;
	}

	public void setIncoming(String incoming) {
		this.incoming = incoming;
	}

	public String getLogin() {
		return login;
	}

	public void setLogin(String login) {
		this.login = login;
	}

	public String getOptions() {
		return options;
	}

	public void setOptions(String options) {
		this.options = options;
	}

	public String getKeypath() {
		return keypath;
	}

	public void setKeypath(String keypath) {
		this.keypath = keypath;
	}

}
