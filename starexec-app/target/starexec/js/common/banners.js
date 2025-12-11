/**
 * Banners Utility Module
 * Provides a centralized system for managing persistent notification banners
 * Extends the Alerts utility with banner-specific behavior like session persistence,
 * auto-initialization, and lifecycle management
 *
 * CENTRALIZED ALERTING: This module is part of the unified alerting system
 * It provides patterns and utilities for creating specialized banners while
 * delegating core alert functionality to the Alerts module
 */

(function(window) {
	'use strict';

	// Ensure Alerts module is available
	if (!window.Alerts) {
		console.error('Banners utility requires Alerts module to be loaded first');
		return;
	}

	var Banners = {
		/**
		 * Registry of active banners
		 * @type {Object}
		 */
		_registry: {},

		/**
		 * Create a new banner instance
		 * Banners are persistent notifications with session storage awareness
		 * @param {object} config - Banner configuration
		 * @param {string} config.id - Unique identifier for the banner
		 * @param {string} config.type - Banner type: 'success', 'error', 'warning', 'info'
		 * @param {string} config.title - Banner title text
		 * @param {string} config.message - Banner message (can contain HTML)
		 * @param {string} config.icon - Optional SVG or icon HTML
		 * @param {string} config.storageKey - Session storage key for dismissal state
		 * @param {boolean} config.dismissible - Whether banner can be dismissed (default: true)
		 * @param {string} config.role - ARIA role (default: 'status')
		 * @returns {object} Banner instance with methods
		 */
		create: function(config) {
			config = config || {};
			var bannerId = config.id || 'banner-' + Date.now();
			var storageKey = config.storageKey || 'banner_dismissed_' + bannerId;

			// Create banner instance
			var bannerInstance = {
				id: bannerId,
				config: config,
				storageKey: storageKey,
				element: null,
				isVisible: false,

				/**
				 * Initialize and show the banner if not previously dismissed
				 * @param {object} options - Initialization options
				 * @param {string} options.targetSelector - Where to insert banner (default: 'body')
				 * @param {boolean} options.autoShow - Whether to auto-show if not dismissed (default: true)
				 * @param {function} options.onShow - Callback when banner is shown
				 * @param {function} options.onDismiss - Callback when banner is dismissed
				 */
				init: function(options) {
					options = options || {};
					this.targetSelector = options.targetSelector || 'body';
					this.onShow = typeof options.onShow === 'function' ? options.onShow : null;
					this.onDismiss = typeof options.onDismiss === 'function' ? options.onDismiss : null;

					// Check if previously dismissed
					if (this.wasDismissed()) {
						return;
					}

					// Auto-show if configured
					if (options.autoShow !== false) {
						this.show();
					}
				},

				/**
				 * Show the banner
				 */
				show: function() {
					// Return if already visible
					if (this.isVisible && this.element) {
						this.element.style.display = '';
						return;
					}

					// Create banner element using Alerts utility
					this.element = Alerts.create({
						id: this.id,
						type: this.config.type || 'info',
						title: this.config.title,
						message: this.config.message,
						icon: this.config.icon,
						dismissible: this.config.dismissible !== false,
						role: this.config.role || 'status'
					});

					// Add accessibility attributes for banners
					if (this.config.role === 'status') {
						this.element.setAttribute('aria-live', 'polite');
						this.element.setAttribute('aria-atomic', 'true');
					}

					// Insert into page
					var targetSelector = this.targetSelector || 'body';
					Alerts.insert(this.element, targetSelector, 'prepend');

					// Setup dismiss handler
					var closeBtn = this.element.querySelector('.alert__close');
					if (closeBtn) {
						var self = this;
						closeBtn.onclick = function(e) {
							e.preventDefault();
							self.dismiss();
						};
					}

					this.isVisible = true;

					// Call show callback
					if (this.onShow) {
						this.onShow();
					}
				},

				/**
				 * Hide the banner (without dismissal)
				 */
				hide: function() {
					if (this.element) {
						this.element.style.display = 'none';
						this.isVisible = false;
					}
				},

				/**
				 * Dismiss the banner permanently (for this session)
				 */
				dismiss: function() {
					this.markDismissed();

					if (this.element) {
						// Use Alerts utility for consistent dismissal animation
						Alerts.dismiss(this.id, true, function() {
							// Cleanup
						});
					}

					this.isVisible = false;

					// Call dismiss callback
					if (this.onDismiss) {
						this.onDismiss();
					}
				},

				/**
				 * Update banner content
				 * @param {object} updates - Properties to update (title, message, type)
				 */
				update: function(updates) {
					if (this.element) {
						Alerts.update(this.id, updates);

						// Update local config
						if (updates.type) {
							this.config.type = updates.type;
						}
						if (updates.title) {
							this.config.title = updates.title;
						}
						if (updates.message) {
							this.config.message = updates.message;
						}
					}
				},

				/**
				 * Mark banner as dismissed in session storage
				 */
				markDismissed: function() {
					try {
						sessionStorage.setItem(this.storageKey, 'true');
					} catch (e) {
						console.warn('Unable to mark banner as dismissed: ' + e.message);
					}
				},

				/**
				 * Check if banner was previously dismissed this session
				 * @returns {boolean}
				 */
				wasDismissed: function() {
					try {
						return sessionStorage.getItem(this.storageKey) === 'true';
					} catch (e) {
						return false;
					}
				},

				/**
				 * Clear dismissal state (allow banner to show again)
				 */
				clearDismissed: function() {
					try {
						sessionStorage.removeItem(this.storageKey);
					} catch (e) {
						console.warn('Unable to clear banner dismissal: ' + e.message);
					}
				},

				/**
				 * Check if banner should be visible based on conditions
				 * @returns {boolean}
				 */
				shouldShow: function() {
					return !this.wasDismissed();
				}
			};

			// Register banner
			this._registry[bannerId] = bannerInstance;

			return bannerInstance;
		},

		/**
		 * Get a banner instance by ID
		 * @param {string} bannerId - The banner ID
		 * @returns {object|null} Banner instance or null if not found
		 */
		get: function(bannerId) {
			return this._registry[bannerId] || null;
		},

		/**
		 * Remove a banner from registry
		 * @param {string} bannerId - The banner ID
		 */
		remove: function(bannerId) {
			var banner = this._registry[bannerId];
			if (banner) {
				banner.dismiss();
				delete this._registry[bannerId];
			}
		},

		/**
		 * Get all active banners
		 * @returns {Array} Array of banner instances
		 */
		getAll: function() {
			var banners = [];
			for (var id in this._registry) {
				if (this._registry.hasOwnProperty(id)) {
					banners.push(this._registry[id]);
				}
			}
			return banners;
		},

		/**
		 * Hide all banners
		 */
		hideAll: function() {
			for (var id in this._registry) {
				if (this._registry.hasOwnProperty(id)) {
					this._registry[id].hide();
				}
			}
		},

		/**
		 * Show all non-dismissed banners
		 */
		showAll: function() {
			for (var id in this._registry) {
				if (this._registry.hasOwnProperty(id)) {
					var banner = this._registry[id];
					if (banner.shouldShow()) {
						banner.show();
					}
				}
			}
		},

		/**
		 * Clear all dismissals
		 */
		clearAllDismissed: function() {
			for (var id in this._registry) {
				if (this._registry.hasOwnProperty(id)) {
					this._registry[id].clearDismissed();
				}
			}
		}
	};

	// Export to global scope
	window.Banners = Banners;

})(window);
