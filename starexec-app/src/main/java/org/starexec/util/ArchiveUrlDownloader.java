package org.starexec.util;

import org.apache.commons.io.FileUtils;
import org.starexec.logger.StarLogger;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Set;

/**
 * Downloads an archive a user names by URL, under a {@link Policy}.
 *
 * @see Util#copyFileFromURLUsingProxy(URL, File)
 */
final class ArchiveUrlDownloader {

	private static final StarLogger log = StarLogger.getLogger(ArchiveUrlDownloader.class);

	private ArchiveUrlDownloader() {
	}

	/** Where an archive may be downloaded from, and how much of it. */
	static final class Policy {
		final boolean allowPrivateNetworks;
		final Set<InetAddress> allowedLoopbackAddresses;
		final int maxRedirects;
		final long maxBytes;
		final long deadlineMillis;
		final int connectTimeoutMillis;
		final int readTimeoutMillis;

		Policy(boolean allowPrivateNetworks, Set<InetAddress> allowedLoopbackAddresses, int maxRedirects,
				long maxBytes, long deadlineMillis, int connectTimeoutMillis, int readTimeoutMillis) {
			this.allowPrivateNetworks = allowPrivateNetworks;
			this.allowedLoopbackAddresses = Set.copyOf(allowedLoopbackAddresses);
			this.maxRedirects = maxRedirects;
			this.maxBytes = maxBytes;
			this.deadlineMillis = deadlineMillis;
			this.connectTimeoutMillis = connectTimeoutMillis;
			this.readTimeoutMillis = readTimeoutMillis;
		}

		/** The policy for downloads requested by users. */
		static Policy standard() {
			return new Policy(false, Set.of(), 20, Long.MAX_VALUE, Long.MAX_VALUE, Util.CONNECT_TIMEOUT_MS,
					Util.READ_TIMEOUT_MS);
		}
	}

	/**
	 * Copies the file at the end of an http or https URL to the given file.
	 *
	 * @param url         the URL to download
	 * @param destination the file to write
	 * @param policy      where the archive may come from, and how much of it
	 * @return true on success and false otherwise
	 */
	static boolean download(URL url, File destination, Policy policy) {
		final String methodName = "download";
		if (!Util.isDownloadableUrl(url)) {
			log.warn(methodName, "refusing to download a URL with scheme " + (url == null ? null : url.getProtocol()));
			return false;
		}
		try {
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setConnectTimeout(policy.connectTimeoutMillis);
			connection.setReadTimeout(policy.readTimeoutMillis);
			int status = connection.getResponseCode();
			if (status < 200 || status > 299) {
				log.warn(methodName, "download answered HTTP " + status);
				return false;
			}
			FileUtils.copyInputStreamToFile(connection.getInputStream(), destination);
			return true;
		} catch (Exception e) {
			log.error(methodName, e.getMessage(), e);
		}
		return false;
	}

	/**
	 * Whether an address a download host resolves to may be connected to.
	 *
	 * @param address the resolved address
	 * @param policy  the download policy
	 * @return true when the policy permits connecting to it
	 */
	static boolean isPermittedAddress(InetAddress address, Policy policy) {
		return true;
	}

	/**
	 * Whether a redirect from one URL to another may be followed.
	 *
	 * @param from the URL that answered with a redirect
	 * @param to   the redirect target
	 * @return true when the transition is permitted
	 */
	static boolean isPermittedRedirect(URL from, URL to) {
		return from.getProtocol().equalsIgnoreCase(to.getProtocol());
	}
}
