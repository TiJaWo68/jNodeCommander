package de.in.jnc.connection.browser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved browser bookmark, stored per {@link de.in.jnc.ConnectionProfile}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Bookmark implements Comparable<Bookmark> {

	private String url;
	private String title;
	private String host;

	public Bookmark() {
	}

	public Bookmark(String url, String title, String host) {
		this.url = url;
		this.title = (title != null && !title.isEmpty()) ? title : url;
		this.host = (host != null && !host.isEmpty()) ? host : extractHost(url);
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	/** Sorted by host, then url. */
	@Override
	public int compareTo(Bookmark other) {
		int hostCmp = this.host.compareToIgnoreCase(other.host);
		if (hostCmp != 0)
			return hostCmp;
		return this.url.compareToIgnoreCase(other.url);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof Bookmark b))
			return false;
		return url.equals(b.url);
	}

	@Override
	public int hashCode() {
		return url.hashCode();
	}

	private static final int MAX_DISPLAY_LENGTH = 72;

	@Override
	public String toString() {
		String s = host + " — " + title;
		if (s.length() > MAX_DISPLAY_LENGTH) {
			s = s.substring(0, MAX_DISPLAY_LENGTH - 1) + "\u2026"; // …
		}
		return s;
	}

	private static String extractHost(String url) {
		if (url == null || url.isEmpty())
			return "";
		int schemeEnd = url.indexOf("://");
		int start = (schemeEnd >= 0) ? schemeEnd + 3 : 0;
		int end = url.indexOf('/', start);
		if (end < 0)
			end = url.length();
		int port = url.indexOf(':', start);
		if (port >= 0 && port < end)
			end = port;
		return url.substring(start, end);
	}
}
