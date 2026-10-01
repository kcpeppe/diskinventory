# Disk Inventory

Disk usage explorer. Scans a directory tree once, then lets you drill into the
usage breakdown with a clickable pie chart — one slice per subdirectory, plus a
`[files]` slice for files directly at that level.

## Design

- `com.kodewerk.diskinventory.model` — headless API. `DiskUsageModel.scan(Path)`
  walks the tree in a single pass (symlinks neither followed nor counted,
  unreadable entries counted and skipped) and returns an immutable
  `DirectoryNode` tree. Both sizes are tracked per file and per directory:
  **logical** (`Files.size()`, apparent) and **allocated** (`st_blocks * 512`
  via an FFM `lstat` downcall, what `du` reports — sparse files like
  `Docker.raw` show their true footprint). Fully testable without a display.
- `com.kodewerk.diskinventory.ui` — thin JavaFX wrapper. Clicking a slice
  navigates the already-built tree; no rescanning on drill-down. Breadcrumb and
  **Up** navigate back — Up keeps working past the launch point (the known
  subtree is grafted, only new siblings are walked) until `/`. **Rescan**
  re-walks the disk. An **Allocated / Logical** toggle switches every number in
  the view instantly (no rescan). Slices under 1.5% are grouped into one
  `[n small dirs]` slice (hover for names); hovering `[files]` lists that
  directory's files largest-first. During a scan: spinner, live dir/byte
  counts, and an **Analyze this path** button that stops the scan and re-roots
  the analysis at the directory being scanned. The bottom panel shows `df -h`
  for the current root as a table. A **Largest files** panel on the right lists
  the top 50 files under the current directory (recursive), biggest first. A
  directory tree of the whole disk sits in the left gutter, kept in sync with
  the pie: click a node to refocus, navigate the pie and the branch opens.
  Right-click a slice or tree node to **Rescan** it; slices, tree nodes and
  Largest-files rows (also Delete / Shift+Delete) offer **Move to Trash** and
  **Delete permanently**. A delete always ends with a rescan of
  the deleted path, so the tree matches the disk even after a failed or stopped
  delete. A file with several hardlinks is charged once, to its owner (its
  smallest path); the other links' directories show it as shared bytes, and the
  delete dialog reports what is actually freed on disk, which is 0 while
  another link survives.

## Run

```
mvn javafx:run -Djavafx.args=/path/to/scan
```

or without an argument to get a directory chooser:

```
mvn javafx:run
```

Or headlessly, printing totals without opening a window:

```
mvn -q -DskipTests package
java -cp target/diskinventory.jar com.kodewerk.diskinventory.ui.Main --scan /path/to/scan
```

Exit codes: `0` scan completed, `1` path missing or unreadable, `3` allocated
sizes are unavailable on this build and the reported figures are logical only.

## Test

```
mvn test
```

## Package

`bin/package.sh` builds a native installer for the current platform with
jpackage — `.dmg` on macOS, `.deb` on Linux — with a minimal jlinked runtime
(app module + JavaFX jmods) bundled, so the result needs no Java on the
target machine. Installers are unsigned for now: macOS users right-click →
Open the first time.

Pushing a `v*` tag runs `.github/workflows/release.yml`, which builds the
installers and the native tarballs described below (mac arm64, linux x64) and
attaches them all to a GitHub Release.

Intel macOS and Windows builds are not maintained — PRs welcome. The
machinery is close: `bin/package.sh` handles osx-x64 already, and
`jpackage --type msi` under git-bash built successfully as of v1.0.0.

## Native executable

A single ahead-of-time compiled binary, built with
[Liberica NIK Full](https://bell-sw.com/liberica-native-image-kit/) 25, which
bundles JavaFX. Starts in milliseconds and carries no runtime directory.

Download `diskinventory-<version>-linux-x64.tar.gz` or
`-osx-aarch64.tar.gz` from the releases page, unpack, run.

- **Linux:** needs glibc 2.35 or newer (Ubuntu 22.04+, Debian 12+) and the usual
  desktop GTK3 stack, which stays dynamically linked.
- **macOS:** the binary is unsigned, so Gatekeeper quarantines it after
  download. Clear it with `xattr -d com.apple.quarantine ./diskinventory`. The
  `.dmg` is unsigned too — this is not specific to the native build.

Build it yourself with NIK Full as `JAVA_HOME`:

```
JAVA_HOME=/path/to/liberica-nik-full-25 ./mvnw -Pnative -DskipTests package
./target/diskinventory --scan .
```

The `.deb` and `.dmg` installers built by `bin/package.sh` are unaffected and
remain the recommended install for most people.

### Regenerating native-image metadata

Needed only if reflection, resources, or FFM usage changes. Requires NIK Full
and a display; the agent writes its config when the JVM exits, so quit the app
normally.

```
mvn -q -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
NIK=~/.sdkman/candidates/java/25.0.4.fx-nik
CP="target/classes:$(cat target/cp.txt)"
OUT=src/main/resources/META-INF/native-image/com.kodewerk/diskinventory

$NIK/bin/java -agentlib:native-image-agent=config-output-dir=$OUT --enable-native-access=ALL-UNNAMED,javafx.graphics -cp "$CP" \
    com.kodewerk.diskinventory.ui.Main            # click through every screen, then quit
$NIK/bin/java -agentlib:native-image-agent=config-merge-dir=$OUT --enable-native-access=ALL-UNNAMED,javafx.graphics -cp "$CP" \
    com.kodewerk.diskinventory.ui.Main --scan .
```

JavaFX internals need no entries here: NIK's own build-time feature registers
them for every platform, which is why one Linux capture is valid for the macOS
build too.
