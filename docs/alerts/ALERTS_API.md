# Alerts Utility API Documentation

## Overview

The `Alerts` utility provides a centralized, accessible system for displaying notifications, warnings, errors, and status messages throughout the StarExec application. It replaces legacy message systems with a modern, standardized approach featuring:

- ✅ **Consistent Styling** - Unified appearance across all alert types
- ✅ **Accessibility** - ARIA roles, live regions, keyboard navigation
- ✅ **Responsive Design** - Mobile-optimized layouts
- ✅ **Session Persistence** - Dismissal state remembered during session
- ✅ **Animation Support** - Respects user motion preferences
- ✅ **Type Safety** - Proper HTML structure and content sanitization

---

## Loading the Alerts Utility

The `Alerts` utility is loaded globally on all StarExec pages via `GLOBAL_JS_FILES` in `Web.java`:

```javascript
// Alerts is automatically available as window.Alerts
if (window.Alerts) {
  // Safe to use Alerts
}
```

**File Location:** `src/main/webapp/js/common/alerts.js`

---

## API Reference

### `Alerts.create(config)`

Creates a new alert element with the specified configuration. Returns a DOM element that can be inserted into the page.

#### Parameters

```typescript
config: {
  id?: string                          // Unique identifier (auto-generated if omitted)
  type?: 'success' | 'error' | 'warning' | 'info'  // Alert variant (default: 'info')
  title?: string                       // Optional title text (shown in bold)
  message: string | HTMLElement        // Alert message (can contain HTML)
  dismissible?: boolean                // Show close button (default: true)
  icon?: string | HTMLElement          // Optional SVG or HTML icon
  role?: string                        // ARIA role (default: 'alert')
}
```

#### Returns

`HTMLElement` - A configured alert div ready to insert into the DOM.

#### Example

```javascript
// Create a success alert
var successAlert = Alerts.create({
  id: 'user-created-alert',
  type: 'success',
  title: 'Success',
  message: 'User account created successfully',
  dismissible: true,
  icon: '<svg><!-- icon SVG --></svg>'
});

// Create an error alert
var errorAlert = Alerts.create({
  id: 'save-failed-alert',
  type: 'error',
  message: 'Failed to save changes. Please try again.'
});

// Create a warning with HTML content
var warningAlert = Alerts.create({
  id: 'maintenance-alert',
  type: 'warning',
  title: 'Scheduled Maintenance',
  message: 'StarExec will be offline from 2-4 AM. <a href="/status">View details</a>',
  dismissible: true
});
```

---

### `Alerts.insert(alertElement, target, position)`

Inserts an alert element into the DOM at the specified location.

#### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `alertElement` | `HTMLElement` | required | The element returned by `Alerts.create()` |
| `target` | `string\|HTMLElement` | required | CSS selector or element where to insert |
| `position` | `string` | `'prepend'` | Where to insert: `'prepend'`, `'append'`, `'before'`, `'after'` |

#### Position Options

- **`'prepend'`** - Insert as first child of target (default, typically top of page)
- **`'append'`** - Insert as last child of target
- **`'before'`** - Insert before target element (target becomes sibling)
- **`'after'`** - Insert after target element (target becomes sibling)

#### Example

```javascript
var alert = Alerts.create({
  type: 'info',
  message: 'Please fill in all required fields'
});

// Prepend to body (top of page)
Alerts.insert(alert, 'body', 'prepend');

// Append to a specific container
Alerts.insert(alert, '#alert-container', 'append');

// Insert before a form
Alerts.insert(alert, '#myForm', 'before');

// Insert after an element
Alerts.insert(alert, '#content', 'after');
```

---

### `Alerts.dismiss(alertId, animate, callback)`

Removes an alert from the DOM with optional fade animation.

#### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `alertId` | `string` | required | The ID of the alert to dismiss |
| `animate` | `boolean` | `true` | Whether to fade out before removal |
| `callback` | `function` | optional | Executed after dismissal completes |

#### Example

```javascript
// Dismiss with animation (default)
Alerts.dismiss('my-alert-id');

// Dismiss immediately without animation
Alerts.dismiss('my-alert-id', false);

// Dismiss with callback
Alerts.dismiss('my-alert-id', true, function() {
  console.log('Alert removed');
  // Perform cleanup or trigger next action
});
```

---

### `Alerts.update(alertId, updates)`

Updates the content or type of an existing alert without removing it.

#### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `alertId` | `string` | The ID of the alert to update |
| `updates` | `object` | Properties to update |
| `updates.title` | `string` | New title text |
| `updates.message` | `string` | New message content |
| `updates.type` | `string` | New alert type (success, error, warning, info) |

#### Example

```javascript
// Create an alert
var alert = Alerts.create({
  id: 'status-alert',
  type: 'info',
  message: 'Processing...'
});
Alerts.insert(alert, 'body');

// Later, update it
Alerts.update('status-alert', {
  type: 'success',
  message: 'Processing complete!'
});

// Update only the message
Alerts.update('status-alert', {
  message: 'New status: Ready'
});
```

---

### `Alerts.setVisible(alertId, show)`

Shows or hides an alert without removing it from the DOM.

#### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `alertId` | `string` | The ID of the alert |
| `show` | `boolean` | `true` to show, `false` to hide |

#### Example

```javascript
// Hide an alert
Alerts.setVisible('my-alert', false);

// Show it again
Alerts.setVisible('my-alert', true);
```

---

### Session Persistence Methods

These methods manage dismissal state using browser `sessionStorage`. Alerts remain dismissed until the user closes their browser or tab.

#### `Alerts.markDismissed(alertId)`

Marks an alert as dismissed in the current session.

```javascript
Alerts.markDismissed('important-announcement');
```

#### `Alerts.wasDismissed(alertId)`

Checks if an alert was previously dismissed in this session.

```javascript
if (!Alerts.wasDismissed('announcement-1')) {
  // Show alert since it hasn't been dismissed
  var alert = Alerts.create({...});
  Alerts.insert(alert, 'body');
}
```

#### `Alerts.clearDismissed(alertId)`

Clears dismissal state, allowing the alert to be shown again.

```javascript
Alerts.clearDismissed('important-announcement');
// Now wasDismissed() will return false
```

---

## CSS Classes & Structure

### Generated HTML Structure

```html
<div id="alert-123456" class="alert alert--success" role="alert">
  <!-- Optional icon -->
  <span class="alert__icon">
    <svg><!-- icon content --></svg>
  </span>

  <!-- Content container -->
  <div class="alert__content">
    <!-- Optional title -->
    <strong class="alert__title">Success:</strong>
    <!-- Message text -->
    Your changes have been saved successfully.
  </div>

  <!-- Close button (if dismissible) -->
  <button class="alert__close" aria-label="Dismiss">&times;</button>
</div>
```

### CSS Class Reference

| Class | Element | Purpose |
|-------|---------|---------|
| `.alert` | Root | Base alert styling |
| `.alert--success` | Root | Success/positive variant (green) |
| `.alert--error` | Root | Error/negative variant (red) |
| `.alert--warning` | Root | Warning variant (orange) |
| `.alert--info` | Root | Info/neutral variant (blue) |
| `.alert__icon` | Span | Icon container (20x20) |
| `.alert__content` | Div | Message and title wrapper |
| `.alert__title` | Strong | Bolded title text |
| `.alert__close` | Button | Dismiss button with × symbol |

### Responsive Variants

```javascript
// Compact alerts for secondary notifications
Alerts.create({
  message: 'Minor update',
  // Apply manually: add 'alert--compact' class
});

// Spacious alerts for prominent messages
Alerts.create({
  message: 'Important notice',
  // Apply manually: add 'alert--spacious' class
});
```

---

## Common Patterns

### Pattern 1: Form Validation Error

```javascript
function handleFormSubmit(e) {
  e.preventDefault();

  // Validate form
  if (!form.checkValidity()) {
    var alert = Alerts.create({
      id: 'form-error-' + Date.now(),
      type: 'error',
      title: 'Validation Error',
      message: 'Please fill in all required fields',
      dismissible: true
    });
    Alerts.insert(alert, form, 'before');
    return;
  }

  // Submit form...
}
```

### Pattern 2: AJAX Operation Result

```javascript
fetch('/api/user/save', { method: 'POST', body: data })
  .then(response => response.json())
  .then(result => {
    if (result.success) {
      showAlert('success', 'Changes saved successfully');
    } else {
      showAlert('error', 'Failed to save: ' + result.error);
    }
  })
  .catch(err => {
    showAlert('error', 'Network error: ' + err.message);
  });

function showAlert(type, message) {
  var alert = Alerts.create({
    id: 'ajax-alert-' + Date.now(),
    type: type,
    message: message,
    dismissible: true
  });
  Alerts.insert(alert, 'body');

  // Auto-dismiss after 5 seconds
  if (type === 'success') {
    setTimeout(() => {
      Alerts.dismiss(alert.id);
    }, 5000);
  }
}
```

### Pattern 3: Persistent Banner with Session Memory

```javascript
// User preferences banner - show once per session
var preferenceBannerId = 'onboarding-tips';

if (!Alerts.wasDismissed(preferenceBannerId)) {
  var banner = Alerts.create({
    id: preferenceBannerId,
    type: 'info',
    title: 'Welcome',
    message: 'Check out our <a href="/help">help center</a> for tips',
    dismissible: true,
    role: 'status'
  });

  Alerts.insert(banner, '#content', 'prepend');

  // Mark as dismissed when user closes it
  var closeBtn = banner.querySelector('.alert__close');
  closeBtn.addEventListener('click', () => {
    Alerts.markDismissed(preferenceBannerId);
  });
}
```

### Pattern 4: Multiple Alerts Container

```javascript
// Group related alerts together
var container = document.createElement('div');
container.className = 'alerts-container';
document.body.insertBefore(container, document.body.firstChild);

function addAlert(type, message) {
  var alert = Alerts.create({
    id: 'alert-' + Date.now(),
    type: type,
    message: message
  });
  Alerts.insert(alert, container, 'append');
}

addAlert('success', 'Item added');
addAlert('warning', 'Low on storage');
addAlert('error', 'Connection error');
```

---

## Accessibility Features

### ARIA Attributes

Alerts automatically include accessibility features:

```html
<!-- Alert role for screen readers -->
<div role="alert" class="alert alert--error">...</div>

<!-- Status banners use polite announcements -->
<div role="status" aria-live="polite" class="alert alert--info">...</div>

<!-- Close buttons have accessible labels -->
<button aria-label="Dismiss">×</button>
```

### Keyboard Navigation

- **Tab** - Navigate to close button
- **Enter/Space** - Activate close button
- **Escape** - Can be implemented on close button if needed

### Motion Preferences

Animations respect `prefers-reduced-motion`:

```css
/* Animation plays for users with motion enabled */
@media (prefers-reduced-motion: no-preference) {
  .toast { animation: slideIn 0.3s ease-out; }
}

/* Users with motion disabled see instant transitions */
@media (prefers-reduced-motion: reduce) {
  .toast { animation: none; }
}
```

### Color Contrast

All alert variants meet WCAG AA standards (4.5:1 text contrast):

- Success: Green text on light green background
- Error: Red text on light red background
- Warning: Orange text on light orange background
- Info: Blue text on light blue background

---

## Security Considerations

### XSS Prevention

When including user-generated content, sanitize it:

```javascript
// ❌ UNSAFE - Direct user input
Alerts.create({
  message: userInput  // Could contain malicious HTML!
});

// ✅ SAFE - Sanitized with DOMPurify
Alerts.create({
  message: DOMPurify.sanitize(userInput)
});

// ✅ SAFE - Plain text with no HTML
Alerts.create({
  message: 'Hello ' + userName  // Plain string concatenation
});

// ✅ SAFE - Server-rendered safe HTML
Alerts.create({
  message: serverProvidedContent  // Already sanitized on server
});
```

### Content Injection

Use `textContent` for plain text, `innerHTML` only for trusted content:

```javascript
// The Alerts utility handles this internally:
// - Plain text messages use textContent (safe)
// - HTML messages use innerHTML (must be pre-sanitized)
```

---

## Migration from Legacy System

### Old Way (Deprecated)

```javascript
// Legacy showMessage() function
showMessage('error', 'User creation failed', 3000);
```

### New Way (Recommended)

```javascript
// Modern Alerts utility
var alert = Alerts.create({
  id: 'create-user-error',
  type: 'error',
  message: 'User creation failed',
  dismissible: true
});
Alerts.insert(alert, 'body');

// Auto-dismiss after 3 seconds if needed
setTimeout(() => {
  Alerts.dismiss(alert.id);
}, 3000);
```

The `showMessage()` wrapper automatically uses `Alerts.create()` internally, so existing code continues to work while benefiting from new styling.

---

## Browser Support

| Browser | Support | Notes |
|---------|---------|-------|
| Chrome/Edge | ✅ Full | Modern Flexbox, CSS variables, sessionStorage |
| Firefox | ✅ Full | Modern Flexbox, CSS variables, sessionStorage |
| Safari | ✅ Full | Modern Flexbox, CSS variables, sessionStorage |
| IE 11 | ⚠️ Limited | CSS variables not supported, fallbacks needed |

---

## Troubleshooting

### Alert doesn't appear

**Problem:** Created alert doesn't show up on page.

**Solutions:**
```javascript
// Ensure alerts.js is loaded
if (!window.Alerts) {
  console.error('Alerts utility not loaded');
}

// Make sure to insert the element
var alert = Alerts.create({...});
// ✅ Required: Insert into DOM
Alerts.insert(alert, 'body');

// Check target element exists
if (document.querySelector('body')) {
  Alerts.insert(alert, 'body');
}
```

### Style looks wrong

**Problem:** Alert colors or spacing seem off.

**Solutions:**
```javascript
// Check if global CSS is loaded
// alert.scss should be compiled into global.css

// Verify class names - use new BEM structure
.alert__content  // ✅ Correct
.alert-content   // Also works (backwards compatible)

// Check z-index if alert is hidden behind other elements
// Default z-index: 700 (can be overridden)
```

### Close button not working

**Problem:** Dismissing alert doesn't work.

**Solutions:**
```javascript
// Ensure close button exists
var alert = Alerts.create({
  dismissible: true  // ✅ Enable close button
});

// Or dismiss programmatically
Alerts.dismiss(alertId);

// Check for JavaScript errors in console
```

### Session dismissal not working

**Problem:** Alert keeps showing even after dismissal.

**Solutions:**
```javascript
// Ensure sessionStorage is available
if (typeof(Storage) !== "undefined") {
  // sessionStorage is available
}

// Check browser privacy settings aren't blocking storage
// Check if user has disabled cookies/storage

// Manually mark as dismissed if needed
Alerts.markDismissed(alertId);
```

---

## Related Documentation

- **[Banners API](./BANNERS_API.md)** - Persistent banner patterns
- **[Password Upgrade Banner](../ALERT_STANDARDIZATION_IMPLEMENTATION.md#password-upgrade-banner)** - Specialized banner example
- **[CSS Reference](./ALERTS_CSS.md)** - Complete style guide
- **[Migration Guide](../ALERT_STANDARDIZATION_IMPLEMENTATION.md)** - Updating legacy code

---

## Contributing

When adding new alert functionality:

1. **Extend `Alerts` namespace** - Don't create duplicate utilities
2. **Use BEM CSS classes** - Follow `.alert__*` naming convention
3. **Include ARIA attributes** - Ensure screen reader support
4. **Test reduced motion** - Verify animations respect preferences
5. **Document the feature** - Update this API documentation

---

## Version History

- **v2.0** (Current) - Centralized system with Alerts, Banners, and PasswordUpgradeBanner utilities
- **v1.x** (Deprecated) - Legacy showMessage() function and individual message CSS classes

Last updated: December 2025