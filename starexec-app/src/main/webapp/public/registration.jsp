<%@page contentType="text/html" pageEncoding="UTF-8"
        import="org.starexec.constants.DB, org.starexec.data.database.Communities"
        session="false" %>
<%@taglib prefix="star" tagdir="/WEB-INF/tags" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%
    request.setAttribute("coms", Communities.getAll());
    request.setAttribute("firstNameLen", DB.USER_FIRST_LEN);
    request.setAttribute("lastNameLen", DB.USER_LAST_LEN);
    request.setAttribute("institutionLen", DB.INSTITUTION_LEN);
    request.setAttribute("emailLen", DB.EMAIL_LEN);
    request.setAttribute("passwordLen", DB.PASSWORD_LEN);
    request.setAttribute("msgLen", DB.MSG_LEN);
%>

<%
	request.setAttribute("csrfToken", org.starexec.util.CsrfUtil.getOrCreateToken(request));
%>

<star:template title="User Registration"
               css="accounts/registration, components/form"
               js="lib/jquery.validate.min, lib/jquery.validate.password, accounts/registration">
  <main role="main" class="registration-page">
    <!-- Header -->
    <div class="registration-header">
      <div class="container">
        <h1 class="page-title">Create Your Account</h1>
        <p class="page-subtitle">Join the StarExec community</p>
      </div>
    </div>

    <!-- Form Container -->
    <div class="container">
      <div class="registration-form-wrapper">
        <noscript>
          <div class="alert alert--error" role="alert">
            <svg class="alert-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="12" y1="8" x2="12" y2="12"></line>
              <line x1="12" y1="16" x2="12.01" y2="16"></line>
            </svg>
            <span>JavaScript is required for StarExec. Please enable it and reload this page.</span>
          </div>
        </noscript>

        <form method="POST" 
              action="${starexecRoot}/public/registration/manager"
              id="regForm" 
              class="registration-form">
          <input type="hidden" name="csrfToken" value="${csrfToken}"/>
          
          <!-- Personal Information Section -->
          <section class="form-section" aria-labelledby="personal-info-heading">
            <h2 id="personal-info-heading" class="form-section-title">
              <svg class="section-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                <circle cx="12" cy="7" r="4"></circle>
              </svg>
              Personal Information
            </h2>

            <div class="form-grid">
              <div class="form-field">
                <label for="firstname" class="form-label">First Name <span class="required">*</span></label>
                <input 
                  id="firstname" 
                  type="text" 
                  name="fn"
                  class="form-input"
                  maxlength="${firstNameLen}" 
                  required 
                  aria-required="true"
                  autocomplete="given-name"/>
              </div>

              <div class="form-field">
                <label for="lastname" class="form-label">Last Name <span class="required">*</span></label>
                <input 
                  id="lastname" 
                  type="text" 
                  name="ln"
                  class="form-input"
                  maxlength="${lastNameLen}" 
                  required 
                  aria-required="true"
                  autocomplete="family-name"/>
              </div>

              <div class="form-field form-field--full">
                <label for="email" class="form-label">Email Address <span class="required">*</span></label>
                <input 
                  id="email" 
                  type="email" 
                  name="em"
                  class="form-input"
                  maxlength="${emailLen}" 
                  required 
                  aria-required="true"
                  autocomplete="email"/>
                <span class="form-hint">We'll send you a confirmation email</span>
              </div>

              <div class="form-field form-field--full">
                <label for="institution" class="form-label">Institution <span class="required">*</span></label>
                <input 
                  id="institution" 
                  type="text" 
                  name="inst"
                  class="form-input"
                  maxlength="${institutionLen}" 
                  required 
                  aria-required="true"
                  autocomplete="organization"/>
              </div>
            </div>
          </section>

          <!-- Security Section -->
          <section class="form-section" aria-labelledby="security-heading">
            <h2 id="security-heading" class="form-section-title">
              <svg class="section-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
              Security
            </h2>

            <div class="form-grid">
              <div class="form-field form-field--full">
                <label for="password" class="form-label">Password <span class="required">*</span></label>
                <input 
                  id="password" 
                  type="password" 
                  name="pwd"
                  class="form-input"
                  length="${passwordLen}" 
                  required 
                  aria-required="true"
                  aria-describedby="pwd-meter"
                  autocomplete="new-password"/>
                
                <div class="password-meter" id="pwd-meter" aria-live="polite">
                  <div class="password-meter-message" aria-live="assertive"></div>
                  <div class="password-meter-bg">
                    <div class="password-meter-bar"></div>
                  </div>
                </div>
              </div>

              <div class="form-field form-field--full">
                <label for="confirm_password" class="form-label">Confirm Password <span class="required">*</span></label>
                <input 
                  id="confirm_password" 
                  type="password"
                  name="confirm_password"
                  class="form-input"
                  required 
                  aria-required="true"
                  autocomplete="new-password"/>
              </div>
            </div>
          </section>

          <!-- Community Section -->
          <section class="form-section" aria-labelledby="community-heading">
            <h2 id="community-heading" class="form-section-title">
              <svg class="section-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path>
                <circle cx="9" cy="7" r="4"></circle>
                <path d="M23 21v-2a4 4 0 0 0-3-3.87m-4-12a4 4 0 0 1 0 7.75"></path>
              </svg>
              Community Information
            </h2>

            <div class="form-grid">
              <div class="form-field form-field--full">
                <label for="community" class="form-label">Select Community <span class="required">*</span></label>
                <select id="community" name="cm" class="form-select" required aria-required="true">
                  <option value="">Choose a community...</option>
                  <c:forEach var="com" items="${coms}">
                    <option value="${com.id}">${com.name}</option>
                  </c:forEach>
                </select>
              </div>

              <div class="form-field form-field--full">
                <label for="reason" class="form-label">Reason for Joining</label>
                <textarea 
                  id="reason" 
                  name="msg"
                  class="form-textarea"
                  rows="4"
                  length="${msgLen}"
                  placeholder="Tell us why you'd like to join this community..."
                  aria-label="Reason for joining"></textarea>
                <span class="form-hint">Optional but helpful for community leaders</span>
              </div>

              <div class="form-field">
                <div class="form-checkbox">
                  <input type="checkbox" id="uc" name="uc" value="uc">
                  <label for="uc">I'm new to StarExec</label>
                </div>
              </div>
            </div>
          </section>

          <!-- Terms Section -->
          <section class="form-section form-section--bordered">
            <div class="terms-agreement">
              <div class="form-checkbox">
                <input 
                  type="checkbox"
                  id="termsOfService"
                  name="termsOfService"
                  value="termsOfService"
                  required
                  aria-required="true"
                  aria-label="I agree to the Terms of Service and Legal Notice"
                  oninput="$('#submit').prop('disabled', !this.checked);">
                <label for="termsOfService">
                  I have read and agree to the 
                  <a href="/starexec/public/TermsOfService2019.pdf" target="_blank" rel="external">Terms of Service</a>
                  and
                  <a href="https://welcome.miami.edu/privacy-and-legal/index.html" target="_blank" rel="external">Legal Notice</a>
                  <span class="required">*</span>
                </label>
              </div>
            </div>
          </section>

          <!-- Submit Button -->
          <div class="form-actions">
            <button type="submit" id="submit" class="btn btn--primary btn--large" disabled>
              Create Account
            </button>
            <p class="form-footer-text">
              Already have an account? <a href="${starexecRoot}/secure/index.jsp">Sign in here</a>
            </p>
          </div>
        </form>

        <!-- Status Messages -->
        <c:if test="${not empty param.result and param.result == 'regSuccess'}">
          <div class="alert alert--success" role="status">
            <svg class="alert-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
              <polyline points="22 4 12 14.01 9 11.01"></polyline>
            </svg>
            <span>Registration successful! Check your email to activate your account.</span>
          </div>
        </c:if>
        
        <c:if test="${not empty param.result and param.result == 'regFail'}">
          <div class="alert alert--error" role="alert">
            <svg class="alert-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="15" y1="9" x2="9" y2="15"></line>
              <line x1="9" y1="9" x2="15" y2="15"></line>
            </svg>
            <span>Registration failed. A user with this email already exists.</span>
          </div>
        </c:if>
      </div>
    </div>
  </main>
</star:template>
