package org.starexec.exceptions;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Response.ResponseBuilder;

/**
 * Exceptions that can be thrown from JAX-RS methods.
 * Missing and forbidden resources use HTTP 404. Internal failures use HTTP 500.
 */
public class RESTException extends WebApplicationException {
	private RESTException() throws UnsupportedOperationException {
		throw new UnsupportedOperationException("Cannot instantiate class because it is static.");
	}

	private RESTException(Status status, String message) {
		super(buildResponse(status, message));
	}

	private static Response buildResponse(Status status, String message) {
		ResponseBuilder rb = Response.status(status);
		rb.type("text/plain");
		rb.entity(message);
		return rb.build();
	}

	public static final RESTException NOT_FOUND = new RESTException(Status.NOT_FOUND, "not available");
	public static final RESTException FORBIDDEN = new RESTException(Status.NOT_FOUND, "not available");
	public static final RESTException INTERNAL_SERVER_ERROR = new RESTException(Status.INTERNAL_SERVER_ERROR, "not available");
}
