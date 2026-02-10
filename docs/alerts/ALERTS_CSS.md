# Alert System CSS Reference Guide

## Overview

This document provides a complete reference for the alert component styling system. It covers CSS classes, responsive behavior, customization options, and design tokens.

---

## File Structure

```
src/main/webapp/css/
├── components/
│   └── alert.scss          # Main alert component styles
├── global/
│   ├── _colors.scss        # Color tokens
│   ├── _spacing.scss       # Spacing tokens
│   ├── _typography.scss    # Typography tokens
│   ├── _mixins.scss        # SCSS mixins
│   ├── _message.scss       # DEPRECATED: Legacy message styles
│   └── _statusMessage.scss # DEPRECATED: Legacy status message styles
└── global.scss             # Main stylesheet (imports all components)
```

---

## Design Tokens

### Colors

All colors are defined in `src/main/webapp/css/global/_colors.scss`:

```scss
// Alert-specific semantic colors
$success: #10b981;      // Green - positive actions, completion
$error: #ff0000;        // Red - errors, failures, danger
$warning: #fa621b;      // Orange - warnings, cautions, attention
$info: #3b82f6;         // Blue - information, neutral, help
```

### Spacing Scale

Spacing is based on an 8px grid system:

```scss
$spacing-3xs: 0.125rem;  // 2px - micro spacing
$spacing-2xs: 0.25rem;   // 4px - extra small
$spacing-xs:  0.5rem;    // 8px - small
$spacing-sm:  0.75rem;   // 12px - medium-small
$spacing-md:  1rem;      // 16px - medium (base unit)
$spacing-lg:  1.5rem;    // 24px - large
$spacing-xl:  2rem;      // 32px - extra large
$spacing-2xl: 3rem;      // 48px - 2x large
$spacing-3xl: 4rem;      // 64px - 3x large
$spacing-4xl: 6rem;      // 96px - 4x large
```

### Typography

Font sizes and weights:

```scss
$font-size-xs:   0.75rem;    // 12px
$font-size-sm:   0.875rem;   // 14px
$font-size-base: 1rem;       // 16px
$font-size-lg:   1.125rem;   // 18px

$font-weight-normal:    400;
$font-weight-medium:    500;
$font-weight-semibold:  600;
$font-weight-bold:      700;
```

---

## CSS Classes

### Base Alert Class

```css
.alert {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 20px 24px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.5;
  margin-bottom: 24px;
  margin-top: 0;
}
```

**Usage:**
- Applied to all alert elements
- Base styling for layout and spacing
- Responsive padding adjusts on mobile

---

### Alert Type Variants

#### `.alert--success`
```css
.alert--success {
  background: rgba(16, 185, 129, 0.15);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.alert--success .alert__icon {
  color: #10b981;
}
```

**Use for:**
- Successful operations (saved, created, deleted)
- Positive confirmations
- Completion messages

**Example:**
```html
<div class="alert alert--success">
  <span class="alert__icon">✓</span>
  <div class="alert__content">
    <strong class="alert__title">Success:</strong>
    Your changes have been saved
  </div>
</div>
```

---

#### `.alert--error`
```css
.alert--error {
  background: rgba(255, 0, 0, 0.15);
  color: #ff0000;
  border: 1px solid rgba(255, 0, 0, 0.3);
}

.alert--error .alert__icon {
  color: #ff0000;
}
```

**Use for:**
- Errors and failures
- Validation errors
- Blocked operations
- Critical issues

**Example:**
```html
<div class="alert alert--error">
  <span class="alert__icon">!</span>
  <div class="alert__content">
    <strong class="alert__title">Error:</strong>
    Failed to save changes
  </div>
</div>
```

---

#### `.alert--warning`
```css
.alert--warning {
  background: rgba(250, 98, 27, 0.15);
  color: #fa621b;
  border: 1px solid rgba(250, 98, 27, 0.3);
}

.alert--warning .alert__icon {
  color: #fa621b;
}
```

**Use for:**
- Warnings and cautions
- Irreversible actions
- System maintenance notices
- Attention-required items

**Example:**
```html
<div class="alert alert--warning">
  <span class="alert__icon">⚠</span>
  <div class="alert__content">
    <strong class="alert__title">Warning:</strong>
    This action cannot be undone
  </div>
</div>
```

---

#### `.alert--info`
```css
.alert--info {
  background: rgba(59, 130, 246, 0.15);
  color: #3b82f6;
  border: 1px solid rgba(59, 130, 246, 0.3);
}

.alert--info .alert__icon {
  color: #3b82f6;
}
```

**Use for:**
- Informational messages
- General announcements
- Help text
- Tips and notes

**Example:**
```html
<div class="alert alert--info">
  <span class="alert__icon">ℹ</span>
  <div class="alert__content">
    <strong class="alert__title">Info:</strong>
    This feature is currently in beta
  </div>
</div>
```

---

### Element Classes (BEM Structure)

#### `.alert__icon`
```css
.alert__icon {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  margin-top: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
}
```

**Purpose:** Container for alert icon (SVG or emoji)
**Sizing:** 20×20px (fixed, non-shrinking)
**Alignment:** Aligned with first line of text

**Example:**
```html
<span class="alert__icon">
  <svg width="20" height="20"><!-- SVG content --></svg>
</span>
```

---

#### `.alert__content`
```css
.alert__content {
  flex: 1;
  padding: 0;
  word-wrap: break-word;
  overflow-wrap: break-word;
}
```

**Purpose:** Main content container for title and message
**Flex:** Takes remaining space after icon and close button
**Text wrapping:** Handles long messages gracefully

**Example:**
```html
<div class="alert__content">
  <strong class="alert__title">Title:</strong>
  Message text here
</div>
```

---

#### `.alert__title`
```css
.alert__title {
  font-weight: 600;  /* semibold */
  margin-bottom: 8px;
  margin-top: 0;
  display: inline;
}
```

**Purpose:** Optional bold title/heading
**Font weight:** Semibold (600) for emphasis
**Spacing:** 8px below title if followed by message

**Example:**
```html
<strong class="alert__title">Success:</strong>
Operation completed successfully
```

---

#### `.alert__close`
```css
.alert__close {
  flex-shrink: 0;
  background: none;
  border: none;
  color: inherit;
  opacity: 0.7;
  cursor: pointer;
  width: 28px;
  height: 28px;
  padding: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  line-height: 1;
  border-radius: 4px;
  transition: all 0.2s ease;
  margin-top: -2px;
}

.alert__close:hover,
.alert__close:focus {
  opacity: 1;
  background: rgba(255, 255, 255, 0.15);
}

.alert__close:focus {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
```

**Purpose:** Dismiss/close button with × symbol
**Hit target:** 28×28px (accessible touch target)
**States:**
  - Normal: 70% opacity
  - Hover: 100% opacity, subtle background
  - Focus: 2px color outline for keyboard navigation

**Example:**
```html
<button class="alert__close" aria-label="Dismiss">&times;</button>
```

---

### Spacing Variants

#### `.alert--compact`
```css
.alert--compact {
  padding: 12px 16px;
  margin-bottom: 12px;
}

@media (max-width: 768px) {
  .alert--compact {
    padding: 8px 12px;
  }
}
```

**Use for:**
- Secondary/inline alerts
- Inline validation messages
- Less prominent notifications

**Example:**
```html
<div class="alert alert--info alert--compact">
  Remember to save your changes
</div>
```

---

#### `.alert--spacious`
```css
.alert--spacious {
  padding: 24px 32px;
  margin-bottom: 32px;
}

@media (max-width: 768px) {
  .alert--spacious {
    padding: 24px 24px;
    margin-bottom: 24px;
  }
}
```

**Use for:**
- Prominent announcements
- Critical messages
- Hero-style alerts

**Example:**
```html
<div class="alert alert--warning alert--spacious">
  <span class="alert__icon">⚠</span>
  <div class="alert__content">
    <strong>Important System Maintenance</strong>
    StarExec will be offline for upgrades
  </div>
</div>
```

---

### Container Classes

#### `.alerts-container`
```css
.alerts-container {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.alerts-container > .alert {
  margin-bottom: 16px;
  margin-top: 0;
}

.alerts-container > .alert:last-child {
  margin-bottom: 0;
}
```

**Purpose:** Group multiple alerts with consistent spacing
**Usage:** Wrap multiple `.alert` elements

**Example:**
```html
<div class="alerts-container">
  <div class="alert alert--success">Success message</div>
  <div class="alert alert--warning">Warning message</div>
  <div class="alert alert--error">Error message</div>
</div>
```

---

### Toast Notifications

#### `.toast`
```css
.toast {
  position: fixed;
  top: 24px;
  right: 24px;
  max-width: 400px;
  z-index: 700;
  box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.3);
  margin: 0;
  animation: slideIn 0.3s ease-out;
}

@media (prefers-reduced-motion: no-preference) {
  .toast {
    animation: slideIn 0.3s ease-out;
  }
}

@media (prefers-reduced-motion: reduce) {
  .toast {
    animation: none;
    opacity: 1;
    transform: translateX(0);
  }
}

@media (max-width: 768px) {
  .toast {
    left: 16px;
    right: 16px;
    max-width: none;
    top: auto;
    bottom: 16px;
    padding: 16px 16px;
  }
}
```

**Purpose:** Fixed position notification (typically temporary)
**Positioning:** Top-right corner (desktop), bottom center (mobile)
**Animation:** Slides in from right (respects motion preferences)
**Z-index:** 700 (above most elements)

**Variant classes:**
- `.toast--success` - Green toast
- `.toast--error` - Red toast
- `.toast--warning` - Orange toast
- `.toast--info` - Blue toast

**Example:**
```javascript
var toast = Alerts.create({
  type: 'success',
  message: 'Operation completed',
  dismissible: true
});
document.body.appendChild(toast);
toast.classList.add('toast');
```

---

## Responsive Design

### Breakpoints

```scss
$breakpoint-mobile: 768px;    // Mobile devices
$breakpoint-tablet: 1024px;   // Tablets
$breakpoint-desktop: 1200px;  // Desktop

@include mobile { ... }        // max-width: 768px
@include tablet { ... }        // max-width: 1024px
@include desktop { ... }       // min-width: 769px
```

### Alert Responsive Behavior

**Desktop (769px+):**
- Padding: 20px 24px
- Icon gap: 12px
- Full width container

**Mobile (max-width: 768px):**
- Padding: 16px 16px (reduced)
- Icon gap: 8px (reduced)
- Full width with margins
- Close button remains 28×28 for touch

**Example:**
```scss
.alert {
  padding: 20px 24px;           // Desktop
  
  @include mobile {
    padding: 16px 16px;         // Mobile
  }
}
```

---

## Animations

### Slide-In Animation (Toast)

```scss
@media (prefers-reduced-motion: no-preference) {
  @keyframes slideIn {
    from {
      opacity: 0;
      transform: translateX(100%);
    }
    to {
      opacity: 1;
      transform: translateX(0);
    }
  }

  .toast {
    animation: slideIn 0.3s ease-out;
  }
}
```

**Duration:** 300ms
**Easing:** ease-out (fast start, smooth end)
**Direction:** Right to left
**Respects:** `prefers-reduced-motion` for accessibility

### Fade-Out Animation (Dismissal)

```javascript
// Internal to Alerts utility
alertEl.style.transition = 'opacity 150ms ease-out';
alertEl.style.opacity = '0';
setTimeout(removeDom, 150);
```

**Duration:** 150ms
**Easing:** ease-out
**Property:** opacity only (no layout shift)

---

## Accessibility Features

### High Contrast Support

Colors meet WCAG AA standard (4.5:1 minimum):

```
Success:  #10b981 (green) on light green background
Error:    #ff0000 (red) on light red background
Warning:  #fa621b (orange) on light orange background
Info:     #3b82f6 (blue) on light blue background
```

### Focus Indicators

```css
.alert__close:focus {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
```

**Visibility:** Thick 2px outline
**Color:** Inherits text color for maximum contrast
**Offset:** 2px from button edge

### Reduced Motion

```css
@media (prefers-reduced-motion: reduce) {
  .toast {
    animation: none;
    opacity: 1;
    transform: translateX(0);
  }
}
```

Animations disabled when user prefers reduced motion.

---

## Legacy Compatibility

### Backwards-Compatible Aliases

For migration support, old class names still work:

```scss
.alert-icon { @extend .alert__icon; }
.alert-content { @extend .alert__content; }
.alert-title { @extend .alert__title; }
.alert-dismiss { @extend .alert__dismiss; }
```

**Old names** (deprecated but supported):
- `.alert-icon` → `.alert__icon`
- `.alert-content` → `.alert__content`
- `.alert-title` → `.alert__title`
- `.alert-dismiss` → `.alert__dismiss`

**Old legacy classes** (for form support):
- `.error.message` or `.message.error` → `.alert.alert--error`
- `.success.message` or `.message.success` → `.alert.alert--success`
- `.warning.message` or `.warn.message` → `.alert.alert--warning`
- `.info.message` or `.message.info` → `.alert.alert--info`

---

## Customization

### Overriding Colors

```css
/* Custom alert style */
.alert--custom {
  background: rgba(128, 90, 213, 0.15);
  color: #805ad5;
  border: 1px solid rgba(128, 90, 213, 0.3);
}

.alert--custom .alert__icon {
  color: #805ad5;
}
```

### Custom Sizing

```css
/* Large alert variant */
.alert--large {
  padding: 32px 40px;
  font-size: 16px;
}

.alert--large .alert__icon {
  width: 24px;
  height: 24px;
}
```

### Custom Positioning

```css
/* Sidebar alert */
.alert--sidebar {
  margin-left: 16px;
  margin-bottom: 16px;
}

/* Sticky alert */
.alert--sticky {
  position: sticky;
  top: 0;
  z-index: 100;
}
```

---

## Common Patterns

### Form Validation Error

```html
<div class="alert alert--error" role="alert">
  <span class="alert__icon" aria-hidden="true">!</span>
  <div class="alert__content">
    <strong class="alert__title">Validation Error:</strong>
    Please fill in all required fields
  </div>
</div>
```

### Success Toast

```html
<div class="alert alert--success toast" role="status">
  <span class="alert__icon" aria-hidden="true">✓</span>
  <div class="alert__content">
    Changes saved successfully
  </div>
  <button class="alert__close" aria-label="Dismiss">&times;</button>
</div>
```

### Information Banner

```html
<div class="alert alert--info alert--spacious" role="status">
  <span class="alert__icon" aria-hidden="true">ℹ</span>
  <div class="alert__content">
    <strong class="alert__title">Welcome to StarExec</strong>
    New user? Check our getting started guide for tips and tricks.
  </div>
  <button class="alert__close" aria-label="Dismiss">&times;</button>
</div>
```

### Multiple Alerts Container

```html
<div class="alerts-container">
  <div class="alert alert--error">
    <span class="alert__icon">!</span>
    <div class="alert__content">Error: File not found</div>
  </div>
  <div class="alert alert--warning">
    <span class="alert__icon">⚠</span>
    <div class="alert__content">Warning: Large file detected</div>
  </div>
</div>
```

---

## Browser Support

### Modern Browsers (Full Support)
- Chrome 60+
- Firefox 55+
- Safari 12+
- Edge 79+

### Features Used
- CSS Flexbox
- CSS Variables (fallback values provided)
- CSS Transitions
- Media Queries
- CSS `gap` property

### Graceful Degradation
- Old browsers get basic functional styling
- Animations disabled in older browsers
- Layout adapts without flexbox (`display: block` fallback)

---

## Size Reference

### Spacing

| Token | Value | Use Case |
|-------|-------|----------|
| `$spacing-xs` | 8px | Icon-content gap |
| `$spacing-sm` | 12px | Compact variant padding |
| `$spacing-md` | 16px | Mobile padding, container margins |
| `$spacing-lg` | 24px | Standard padding, between alerts |
| `$spacing-xl` | 32px | Spacious variant padding |

### Sizing

| Element | Size | Notes |
|---------|------|-------|
| Icon | 20×20px | Fixed, non-shrinking |
| Close button | 28×28px | Accessible touch target |
| Border radius | 8px | Subtle rounding |
| Border width | 1px | Subtle separator |

### Typography

| Element | Font Size | Font Weight |
|---------|-----------|-------------|
| Message | 14px | 400 (normal) |
| Title | 14px | 600 (semibold) |

---

## Z-Index Scale

```scss
$z-index-header:   700;    // Header
$z-index-toast:    700;    // Alerts/toasts
$z-index-modal:    900;    // Modals
$z-index-tooltip:  1000;   // Tooltips
```

Alert default z-index is 700 (same as header).

---

## Migration Guide

### From `.message` to `.alert`

**Old:**
```html
<div class="error message">
  <img src="/images/icons/exclaim.png" />
  <div>Error occurred</div>
  <span class="exit">X</span>
</div>
```

**New:**
```html
<div class="alert alert--error" role="alert">
  <span class="alert__icon">
    <svg><!-- icon --></svg>
  </span>
  <div class="alert__content">
    Error occurred
  </div>
  <button class="alert__close">×</button>
</div>
```

---

## Troubleshooting

### Alert not styled
- Check that `global.scss` imports `components/alert`
- Verify `alert.scss` is in `components/` folder
- Clear browser cache (Ctrl+Shift+R or Cmd+Shift+R)

### Colors look wrong
- Verify color tokens are loaded from `_colors.scss`
- Check for CSS specificity conflicts
- Look at computed styles in browser inspector

### Spacing seems off
- Verify spacing tokens loaded from `_spacing.scss`
- Check for conflicting margins/padding
- Use browser inspector to debug

### Animation stutters
- Check browser performance (DevTools → Performance)
- Verify GPU acceleration enabled
- Disable motion if needed for testing

---

## Resources

- **[Alerts API](./ALERTS_API.md)** - Complete JavaScript API
- **[Banners API](./BANNERS_API.md)** - Persistent banners
- **[Implementation Guide](./IMPLEMENTATION_GUIDE.md)** - Usage examples
- **[Design System](../DESIGN.md)** - Overall design principles

---

## Version History

- **v2.0** (Current) - Modernized with Flexbox, BEM naming, accessibility
- **v1.x** (Deprecated) - Legacy message system, fixed positioning

---

**Last Updated:** December 2025
**Maintained By:** StarExec Development Team