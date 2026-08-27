# ServerMenu

A custom Paper 1.21.x plugin for Survival SMP: a native Minecraft
Dialog-based ESC pause-menu (Donut SMP style), fully configurable via
`config.yml` — no client mod, no resource pack.

Current version: see `CHANGELOG.md` for the full history.

## What it does

- Native ESC pause-menu (`dialogs.main`) with nested dialogs (shop, pay,
  teleport, leaderboards, stats, links, etc.).
- Buttons: `command`, `dialog` (open a submenu), `back`, `close`.
- Per-button `permission:` gating.
- PlaceholderAPI support in titles/tooltips/names/body text.
- Text input fields (`inputs:`) with `{key}` substitution in commands
  (e.g. a Pay menu with Player Name + Amount fields).
- Body text and item-icon bodies (`body:`), MiniMessage + PAPI resolved.
- Dynamic buttons generated from a PAPI list placeholder.
- `/servermenu open <dialog>`, `/servermenu reload` (hot-reloads every
  submenu; `dialogs.main` still needs a full restart), `/servermenu click`
  (internal, permission/input redirect).

See `AI_HANDOFF.md` for the full technical rundown of how each piece works
and its limitations — read that first before making changes.

## Build

```
mvn clean package
```

Output: `target/ServerMenu-<version>.jar`

## Deploy

Drop the jar in `plugins/`, and `src/main/resources/config.yml` is what
ships as the default config on first run (it's this server's actual live
`digra SMP` config, not a generic template).

**Important**: `dialogs.main` (the pause-menu dialog) is baked into Paper's
static registry at bootstrap — changes to it need a **full server restart**,
not `/servermenu reload`. Every other dialog (opened via `/servermenu open`,
i.e. any submenu) picks up `/servermenu reload` immediately.

## Known gaps

- `dialogs.main`'s "Shop" button points to `dialog: shop`, but no
  `dialogs.shop` section currently exists in `config.yml` — add one or it'll
  show "Unknown ServerMenu dialog".
- No PlaceholderAPI placeholder exists (as of writing) to list a player's
  homes or server warps, so `dialogs.homes` / `dialogs.warps` are static —
  edit them by hand to match your actual home/warp names.
