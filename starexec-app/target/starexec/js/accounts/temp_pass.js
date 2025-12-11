/**
 * JavaScript for the temporary password page.
 * Provides password visibility toggle and copy-to-clipboard functionality.
 */
$(document).ready(function() {
    'use strict';

    var $passwordInput = $('#temp_pass');
    var $toggleBtn = $('#togglePassword');
    var $copyBtn = $('#copyPassword');
    var $showIcon = $toggleBtn.find('.show-icon');
    var $hideIcon = $toggleBtn.find('.hide-icon');

    // Toggle password visibility
    $toggleBtn.on('click', function() {
        var isPassword = $passwordInput.attr('type') === 'password';
        
        if (isPassword) {
            $passwordInput.attr('type', 'text');
            $showIcon.addClass('hidden');
            $hideIcon.removeClass('hidden');
            $toggleBtn.attr('aria-label', 'Hide password');
            $toggleBtn.attr('title', 'Hide password');
        } else {
            $passwordInput.attr('type', 'password');
            $showIcon.removeClass('hidden');
            $hideIcon.addClass('hidden');
            $toggleBtn.attr('aria-label', 'Show password');
            $toggleBtn.attr('title', 'Show password');
        }
    });

    // Copy to clipboard
    $copyBtn.on('click', function() {
        var password = $passwordInput.val();
        
        // Modern clipboard API
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(password).then(function() {
                showCopyFeedback('Copied!');
            }).catch(function(err) {
                console.error('Failed to copy:', err);
                fallbackCopy(password);
            });
        } else {
            fallbackCopy(password);
        }
    });

    // Fallback copy method for older browsers
    function fallbackCopy(text) {
        // Temporarily show the password to allow copying
        var originalType = $passwordInput.attr('type');
        $passwordInput.attr('type', 'text');
        $passwordInput.select();
        
        try {
            var successful = document.execCommand('copy');
            if (successful) {
                showCopyFeedback('Copied!');
            } else {
                showCopyFeedback('Failed to copy');
            }
        } catch (err) {
            console.error('Fallback copy failed:', err);
            showCopyFeedback('Failed to copy');
        }
        
        // Restore original type
        $passwordInput.attr('type', originalType);
        window.getSelection().removeAllRanges();
    }

    // Show feedback after copy
    function showCopyFeedback(message) {
        var originalText = $copyBtn.text();
        $copyBtn.text(message);
        $copyBtn.attr('disabled', true);
        
        setTimeout(function() {
            $copyBtn.text(originalText);
            $copyBtn.attr('disabled', false);
        }, 1500);
    }

    // Auto-hide password after 30 seconds for security
    setTimeout(function() {
        if ($passwordInput.attr('type') === 'text') {
            $toggleBtn.click();
        }
    }, 30000);
});
