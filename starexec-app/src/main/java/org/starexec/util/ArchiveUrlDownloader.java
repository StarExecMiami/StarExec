package org.starexec.util;

import org.apache.commons.io.FileUtils;
import org.starexec.config.EnvironmentConfig;
import org.starexec.logger.StarLogger;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads an archive a user names by URL, under a {@link Policy}.
 *
 * @see Util#copyFileFromURLUsingProxy(URL, File)
 */
final class ArchiveUrlDownloader {

	private static final StarLogger log = StarLogger.getLogger(ArchiveUrlDownloader.class);

	/** Redirects followed before a download is abandoned. */
	static final int MAX_REDIRECTS = 5;

	/** The IPv6 instance metadata address, inside the unique-local range. */
	private static final InetAddress IPV6_METADATA = address("fd00:ec2::254");

	/** The well-known NAT64 prefix, 64:ff9b::/96, which embeds an IPv4 address. */
	private static final byte[] NAT64_PREFIX = {0, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};

	private ArchiveUrlDownloader() {
	}

	/** Where an archive may be downloaded from, and how much of it. */
	static final class Policy {
		/** Whether private ranges (RFC 1918, 100.64.0.0/10, unique-local) may be reached. */
		final boolean allowPrivateNetworks;
		/** Loopback addresses that may be reached; empty outside tests. */
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
			return new Policy(EnvironmentConfig.isUrlDownloadPrivateNetworksAllowed(), Set.of(), MAX_REDIRECTS,
					Long.MAX_VALUE, Long.MAX_VALUE, Util.CONNECT_TIMEOUT_MS, Util.READ_TIMEOUT_MS);
		}
	}

	/**
	 * Copies the file at the end of an http or https URL to the given file.
	 *
	 * <p>Every hop is checked before it is contacted: the scheme must be http or https, and every
	 * address the host resolves to must be permitted by {@link #isPermittedAddress}. Redirects are
	 * followed here rather than by the JVM, at most {@code policy.maxRedirects} of them, and only
	 * as {@link #isPermittedRedirect} allows.
	 *
	 * <p>The host is resolved for the check and again by the connection, so a name whose DNS
	 * answer changes between the two can still reach an address the check did not see. Pinning
	 * the connection to the checked address is not done here.
	 *
	 * @param url         the URL to download
	 * @param destination the file to write
	 * @param policy      where the archive may come from, and how much of it
	 * @return true on success and false otherwise
	 */
	static boolean download(URL url, File destination, Policy policy) {
		final String methodName = "download";
		try {
			URL current = url;
			for (int redirects = 0; ; redirects++) {
				String refusal = refusal(current, policy);
				if (refusal != null) {
					log.warn(methodName, refusal);
					return false;
				}
				HttpURLConnection connection = (HttpURLConnection) current.openConnection();
				try {
					connection.setInstanceFollowRedirects(false);
					connection.setConnectTimeout(policy.connectTimeoutMillis);
					connection.setReadTimeout(policy.readTimeoutMillis);
					int status = connection.getResponseCode();
					if (isRedirect(status)) {
						URL next = redirectTarget(current, connection.getHeaderField("Location"));
						if (next == null) {
							log.warn(methodName, "download answered HTTP " + status + " without a usable Location");
							return false;
						}
						if (redirects >= policy.maxRedirects) {
							log.warn(methodName, "download redirected more than " + policy.maxRedirects + " times");
							return false;
						}
						if (!isPermittedRedirect(current, next)) {
							log.warn(methodName, "refusing a redirect from " + current.getProtocol() + " to "
									+ next.getProtocol());
							return false;
						}
						current = next;
						continue;
					}
					if (status < 200 || status > 299) {
						log.warn(methodName, "download answered HTTP " + status);
						return false;
					}
					FileUtils.copyInputStreamToFile(connection.getInputStream(), destination);
					return true;
				} finally {
					connection.disconnect();
				}
			}
		} catch (Exception e) {
			log.error(methodName, e.getMessage(), e);
		}
		return false;
	}

	/**
	 * Whether an address a download host resolves to may be connected to.
	 *
	 * <p>Loopback, unspecified, link-local (including 169.254.169.254), multicast, 0.0.0.0/8 and
	 * the IPv6 metadata address are always refused. Private ranges (RFC 1918, 100.64.0.0/10, IPv6
	 * unique-local and site-local) are refused unless the policy allows private networks. IPv6
	 * forms that embed an IPv4 address (mapped, compatible, NAT64) are judged by that address.
	 *
	 * @param address the resolved address
	 * @param policy  the download policy
	 * @return true when the policy permits connecting to it
	 */
	static boolean isPermittedAddress(InetAddress address, Policy policy) {
		InetAddress candidate = embeddedIpv4(address);
		if (candidate.isLoopbackAddress()) {
			return policy.allowedLoopbackAddresses.contains(candidate);
		}
		if (candidate.isAnyLocalAddress() || candidate.isLinkLocalAddress() || candidate.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = candidate.getAddress();
		boolean privateRange;
		if (bytes.length == 4) {
			if (bytes[0] == 0) {
				return false;
			}
			boolean sharedAddressSpace = (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
			privateRange = candidate.isSiteLocalAddress() || sharedAddressSpace;
		} else {
			if (candidate.equals(IPV6_METADATA)) {
				return false;
			}
			boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
			privateRange = uniqueLocal || candidate.isSiteLocalAddress();
		}
		return !privateRange || policy.allowPrivateNetworks;
	}

	/**
	 * Whether a redirect from one URL to another may be followed: http to http, https to https,
	 * or http to https.
	 *
	 * @param from the URL that answered with a redirect
	 * @param to   the redirect target
	 * @return true when the transition is permitted
	 */
	static boolean isPermittedRedirect(URL from, URL to) {
		String source = from.getProtocol().toLowerCase(Locale.ROOT);
		String target = to.getProtocol().toLowerCase(Locale.ROOT);
		if (!"http".equals(target) && !"https".equals(target)) {
			return false;
		}
		return source.equals(target) || ("http".equals(source) && "https".equals(target));
	}

	/** Why a URL may not be contacted, or null when it may. */
	private static String refusal(URL url, Policy policy) {
		if (!Util.isDownloadableUrl(url)) {
			return "refusing to download a URL with scheme " + (url == null ? null : url.getProtocol());
		}
		String host = url.getHost();
		if (host == null || host.isEmpty()) {
			return "refusing to download a URL without a host";
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			return "could not resolve download host " + host;
		}
		for (InetAddress address : addresses) {
			if (!isPermittedAddress(address, policy)) {
				return "refusing to download from " + host + ", which resolves to a non-public address";
			}
		}
		return null;
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	/** The absolute URL a Location header names, resolved against the current URL; null if unusable. */
	private static URL redirectTarget(URL current, String location) {
		if (location == null || location.isBlank()) {
			return null;
		}
		try {
			return current.toURI().resolve(location.trim()).toURL();
		} catch (Exception e) {
			return null;
		}
	}

	private static InetAddress embeddedIpv4(InetAddress address) {
		if (!(address instanceof Inet6Address) || address.isLoopbackAddress() || address.isAnyLocalAddress()) {
			return address;
		}
		byte[] bytes = address.getAddress();
		boolean compatible = isZero(bytes, 12);
		boolean mapped = isZero(bytes, 10) && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
		boolean nat64 = Arrays.equals(bytes, 0, 12, NAT64_PREFIX, 0, 12);
		if (!compatible && !mapped && !nat64) {
			return address;
		}
		try {
			return InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
		} catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean isZero(byte[] bytes, int length) {
		for (int i = 0; i < length; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static InetAddress address(String literal) {
		try {
			return InetAddress.getByName(literal);
		} catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}
}
