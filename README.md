# Bun Actions on Save

Bun Actions on Save brings Bun's built-in formatter and linter into WebStorm's native **Actions on Save** settings. Enable formatting and lint fixes independently, then control which file types each action processes.

## Features

- Native **Run bun fmt** and **Run bun lint --fix** save actions. The fmt row follows Prettier; the lint row follows ESLint.
- Optional live lint diagnostics in the editor while you edit. This runs without fixes and is independent of the `bun lint --fix` save action.
- A file type dropdown on each save action, with the same selections available in Bun's settings page.
- Three project-level modes: **Disable Bun**, **Automatic Bun configuration**, and **Manual Bun configuration**.
- Automatic mode uses `bun` from the IDE's `PATH` and Bun's supported default file types.
- Manual mode allows separate file type selections for fmt and lint and a Bun executable path selected with a file picker.
- Per-project settings, so each project can choose its own mode, actions, file types, and executable.
- Notifications when a Bun command fails.

## Configure

Under **Settings | Tools | Actions on Save**, enable **Run bun fmt** and/or **Run bun lint --fix**. Each row's dropdown selects the file types for that action.

Under **Settings | Tools | Bun**, choose a configuration mode and enable fmt, `bun lint --fix` on save, and live lint warnings independently. Turn on **Show bun lint warnings in editor** to display live diagnostics without applying fixes. In Manual mode, the file type selectors and executable picker expand beneath the Manual option. Selecting file types from an Actions on Save dropdown also switches the project to Manual mode so both settings views remain in sync.

Automatic mode uses Bun's supported defaults: `bun fmt` handles JavaScript, TypeScript, JSON, CSS, HTML, Vue, Svelte, Markdown, YAML, TOML, and GraphQL files; `bun lint --fix` handles JavaScript and TypeScript files. Bun runs from the project root, so its configuration and ignore files apply.

## Build

Run `gradle buildPlugin` to create a plugin ZIP in `build/distributions`, or `gradle runIde` to launch a development IDE.
## Plugin signing

The signing key and certificate chain are stored outside this repository in:

- `%APPDATA%\BunPluginSigning\private.pem` — private signing key
- `%APPDATA%\BunPluginSigning\chain.crt` — certificate chain

Set `BUN_PLUGIN_PRIVATE_KEY_FILE` and `BUN_PLUGIN_CERTIFICATE_CHAIN_FILE` to those file paths before running the Gradle `signPlugin` task. Keep the key outside the repository and do not commit it.
