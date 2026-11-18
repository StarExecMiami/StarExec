# Lucide icons (vendored subset)

Source: `lucide-static` **1.61.0** from npm, ISC licence (see `LICENSE`).
Tarball integrity: `sha512-yLAiQB3MWUJEstsEDAwenSA3m7vr1tdLvgnC16Q/XMxRRma0DWwMs8M2bWlvgShrIs1m2mWMJ+0AiahqQO4GrA==`

Only the icons the UI uses are kept here. They are not served as files: the
build script below turns them into CSS data URIs, so icons also work in the
offline job page (`DoJobPage`), which browsers open from `file://` where they
refuse to load mask images from separate files.

## Adding or updating an icon

1. Copy `icons/<name>.svg` from the same `lucide-static` version into this folder.
2. Run `python3 scripts/build-icon-data.py` from the repository root.
3. Use it in SCSS with `icon("<name>")` (defined in `css/global/_icon-data.scss`).
   jQuery UI classes are mapped in `css/components/_ui-icons.scss`.

Never edit `_icon-data.scss` by hand; it is regenerated from this folder.
