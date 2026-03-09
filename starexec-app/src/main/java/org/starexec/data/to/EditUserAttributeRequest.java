package org.starexec.data.to;

/**
 * Request body for POST /edit/user/{userId}: attribute to update and its new value.
 * Used to avoid sending PII or arbitrary user data in the URL path.
 */
public class EditUserAttributeRequest {
	private String attribute;
	private String value;

	public String getAttribute() {
		return attribute;
	}

	public void setAttribute(String attribute) {
		this.attribute = attribute;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
