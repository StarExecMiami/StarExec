"use strict";

jQuery(function($) {
	/**
	 * Configuration constants
	 */
	const CONFIG = {
		API_ENDPOINT: starexecRoot + "services/analytics",
		TIMEOUT: 10000,
		MAX_RETRIES: 3,
		RETRY_DELAY: 1000, // ms
		RETRY_BACKOFF: 2, // exponential factor
		PRESET_SUBMIT_DELAY: 300, // ms - Allow UI to update before auto-submit
	};

	/**
	 * Date preset definitions
	 */
	const DATE_PRESETS = {
		today: {
			label: "Today",
			getValue: () => ({
				start: new Date(),
				end: new Date(),
			}),
		},
		last7days: {
			label: "Last 7 Days",
			getValue: () => {
				const end = new Date();
				const start = new Date(end);
				start.setDate(start.getDate() - 7);
				return { start, end };
			},
		},
		last30days: {
			label: "Last 30 Days",
			getValue: () => {
				const end = new Date();
				const start = new Date(end);
				start.setDate(start.getDate() - 30);
				return { start, end };
			},
		},
		last90days: {
			label: "Last 90 Days",
			getValue: () => {
				const end = new Date();
				const start = new Date(end);
				start.setDate(start.getDate() - 90);
				return { start, end };
			},
		},
	};

	/**
	 * DataTable configuration
	 */
	const resultsTableConfig = new window.star.DataTableConfig({
		paging: false,
		searching: false,
		info: false,
		columns: [
			{
				title: "Event",
				data: "event",
				className: "dt-left",
			},
			{
				title: "Users",
				data: "users",
				width: "120px",
				className: "dt-right",
			},
			{
				title: "Count",
				data: "count",
				width: "120px",
				className: "dt-right",
			},
		],
	});

	// Initialize DataTable
	const resultsTable = $("#analytics_results").DataTable(
		resultsTableConfig
	);

	/**
	 * DOM element cache
	 */
	const elements = {
		$form: $("#dateselector"),
		$startField: $("#dateselector [name='start']"),
		$endField: $("#dateselector [name='end']"),
		$submitBtn: $("#dateselector input[type='submit']"),
		$errorContainer: $("#analytics_error"),
		$infoContainer: $("#analytics_info"),
		$loadingSpinner: $("#analytics_loading"),
	};

	/**
	 * Escapes HTML special characters to prevent XSS
	 * @param {string} text - Text to escape
	 * @returns {string} Escaped text safe for DOM insertion
	 */
	const escapeHtml = (text) => {
		const map = {
			"&": "&amp;",
			"<": "&lt;",
			">": "&gt;",
			'"': "&quot;",
			"'": "&#039;",
		};
		return String(text).replace(/[&<>"']/g, (char) => map[char]);
	};

	/**
	 * Formats a Date object to YYYY-MM-DD string for HTML5 date input
	 * @param {Date} date - Date to format
	 * @returns {string} Formatted date string
	 */
	const formatDateForInput = (date) => {
		const year = date.getFullYear();
		const month = String(date.getMonth() + 1).padStart(2, "0");
		const day = String(date.getDate()).padStart(2, "0");
		return `${year}-${month}-${day}`;
	};

	/**
	 * Sets loading state for form and table
	 * @param {boolean} isLoading - Whether to show loading state
	 */
	const setLoading = (isLoading) => {
		elements.$submitBtn.prop("disabled", isLoading);
		elements.$startField.prop("disabled", isLoading);
		elements.$endField.prop("disabled", isLoading);

		if (isLoading) {
			elements.$form.addClass("loading");
			if (elements.$loadingSpinner.length) {
				elements.$loadingSpinner.show().attr("aria-hidden", false);
			}
		} else {
			elements.$form.removeClass("loading");
			if (elements.$loadingSpinner.length) {
				elements.$loadingSpinner.hide().attr("aria-hidden", true);
			}
		}
	};

	/**
	 * Displays error message to user
	 * @param {string} message - Error message to display
	 */
	const showError = (message) => {
		const html = `<div role="alert" class="error-message">${escapeHtml(
			message
		)}</div>`;
		elements.$errorContainer.html(html).show();
		console.error("[Analytics Error]", message);
	};

	/**
	 * Displays informational message to user
	 * @param {string} message - Info message to display
	 */
	const showInfo = (message) => {
		const html = `<div role="status" class="info-message">${escapeHtml(
			message
		)}</div>`;
		elements.$infoContainer.html(html).show();
	};

	/**
	 * Clears all user messages
	 */
	const clearMessages = () => {
		elements.$errorContainer.empty().hide();
		elements.$infoContainer.empty().hide();
	};

	/**
	 * Announces message to screen readers using aria-live region
	 * @param {string} message - Message to announce
	 */
	const announceToScreenReader = (message) => {
		const $liveRegion = $("#analytics_live_region");
		if ($liveRegion.length) {
			$liveRegion.text(message);
		}
	};

	/**
	 * Validates date form inputs
	 * @returns {Object} Validation result { valid: boolean, error?: string }
	 */
	const validateDates = () => {
		const startVal = elements.$startField.val().trim();
		const endVal = elements.$endField.val().trim();

		// At least one date must be provided
		if (!startVal && !endVal) {
			return {
				valid: false,
				error: "Please select at least one date.",
			};
		}

		// Both dates must be valid if both provided
		if (startVal && endVal) {
			const startDate = new Date(startVal);
			const endDate = new Date(endVal);

			// Check for invalid dates
			if (isNaN(startDate.getTime())) {
				return {
					valid: false,
					error: "Start date is invalid.",
				};
			}
			if (isNaN(endDate.getTime())) {
				return {
					valid: false,
					error: "End date is invalid.",
				};
			}

			// Start must be before or equal to end
			if (startDate > endDate) {
				return {
					valid: false,
					error: "Start date must be before or equal to end date.",
				};
			}
		}

		return { valid: true };
	};

	/**
	 * Populates DataTable with analytics results
	 * @param {Array} data - Array of analytics event objects
	 */
	const populateTable = (data) => {
		if (!Array.isArray(data) || data.length === 0) {
			resultsTable.clear().draw();
			showInfo("No analytics data available for selected dates.");
			announceToScreenReader("No data available.");
			return;
		}

		resultsTable.clear();
		const rows = data.map((item) => [
			escapeHtml(item.event || ""),
			item.users || 0,
			item.count || 0,
		]);
		resultsTable.rows.add(rows).draw();

		const message = `Loaded ${data.length} event(s).`;
		announceToScreenReader(message);
		setLoading(false);
	};

	/**
	 * Handles HTTP error responses
	 * @param {number} status - HTTP status code
	 * @returns {string} User-friendly error message
	 */
	const getErrorMessage = (status) => {
		const messages = {
			400: "Invalid date format. Please use YYYY-MM-DD.",
			403: "You do not have permission to view analytics.",
			404: "Analytics service not found.",
			500: "Server error. Please try again later.",
			504: "Service timeout. Please try again.",
		};
		return messages[status] || "Failed to load analytics. Please try again.";
	};

	/**
	 * Fetches analytics data from API with retry logic
	 * @param {number} retryCount - Current retry attempt (default: 0)
	 */
	const fetchAnalytics = (retryCount = 0) => {
		const validation = validateDates();

		if (!validation.valid) {
			showError(validation.error);
			setLoading(false);
			return;
		}

		clearMessages();
		setLoading(true);

		const payload = {};
		const startVal = elements.$startField.val().trim();
		const endVal = elements.$endField.val().trim();

		if (startVal) {
			payload.start = startVal;
		}
		if (endVal) {
			payload.end = endVal;
		}

		$.ajax({
			url: CONFIG.API_ENDPOINT,
			type: "GET",
			data: payload,
			timeout: CONFIG.TIMEOUT,
			success: (data) => {
				populateTable(data);
				setLoading(false);
			},
			error: (jqXHR, textStatus, errorThrown) => {
				// Handle timeout and network errors with retry
				if (
					(textStatus === "timeout" || textStatus === "error") &&
					retryCount < CONFIG.MAX_RETRIES
				) {
					const delay =
						CONFIG.RETRY_DELAY *
						Math.pow(CONFIG.RETRY_BACKOFF, retryCount);
					console.warn(
						`[Analytics] Retry ${retryCount + 1}/${
							CONFIG.MAX_RETRIES
						} after ${delay}ms`
					);
					setTimeout(() => {
						fetchAnalytics(retryCount + 1);
					}, delay);
					return;
				}

				// Handle specific HTTP status codes
				const errorMessage = getErrorMessage(jqXHR.status);
				showError(errorMessage);
				console.error(
					"[Analytics API Error]",
					`Status: ${jqXHR.status}`,
					`Error: ${textStatus}`,
					errorThrown
				);
				setLoading(false);
			},
		});
	};

	/**
	 * Sets form dates based on preset
	 * @param {string} presetKey - Key of date preset to apply
	 */
	const setDatePreset = (presetKey) => {
		const preset = DATE_PRESETS[presetKey];
		if (!preset) {
			console.warn(`[Analytics] Unknown preset: ${presetKey}`);
			return;
		}

		const { start, end } = preset.getValue();
		elements.$startField.val(formatDateForInput(start));
		elements.$endField.val(formatDateForInput(end));

		const message = `Preset "${preset.label}" applied.`;
		announceToScreenReader(message);
		showInfo(message);

		// Auto-submit after preset selection
		setTimeout(() => {
			elements.$form.trigger("submit");
		}, CONFIG.PRESET_SUBMIT_DELAY);
	};

	/**
	 * Form submission event handler
	 */
	const handleFormSubmit = (event) => {
		event.preventDefault();
		fetchAnalytics();
	};

	/**
	 * Initialize event handlers and first load
	 */
	const init = () => {
		// Form submission
		elements.$form.on("submit", handleFormSubmit);

		// Preset buttons (if they exist in markup)
		$(document).on("click", "[data-analytics-preset]", (e) => {
			const presetKey = $(e.target).data("analytics-preset");
			setDatePreset(presetKey);
		});

		// Initial load
		setTimeout(() => {
			fetchAnalytics();
		}, 100);
	};

	// Start initialization
	init();
});
