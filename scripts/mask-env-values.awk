# Masks secret values in a rendered manifest for `make config-show`.
#
# An env entry whose name contains PASSWORD, SECRET or TOKEN has its value printed as
# ***REDACTED***. The value is masked wherever it comes before the entry's next name: key,
# so a comment or a blank line between the two does not expose it, and the remaining lines
# of a value that spans lines are dropped. A valueFrom: reference prints unchanged: it names
# a Secret, not its contents. An entry must list name: before value:, as
# render.yaml.template, the chart and kubectl all do.
#
# Usage: awk -f scripts/mask-env-values.awk render.yaml

# Inside a masked value, a blank line is harmless and a line indented deeper than the
# value's key is the rest of the value.
masking && !/[^ ]/ { print; next }
masking && match($0, /[^ ]/) > indent + 1 { next }
{ masking = 0 }

# A value line is settled here, so text inside a value never reaches the name rule.
sensitive && /^ *(- +)?value:/ {
    indent = index($0, "value:") - 1
    sub(/value:.*/, "value: ***REDACTED***")
    masking = 1
    print
    next
}

# Only a name: key starts a new entry; a comment or a value that mentions name: does not.
/^ *(- +)?name:/ { sensitive = ($0 ~ /name: .*(PASSWORD|SECRET|TOKEN)/) }

{ print }
