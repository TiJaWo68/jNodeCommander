package de.in.jnc.connection.browser;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A browser history entry, stored per {@link de.in.jnc.ConnectionProfile}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HistoryEntry {

    private String url;
    private String title;
    private long timestamp; // epoch millis

    /** Required for Jackson deserialization. */
    public HistoryEntry() {
        this.timestamp = System.currentTimeMillis();
    }

    public HistoryEntry(String url, String title) {
        this();
        this.url = url;
        this.title = (title != null && !title.isEmpty()) ? title : url;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @JsonIgnore
    public Instant getInstant() {
        return Instant.ofEpochMilli(timestamp);
    }

    @Override
    public String toString() {
        return title + " (" + url + ")";
    }
}
