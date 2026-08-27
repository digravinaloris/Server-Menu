# ServerMenu — AI HANDOFF — 1.10.0

## Goal
Build a Paper 1.21.11 plugin that reproduces the native Minecraft Dialog pause-screen menu shown in the user's screenshots (Donut SMP style), with no client mod and no resource pack.

## Current implementation
- Native Paper Dialog API.
- `servermenu:main` is added to `minecraft:pause_screen_additions`.
- Configurable multi-action buttons.
- Nested dialogs are supported.
- Button types: `command`, `dialog`, `back`, `close`.
- Dialogs are registered from `config.yml` during bootstrap.
- `/servermenu open <dialog>` retrieves a registered dialog and calls `player.showDialog(dialog)`.
- `dialogs.main` is the pause-screen dialog when present.
- `/servermenu` (+ alias `smenu`) is registered via **Brigadier** (`LifecycleEvents.COMMANDS`) inside `ServerMenuBootstrap`, not via `plugin.yml`/`getCommand()`. Paper plugins (bootstrapper + `paper-plugin.yml`) do not support the legacy command declaration flow — using `getCommand()` in `onEnable()` throws `UnsupportedOperationException` at startup.
- Only one manifest file exists: `paper-plugin.yml` (holds `permissions:` too). The old `plugin.yml` was removed — having both files at once risked the plugin being loaded twice with mismatched versions.
- `ServerMenu.java` no longer implements `CommandExecutor`; it only exposes `openDialog(Player, String)`, called from the Brigadier handlers in the bootstrap class via `JavaPlugin.getPlugin(ServerMenu.class)`.
- Buttons support an optional `permission:` field. A permission-gated button's action is redirected to `/servermenu click <dialog> <button>` (internal), which checks `player.hasPermission(...)` at runtime before running the real command. **Limitation**: the button is still visible to everyone since the Dialog object is built once in the registry and shared by all players — this only blocks the *action*, it doesn't hide the button. True hiding would require building the Dialog per-player at open time instead of once at bootstrap.
- **PlaceholderAPI support** (soft dependency, `join-classpath: true` in `paper-plugin.yml`). `/servermenu open <id>` now rebuilds the target dialog via `Dialog.create(...)` for the requesting player each time, resolving `%placeholder%` in title/external-title/name/tooltip via `PlaceholderAPI.setPlaceholders(player, text)` before MiniMessage parsing. `config` is kept as an instance field on `ServerMenuBootstrap` (not just a bootstrap-local variable) so it can be reused for these on-demand rebuilds. **Limitation**: `dialogs.main` opened straight from the ESC pause-menu button is still the static registry object (vanilla client behavior bypasses our command), so it doesn't get per-player placeholders — only global ones resolved once at startup.
- **Text inputs**. A dialog's optional `inputs:` section builds `DialogInput.text(...)` fields (`DialogBase.builder(...).inputs(...)`). Buttons on the same dialog reference input values with `{key}` in `command:` (e.g. `pay {target} {amount}`), substituted from `DialogResponseView.getText(key)` at click time. Buttons with `{key}` tokens use `DialogAction.customClick(...)` instead of `ClickEvent.runCommand`, since only the server-side callback can read input values — permission checks for those buttons happen inline in the callback rather than via the `/servermenu click` redirect. **Limitation**: only works for dialogs opened via `/servermenu open` (player != null at build time); disabled (no-op) on the static registry copies built at bootstrap, so don't put `{key}` buttons on `dialogs.main`.
- **Body text**. Optional `body:` list of MiniMessage strings on a dialog, shown above the buttons (`DialogBase.body(List.of(DialogBody.plainMessage(...)))`). Same PAPI + MiniMessage pipeline as everything else. Used for leaderboard-style dialogs (5 ranked lines + a Back button, no interactive buttons besides Back). Body entries can also be maps for item icons (`{type: item, material: DIAMOND, description: "..."}` → `DialogBody.item(...)`) — width/height/show-decorations/show-tooltip all optional, invalid `material` silently drops that entry.
- **Configurable system messages**. `message(key, fallback, resolvers...)` reads `messages.<key>` from `config.yml` (MiniMessage, with optional `TagResolver`s for named placeholders like `<dialog>`), falling back to a sane default. Used for every player-facing message ServerMenu sends itself (not dialog content) — no more hardcoded `§` codes.
- **`/servermenu reload`** re-reads `config.yml` from disk into the in-memory `config` field. Takes effect immediately for every submenu (anything opened via `/servermenu open`, since those rebuild from `config` on each open). Does NOT touch the static registry (`dialogs.main` specifically) — no public Paper API to redo that at runtime — so `dialogs.main` changes still need a full restart.
- **Tab-completion** on `/servermenu open <dialog>` via `.suggests(this::suggestDialogIds)` on the Brigadier argument — lists `main` + every key under `dialogs:` in the current in-memory config, filtered by what's typed so far.
- **Dynamic buttons** (`createDynamicButtons`). A dialog's optional `dynamic-buttons:` section resolves a PAPI list placeholder (e.g. a homes plugin's `%..._list%`) for the player, splits it by `separator`, and generates one `ActionButton` per item with `{value}` substituted into `name`/`tooltip`/`command`. Appended after any statically-defined buttons on the same dialog. If the placeholder string comes back unchanged (not resolved), no buttons are generated — avoids a dialog full of literal `%placeholder%` buttons. **Limitation**: same as `{key}` inputs — player-dependent, so only works via `/servermenu open`, not on `dialogs.main`.

## Config syntax
```yaml
dialogs:
  main:
    title: "Donut SMP"
    columns: 2
    button-width: 320
    buttons:
      shop:
        name: "Shop"
        type: dialog
        dialog: shop
        tooltip: "Open the shop"

  shop:
    title: "Shop"
    columns: 2
    button-width: 300
    buttons:
      blocks:
        name: "Blocks"
        type: command
        command: "shop blocks"
      back:
        name: "Back"
        type: back
        dialog: main
```

## Important
- Dialog registration occurs in the bootstrap/registry lifecycle. `/servermenu reload` (not `/reload`) re-reads config.yml and takes effect for submenus opened via `/servermenu open`; `dialogs.main` (the pause-screen dialog) still needs a full server restart since it's baked into the static registry — see "Command registration" note above.
- Command registration also happens in the bootstrap, so anything touching `/servermenu` (new subcommands, args) needs a full restart too.
- If you ever add another manifest file back (`plugin.yml`), make sure it's not duplicating `paper-plugin.yml` — pick one.
- **`Bukkit.getServer()` is not initialized during `bootstrap()`** (which is when `RegistryEvents.DIALOG.compose()` runs to build the static registry dialogs). Any `Bukkit.*` static call — `Bukkit.getPluginManager()`, `Bukkit.getServer()`, etc. — throws `NullPointerException: Bukkit.server is null` if reached from that code path, and Paper reports it misleadingly as "Failed to load datapacks, can't proceed with server load." Wrap any such call in try/catch (see `applyPlaceholders()`), or better, avoid it entirely in code reachable from `bootstrap()`. It's safe again once commands actually execute (`runOpen`, `runClick`, etc.), since those only run after full startup.

## API references
Paper Dialog API docs: https://docs.papermc.io/paper/dev/dialogs/
Paper 1.21.11 API: https://jd.papermc.io/paper/1.21.11/
Paper Brigadier commands: https://docs.papermc.io/paper/dev/command-api/basics/

## Next ideas
- ~~Permission per button.~~ Done (1.3.0, click-blocked, not hidden — see above).
- ~~PlaceholderAPI support in labels/tooltips.~~ Done (1.3.0, per-player on `/servermenu open`, static-only on the pause-menu main dialog — see above).
- ~~Rich MiniMessage formatting.~~ Done (1.6.0, system messages now configurable via `messages:` in config.yml — see above). Dialog content already had it since 1.2.0.
- ~~Per-dialog body text/items.~~ Done (1.5.0, text only — see above). Item bodies (`DialogBody.item(...)`) not wired up yet.
- Optional icons/items in dialog bodies. ~~Done (1.7.0, `{type: item, material: ...}` body entries — see above.)~~
- Better `/servermenu reload` behavior if Paper registry reload support makes it safe. ~~Done (1.8.0) for submenus; `dialogs.main` still needs a restart, no public API for that — see above.~~
- Match the screenshots pixel/spacing-wise as closely as the native Dialog API allows.
- Tab-completion for `/servermenu open <dialog>` (suggest registered dialog IDs). ~~Done (1.9.0) — see above.~~

