# UI framework services

`core` contains no game, Android or desktop UI classes. A framework receives the same services on
both hosts through `UiHost.services()`; extensions can access them during `onLoad` through
`ExtensionContext.ui()`. The `game-adapter` module supplies game-backed services after platform
preparation, so a framework can render a launcher and manage extensions without resolving game classes.

## Translations

```java
Messages messages = host.services().messages();
String title = messages.text("extensions.title");
messages.language("zh"); // "en", "system", or an extension-supplied BCP 47 language
AutoCloseable registration = messages.register((locale, key) -> {
    if (!key.equals("example.title")) { return null; }
    return locale.getLanguage().equals("zh") ? "示例" : "Example";
});
```

RWML bundles UTF-8 English and Simplified Chinese translations. Unknown languages fall back to
English; unknown keys are returned unchanged. Extension sources should use their own namespace and
return null for keys they do not supply. Sources are also queried in English when the selected language
has no value. Close temporary registrations when they are no longer needed.
The language preference is stored in `data/language.txt`. The shared locale hook applies it when the
engine loads or reloads its bundle. Console output and diagnostic details keep their original language.
`Messages.label` adapts static labels in restored native layouts; spinner renderers translate the view
while preserving the original selected item and index. Metadata can use
`extensions.<id>.name` / `extensions.<id>.description` through `ExtensionEntry.displayName` and
`displayDescription`, falling back to the archive's original metadata.

## Extension management

`services.extensions()` is an `ExtensionLibrary`. `entries()` returns immutable metadata for installed,
built-in, disabled, failed and malformed archives, including dependencies and session load state.
`enabled` means the persisted enabled flag for the id; `loaded` describes the exact archive loaded in
this session. `versionSelected` identifies the one version selected for the next startup;
`versionChoiceRequired` marks an id with multiple distinct archives and no matching persisted choice.
The currently selected UI framework may suppress other enabled UI frameworks according to the existing
single-framework selection rules.

Use `setEnabled(id, enabled)` for the next startup, `importArchive(input, replace)` to import or update,
and `removeArchive(source)` to remove a selected archive, including malformed or duplicate files.
Built-ins cannot be replaced or removed. Metadata inspection and import do not instantiate extension
code. Imports are staged, validated and moved into place; replacing the same id and version requires
an explicit choice. Importing a different version preserves the installed versions and offers a chooser.
Archive input is limited to 128 MiB. Android also requires carried dex as well as class metadata.
Its importer verifies dex headers, sizes, signatures and checksums before replacing the archive.
Removal preserves extension data. Keep archive operations off the UI thread.

Use `selectVersion(source)` to choose an installed archive without changing its id's enabled flag.
The shared version resolver runs before dependency ordering, early UI mounting, mixin registration and
callbacks. Identical copies are deduplicated by SHA-256. A choice is persisted in
`data/extension-state.properties` by content identity, so renaming an archive or Android's private staging
does not lose the choice. Replacing the selected version through the importer updates its identity.
When the chosen file disappears, a single remaining version is used; multiple remaining versions require
another choice. Disabled conflicts do not block startup. Unresolved enabled conflicts open management
at startup and never select a version merely by filename or version-string ordering.
`versionSelectionRequired()` is a cached result updated by `entries()`; refresh the catalog off the UI
thread before deciding whether to proceed. Both built-in hosts do this during startup.

`restartRequired()` distinguishes changed files/preferences from the classes and mixins already mounted
in the current process. Call `host.restart()` to apply changes in a fresh process. A framework must not
try to hot-unload or rerun mixins. Both builtin launchers require restart before booting with a changed
catalog.

Android uses `rustedWarfare/extensions` as the import directory and retains the existing SHA-checked
private staging cache. Desktop uses the game directory's `extensions` folder. File pickers and Android
VIEW intents feed the same importer; external files are offered for confirmation before import.

## Data services and hooks

```java
UiServices services = host.services();
GameUi game = services.find(GameUi.class); // null before platform/game preparation
GameUi.Snapshot snapshot = services.game(); // UNAVAILABLE is safe before boot
GameSettings settings = services.find(GameSettings.class);

AutoCloseable subscription = services.hooks().subscribe(
    UiHooks.SERVERS_CHANGED, uiExecutor, ignored -> redrawServers());
```

`GameUi` exposes portable menu state, version and game translation/map-name lookups. `GameSettings`
reads/writes typed public scalar properties without giving the UI an engine object. Unsupported names,
types and unavailable settings fail explicitly. The platform owns rendering, GL surfaces and native
game/network actions; those remain in platform adapters rather than leaking into core.

Hooks deliver through the subscriber's executor. Close subscriptions when a screen stops or is disposed;
queued callbacks are suppressed after closure. One failing listener does not prevent another from
receiving a notification. Standard topics cover extension changes, language, game availability and server
list invalidation. The server-list mixin publishes a notification instead of calling a builtin screen.
Extensions can declare `new UiHooks.Topic<MyPayload>()` and register typed services with
`services.register(MyService.class, implementation)`; close the returned registration when done.
Custom navigation uses `services.registerScreen("example.settings", uiExecutor, host -> openSettings())`.
Both hosts route `host.openScreen("example.settings")` to it. Standard screen names are reserved;
closing the registration also cancels queued opens for a screen that has gone away.

Use `host.gameBoot()` on a worker after drawing the launcher. It retains its once-per-process contract;
after a failed boot, restart instead of retrying the same mounted classes. Screen names `game`,
`main-menu` and `extensions` are provided by both hosts; Android also provides `launcher`.

## Desktop options and recovery

The PC host opens its bootstrap before executing extension classes, onLoad callbacks or registering
extension mixins. Starting from that bootstrap runs extension loading and validation on a worker, then
starts the selected UI framework or the shared game boot. Import, version choice and language selection
are available before extension code executes. Custom UI frameworks also follow this host bootstrap.
The graphical launcher is the default. `--rwml-no-launcher` skips it for direct launch; headless
environments also proceed without a window when version choices are resolved. An unresolved enabled
version conflict prevents direct/headless boot and is reported in the load report. `--rwml-manager` selects the management launcher even when
the builtin UI is disabled. RWML options are removed before passing arguments to the game.
The Java 8 provisioning layer shows a loading window before core is available and reads the same
translation resources without linking to core classes.

## Extension metadata

All metadata is UTF-8 `META-INF/extension.properties`. `id` and `mainClass` are required. Supported keys
are `name`, `version`, `description`, `authors` (or `author`), `license`, `licenseUrl`, `homepage`, `icon`,
`tags`, `apiVersion`, `javaVersion`, `ui-framework`, `dependsOn`, `loadBefore`, and `loadAfter`.
Author lists split on commas or semicolons, preserving spaces in names. Dependency, tag and ordering
lists also accept whitespace separators. Defaults are id as name, version `1.0.0`, API `1.0`, and Java 8;
other optional fields are empty. Runtime bytecode checks may raise the Java requirement.

The `ExtensionInfo` annotation API and annotation scanning have been removed. Migrate old declarations
into properties, including an explicit `mainClass`; annotation-only archives are shown as invalid.
Built-ins use the same parser with host-specific resources under `META-INF/rwml/`, while retaining their
source-free and protected lifecycle. The sample extension contains only the properties descriptor.

## Failure recovery

The hosts stop game preparation when extension loading fails. A mixin registration error, callback
exception (including linkage errors), invalid UI entry or failed mixin transformation disables the
responsible extension in the persisted state and returns to the host bootstrap. Dependencies and
unrelated extensions are not automatically disabled for another extension's failure. Version conflicts
remain user choices rather than errors that disable an id.

Unparseable archives have no usable id, so the manager quarantines their SHA-256 instead of renaming or
deleting files. The same broken bytes are skipped on the next launch; replacing the file with corrected
bytes allows it to be inspected again. Malformed archives remain visible and removable in management.
Original failure causes remain in the diagnostic log.

A process may already have registered mixins, appended an extension to the host loader or defined
patched classes when a failure occurs. Recovery therefore requires restart; it does not retry the game
in that process. `ExtensionManager.recoveryRequired()` and `ExtensionLibrary.restartRequired()` expose
that state. Both hosts configure `GameBoot.stopOnFailure()` so later engine/mod stages cannot run after
a failed stage. The original continue-and-report policy remains available to other GameBoot clients.

Desktop reads only annotated mixin class headers before registration, validates raw targets, and
retransforms only targets already defined by the JVM. Its transformer reports failures and rejects the
class definition instead of letting the JVM silently continue with unpatched bytes. Android maps
transformation failures to their owning archive before returning to its bootstrap, and constructs
recovery services before early UI dex mounting.
