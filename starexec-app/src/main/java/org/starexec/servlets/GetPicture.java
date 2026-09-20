package org.starexec.servlets;

import org.apache.commons.io.FileUtils;
import org.starexec.constants.R;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.logger.StarLogger;
import org.starexec.util.Util;
import org.starexec.util.Validator;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Handles the request to get the picture from the file system. If there is such a picture for the request, or else a
 * default one, named as Pic0.jpg is returned.
 *
 * @author Ruoyu Zhang & Todd Elvers
 */
public class GetPicture extends HttpServlet {
	private static final StarLogger log = StarLogger.getLogger(GetPicture.class);

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Wrong type of request.");
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		try {
			// If the request is not valid, then respond with an error
			ValidatorStatusCode status = validateRequest(request);
			if (!status.isSuccess()) {
				//attach the message as a cookie so we don't need to be parsing HTML in StarexecCommand
				response.addCookie(Util.createEncodedCookie(R.STATUS_MESSAGE_COOKIE, status.getMessage()));
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, status.getMessage());
				return;
			}

			// Parsed once, and every name below is built from this int rather than from the
			// parameter. validateRequest has already refused anything that is not a
			// non-negative integer, so this cannot throw; what it adds is that the value put
			// into a path has been through Integer.parseInt, which is where the check and the
			// use stop being in two different methods. It also canonicalises the id: "007"
			// names Pic7, as it already does when a picture is uploaded.
			int id = Integer.parseInt(request.getParameter("Id"));

			// Check what type is the request, and generate file in different folders according to it.
			String defaultPicFilename = "";
			String picFilename = "";
			String pictureDir = R.getPicturePath();
			StringBuilder sb = new StringBuilder();

			if (request.getParameter("type").equals("uthn")) {
				sb.delete(0, sb.length());
				sb.append("users");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_thn.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("users");
			} else if (request.getParameter("type").equals("uorg")) {
				sb.delete(0, sb.length());
				sb.append("users");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_org.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("users");
			} else if (request.getParameter("type").equals("sthn")) {
				sb.delete(0, sb.length());
				sb.append("solvers");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_thn.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("solvers");
			} else if (request.getParameter("type").equals("sorg")) {
				sb.delete(0, sb.length());
				sb.append("solvers");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_org.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("solvers");
			} else if (request.getParameter("type").equals("bthn")) {
				sb.delete(0, sb.length());
				sb.append("benchmarks");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_thn.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("benchmarks");
			} else if (request.getParameter("type").equals("borg")) {
				sb.delete(0, sb.length());
				sb.append("benchmarks");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append("_org.jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("benchmarks");
			} else if (request.getParameter("type").equals("corg")) {
				sb.delete(0, sb.length());
				sb.append("resultCharts");
				sb.append(File.separator);
				sb.append("Pic");
				sb.append(id);
				sb.append(".jpg");

				defaultPicFilename = GetPicture.getDefaultPicture("chart");
			}

			picFilename = sb.toString();

			sb.delete(0, sb.length());
			sb.append(pictureDir);
			sb.append(File.separator);
			sb.append(picFilename);
			File file = new File(sb.toString());

			// If the desired file exists, then the file will return it, or else return the default file Pic0.jpg
			if (!file.exists()) {
				sb.delete(0, sb.length());
				sb.append(pictureDir);
				sb.append(File.separator);
				sb.append(defaultPicFilename);
				file = new File(sb.toString());
			}

			// Return the file in the response.
			try {
				if (!file.exists()) {
					// The defaults ship inside the application, and the Dockerfile copies them
					// into the data directory of the image. A volume mounted over that directory
					// -- every Kubernetes deployment -- hides them, and this used to answer 200
					// with an empty body instead of a picture. Serve the bundled copy, and say
					// so honestly when there is none.
					serveBundledDefault(defaultPicFilename, response);
					return;
				}
				java.io.OutputStream os = response.getOutputStream();
				FileUtils.copyFile(file, os);
			} catch (Exception e) {
				log.warn("User: " + System.getProperty("user.name") + "\nCan Read: " + file.canRead() + "\nExists: " + file.exists());
				log.warn("picture with path " + file.getAbsolutePath() + " could not be found", e);
			}
		} catch (Exception e) {
			log.warn("Caught Exception in GetPicture.doGet", e);
			throw e;
		}
	}

	/** Where the default pictures sit on the application's classpath. */
	private static final String BUNDLED_DEFAULTS = "static/default-pics/default-pics/";

	/**
	 * Writes the default picture bundled with the application, or a 404 when none is bundled
	 * for this kind of picture (result charts have no default).
	 *
	 * @param defaultPicFilename the default's path relative to the picture directory
	 * @param response the response to write to
	 */
	private static void serveBundledDefault(String defaultPicFilename, HttpServletResponse response)
			throws IOException {
		String resource = BUNDLED_DEFAULTS + defaultPicFilename.replace(File.separatorChar, '/');
		try (InputStream bundled = GetPicture.class.getClassLoader().getResourceAsStream(resource)) {
			if (bundled == null) {
				response.sendError(HttpServletResponse.SC_NOT_FOUND, "No such picture.");
				return;
			}
			bundled.transferTo(response.getOutputStream());
		}
	}

	/**
	 * Validates the GetPicture request to make sure the requested data is of the right format
	 *
	 * @param request The request need to be validated.
	 * @return true if the request is valid.
	 */
	private static ValidatorStatusCode validateRequest(HttpServletRequest request) {
		try {
			if (!Util.paramExists("type", request)) {
				return new ValidatorStatusCode(false, "The supplied type is not valid");
			}

			if (!Validator.isValidPosInteger(request.getParameter("Id"))) {
				return new ValidatorStatusCode(false, "The supplied id is not a valid integer");
			}

			if (!(request.getParameter("type").equals("uthn") || request.getParameter("type").equals("uorg") ||
					request.getParameter("type").equals("sthn") || request.getParameter("type").equals("sorg") ||
					request.getParameter("type").equals("bthn") || request.getParameter("type").equals("borg") ||
					request.getParameter("type").equals("corg"))) {
				return new ValidatorStatusCode(false, "The supplied type is not valid");
			}

			return new ValidatorStatusCode(true);
		} catch (Exception e) {
			log.warn(e.getMessage(), e);
		}

		return new ValidatorStatusCode(false, "Internal error getting image");
	}

	/**
	 * Gets the path of the default picture for a particular primitive
	 *
	 * @param primType the type of primitive whose default picture we need
	 * @return the default picture of the specified primitive type
	 * @author Todd Elvers
	 */
	private static String getDefaultPicture(String primType) {
		StringBuilder sb = new StringBuilder();

		switch (primType.charAt(0)) {
			case 'u':
				sb.append("users");
				sb.append(File.separator);
				sb.append("Pic0.jpg");
				break;
			case 'b':
				sb.append("benchmarks");
				sb.append(File.separator);
				sb.append("Pic0.jpg");
				break;
			case 's':
				sb.append("solvers");
				sb.append(File.separator);
				sb.append("Pic0.jpg");
				break;
			case 'c':
				sb.append("resultCharts");
				sb.append(File.separator);
				sb.append("Pic0.jpg");
				break;
		}

		return sb.toString();
	}
}
