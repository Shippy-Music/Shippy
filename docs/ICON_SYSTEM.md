# Shippy Icon System

Shippy's ordinary interface glyphs come directly from official Lucide SVG
geometry. They are generated as dependency-free Android `VectorDrawable`
resources; Tamagui and Lucide are not runtime dependencies.

## Source lock

- Repository: `https://github.com/lucide-icons/lucide`
- Commit: `6d1fc51b9a3c1cdd6f606ffe0ba8ea6b70b814bb`
- Mapping: `tools/lucide/lucide-icons.json`
- Generator: `tools/lucide/generate_android_vectors.py`
- License: `third_party/lucide/LICENSE`

`ic_settings_24` deliberately maps to Lucide `settings-2`, not `settings`.

## State contract

Existing Android resource names remain stable, so layouts, menus, widgets,
notifications, Kotlin bindings, and selectors do not change identity. Stateful
presentation remains owned by the existing selectors and tint variants:

- `sel_playing_state_24`: Play / Pause
- `sel_shuffle_state_24`: Shuffle off / on
- Repeat off / on / one: separate retained resource IDs

The launcher artwork, notification silhouette, seek triangle, animated playing
indicator, and splash animation remain Shippy-specific assets rather than being
forced into the Lucide family.

## Regeneration

With the locked Lucide checkout available locally:

```powershell
python tools\lucide\generate_android_vectors.py <lucide-repo>\icons
```

The converter preserves each existing drawable's physical size, theme tint,
and auto-mirroring flag while using the official 24 by 24 geometry and stroke
style.
