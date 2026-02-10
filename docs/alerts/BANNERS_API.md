# Banners Utility API Documentation

## Overview

The `Banners` utility extends the `Alerts` system with specialized patterns for **session-persistent notifications**. While `Alerts` handles individual, transient notifications, `Banners` manages longer-lived notifications that remember dismissal state throughout a user's session.

### Use Cases

- **Informational banners** - Tips, announcements, onboarding messages
- **Status updates** - Maintenance notices, server status
- **User preferences** - Settings reminders, upgrade suggestions
- **Educational content** - Feature highlights, help prompts
- **Security notices** - Password upgrade notifications, permission changes

### Key Differences from Alerts

| Feature | Alerts | Banners |
|---------|--------|---------|
| **Persistence** | Auto-dismiss after duration | Remains until dismissed |
| **Session Memory** | Optional dismissal tracking | Built-in session storage |
| **Auto-show** | Manual insertion | Can auto-show on init |
| **Registry** | None | Maintains registry of active banners |
| **Callbacks** | Limited | Rich lifecycle callbacks |
| **Use Case** | Transient notifications | Long-term messages |

---

## Loading the Banners Utility

The `Banners` utility depends on `Alerts` being loaded first:

```javascript
// Check both are available
if (window.Alerts && window.Banners) {
  // Safe to use Banners
}
```

**File Location:** `src/main/webapp/js/common/banners.js`
**Dependency:** `src/main/webapp/js/common/alerts.js` (must load first)

---

## API Reference

### `Banners.create(config)`

Creates a new banner instance with persistence features. Returns a banner object with lifecycle methods.

#### Parameters

```typescript
config: {
  id: string                           // Unique identifier (required)
  type?: 'success' | 'error' | 'warning' | 'info'  // Variant (default: 'info')
  title?: string                       // Optional title text
  message: string                      // Banner message content
  icon?: string | HTMLElement          // Optional SVG or icon HTML
  dismissible?: boolean                // Show close button (default: true)
  storageKey?: string                  // Session storage key for dismissal
  role?: string                        // ARIA role (default: 'status')
}
```

#### Returns

`BannerInstance` - Object with methods for lifecycle management

#### Example

```javascript
// Create a maintenance notice banner
var maintenanceBanner = Banners.create({
  id: 'maintenance-notice',
  type: 'warning',
  title: 'Scheduled Maintenance',
  message: 'StarExec will be offline from 2-4 AM EST for system updates.',
  dismissible: true,
  storageKey: 'banner_maintenance_2025_01'
});

// Create an onboarding tip
var onboardingBanner = Banners.create({
  id: 'onboarding-tips',
  type: 'info',
  message: 'Tip: Use keyboard shortcuts to navigate faster. <a href="/help/keyboard">Learn more</a>',
  dismissible: true
});

// Create a security notice
var securityBanner = Banners.create({
  id: 'password-upgrade',
  type: 'warning',
  title: 'Security Update Available',
  message: 'Your account is using an older password format. <a href="/settings/password">Upgrade now</a>',
  role: 'alert'  // Use 'alert' for critical notices
});
```

---

## Banner Instance API

Once created, a banner instance has the following methods:

### `bannerInstance.init(options)`

Initialize and optionally display the banner. Call this after `Banners.create()`.

#### Parameters

```typescript
options: {
  targetSelector?: string              // Where to insert (default: 'body')
  autoShow?: boolean                   // Auto-show if not dismissed (default: true)
  onShow?: function                    // Callback when banner is shown
  onDismiss?: function                 // Callback when banner is dismissed
}
```

#### Example

```javascript
var banner = Banners.create({
  id: 'welcome-banner',
  type: 'info',
  message: 'Welcome to StarExec!'
});

banner.init({
  targetSelector: '#main-content',
  autoShow: true,
  onShow: function() {
    console.log('Banner displayed to user');
    // Track analytics, etc.
  },
  onDismiss: function() {
    console.log('User dismissed banner');
    // Clean up resources
  }
});
```

---

### `bannerInstance.show()`

Display the banner on the page. Called automatically by `init()` if `autoShow: true`.

#### Example

```javascript
var banner = Banners.create({
  id: 'alert-banner',
  type: 'info',
  message: 'New features available'
});

// Initialize without auto-show
banner.init({ autoShow: false });

// Show later based on user action
document.getElementById('feature-button').addEventListener('click', function() {
  banner.show();
});
```

---

### `bannerInstance.hide()`

Hide the banner without marking it as dismissed. User can see it again if page reloads.

#### Example

```javascript
// Hide banner temporarily
banner.hide();

// Show it again later
banner.show();

// But mark as dismissed if you want permanence
banner.dismiss();
```

---

### `bannerInstance.dismiss()`

Remove the banner from DOM and mark as dismissed for the session. User won't see it again until session ends.

#### Example

```javascript
// When close button is clicked (automatic)
// Or manually dismiss
banner.dismiss();

// Or with timeout
setTimeout(function() {
  banner.dismiss();
}, 5000);
```

---

### `bannerInstance.update(updates)`

Change banner content without recreating it.

#### Parameters

```typescript
updates: {
  title?: string        // New title
  message?: string      // New message
  type?: string         // New type (success, error, warning, info)
}
```

#### Example

```javascript
var banner = Banners.create({
  id: 'status-banner',
  type: 'info',
  message: 'Checking status...'
});
banner.init();

// Update as status changes
fetch('/api/status')
  .then(response => response.json())
  .then(data => {
    if (data.healthy) {
      banner.update({
        type: 'success',
        message: 'All systems operational'
      });
    } else {
      banner.update({
        type: 'error',
        message: 'Issues detected: ' + data.issues
      });
    }
  });
```

---

### Session Persistence Methods

#### `bannerInstance.markDismissed()`

Mark banner as dismissed in sessionStorage.

```javascript
banner.markDismissed();  // User won't see it again this session
```

#### `bannerInstance.wasDismissed()`

Check if banner was dismissed in current session.

```javascript
if (banner.wasDismissed()) {
  console.log('User already dismissed this banner');
} else {
  banner.show();
}
```

#### `bannerInstance.clearDismissed()`

Clear dismissal state, allowing banner to show again.

```javascript
banner.clearDismissed();  // Reset session memory
banner.show();
```

#### `bannerInstance.shouldShow()`

Check if banner should be displayed based on dismissal state.

```javascript
if (banner.shouldShow()) {
  banner.show();
}
```

---

## Registry Methods

### `Banners.get(bannerId)`

Retrieve a banner instance by ID.

#### Example

```javascript
var banner = Banners.get('welcome-banner');
if (banner && banner.shouldShow()) {
  banner.show();
}
```

---

### `Banners.remove(bannerId)`

Remove a banner from the registry and DOM.

#### Example

```javascript
// Clean up banner
Banners.remove('expired-announcement');
```

---

### `Banners.getAll()`

Get all registered banner instances.

#### Example

```javascript
var allBanners = Banners.getAll();
console.log('Active banners:', allBanners.length);

// Hide all banners
allBanners.forEach(function(banner) {
  banner.hide();
});
```

---

### `Banners.hideAll()`

Hide all banners without marking as dismissed.

#### Example

```javascript
// Temporarily hide all banners
Banners.hideAll();

// Later, show them again
Banners.showAll();
```

---

### `Banners.showAll()`

Show all non-dismissed banners.

#### Example

```javascript
// Show all banners that haven't been dismissed
Banners.showAll();
```

---

### `Banners.clearAllDismissed()`

Reset dismissal state for all banners in current session.

#### Example

```javascript
// Reset banner state for testing or admin purposes
Banners.clearAllDismissed();

// All banners can now be shown again
Banners.showAll();
```

---

## Common Patterns

### Pattern 1: Simple Welcome Banner

```javascript
document.addEventListener('DOMContentLoaded', function() {
  var welcomeBanner = Banners.create({
    id: 'welcome-first-time',
    type: 'info',
    title: 'Welcome to StarExec',
    message: 'New here? Check out our <a href="/getting-started">getting started guide</a>',
    dismissible: true
  });

  welcomeBanner.init({
    autoShow: true,
    targetSelector: '#main-content'
  });
});
```

### Pattern 2: Conditional Banner (Based on User State)

```javascript
// Show upgrade banner only to users with old password format
fetch('/api/user/status')
  .then(response => response.json())
  .then(data => {
    if (data.passwordNeedsUpgrade) {
      var upgradeBanner = Banners.create({
        id: 'password-upgrade',
        type: 'warning',
        title: 'Security Upgrade Available',
        message: 'Strengthen your account security. <a href="/settings/password">Update password</a>',
        dismissible: true
      });

      upgradeBanner.init({
        autoShow: true,
        onDismiss: function() {
          // Track dismissal in analytics
          analytics.track('password_upgrade_dismissed');
        }
      });
    }
  });
```

### Pattern 3: Announcement Banner (Non-dismissible)

```javascript
// Critical announcement that can't be dismissed
var announcement = Banners.create({
  id: 'critical-announcement-2025',
  type: 'error',
  title: 'Important Notice',
  message: 'Please update your profile information by December 31',
  dismissible: false  // User must see this
});

announcement.init();
```

### Pattern 4: Status Monitoring Banner

```javascript
// Banner that updates as system status changes
var statusBanner = Banners.create({
  id: 'system-status',
  type: 'info',
  message: 'Checking system status...',
  dismissible: true
});

statusBanner.init({ autoShow: false });

// Start monitoring
setInterval(function() {
  fetch('/api/status')
    .then(response => response.json())
    .then(data => {
      // Show banner only if there's an issue
      if (data.hasIssues) {
        statusBanner.update({
          type: 'warning',
          message: 'Performance degradation detected: ' + data.message
        });
        statusBanner.show();
      } else {
        statusBanner.hide();
      }
    });
}, 60000);  // Check every minute
```

### Pattern 5: Multi-banner with Lifecycle

```javascript
// Create multiple related banners
var banners = {
  maintenance: Banners.create({
    id: 'scheduled-maintenance',
    type: 'warning',
    title: 'Scheduled Maintenance',
    message: 'System will be down Sunday 2-4 AM EST'
  }),

  backup: Banners.create({
    id: 'backup-in-progress',
    type: 'info',
    message: 'System backup in progress. Performance may be affected.'
  }),

  complete: Banners.create({
    id: 'maintenance-complete',
    type: 'success',
    message: 'Maintenance completed successfully'
  })
};

// Initialize without auto-show
Object.keys(banners).forEach(function(key) {
  banners[key].init({ autoShow: false });
});

// Show them at appropriate times
function startMaintenance() {
  banners.maintenance.show();
  setTimeout(function() {
    banners.backup.show();
  }, 1000);
}

function completeMaintenance() {
  banners.maintenance.dismiss();
  banners.backup.dismiss();
  banners.complete.show();

  setTimeout(function() {
    banners.complete.dismiss();
  }, 5000);
}
```

---

## Accessibility Features

### ARIA Attributes

Banners automatically include accessibility features:

```html
<!-- Status banners for general information -->
<div role="status" aria-live="polite" aria-atomic="true" class="alert alert--info">
  Information message
</div>

<!-- Alert role for critical notices -->
<div role="alert" aria-live="assertive" class="alert alert--error">
  Critical message
</div>
```

### Live Region Announcements

- **Role: `status`** - Polite announcements for informational messages
- **Role: `alert`** - Assertive announcements for critical messages
- **`aria-live="polite"`** - Screen reader waits for natural pause
- **`aria-live="assertive"`** - Screen reader interrupts immediately
- **`aria-atomic="true"`** - Screen reader reads entire message

### Keyboard Navigation

Users can:
- **Tab** to close button
- **Enter/Space** to dismiss
- **Arrow keys** to navigate to links within banner

---

## Security Considerations

### Content Sanitization

Always sanitize user-generated or dynamic content:

```javascript
// ❌ UNSAFE
var banner = Banners.create({
  message: userInput  // Could contain XSS!
});

// ✅ SAFE - Use DOMPurify
var banner = Banners.create({
  message: DOMPurify.sanitize(userInput)
});

// ✅ SAFE - Server-sanitized content
var banner = Banners.create({
  message: serverProvidedMessage  // Already sanitized
});

// ✅ SAFE - Plain text only
var banner = Banners.create({
  message: 'Welcome, ' + userName  // No HTML
});
```

### Storage Security

Session storage is browser-local and cleared when tab closes:

```javascript
// Session storage contents are NOT sent to server
sessionStorage.setItem('banner_dismissed_xyz', 'true');

// Storage is cleared on tab/window close
// Storage is NOT available in private browsing mode
```

---

## Browser Support

| Browser | Support | Notes |
|---------|---------|-------|
| Chrome/Edge | ✅ Full | Modern JavaScript, sessionStorage |
| Firefox | ✅ Full | Modern JavaScript, sessionStorage |
| Safari | ✅ Full | Modern JavaScript, sessionStorage |
| IE 11 | ⚠️ Limited | No template literals, Object methods need polyfills |

---

## Performance Considerations

### Memory Management

Banners are stored in registry - clean up when no longer needed:

```javascript
// After banner is done
Banners.remove('old-announcement');

// Or hide temporarily
banner.hide();

// Use shouldShow() to avoid creating unnecessary banners
if (!banner.wasDismissed()) {
  banner.show();
}
```

### Session Storage Limits

Each browser has limits (~5-10MB per domain):

```javascript
// Be mindful of total banner dismissals
// Each dismissal uses ~50 bytes of storage
// 100 dismissed banners ≈ 5KB of storage
```

### Best Practices

```javascript
// ✅ Good - Reuse banner instances
var banner = Banners.create({...});
banner.init();
// Later...
banner.hide();
banner.show();  // Reuse same instance

// ❌ Avoid - Creating many banner instances
for (var i = 0; i < 1000; i++) {
  Banners.create({...});  // Memory leak!
}
```

---

## Comparison: Banners vs Alerts

### When to use Banners

✅ **Use Banners when:**
- Message should persist until dismissed
- User might miss first presentation
- Need session memory of dismissal
- Multiple banners shown simultaneously
- Lifecycle callbacks needed

### When to use Alerts

✅ **Use Alerts when:**
- Temporary notification (errors, confirmations)
- Auto-dismiss after duration
- Single alert per action
- No session memory needed
- Simple, lightweight solution

---

## Migration Examples

### Old Pattern (jQuery)

```javascript
// Old way
var announcementHtml = '<div class="status-message">Important announcement</div>';
$('body').prepend(announcementHtml);

// Manual dismissal
$('.status-message .exit').click(function() {
  $(this).parent().slideUp().remove();
});
```

### New Pattern (Banners)

```javascript
// New way
var announcement = Banners.create({
  id: 'announcement-1',
  type: 'info',
  message: 'Important announcement',
  dismissible: true
});

announcement.init({
  onDismiss: function() {
    console.log('User dismissed announcement');
  }
});
```

---

## Troubleshooting

### Banner doesn't show

**Problem:** Banner created but not visible.

**Solutions:**

```javascript
// Ensure Alerts is loaded first
if (!window.Alerts) {
  console.error('Alerts not loaded - load it before Banners');
}

// Call init() to show banner
banner.init();  // This shows it if not dismissed

// Or show explicitly
banner.show();

// Check if it's dismissed
if (banner.wasDismissed()) {
  banner.clearDismissed();
  banner.show();
}
```

### Dismissal not persisting

**Problem:** Banner shows again after refresh.

**Note:** SessionStorage only lasts for current tab/window. This is by design.

```javascript
// If you need longer persistence, use localStorage instead:
// (Customize Banners class or create wrapper)

var banner = Banners.create({id: 'persistent'});
banner.init();

// Manually use localStorage
var storageKey = 'banner_dismissed_' + banner.id;
localStorage.setItem(storageKey, 'true');

// Check localStorage before showing
if (!localStorage.getItem(storageKey)) {
  banner.show();
}
```

### Multiple banners overlapping

**Problem:** Banners stack in confusing ways.

**Solutions:**

```javascript
// Specify different targets
banner1.init({ targetSelector: '#alerts-top' });
banner2.init({ targetSelector: '#alerts-secondary' });

// Or use alerts-container
var container = document.createElement('div');
container.className = 'alerts-container';
document.body.insertBefore(container, document.body.firstChild);

banner.init({ targetSelector: container });
```

### Close button not working

**Problem:** Dismiss button doesn't respond.

**Solutions:**

```javascript
// Ensure dismissible is true
var banner = Banners.create({
  dismissible: true  // Required for close button
});

// Check for JavaScript errors in console
// Verify Alerts.dismiss() is working

// Manual dismiss if needed
banner.dismiss();
```

---

## Related Documentation

- **[Alerts API](./ALERTS_API.md)** - Core alert functionality
- **[Password Upgrade Banner](../ALERT_STANDARDIZATION_IMPLEMENTATION.md)** - Real-world example
- **[Migration Guide](../ALERT_STANDARDIZATION_IMPLEMENTATION.md)** - Legacy code updates
- **[CSS Reference](./ALERTS_CSS.md)** - Styling reference

---

## Contributing

Extending Banners functionality:

1. **Preserve backwards compatibility** - Don't break existing banner instances
2. **Follow naming conventions** - Use `banner*` for methods, `_private` for internals
3. **Document new features** - Update this documentation
4. **Test dismissal state** - Verify sessionStorage works
5. **Check accessibility** - Ensure ARIA roles are present

---

## Version History

- **v1.0** (Current) - Initial release with session persistence and lifecycle callbacks
- **Future:** localStorage option for longer persistence, banner queuing system

Last updated: December 2025