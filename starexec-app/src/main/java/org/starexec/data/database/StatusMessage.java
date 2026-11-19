package org.starexec.data.database;

import org.starexec.data.security.GeneralSecurity;
import org.starexec.logger.StarLogger;

import com.google.gson.*;
import java.sql.SQLException;

public class StatusMessage {
	private static final StarLogger log = StarLogger.getLogger(StatusMessage.class);
	private static final Gson gson = new GsonBuilder().create();

	private StatusMessage() {} // Class is not instantiable

	/**
	 * Formats an enabled status banner safely for HTML output.
	 * Escapes message text and only renders a link for http(s) or site-relative URLs.
	 */
	public static String formatStatusHtml(String message, String url) {
		String safeMessage = GeneralSecurity.getHTMLSafeString(message == null ? "" : message);
		String trimmedUrl = url == null ? "" : url.trim();
		String linkHtml = "";
		if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("/")) {
			String safeUrl = GeneralSecurity.getHTMLAttributeSafeString(trimmedUrl);
			linkHtml = "<a href='" + safeUrl + "'>More Information</a>";
		}
		return "<div class='status-message'><p>" + safeMessage + linkHtml + "</p></div>";
	}

	public static void set(boolean enabled, String message, String url) throws SQLException {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT starexec.SetStatusMessage(?, ?, ?)");
			ps.setBoolean(1, enabled);
			ps.setString(2, message.trim());
			ps.setString(3, url.trim());
			ps.execute();
		} finally {
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}

	public static String getAsHtml() {
		final String html;
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetStatusMessage()");
			results = ps.executeQuery();
			if (results.next()) {
				if (results.getBoolean("enabled")) {
					final String message = results.getString("message");
					final String url = results.getString("url");
					html = formatStatusHtml(message, url);
				} else {
					html = "";
				}
			} else {
				html = "";
			}
		} catch (SQLException e) {
			log.error("getAsHtml", e);
			return "";
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
		return html;
	}

	public static String getAsJson() {
		java.sql.Connection con = null;
		java.sql.PreparedStatement ps = null;
		java.sql.ResultSet results = null;
		try {
			con = Common.getConnection();
			ps = con.prepareStatement("SELECT * FROM starexec.GetStatusMessage()");
			results = ps.executeQuery();
			JsonObject json = new JsonObject();
			if (results.next()) {
				json.addProperty("enabled", results.getBoolean("enabled"));
				if (results.getBoolean("enabled")) {
					json.addProperty("message", results.getString("message"));
					json.addProperty("url", results.getString("url"));
				}
			} else {
				json.addProperty("enabled", false);
			}
			return gson.toJson(json);
		} catch (SQLException e) {
			log.error("getAsHtml", e);
			return "{}";
		} finally {
			Common.safeClose(results);
			Common.safeClose(ps);
			Common.safeClose(con);
		}
	}
}
