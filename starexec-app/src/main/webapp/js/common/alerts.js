/**
 * Alert Utilities Module
 * Provides reusable functionality for alert/notification management
 * including dismiss, persistence, and accessibility features
 */

(function (window) {
  "use strict";

  // Create the alerts namespace
  var Alerts = {
    /**
     * Dismiss an alert element by ID
     * Supports smooth fade-out animation before removal
     * @param {string} alertId - The ID of the alert element to dismiss
     * @param {boolean} animate - Whether to animate the dismissal (default: true)
     * @param {function} callback - Optional callback after dismissal
     */
    dismiss: function (alertId, animate, callback) {
      var alertEl = document.getElementById(alertId);
      if (!alertEl) {
        console.warn("Alert element not found: " + alertId);
        return;
      }

      var duration = animate !== false ? 150 : 0;
      var performDismiss = function () {
        if (alertEl.parentNode) {
          alertEl.parentNode.removeChild(alertEl);
        }
        if (typeof callback === "function") {
          callback();
        }
      };

      if (animate !== false) {
        alertEl.style.transition = "opacity " + duration + "ms ease-out";
        alertEl.style.opacity = "0";
        setTimeout(performDismiss, duration);
      } else {
        performDismiss();
      }
    },

    /**
     * Create a new alert element with standard structure
     * @param {object} config - Configuration object
     * @param {string} config.id - Unique identifier for the alert
     * @param {string} config.type - Alert type: 'success', 'error', 'warning', 'info'
     * @param {string} config.title - Optional title/strong text
     * @param {string} config.message - Alert message (can contain HTML)
     * @param {boolean} config.dismissible - Whether to show dismiss button (default: true)
     * @param {string} config.icon - Optional SVG or icon HTML
     * @param {string} config.role - ARIA role (default: 'alert')
     * @returns {HTMLElement} The created alert element
     */
    create: function (config) {
      config = config || {};
      var id = config.id || "alert-" + Date.now();
      var type = config.type || "info";
      var dismissible = config.dismissible !== false;
      var role = config.role || "alert";

      // Create main alert container
      var alertDiv = document.createElement("div");
      alertDiv.id = id;
      alertDiv.className = "alert alert--" + type;
      alertDiv.setAttribute("role", role);

      // Add icon if provided
      if (config.icon) {
        var iconSpan = document.createElement("span");
        iconSpan.className = "alert__icon";
        iconSpan.innerHTML = config.icon;
        alertDiv.appendChild(iconSpan);
      }

      // Create content container
      var contentDiv = document.createElement("div");
      contentDiv.className = "alert__content";

      // Add title if provided
      if (config.title) {
        var titleSpan = document.createElement("strong");
        titleSpan.className = "alert__title";
        titleSpan.textContent = config.title;
        contentDiv.appendChild(titleSpan);

        if (config.message) {
          contentDiv.appendChild(document.createTextNode(": "));
        }
      }

      // Add message
      if (config.message) {
        if (
          config.message.indexOf("<") > -1 ||
          config.message.indexOf("&") > -1
        ) {
          // Message contains HTML/entities, use innerHTML
          var msgSpan = document.createElement("span");
          msgSpan.innerHTML = config.message;
          contentDiv.appendChild(msgSpan);
        } else {
          // Plain text, use textContent for safety
          contentDiv.appendChild(document.createTextNode(config.message));
        }
      }

      alertDiv.appendChild(contentDiv);

      // Add dismiss button if needed
      if (dismissible) {
        var closeBtn = document.createElement("button");
        closeBtn.type = "button";
        closeBtn.className = "alert__close";
        closeBtn.setAttribute("aria-label", "Dismiss");
        closeBtn.innerHTML = "&times;";
        closeBtn.onclick = function (e) {
          e.preventDefault();
          Alerts.dismiss(id);
        };
        alertDiv.appendChild(closeBtn);
      }

      return alertDiv;
    },

    /**
     * Insert an alert into the DOM
     * @param {HTMLElement} alertElement - The alert element to insert
     * @param {string|HTMLElement} target - Target element or selector for insertion
     * @param {string} position - Insertion position: 'before', 'after', 'prepend', 'append' (default: 'prepend')
     */
    insert: function (alertElement, target, position) {
      position = position || "prepend";
      var targetEl =
        typeof target === "string" ? document.querySelector(target) : target;

      if (!targetEl) {
        console.warn("Target element not found");
        return;
      }

      if (position === "before") {
        targetEl.parentNode.insertBefore(alertElement, targetEl);
      } else if (position === "after") {
        targetEl.parentNode.insertBefore(alertElement, targetEl.nextSibling);
      } else if (position === "append") {
        targetEl.appendChild(alertElement);
      } else {
        // prepend (default)
        targetEl.insertBefore(alertElement, targetEl.firstChild);
      }
    },

    /**
     * Show or hide an alert
     * @param {string} alertId - The ID of the alert element
     * @param {boolean} show - Whether to show (true) or hide (false)
     */
    setVisible: function (alertId, show) {
      var alertEl = document.getElementById(alertId);
      if (!alertEl) return;
      alertEl.style.display = show ? "" : "none";
    },

    /**
     * Replace alert message/content
     * @param {string} alertId - The ID of the alert element
     * @param {object} updates - Object with properties to update (title, message, type)
     */
    update: function (alertId, updates) {
      var alertEl = document.getElementById(alertId);
      if (!alertEl) return;

      if (updates.type) {
        alertEl.className = alertEl.className.replace(
          /alert--\w+/,
          "alert--" + updates.type,
        );
      }

      if (updates.title || updates.message) {
        var contentDiv = alertEl.querySelector(".alert-content");
        if (contentDiv) {
          contentDiv.innerHTML = "";
          if (updates.title) {
            var titleSpan = document.createElement("strong");
            titleSpan.textContent = updates.title;
            contentDiv.appendChild(titleSpan);
            if (updates.message) {
              contentDiv.appendChild(document.createTextNode(": "));
            }
          }
          if (updates.message) {
            contentDiv.appendChild(document.createTextNode(updates.message));
          }
        }
      }
    },

    /**
     * Store alert dismissal in session storage
     * Allows alerts to remain dismissed for the session
     * @param {string} alertId - The ID of the alert
     */
    markDismissed: function (alertId) {
      try {
        sessionStorage.setItem("alert_dismissed_" + alertId, "true");
      } catch (e) {
        console.warn("Unable to store dismissal: " + e.message);
      }
    },

    /**
     * Check if an alert was previously dismissed in this session
     * @param {string} alertId - The ID of the alert
     * @returns {boolean} Whether the alert was dismissed
     */
    wasDismissed: function (alertId) {
      try {
        return sessionStorage.getItem("alert_dismissed_" + alertId) === "true";
      } catch (e) {
        return false;
      }
    },

    /**
     * Clear session dismissal for an alert
     * @param {string} alertId - The ID of the alert
     */
    clearDismissed: function (alertId) {
      try {
        sessionStorage.removeItem("alert_dismissed_" + alertId);
      } catch (e) {
        console.warn("Unable to clear dismissal: " + e.message);
      }
    },
  };

  // Export to global scope
  window.Alerts = Alerts;
})(window);
