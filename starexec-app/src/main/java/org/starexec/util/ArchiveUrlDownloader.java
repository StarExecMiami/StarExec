package org.starexec.util;

import org.starexec.config.EnvironmentConfig;
import org.starexec.logger.StarLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads an archive a user names by URL, under a {@link Policy}.
 *
 * @see Util#copyFileFromURLUsingProxy(URL, File)
 */
final class ArchiveUrlDownloader {

	private static final StarLogger log = StarLogger.getLogger(ArchiveUrlDownloader.class);

	/** Redirects followed before a download is abandoned. */
	static final int MAX_REDIRECTS = 5;

	/**
	 * Largest archive downloaded: the limit on a benchmark archive sent through the upload form,
	 * {@code UploadBenchmark}'s {@code @MultipartConfig maxFileSize} of 5 GiB.
	 */
	static final long MAX_BYTES = 5L * 1024 * 1024 * 1024;

	/**
	 * Longest a download may take, redirects and body together. 30 minutes admits a 5 GiB archive
	 * at about 3 MiB/s; past that, a slow server only holds the upload's thread.
	 */
	static final long DEADLINE_MILLIS = 30L * 60 * 1000;

	/**
	 * Longest wait for any single read. A server silent for a minute is treated as gone. A server
	 * that keeps sending slowly is stopped by the deadline instead.
	 */
	static final int READ_TIMEOUT_MILLIS = 60_000;

	/**
	 * Permissions of a downloaded archive: readable by its group, as under the application's
	 * usual umask, so an extraction run as another member of the group can read it.
	 */
	private static final Set<PosixFilePermission> ARCHIVE_PERMISSIONS = PosixFilePermissions.fromString("rw-r-----");

	/** Disconnects connections still open at their download's deadline. */
	private static final ScheduledExecutorService DEADLINE_WATCHDOG = Executors.newSingleThreadScheduledExecutor(
			task -> {
				Thread thread = new Thread(task, "archive-download-deadline");
				thread.setDaemon(true);
				return thread;
			});

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
			return standard(MAX_BYTES);
		}

		/**
		 * The policy for downloads requested by users, with a caller's own size cap.
		 *
		 * @param maxBytes the largest archive the caller accepts
		 */
		static Policy standard(long maxBytes) {
			return new Policy(EnvironmentConfig.isUrlDownloadPrivateNetworksAllowed(), Set.of(), MAX_REDIRECTS,
					maxBytes, DEADLINE_MILLIS, Util.CONNECT_TIMEOUT_MS, READ_TIMEOUT_MILLIS);
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
	 * <p>The body is written to a temporary file beside the destination and moved into place only
	 * once it is complete. A declared length over {@code policy.maxBytes} is refused before
	 * reading; a body that grows past it is abandoned and its temporary file deleted. A download
	 * still running at {@code policy.deadlineMillis}, whether in its headers or its body, is
	 * disconnected and abandoned the same way.
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
		long started = System.nanoTime();
		try {
			Path target = fileInItsDirectory(destination);
			URL current = url;
			for (int redirects = 0; ; redirects++) {
				checkDestination(current, policy);
				HttpURLConnection connection = (HttpURLConnection) current.openConnection();
				AtomicBoolean pastDeadline = new AtomicBoolean();
				ScheduledFuture<?> watchdog = DEADLINE_WATCHDOG.schedule(() -> {
					pastDeadline.set(true);
					connection.disconnect();
				}, remainingMillis(started, policy), TimeUnit.MILLISECONDS);
				try {
					connection.setInstanceFollowRedirects(false);
					connection.setConnectTimeout(timeout(policy.connectTimeoutMillis, started, policy));
					connection.setReadTimeout(timeout(policy.readTimeoutMillis, started, policy));
					int status = connection.getResponseCode();
					if (isRedirect(status)) {
						URL next = redirectTarget(current, connection.getHeaderField("Location"));
						if (next == null) {
							throw new Refused("download answered HTTP " + status + " without a usable Location");
						}
						if (redirects >= policy.maxRedirects) {
							throw new Refused("download redirected more than " + policy.maxRedirects + " times");
						}
						if (!isPermittedRedirect(current, next)) {
							throw new Refused("refusing a redirect from " + current.getProtocol() + " to "
									+ next.getProtocol());
						}
						current = next;
						continue;
					}
					if (status < 200 || status > 299) {
						closeErrorStream(connection);
						throw new Refused("download answered HTTP " + status);
					}
					copyBounded(connection, target, policy, started);
					return true;
				} catch (IOException e) {
					if (pastDeadline.get()) {
						throw new Refused("download did not finish within " + policy.deadlineMillis + " ms");
					}
					throw e;
				} finally {
					watchdog.cancel(false);
					connection.disconnect();
				}
			}
		} catch (Refused e) {
			log.warn(methodName, e.getMessage());
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

	/** Refuses a URL whose scheme or resolved addresses the policy does not permit. */
	private static void checkDestination(URL url, Policy policy) throws Refused {
		if (!Util.isDownloadableUrl(url)) {
			throw new Refused("refusing to download a URL with scheme " + (url == null ? null : url.getProtocol()));
		}
		String host = url.getHost();
		if (host == null || host.isEmpty()) {
			throw new Refused("refusing to download a URL without a host");
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new Refused("could not resolve download host " + host);
		}
		for (InetAddress address : addresses) {
			if (!isPermittedAddress(address, policy)) {
				throw new Refused("refusing to download from " + host + ", which resolves to a non-public address");
			}
		}
	}

	/**
	 * The destination as a normalized path, refused unless it names a file directly inside its
	 * directory: not ".", "..", or anything that normalizes elsewhere.
	 */
	private static Path fileInItsDirectory(File destination) throws Refused {
		File parent = destination.getAbsoluteFile().getParentFile();
		String name = destination.getName();
		if (parent == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			throw new Refused("the download destination is not a file name");
		}
		Path directory = parent.toPath().normalize();
		Path target = directory.resolve(name).normalize();
		if (!target.startsWith(directory) || !directory.equals(target.getParent())) {
			throw new Refused("the download destination is not a file in its directory");
		}
		return target;
	}

	/** Copies the response body to the target within the policy's size and time limits. */
	private static void copyBounded(HttpURLConnection connection, Path target, Policy policy, long started)
			throws IOException, Refused {
		long declared = connection.getContentLengthLong();
		if (declared > policy.maxBytes) {
			throw new Refused("download declares " + declared + " bytes, over the limit of " + policy.maxBytes);
		}
		Path directory = target.getParent();
		Files.createDirectories(directory);
		Path partial = Files.createTempFile(directory, "." + target.getFileName() + ".", ".part");
		try {
			try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(partial)) {
				byte[] buffer = new byte[64 * 1024];
				long total = 0;
				int read;
				while ((read = in.read(buffer)) != -1) {
					total += read;
					if (total > policy.maxBytes) {
						throw new Refused("download passed the limit of " + policy.maxBytes + " bytes");
					}
					if (elapsedMillis(started) > policy.deadlineMillis) {
						throw new Refused("download did not finish within " + policy.deadlineMillis + " ms");
					}
					out.write(buffer, 0, read);
				}
			}
			if (Files.getFileStore(partial).supportsFileAttributeView("posix")) {
				Files.setPosixFilePermissions(partial, ARCHIVE_PERMISSIONS);
			}
			try {
				Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	/** Releases an error response's body; the connection is disconnected in any case. */
	private static void closeErrorStream(HttpURLConnection connection) {
		try (InputStream body = connection.getErrorStream()) {
			// Only closing it.
		} catch (IOException e) {
			log.debug("closeErrorStream", "could not close an error response: " + e.getMessage());
		}
	}

	/** A connection timeout no longer than the time left before the deadline. */
	private static int timeout(int configuredMillis, long started, Policy policy) throws Refused {
		return (int) Math.min(configuredMillis, remainingMillis(started, policy));
	}

	/** Time left before the deadline; refuses the download once none is left. */
	private static long remainingMillis(long started, Policy policy) throws Refused {
		long remaining = policy.deadlineMillis - elapsedMillis(started);
		if (remaining <= 0) {
			throw new Refused("download did not finish within " + policy.deadlineMillis + " ms");
		}
		return remaining;
	}

	private static long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000;
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

	/** A download the policy does not permit, or that broke one of its limits. */
	private static final class Refused extends Exception {
		Refused(String message) {
			super(message);
		}
	}

	private static InetAddress address(String literal) {
		try {
			return InetAddress.getByName(literal);
		} catch (UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}
}
