# Changelog

All notable changes to ServerMenu are documented here.

## [1.10.0]

### Added
- **Dynamic buttons from a PAPI list.** A dialog can declare
  `dynamic-buttons: {source: "%placeholder%", separator: ",", name: "{value}",
  tooltip: "...", command: "home {value}", max: 20}` to generate one button
  per item in a delimited-list placeholder (e.g. a homes plugin's list of
  home names), instead of hand-writing every button in config.yml.
  - Buttons are appended after any statically-defined buttons on the dialog.
  - If the placeholder doesn't resolve (PAPI missing, unknown placeholder),
    no dynamic buttons are added — no crash, no literal `%placeholder%` button.
  - **Limitation**: same as `{key}` input substitution — needs a player to
    resolve the placeholder, so only works on dialogs opened via
    `/servermenu open` (i.e. any submenu), not on the static `dialogs.main`.

## [1.9.0]

### Added
- **Tab-completion** for `/servermenu open <dialog>`: suggests `main` plus
  every id under `dialogs:` in config.yml, filtered by what's already typed.

## [1.8.0]

### Changed
- **`/servermenu reload` now actually reloads.** It re-reads `config.yml`
  from disk into memory. This takes effect immediately for anything opened
  via `/servermenu open` (every submenu, since those are always rebuilt
  fresh from the in-memory config on each open). It does **not** affect
  `dialogs.main` (the ESC pause-menu dialog) — that one is baked into
  Paper's static DIALOG registry at bootstrap, and there's no public API to
  re-register it at runtime, so changes to `dialogs.main` still need a full
  restart. Added a `reload-failed` message key for when the file can't be
  parsed (bad YAML), so a broken edit doesn't silently leave the old config
  in a confusing state.

## [1.7.0]

### Added
- **Item icons in dialog bodies.** A `body:` entry can now be a map instead
  of a plain string, to show a Minecraft item icon (`DialogBody.item(...)`)
  alongside/instead of text lines:
  ```yaml
  body:
    - "<gold>Top reward:</gold>"
    - {type: item, material: DIAMOND, description: "<gray>1 Diamond</gray>"}
  ```
  Optional fields: `width`/`height` (1-256, default 16), `show-decorations`
  (default true), `show-tooltip` (default true). Unknown/invalid `material`
  values are silently skipped (that body entry is dropped) rather than
  crashing dialog registration.

## [1.6.0]

### Added
- **Configurable MiniMessage system messages.** All of ServerMenu's own
  messages (invalid dialog, unknown dialog, unknown button, no permission,
  reload notice, players-only) moved from hardcoded legacy `§` color codes
  into a `messages:` section in `config.yml`, parsed as MiniMessage (so
  gradients/hex work there too). Falls back to sane defaults if a key isn't
  set. `unknown-dialog` supports a `<dialog>` placeholder tag for the
  dialog id that wasn't found.

## [1.5.0]

### Added
- **Dialog body text.** A dialog can declare an optional `body:` list of
  plain-text lines, shown above the buttons (`DialogBase.body(List<DialogBody>)`,
  each line via `DialogBody.plainMessage(...)`). Same MiniMessage +
  PlaceholderAPI resolution as titles/tooltips/names, so leaderboard-style
  `%placeholder%` lines work directly.
- Added `dialogs.leaderboards` (category picker) example wiring pattern —
  actual leaderboard dialogs are user-config-specific, not bundled in the
  default `config.yml`.

## [1.4.0]

### Added
- **Text inputs + variable substitution.** A dialog can declare an optional
  `inputs:` section (currently `type: text` only). Any button's `command` on
  the same dialog can reference `{key}` and it gets replaced with what the
  player typed when the button is clicked (e.g. `pay {target} {amount}`).
  - Added a stock `dialogs.pay` example dialog (Player Name + Amount text
    inputs, Pay/Back buttons) wired up from `dialogs.main`'s "Pay" button.
  - Implementation: buttons whose command contains a `{key}` token use
    `DialogAction.customClick(...)` (server-side callback reading
    `DialogResponseView`) instead of the usual `ClickEvent.runCommand`,
    since only the callback has access to input values. Permission checking
    for such buttons happens inline in the callback instead of via the
    `/servermenu click` redirect used by plain buttons.
  - **Limitation**: only works on dialogs opened via `/servermenu open`
    (any submenu). `{key}` buttons placed directly on `dialogs.main` are a
    no-op, because `main` is a static dialog object shared by every player
    for the whole server uptime, and a single-use click callback can't
    safely live there.

## [1.3.1]

### Fixed
- **Startup crash** (`NullPointerException: Cannot invoke "Server.getPluginManager()" because "Bukkit.server" is null`,
  surfacing as Paper's generic "Failed to load datapacks" error): `applyPlaceholders()`
  called `Bukkit.getPluginManager()` directly, which throws during the bootstrap
  phase since `Bukkit.getServer()` isn't initialized yet at that point (registry
  building for `minecraft:dialog` happens in bootstrap). The `Bukkit.getPluginManager()`
  call itself was outside the try/catch — only the PlaceholderAPI call was guarded.
  Now the whole placeholder-resolution path is wrapped, so bootstrap-time dialog
  building never touches Bukkit state that isn't ready yet; placeholders on the
  static/pause-menu dialog simply stay unresolved until the server is fully up
  (see the existing PAPI limitation note below), and `/servermenu open` (always
  called post-startup) resolves them normally.

## [1.3.0] — in progress

### Added
- **PlaceholderAPI support** in dialog titles/external-titles and button
  names/tooltips. Placeholders resolve per-player. Soft-depends on
  PlaceholderAPI (`paper-plugin.yml` `dependencies.server`, `load: BEFORE`,
  `required: false`, `join-classpath: true`).
  - Dialogs opened via `/servermenu open <id>` (i.e. any `dialog`/`back`
    button click, or the command run directly) are now **rebuilt on demand**
    with `Dialog.create(...)` for the requesting player instead of being
    pulled from the static registry — this is what makes per-player
    placeholders possible.
  - **Limitation**: the very first dialog opened from the ESC pause-menu
    button (`servermenu:main`) is still sent by the client straight from the
    static registry entry (vanilla behavior, doesn't go through our
    command), so it only gets placeholders resolved once at server startup,
    without per-player context.
- Optional `permission:` field per button. If set, clicking the button
  without the permission shows a "no permission" message instead of running
  the command. **Note:** the button itself is still visible to everyone —
  the same Dialog object is shared by all players, so hiding buttons
  per-player would need a bigger rework (dialogs built per-open instead of
  once at bootstrap).
- New internal subcommand `/servermenu click <dialog> <button>` that
  permission-gated buttons redirect through at runtime.

## [1.2.1] — 2026-08-08

### Fixed
- **Startup crash**: `getCommand("servermenu")` in `ServerMenu#onEnable()` threw
  `UnsupportedOperationException` because Paper plugins (bootstrapper +
  `paper-plugin.yml`) don't support the legacy `plugin.yml` `commands:` /
  `getCommand()` flow.
- Removed the duplicate manifest: `plugin.yml` and `paper-plugin.yml` both
  existed with different version numbers (1.2.1 vs 1.1.0), risking the plugin
  being registered twice.

### Changed
- `/servermenu` (and alias `smenu`) is now registered via Brigadier
  (`LifecycleEvents.COMMANDS`) inside `ServerMenuBootstrap`, the supported
  way to declare commands for Paper plugins.
- `ServerMenu.java` no longer implements `CommandExecutor`; it now only
  exposes `openDialog(Player, String)`, called by the Brigadier handlers.
- `permissions:` (`servermenu.reload`) moved into `paper-plugin.yml`.

## [1.2.0] — GradientFixed

### Added
- Nested dialog support (`type: dialog`, `type: back`, `type: close`).
- MiniMessage parsing for titles/tooltips/button names, including gradients
  and hex colors (fix for gradient rendering).
- `dialogs.*` config structure, with backwards-compatible fallback to the
  legacy top-level `buttons:` section when `dialogs.main` isn't defined.

## [1.1.0]

### Added
- Initial native Paper Dialog pause-screen menu (`servermenu:main` added to
  `minecraft:pause_screen_additions`).
- Configurable multi-action buttons via `config.yml`.
- `/servermenu open <dialog>` and `/servermenu reload` (restart notice only).
