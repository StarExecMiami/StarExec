package org.starexec.data.database;

import org.starexec.logger.StarLogger;

import com.google.gson.*;
import java.sql.SQLException;

public class StatusMessage {
	private static final StarLogger log = StarLogger.getLogger(StatusMessage.class);
	private static final Gson gson = new GsonBuilder().create();

	private StatusMessage() {} // Class is not instantiable

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
					html = "<div class='status-message'><p>" + message
							+ (url.isEmpty() ? "" : "<a href='" + url + "'>More Information</a>")
							+ "</p></div>";
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
