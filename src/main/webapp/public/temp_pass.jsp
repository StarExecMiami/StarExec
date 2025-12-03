<%@page contentType="text/html" pageEncoding="UTF-8" %>
<%@page trimDirectiveWhitespaces="true" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>

<star:template title="Password reset" css="accounts/password_reset" js="accounts/temp_pass">
	<main role="main" class="temp-pass-container">
		<section aria-labelledby="temp-pass-heading">
			<h1 id="temp-pass-heading" class="sr-only">Temporary Password Generated</h1>
			<p>A temporary password has been generated for you - you can change it after
				you log in with it.</p>
			<form id="resetForm">
				<fieldset>
					<legend>Password information</legend>
					<table class="shaded" role="presentation">
						<tbody>
						<tr>
							<td class="label"><label for="temp_pass">Temporary password:</label></td>
							<td class="temp-pass-field">
								<input id="temp_pass" type="password" readonly="readonly"
								       value="<c:out value='${pwd}'/>" aria-readonly="true"/>
								<button type="button" id="togglePassword" class="btn btn-secondary" 
								        aria-label="Show password" title="Show password">
									<span class="show-icon"><span class="ui-icon ui-icon-search" aria-hidden="true"></span> Show</span>
									<span class="hide-icon hidden"><span class="ui-icon ui-icon-locked" aria-hidden="true"></span> Hide</span>
								</button>
								<button type="button" id="copyPassword" class="btn btn-secondary"
								        aria-label="Copy to clipboard" title="Copy to clipboard">
									<span class="ui-icon ui-icon-copy" aria-hidden="true"></span> Copy
								</button>
							</td>
						</tr>
						</tbody>
					</table>
					<p class="security-note" role="note">
						<strong>Security notice:</strong> Please change your password after logging in.
						This temporary password will not be shown again if you refresh this page.
					</p>
				</fieldset>
			</form>
		</section>
	</main>
</star:template>
