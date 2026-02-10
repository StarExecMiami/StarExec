# Alert System Implementation Guide

## Overview

This guide provides step-by-step instructions for implementing the centralized alert system in your StarExec code. It covers migration from legacy patterns, best practices, and real-world examples.

---

## Quick Start

### 1. Using the Alerts Utility (Simple Alerts)

For one-off notifications, use `Alerts.create()` and `Alerts.insert()`:

```javascript
// Create and show an alert
var alert = Alerts.create({
  id: 'save-success-1',
  type: 'success',
  title: 'Success',
  message: 'Your changes have been saved',
  dismissible: true
});

Alerts.insert(alert, 'body');  // Insert at top of page

// Auto-dismiss after 3 seconds
setTimeout(() => {
  Alerts.dismiss(alert.id);
}, 3000);
```

### 2. Using the Banners Utility (Persistent Banners)

For messages that should remain visible until dismissed:

```javascript
// Create a persistent banner
var banner = Banners.create({
  id: 'onboarding-tips',
  type: 'info',
  message: 'Welcome! Check out our <a href="/help">help center</a>',
  dismissible: true
});

// Initialize and display
banner.init({
  autoShow: true,
  targetSelector: '#main-content'
});
```

### 3. Migrating from Legacy showMessage()

The old `showMessage()` function still works but now uses the new system:

```javascript
// Old way (still works)
showMessage('error', 'Something went wrong', 3000);

// New way (recommended for new code)
Alerts.create({
  id: 'operation-error-' + Date.now(),
  type: 'error',
  message: 'Something went wrong',
  dismissible: true
});
```

---

## Implementation Patterns

### Pattern 1: Form Validation

```javascript
document.getElementById('myForm').addEventListener('submit', function(e) {
  e.preventDefault();

  // Validate
  const errors = validateForm();
  
  if (errors.length > 0) {
    // Show error alert above form
    const alert = Alerts.create({
      id: 'form-errors-' + Date.now(),
      type: 'error',
      title: 'Validation Error',
      message: errors.join('<br>'),
      dismissible: true
    });
    
    Alerts.insert(alert, this, 'before');
    return;
  }

  // Submit form...
  this.submit();
});
```

### Pattern 2: AJAX Success/Error

```javascript
fetch('/api/user/save', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data)
})
.then(response => response.json())
.then(result => {
  if (result.success) {
    showAlert('success', 'User saved successfully');
  } else {
    showAlert('error', 'Save failed: ' + result.error);
  }
})
.catch(err => {
  showAlert('error', 'Network error: ' + err.message);
});

function showAlert(type, message) {
  const alert = Alerts.create({
    id: 'ajax-alert-' + Date.now(),
    type: type,
    message: message,
    dismissible: true
  });
  
  Alerts.insert(alert, 'body');
  
  // Auto-dismiss on success
  if (type === 'success') {
    setTimeout(() => Alerts.dismiss(alert.id), 4000);
  }
}
```

### Pattern 3: Multiple Related Alerts

```javascript
// Use a container for related alerts
const container = document.createElement('div');
container.className = 'alerts-container';
document.body.insertBefore(container, document.body.firstChild);

function addAlert(type, message) {
  const alert = Alerts.create({
    id: 'alert-' + Date.now() + '-' + Math.random(),
    type: type,
    message: message,
    dismissible: true
  });
  
  Alerts.insert(alert, container, 'append');
}

// Now add multiple alerts
addAlert('success', 'Item 1 processed');
addAlert('success', 'Item 2 processed');
addAlert('warning', 'Item 3 has issues');
```

### Pattern 4: Status Monitoring Banner

```javascript
const statusBanner = Banners.create({
  id: 'system-status',
  type: 'info',
  message: 'Monitoring system status...'
});

statusBanner.init({ autoShow: false });

// Check status periodically
setInterval(() => {
  fetch('/api/system/status')
    .then(r => r.json())
    .then(status => {
      if (status.healthy) {
        statusBanner.hide();
      } else {
        statusBanner.update({
          type: 'warning',
          message: `System degraded: ${status.message}`
        });
        statusBanner.show();
      }
    });
}, 30000);
```

### Pattern 5: Conditional User Education Banner

```javascript
// Show upgrade banner only to users with old password format
fetch('/api/user/info')
  .then(r => r.json())
  .then(user => {
    if (user.passwordFormat === 'legacy') {
      const banner = Banners.create({
        id: 'password-upgrade',
        type: 'warning',
        title: 'Security Update',
        message: 'Your password format is outdated. <a href="/settings/password">Upgrade now</a>',
        dismissible: true
      });

      banner.init({
        autoShow: true,
        onDismiss: () => {
          // Track in analytics
          analytics.track('password_upgrade_banner_dismissed');
        }
      });
    }
  });
```

---

## Updating Legacy Markup

### Old Pattern: Hardcoded Message HTML

**Before:**
```jsp
<div class="success message">
  <img src="${starexecRoot}/images/icons/exclaim.png" />
  <div>User was created successfully</div>
  <span class="exit">X</span>
</div>
```

**After:**
```jsp
<div class="alert alert--success" role="alert">
  <span class="alert__icon" aria-hidden="true">
    <svg><!-- icon SVG --></svg>
  </span>
  <div class="alert__content">
    <strong class="alert__title">Success:</strong>
    User was created successfully
  </div>
  <button class="alert__close" aria-label="Dismiss">&times;</button>
</div>
```

Or better yet, use JavaScript:

```jsp
<script>
document.addEventListener('DOMContentLoaded', function() {
  var successAlert = Alerts.create({
    id: 'user-created-alert',
    type: 'success',
    title: 'Success',
    message: 'User was created successfully',
    dismissible: true
  });
  Alerts.insert(successAlert, 'body');
});
</script>
```

### Old Pattern: jQuery showMessage()

**Before:**
```javascript
// 232+ places in the codebase
showMessage('error', 'Failed to create user', 3000);
showMessage('success', 'User saved', 0);
showMessage('warn', 'Check your input', 5000);
```

**After (still works, but uses new system internally):**
```javascript
// Same call - automatically uses Alerts now
showMessage('error', 'Failed to create user', 3000);

// Or new explicit way:
Alerts.create({
  id: 'create-error-' + Date.now(),
  type: 'error',
  message: 'Failed to create user',
  dismissible: true
});
```

---

## Best Practices

### 1. Always Provide Unique IDs

```javascript
// ✅ Good - Unique ID for targeting
Alerts.create({
  id: 'save-error-' + Date.now(),
  message: 'Save failed'
});

// ❌ Avoid - Auto-generated ID, can't reference later
Alerts.create({
  message: 'Save failed'
});
```

### 2. Sanitize User Content

```javascript
// ❌ UNSAFE - Direct user input
Alerts.create({
  message: userSubmittedText  // Could be XSS!
});

// ✅ SAFE - Sanitized with DOMPurify
Alerts.create({
  message: DOMPurify.sanitize(userSubmittedText)
});

// ✅ SAFE - Plain text only
Alerts.create({
  message: 'Hello, ' + userName  // Plain concatenation
});

// ✅ SAFE - Server-validated HTML
Alerts.create({
  message: serverProvidedContent  // Pre-validated on server
});
```

### 3. Use Appropriate Types

```javascript
// Success - for completed actions
Alerts.create({ type: 'success', message: 'Saved successfully' });

// Error - for failures
Alerts.create({ type: 'error', message: 'Save failed' });

// Warning - for cautions
Alerts.create({ type: 'warning', message: 'This action cannot be undone' });

// Info - for informational messages
Alerts.create({ type: 'info', message: 'Note: This feature is in beta' });
```

### 4. Auto-dismiss Appropriately

```javascript
// ✅ Good - Success alerts can auto-dismiss
const alert = Alerts.create({ type: 'success', message: '...' });
Alerts.insert(alert, 'body');
setTimeout(() => Alerts.dismiss(alert.id), 3000);

// ✅ Good - Errors stay until user dismisses
Alerts.create({ type: 'error', message: '...' });

// ❌ Avoid - Don't auto-dismiss errors!
// Users might miss important error messages
```

### 5. Place Alerts Strategically

```javascript
// Top of page - for critical messages
Alerts.insert(alert, 'body', 'prepend');

// Before form - for validation errors
Alerts.insert(alert, '#myForm', 'before');

// In content area - for contextual messages
Alerts.insert(alert, '#main-content', 'prepend');

// After element - for completion messages
Alerts.insert(alert, '#saved-item', 'after');
```

### 6. Handle Multiple Alerts

```javascript
// Create container for grouping
const container = document.createElement('div');
container.className = 'alerts-container';
document.body.insertBefore(container, document.body.firstChild);

// Add alerts to container instead of body
Alerts.insert(alert, container, 'append');

// Style manages spacing automatically
```

---

## Migration Checklist

Use this checklist when migrating existing alert code:

### Phase 1: Assessment
- [ ] Identify all `showMessage()` calls in codebase
- [ ] Identify all hardcoded alert HTML in JSP files
- [ ] Document alert usage patterns
- [ ] List pages using alerts

### Phase 2: Testing
- [ ] Verify `alerts.js` loads on target pages
- [ ] Test `Alerts` utility manually in console
- [ ] Check CSS is compiled into `global.css`
- [ ] Verify no JavaScript errors

### Phase 3: Migration (Page by Page)
For each page:
- [ ] Replace hardcoded alert HTML with `Alerts.create()`
- [ ] Update form validation to use new alerts
- [ ] Update AJAX handlers to use new alerts
- [ ] Test visual appearance on desktop/mobile
- [ ] Test keyboard navigation
- [ ] Test with screen reader (if applicable)
- [ ] Test on supported browsers

### Phase 4: Deployment
- [ ] Code review all changes
- [ ] Deploy to staging
- [ ] Run integration tests
- [ ] Deploy to production
- [ ] Monitor logs for errors (24-48 hours)

### Phase 5: Cleanup (Later)
- [ ] After successful adoption, mark legacy CSS as deprecated
- [ ] Eventually remove `_message.scss` and `_statusMessage.scss`
- [ ] Remove legacy `showMessageLegacy()` fallback
- [ ] Update documentation

---

## Troubleshooting

### Alert doesn't appear

```javascript
// 1. Check if Alerts is loaded
console.log(window.Alerts);  // Should not be undefined

// 2. Ensure you inserted it into DOM
var alert = Alerts.create({...});
Alerts.insert(alert, 'body');  // Required!

// 3. Check target element exists
if (document.body) {
  Alerts.insert(alert, 'body');
}

// 4. Check browser console for errors
```

### Alert styling looks wrong

```javascript
// 1. Verify CSS is loaded
var styles = getComputedStyle(document.body);
console.log(styles.getPropertyValue('--spacing-lg'));

// 2. Check class names match CSS
// Old: .alert-content
// New: .alert__content
// Both work due to backwards compatibility

// 3. Clear browser cache
// Hard refresh: Ctrl+Shift+R (Windows) or Cmd+Shift+R (Mac)

// 4. Check for CSS conflicts
// Inspect element to see applied styles
```

### Close button doesn't work

```javascript
// 1. Ensure dismissible: true
Alerts.create({
  dismissible: true,  // Required
  message: '...'
});

// 2. Dismiss programmatically
Alerts.dismiss(alertId);

// 3. Check for JavaScript errors in console
```

### Session dismissal not working

```javascript
// 1. Check sessionStorage is available
console.log(typeof(Storage));  // Should be "object"

// 2. Check browser allows storage
// Some browsers in private mode don't support it

// 3. Manually mark as dismissed
Alerts.markDismissed(alertId);

// 4. Check dismissal state
console.log(Alerts.wasDismissed(alertId));
```

---

## Code Review Checklist

When reviewing alert implementations:

- [ ] Unique ID provided (not auto-generated)
- [ ] Appropriate alert type (success/error/warning/info)
- [ ] User content is sanitized
- [ ] Alert is inserted into DOM
- [ ] Auto-dismiss time is appropriate (errors stay, successes can go)
- [ ] Accessibility attributes present (role, aria-label, etc.)
- [ ] No console errors
- [ ] Mobile responsive
- [ ] Keyboard navigable
- [ ] Screen reader friendly

---

## Performance Tips

### Memory Management

```javascript
// ✅ Good - Reuse alert instances
var alert = Alerts.create({...});

// Later, update and reuse
Alerts.update(alert.id, { message: 'New message' });

// When done, dismiss
Alerts.dismiss(alert.id);
```

### Avoiding Memory Leaks

```javascript
// ❌ Bad - Creates many alert instances without cleanup
for (var i = 0; i < 1000; i++) {
  var alert = Alerts.create({...});
  Alerts.insert(alert, 'body');
}

// ✅ Good - Create once, update as needed
var statusAlert = Alerts.create({
  id: 'status',
  message: 'Initial message'
});
Alerts.insert(statusAlert, 'body');

// Update instead of creating new ones
Alerts.update('status', { message: 'Updated message' });
```

### Efficient DOM Insertion

```javascript
// ✅ Good - Insert to container
var container = document.getElementById('alerts');
Alerts.insert(alert, container, 'append');

// ✅ Good - Use selector
Alerts.insert(alert, '#main-content', 'prepend');

// ❌ Avoid - Querying same element multiple times
var body = document.querySelector('body');
Alerts.insert(alert1, body);
Alerts.insert(alert2, body);
```

---

## Browser Compatibility

### Modern Browsers (Full Support)
- Chrome 60+
- Firefox 55+
- Safari 12+
- Edge 79+

### Legacy Browsers (Partial Support)
- IE 11: CSS variables not supported, basic functionality works
- Mobile Safari: Full support

### Graceful Degradation

```javascript
// Check for Alerts availability
if (window.Alerts) {
  // Use new system
  Alerts.create({...});
} else {
  // Fallback to legacy
  showMessageLegacy('error', 'Failed', 3000);
}
```

---

## Advanced Topics

### Custom Icon SVGs

```javascript
Alerts.create({
  type: 'success',
  icon: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" 
         stroke="currentColor" stroke-width="2">
    <polyline points="20 6 9 17 4 12"></polyline>
  </svg>`,
  message: 'Success'
});
```

### HTML Content in Messages

```javascript
// Can include links and basic formatting
Alerts.create({
  type: 'info',
  message: 'Learn more at <a href="/help">our help center</a>',
  dismissible: true
});
```

### Custom Styling

```javascript
// Extend with custom classes
var alert = Alerts.create({
  message: 'Custom alert'
});

// Add additional classes
alert.classList.add('alert--compact', 'custom-style');
```

### Session Persistence

```javascript
// Check if previously dismissed
if (!Alerts.wasDismissed('welcome-banner')) {
  var banner = Alerts.create({
    id: 'welcome-banner',
    message: 'Welcome!'
  });
  Alerts.insert(banner, 'body');
}

// User dismisses it
// It won't show again this session

// Clear dismissal (for testing)
Alerts.clearDismissed('welcome-banner');
```

---

## Resources

- **[Alerts API](./ALERTS_API.md)** - Complete API reference
- **[Banners API](./BANNERS_API.md)** - Persistent banner patterns
- **[CSS Styles](./ALERTS_CSS.md)** - Styling reference and customization
- **[Migration Report](../ALERT_AUDIT_REPORT.md)** - Original audit findings

---

## Getting Help

### Common Questions

**Q: Should I use Alerts or Banners?**
- Use `Alerts` for temporary notifications (errors, confirmations)
- Use `Banners` for persistent messages (announcements, education)

**Q: How do I dismiss an alert programmatically?**
```javascript
Alerts.dismiss(alertId);
```

**Q: Can I update an alert's message?**
```javascript
Alerts.update(alertId, { message: 'New message' });
```

**Q: How do I remember dismissals across sessions?**
- Current system uses `sessionStorage` (cleared when tab closes)
- For longer persistence, use `localStorage` (custom implementation)

**Q: Where do I add custom styles?**
- Override CSS classes or add custom classes to the alert element

### Support Channels

For questions or issues:
1. Check this documentation
2. Review [Alerts API](./ALERTS_API.md)
3. Look at [code examples](./IMPLEMENTATION_GUIDE.md#patterns)
4. Check browser console for errors
5. Contact development team

---

## Version History

- **v2.0** (Current) - Alerts, Banners, and centralized system
- **v1.x** (Deprecated) - Legacy showMessage() function

---

**Last Updated:** December 2025
**Maintained By:** StarExec Development Team