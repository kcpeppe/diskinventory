# Delete and Partial Rescan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete (Trash or permanent) and rescan one path from inside the app, with hardlinks charged to exactly one owning link, and every view updated without a full rescan.

**Architecture:** Every model operation returns an immutable `ScanResult(DirectoryNode root, InodeIndex inodes)`. `TreeEdit` rebuilds only the path from a changed node to the root; `InodeIndex` tracks shared files (`nlink > 1`) and re-settles each one's owner (smallest path) after every scan, Up graft and rescan. A delete is `Deleter` followed by `rescan` of each deleted path.

**Tech Stack:** Java 25, FFM (`lstat` downcall), JavaFX 25, JUnit 5.11, Maven.

**Spec:** `docs/superpowers/specs/2026-09-29-delete-and-partial-rescan-design.md` (also `docs/adr/0001-one-owner-per-shared-file.md`, `CONTEXT.md` for vocabulary).

## Global Constraints

- Platforms: Linux and macOS. Windows: `fileKey()` is `null` → hardlinks counted per link, Trash items hidden, rescan and permanent delete still work. No Windows-specific code.
- Owner of a shared file = lexicographically smallest link path in the tree, by `Path.compareTo`.
- `InodeIndex` holds only files with `nlink > 1` and a non-null `fileKey`.
- **Freed on disk** always uses allocated size, whatever the Allocated/Logical toggle says.
- `st_nlink` offsets: linux x86_64 @16 u64; linux aarch64 @20 u32; darwin 64-bit @6 u16. Fallback when the probe is unavailable: `Files.getAttribute(f, "unix:nlink", NOFOLLOW_LINKS)`.
- Trash: Linux `gio trash <path>`; macOS Finder via `osascript`. Never a silent fallback to permanent delete.
- Never delete the scan root or anything above it. One operation (scan/rescan/delete) at a time.
- A previous `ScanResult` is never mutated: copy `InodeIndex` before changing it.
- Vocabulary in code, UI text and comments follows `CONTEXT.md` (rescan, link, owner, shared bytes, freed on disk).
- Hardlink tests: `@EnabledOnOs({OS.MAC, OS.LINUX})`. Tests need no display.
- Run tests from the repo root: `mvn -q test` (CI runs `mvn -B test`).
- The native image (`-Pnative`, Liberica NIK) must keep building: no AWT (so no `java.awt.Desktop` for Trash), and no new FFM downcall signatures — nlink comes from the existing `lstat` call, whose stub is already in `reachability-metadata.json`.

## Review Focus

1. **A rescanned path changed type** (file became a directory, or the reverse): the old entry disappears and the new one appears with correct totals. Test: `rescanWhenFileBecameDirectory` (Task 4).
2. **Rescan of a path the tree doesn't know yet** (created outside the app inside a known directory): it is inserted, not rejected. Test: `rescanOfNewPathInsertsIt` (Task 4).
3. **Two links of one file in the same directory**: counted once; deleting either keeps the directory total. Test: `twoLinksInOneDirectoryCountedOnce` (Task 3) and `deleteOneOfTwoLinksInSameDirectory` (Task 4).
4. **Rescan cancelled mid-walk**: the previous `ScanResult` is untouched, including its index. Test: `cancelledRescanLeavesPreviousResultIntact` (Task 4).
5. **Totals drift after a chain of edits** (double uncharge, charge left on a vanished dir): after any sequence of rescans the tree equals a fresh scan. Every Task 4/5 test ends with `assertMatchesFreshScan(result)`.

---

## File Structure

```text
model/
  FileEntry.java        MODIFY  + nlink, fileKey; shared()
  FileRef.java          MODIFY  + otherLinks
  AllocatedSizeProbe    MODIFY  allocatedOf → stat() returning Stat(allocated, nlink)
  TreeEdit.java         CREATE  replace(), charge(), Charge record
  InodeIndex.java       CREATE  links per fileKey, owner, settle()
  ScanResult.java       CREATE  record(root, inodes) + view helpers
  Freed.java            CREATE  record(freed, staying)
  Deleter.java          CREATE  trash / permanent, Outcome
  DirectoryNode.java    MODIFY  largestFiles dedupes shared files
  DiskUsageModel.java   MODIFY  returns ScanResult; rescan; freedOnDisk
ui/
  LazyDirectoryItem     MODIFY  reload()
  DiskInventoryApp      MODIFY  ScanResult state, rescan, context menus, delete flow
  Main                  MODIFY  headless --scan reads ScanResult.root()
test/.../model/
  TreeEditTest.java     CREATE
  HardlinkTest.java     CREATE  ownership, rescan, freedOnDisk (Tasks 3–5)
  DeleterTest.java      CREATE
  AllocatedSizeProbeTest.java CREATE
  DiskUsageModelTest    MODIFY  .root() on scan results; rescan tests without hardlinks
```

---

### Task 1: nlink and fileKey per file

**Files:**
- Modify: `src/main/java/com/kodewerk/diskinventory/model/AllocatedSizeProbe.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/model/FileEntry.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java` (Visitor.visitFile; `allocatedSizeSupported()` switches to `probe.stat(Path.of("."), -1L).allocated() >= 0L`)
- Test: `src/test/java/com/kodewerk/diskinventory/model/AllocatedSizeProbeTest.java`

**Interfaces:**
- Produces:
  - `record AllocatedSizeProbe.Stat(long allocated, long nlink)` (package-private, nested)
  - `AllocatedSizeProbe.Stat stat(Path path, long fallbackAllocated)` — on any failure returns `Stat(fallbackAllocated, 1)`. Replaces `allocatedOf`.
  - `public record FileEntry(String name, long size, long allocated, long nlink, Object fileKey)`; `fileKey` is non-null only when `nlink > 1`. Adds `public boolean shared() { return fileKey != null; }`.
  - Package-private static in `DiskUsageModel`: `FileEntry entryFor(Path file, BasicFileAttributes attrs, AllocatedSizeProbe probe)` — the single place a `FileEntry` is built (used by Visitor now, by rescan in Task 4).

- [ ] **Step 1: Write the failing test**

```java
@Test
@EnabledOnOs({OS.MAC, OS.LINUX})
void nlinkReadByProbeMatchesJdk(@TempDir Path dir) throws IOException {
    Path a = Files.write(dir.resolve("a"), new byte[10]);
    Files.createLink(dir.resolve("b"), a);
    Files.createLink(dir.resolve("c"), a);
    Path single = Files.write(dir.resolve("single"), new byte[10]);
    try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
        assertNotNull(probe);
        for (Path p : List.of(a, single)) {
            assertEquals(((Number) Files.getAttribute(p, "unix:nlink", NOFOLLOW_LINKS)).longValue(),
                    probe.stat(p, 0).nlink());
        }
        assertEquals(3, probe.stat(a, 0).nlink());
        assertEquals(new AllocatedSizeProbe.Stat(77, 1), probe.stat(dir.resolve("missing"), 77));
    }
}
```

Also add to `DiskUsageModelTest` (still on the old `DirectoryNode` API in this task):

```java
@Test
@EnabledOnOs({OS.MAC, OS.LINUX})
void sharedFileEntriesCarryNlinkAndFileKey(@TempDir Path root) throws IOException {
    write(root.resolve("a"), 100);
    Files.createLink(root.resolve("b"), root.resolve("a"));
    write(root.resolve("c"), 100);
    DirectoryNode result = model.scan(root);
    FileEntry a = entry(result, "a"), b = entry(result, "b"), c = entry(result, "c");
    assertEquals(2, a.nlink());
    assertEquals(a.fileKey(), b.fileKey());
    assertTrue(a.shared());
    assertFalse(c.shared());
    assertEquals(1, c.nlink());
}
```

(`entry(node, name)` is a small private helper finding a file by name.)

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest='AllocatedSizeProbeTest,DiskUsageModelTest#sharedFileEntriesCarryNlinkAndFileKey'`
Expected: compilation failure — `stat` / `nlink()` not defined.

- [ ] **Step 3: Implement**

- Probe: pick the nlink offset and width next to `blocksOffsetForOs()` using `os.name` and `os.arch` (`amd64`/`x86_64` vs `aarch64`), per the Global Constraints table. Darwin reads `JAVA_SHORT & 0xFFFF`, linux aarch64 `JAVA_INT & 0xFFFFFFFFL`, linux x86_64 `JAVA_LONG`. Unknown arch on Linux → `create()` returns null (existing fallback path).
- `entryFor`: `attrs.fileKey() == null` → `nlink = 1`, no stat for nlink beyond the probe. Probe null → nlink from `unix:nlink` (catch `UnsupportedOperationException`/`IOException` → 1). `fileKey` kept only when `nlink > 1`.
- `visitFile` still counts every file in direct size in this task (ownership arrives in Task 3).

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/model src/test/java/com/kodewerk/diskinventory/model
git commit -m "feat: read nlink and fileKey for every scanned file"
```

---

### Task 2: TreeEdit — path-to-root rebuild

**Files:**
- Create: `src/main/java/com/kodewerk/diskinventory/model/TreeEdit.java`
- Test: `src/test/java/com/kodewerk/diskinventory/model/TreeEditTest.java`

**Interfaces:**
- Consumes: `DirectoryNode` package-private constructor; `FileEntry.shared()` (Task 1).
- Produces (all package-private, `final class TreeEdit`, static methods):
  - `record Charge(long size, long allocated)` with `Charge plus(Charge o)` and `Charge negate()`.
  - `static DirectoryNode replace(DirectoryNode root, Path p, DirectoryNode dir, FileEntry file)` — `p` strictly under `root.path()`; at most one of `dir`/`file` non-null. Removes any child directory **and** any file entry named `p.getFileName()` in `p`'s parent, then inserts `dir` or `file`. Throws `IllegalArgumentException` if `p` is not strictly under the root or its parent directory is not in the tree.
  - `static DirectoryNode charge(DirectoryNode root, Map<Path, Charge> deltas)` — adds each delta to that directory's direct and total sizes and to every ancestor's total. Keys not in the tree are ignored. Each affected path is rebuilt once.

Rules the tests pin:
- A file entry contributes to its directory's direct size only when `!file.shared()` (shared files are charged via `charge`).
- `errorCount` along the path changes by `(new subtree errors − removed subtree errors)`.
- Rebuilt `children` stay sorted by logical `totalSize` descending, `files` by logical size descending (same comparators as the Visitor).
- Untouched subtrees are the same instances.

- [ ] **Step 1: Write the failing tests** (build input trees with `new DiskUsageModel().scan(tempDir)` — `.root()` once Task 3 lands; in this task `scan` still returns `DirectoryNode`)

```java
@Test
void treeEditSharesUntouchedSubtrees(@TempDir Path root) throws IOException {
    // root/a/x/f(100), root/b/g(200)
    DirectoryNode before = scan(root);
    DirectoryNode after = TreeEdit.replace(before, root.resolve("a/x/f"), null, null);
    assertSame(before.child("b").orElseThrow(), after.child("b").orElseThrow());
    assertEquals(200, after.totalSize());
    assertEquals(0, after.child("a").orElseThrow().totalSize());
}

@Test
void replaceInsertsNewDirectoryAndKeepsSortOrder(@TempDir Path root) ...
    // root/small/f(10), root/big/f(500); replace root/new with a scanned 1000-byte dir
    // → children names == [new, big, small], root total == 1510

@Test
void replaceSwapsFileForDirectoryOfSameName(@TempDir Path root) ...
    // root/n is a 100-byte file; replace(root, root/n, dirNodeOf300Bytes, null)
    // → root.files() has no "n", child("n") total 300, root total 300

@Test
void chargeAddsToDirectAndAncestorTotals(@TempDir Path root) ...
    // root/a/b empty; charge {root/a/b: Charge(10, 4096)}
    // → b.directFileSize()==10, b.directFileSize(ALLOCATED)==4096, a.totalSize()==10, root.totalSize()==10
    // charge back with negate() → all zero; unknown key root/zzz ignored

@Test
void replaceRejectsPathsNotUnderRoot(@TempDir Path root) ...
    // assertThrows(IllegalArgumentException, replace(tree, root, null, null))
    // assertThrows(IllegalArgumentException, replace(tree, root.resolve("missing/x"), null, null))
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=TreeEditTest`
Expected: compilation failure — `TreeEdit` not defined.

- [ ] **Step 3: Implement `TreeEdit`**

Recursive descent from `root` along `root.path().relativize(target)`; at each level rebuild only the child on the path. For `charge`, recurse into a child only when some delta key `startsWith(child.path())`.

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/model/TreeEdit.java src/test/java/com/kodewerk/diskinventory/model/TreeEditTest.java
git commit -m "feat: TreeEdit rebuilds only the path to the root"
```

---

### Task 3: InodeIndex, ScanResult and one owner per shared file

**Files:**
- Create: `src/main/java/com/kodewerk/diskinventory/model/InodeIndex.java`
- Create: `src/main/java/com/kodewerk/diskinventory/model/ScanResult.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java` (compile only: `Task<DirectoryNode>` call sites take `.root()`; `scanParent` gets a `ScanResult` — keep a `ScanResult result` field set in `setOnSucceeded`)
- Modify: `src/test/java/com/kodewerk/diskinventory/model/DiskUsageModelTest.java` (`.root()`; `scanParentOfFilesystemRootReturnsSameNode` wraps the node in `new ScanResult(node, new InodeIndex())` and asserts the same `ScanResult` is returned)
- Modify: `src/test/java/com/kodewerk/diskinventory/model/TreeEditTest.java` (its `scan` helper takes `.root()`)
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/Main.java` (headless `--scan` takes `.root()`; `MainTest` must keep passing)
- Test: `src/test/java/com/kodewerk/diskinventory/model/HardlinkTest.java`

**Interfaces:**
- Consumes: `TreeEdit.charge`, `TreeEdit.Charge` (Task 2); `FileEntry.fileKey/nlink/shared` (Task 1).
- Produces:
  - `public record ScanResult(DirectoryNode root, InodeIndex inodes)`.
  - `public final class InodeIndex` (public type, package-private mutators):
    - `InodeIndex()`, `InodeIndex copy()` (deep: each inode's link set copied)
    - `void addLink(Object key, Path link, long nlink, long size, long allocated)` — latest nlink/sizes win
    - `Set<Object> removeLinksUnder(Path p)` — removes links equal to or under `p`, returns their keys; sets an inode's owner to null when the owner is **strictly** under `p` (its charge vanishes with the replaced subtree; an owner equal to `p` keeps its charge and is uncharged by `settle`)
    - `Map<Path, TreeEdit.Charge> settle(Set<Object> keys)` — per key: new owner = smallest link; old owner ≠ new → `−size` on old owner's parent (if old non-null) and `+size` on new owner's parent; no links left → remove the inode. Mutates owners, returns merged per-directory deltas.
    - `public Optional<Path> owner(Object key)`, `public List<Path> linksOf(Object key)` (sorted)
  - `DiskUsageModel` (public API changes):
    - `ScanResult scan(Path root)`, `ScanResult scan(Path root, ScanListener)`
    - `ScanResult scanParent(ScanResult known, ScanListener)` — returns `known` itself at the filesystem root
  - Visitor: takes an `InodeIndex` to fill; shared entries add 0 to direct size, call `addLink`, and record the key in `Set<Object> touched()`. After the walk: `TreeEdit.charge(root, index.settle(touched))`.

- [ ] **Step 1: Write the failing tests** in `HardlinkTest` (class-level `@EnabledOnOs({OS.MAC, OS.LINUX})`; `MIB = 10 * 1024 * 1024`; helper `link(Path from, Path to)` creating parent dirs then `Files.createLink`)

```java
@Test
void hardlinkCountedOnce(@TempDir Path root) throws IOException {
    write(root.resolve("a/f"), MIB);
    link(root.resolve("b/f"), root.resolve("a/f"));
    ScanResult r = model.scan(root);
    assertEquals(MIB, r.root().totalSize());
    assertEquals(MIB, node(r, "a").totalSize());
    assertEquals(0, node(r, "b").totalSize());
    assertEquals(MIB, entry(r, "b/f").size());     // listed at full size, not charged
}

@Test
void twoLinksInOneDirectoryCountedOnce(@TempDir Path root) ...
    // root/d/x, root/d/y same file → d.totalSize()==MIB, d.directFileSize()==MIB

@Test
void scanParentResettlesOwnership(@TempDir Path parent) throws IOException {
    write(parent.resolve("child/f"), MIB);
    link(parent.resolve("aaa/f"), parent.resolve("child/f"));
    ScanResult known = model.scan(parent.resolve("child"));
    assertEquals(MIB, known.root().totalSize());
    ScanResult up = model.scanParent(known, (d, n, b) -> { });
    assertEquals(MIB, up.root().totalSize());
    assertEquals(MIB, node(up, "aaa").totalSize());
    assertEquals(0, node(up, "child").totalSize());
    assertEquals(MIB, known.root().totalSize());    // previous result untouched
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=HardlinkTest`
Expected: compilation failure — `ScanResult` not defined.

- [ ] **Step 3: Implement** `InodeIndex`, `ScanResult`, Visitor/`scan`/`scanParent` changes, the test and UI call-site updates listed under **Files**. `scanParent` walks with `known.inodes().copy()` and grafts `known.root()`.

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass, including every pre-existing `DiskUsageModelTest` test.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat: charge each shared file to its smallest link"
```

---

### Task 4: rescan

**Files:**
- Modify: `src/main/java/com/kodewerk/diskinventory/model/DiskUsageModel.java`
- Test: `src/test/java/com/kodewerk/diskinventory/model/HardlinkTest.java`, `DiskUsageModelTest.java`

**Interfaces:**
- Consumes: `TreeEdit.replace` (Task 2); `InodeIndex.copy/removeLinksUnder/settle`, Visitor `touched()` (Task 3); `entryFor` (Task 1).
- Produces: `public ScanResult rescan(ScanResult result, Path p, ScanListener listener) throws IOException`
  - `p.equals(scan root)` → `scan(root, listener)`.
  - `p` not strictly under the scan root → `IllegalArgumentException`.
  - Steps exactly as the spec's *Rescan* section: copy index → `removeLinksUnder(p)` → walk (`p` a real directory: Visitor over `p` sharing the index; `p` a regular file: `entryFor` + `addLink` if shared; gone or a symlink: nothing) → `TreeEdit.replace` → `TreeEdit.charge(…, settle(affected ∪ touched))`.
  - Interrupt → `ScanCancelledException`, `result` untouched.

Test helper used by every test from here on (put in a shared `TestTrees` class in the test package):

```java
/** Every directory in result has the same logical and allocated totals as a fresh scan of the disk. */
static void assertMatchesFreshScan(DiskUsageModel model, ScanResult result) throws IOException
```

Walk both trees by path; compare `totalSize(LOGICAL)`, `totalSize(ALLOCATED)`, `directFileSize(LOGICAL)` and the set of child names at every node.

- [ ] **Step 1: Write the failing tests**

`DiskUsageModelTest` (no hardlinks):
- `rescanPicksUpNewFiles` — scan; add `sub/new.bin` (300); `rescan(r, root/sub)` → root total +300; `assertMatchesFreshScan`.
- `rescanOfVanishedPathDropsIt` — scan; delete `sub` recursively outside the app; `rescan(r, root/sub)` → `child("sub")` empty; matches fresh scan.
- `rescanOfNewPathInsertsIt` — scan; create `root/fresh/x.bin`; `rescan(r, root/fresh)` → child present; matches fresh scan.
- `rescanWhenFileBecameDirectory` — `root/n` a 100-byte file; scan; replace with directory `root/n/y.bin` (300); `rescan(r, root/n)` → no file `n`, child `n` = 300; matches fresh scan.
- `rescanOfSingleFile` — scan; grow `root/a.bin` from 100 to 900; `rescan(r, root/a.bin)` → direct size 900; matches fresh scan.
- `rescanOfScanRootIsFullScan` and `rescanOutsideScanRootRejected` (`IllegalArgumentException`).
- `cancelledRescanLeavesPreviousResultIntact` — interrupt, assert `ScanCancelledException`, clear flag; previous `r` still totals the same and `r.inodes()` still lists its links (run this one in `HardlinkTest` with a shared file).

`HardlinkTest`:
- `ownerIsSmallestPathRegardlessOfWalkOrder` — `root/b/f` created first, `root/a/f` linked; scan → owner `a/f`; then `rescan(b)`, `rescan(a)`, and in the other order on a fresh scan: owner and every total identical.
- `deleteMovesOwnershipToSurvivingLink` — spec example: `root/a/x/big`, `root/b/y/z/big`; `Files.delete(a/x/big)`; `rescan(r, a/x/big)` → `a/x` = 0, `b/y/z` = MIB, root = MIB, owner `b/y/z/big`; matches fresh scan.
- `deleteLastLinkRemovesBytes` — single-link MIB file in `a/x`; delete + rescan → `a/x`, `a`, root each drop by MIB.
- `deleteOneOfTwoLinksInSameDirectory` — `d/x`, `d/y`; delete `d/x` + rescan → `d` = MIB; matches fresh scan.
- `rescanAddingSmallerLinkMovesOwnership` — owner `root/m/f`; outside the app link `root/a/f`; `rescan(r, root/a)` → owner `a/f`, `m` = 0, `a` = MIB.
- `rescanOfVanishedDirectoryResettlesOwnership` — `root/a/f` owner, `root/b/f` link; remove `root/a` recursively; `rescan(r, root/a)` → owner `b/f`, root = MIB.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest='HardlinkTest,DiskUsageModelTest'`
Expected: compilation failure — `rescan` not defined.

- [ ] **Step 3: Implement `rescan`**

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat: rescan one path and re-settle owners"
```

---

### Task 5: Freed on disk, Largest files once per file, shared bytes

**Files:**
- Create: `src/main/java/com/kodewerk/diskinventory/model/Freed.java`
- Modify: `DiskUsageModel.java`, `DirectoryNode.java`, `FileRef.java`, `ScanResult.java`, `InodeIndex.java`
- Test: `HardlinkTest.java`, `DiskUsageModelTest.java` (existing `largestFilesRanksRecursivelyAcrossTheSubtree` must keep passing)

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces:
  - `public record Freed(long freed, long staying)` — allocated bytes.
  - `public Freed freedOnDisk(ScanResult result, Set<Path> selection)` on `DiskUsageModel`: unshared files under the selection (from the tree, no disk access) → `freed += allocated`. Each shared inode with ≥1 link in the selection: re-stat one of its existing links (new probe, `unix:nlink` fallback); all links gone → skip; fresh `nlink == links in selection` → `freed`, else `staying`. A selection element may be a file or a directory.
  - `public record FileRef(Path directory, FileEntry file, List<Path> otherLinks)` — `path()` unchanged.
  - `DirectoryNode.largestFiles(limit, mode)`: a shared file appears once, under its smallest path within this node; `otherLinks` empty here.
  - `public List<FileRef> largestFiles(DirectoryNode node, int limit, SizeMode mode)` on `ScanResult` — same rows with `otherLinks = linksOf(key)` minus the row's path.
  - `public long sharedBytes(Path dir, SizeMode mode)` on `ScanResult` (delegates to `InodeIndex.sharedBytesUnder`) — sum, once per inode, of sizes of inodes with a link under `dir` and owner not under `dir`.

- [ ] **Step 1: Write the failing tests** (`HardlinkTest`)
- `largestFilesListsSharedFileOnce` — `a/top` (MIB) linked as `b/top`, plus `c/small` (100): `r.largestFiles(r.root(), 10, LOGICAL)` has 2 rows; first row path `a/top`, `otherLinks == [b/top]`.
- `deleteAllLinksFreesTheFile` — `freedOnDisk(r, {a/f, b/f})` → `freed == entry(a/f).allocated()`, `staying == 0`.
- `freedOnDiskCountsLinksOutsideRoot` — `tmp/root/f` linked from `tmp/outside/f`; scan `tmp/root` → root = MIB; `freedOnDisk(r, {root/f})` → `freed == 0`, `staying == allocated`; delete `root/f` + rescan → root total 0.
- `freedOnDiskUsesFreshNlink` — links `a/f`, `b/f`; scan; `Files.delete(b/f)` outside the app; `freedOnDisk(r, {a/f})` → `freed == allocated`.
- `freedOnDiskOfDirectoryCountsUnsharedFilesAndOwnedLinks` — `d/plain` (100) + `d/f` linked from `e/f`; `freedOnDisk(r, {d})` → `freed == allocated(d/plain)`, `staying == allocated(f)`.
- `sharedBytesReportsLinksOwnedElsewhere` — `a/f` owner, `b/f` link → `sharedBytes(b, LOGICAL) == MIB`, `sharedBytes(a, LOGICAL) == 0`, `sharedBytes(root, LOGICAL) == 0`.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=HardlinkTest`
Expected: compilation failure — `Freed` not defined.

- [ ] **Step 3: Implement**; update the UI cell factory to call `result.largestFiles(current(), TOP_FILES, mode)` (row text change waits for Task 8).

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main src/test
git commit -m "feat: freed-on-disk, shared bytes, one Largest-files row per file"
```

---

### Task 6: Deleter

**Files:**
- Create: `src/main/java/com/kodewerk/diskinventory/model/Deleter.java`
- Test: `src/test/java/com/kodewerk/diskinventory/model/DeleterTest.java`

**Interfaces:**
- Produces (`public final class Deleter`, static methods):
  - `public enum Status { OK, FAILED, STOPPED }`
  - `public record Outcome(Status status, List<Path> failed)`
  - `public static Outcome deletePermanently(Path p, BooleanSupplier stop)` — `Files.walkFileTree`, files in `visitFile`, directories in `postVisitDirectory` (deepest first); does not follow symlinks (deletes the link itself); a failure (incl. `visitFileFailed`) is added to `failed` and the walk continues; `stop.getAsBoolean()` checked before each deletion → `STOPPED` (with any failures so far). Result `OK` only when nothing failed.
  - `public static boolean trashAvailable()` — Linux: an executable `gio` in a `PATH` entry; macOS: true; otherwise false.
  - `public static void trash(Path p) throws IOException` — Linux `new ProcessBuilder("gio", "trash", p.toString())`; macOS `osascript -e 'on run argv' -e 'tell application "Finder" to delete (POSIX file (item 1 of argv))' -e 'end run' <path>` (path passed as argv, never interpolated into the script). Non-zero exit → `IOException` carrying stderr.
  - `public static Optional<Path> trashDir()` — Linux `$XDG_DATA_HOME/Trash` else `~/.local/share/Trash`; macOS `~/.Trash`; otherwise empty.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void permanentDeleteRemovesTree(@TempDir Path root) ...
    // root/t/a, root/t/s/b → OK, failed empty, t gone

@Test
@EnabledOnOs({OS.MAC, OS.LINUX})
void permanentDeleteReportsPartialFailure(@TempDir Path root) throws IOException {
    // root/t/ok.bin, root/t/locked/x.bin; chmod locked 000 (restore in finally)
    assumeFalse("root".equals(System.getProperty("user.name")));
    ScanResult r = model.scan(root);
    Deleter.Outcome out = Deleter.deletePermanently(root.resolve("t"), () -> false);
    assertEquals(Deleter.Status.FAILED, out.status());
    assertTrue(out.failed().contains(root.resolve("t/locked")));
    assertFalse(Files.exists(root.resolve("t/ok.bin")));
    ScanResult after = model.rescan(r, root.resolve("t"), (d, n, b) -> { });
    TestTrees.assertMatchesFreshScan(model, after);
}

@Test
void permanentDeleteStops(@TempDir Path root) ...
    // root/t with 10 files; stop = () -> calls.incrementAndGet() > 3
    // → STOPPED; exactly 3 files gone; rescan(r, root/t) matches fresh scan

@Test
@EnabledOnOs(OS.LINUX)
void trashDirFollowsXdgDefault() ...
    // with XDG_DATA_HOME unset in the env this test runs under → ~/.local/share/Trash
    // (skip via assumeTrue(System.getenv("XDG_DATA_HOME") == null))
```

Trash itself is not unit-tested (it would touch the user's real Trash); it's covered by the spec's manual check in Task 8.

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest=DeleterTest`
Expected: compilation failure — `Deleter` not defined.

- [ ] **Step 3: Implement `Deleter`**

- [ ] **Step 4: Run tests**

Run: `mvn -q test`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/model/Deleter.java src/test/java/com/kodewerk/diskinventory/model/DeleterTest.java
git commit -m "feat: Deleter for trash and stoppable permanent delete"
```

---

### Task 7: UI — ScanResult state and Rescan

**Files:**
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java`
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/LazyDirectoryItem.java`

**Interfaces:**
- Consumes: `ScanResult`, `DiskUsageModel.rescan`, `ScanResult.sharedBytes` (Tasks 3–5).
- Produces (private, used by Task 8):
  - `ScanResult result` field — the only tree state; `trail` stays a `List<DirectoryNode>` but is re-resolved by path after every edit (equivalent to the spec's `List<Path> trail`).
  - `void runTask(Task<ScanResult> task, boolean keepTrail)` — generalizes `runScan`. `keepTrail=false`: trail = `[result.root()]` (scan, Up). `keepTrail=true`: map old trail paths onto the new tree, truncating at the first path that no longer exists (nearest existing ancestor).
  - `void rescan(Path p)` — `runTask` of `model.rescan(result, p, progressReporter(...))`, `keepTrail=true`, then `reloadTreeItem(p.getParent())` and `refreshDf()`.
  - `TreeItem<Path> findTreeItem(Path p)` — extracted from `revealInTree`; `LazyDirectoryItem.reload()` sets `loaded=false` and re-lists on next `getChildren()`.
  - `ContextMenu pathMenu(Path p, boolean isDirectory)` — this task: **Rescan** only. Task 8 adds delete items.

Behavior:
- ↻ → `rescan(current().path())` (falls through to a full scan when current is the scan root). Shift+↻ (`MouseEvent.isShiftDown()` in the action handler) → full `scan()`. Update the ↻ tooltip to say both.
- Pie slices with a target directory and `dirTree` cells get `pathMenu`; `[files]` and `[n small dirs]` slices get none. A tree cell outside the scan root gets no Rescan item.
- Slice tooltip: when `result.sharedBytes(child.path(), mode) > 0`, append a line `"<X> shared with other directories"`.
- Menu items disabled while `currentTask` is running.

- [ ] **Step 1: Implement the above**

- [ ] **Step 2: Run tests and the app**

Run: `mvn -q test` → all pass.
Run: `mvn javafx:run -Djavafx.args=$HOME/some/dir`. Check: add a file in a subdirectory from a shell, right-click its slice → Rescan, totals update and the view stays on the current directory; delete that subdirectory from a shell, ↻ from inside it → view moves to its parent; Shift+↻ rescans from the scan root.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/ui
git commit -m "feat: rescan the current or any directory from the UI"
```

---

### Task 8: UI — delete flow and Largest-files rows

**Files:**
- Modify: `src/main/java/com/kodewerk/diskinventory/ui/DiskInventoryApp.java`
- Modify: `README.md` (Design section: delete, rescan, hardlink ownership — two or three sentences)

**Interfaces:**
- Consumes: `Deleter` (Task 6), `freedOnDisk`, `InodeIndex.linksOf`, `ScanResult.largestFiles` (Task 5), `runTask`, `pathMenu`, `rescan` (Task 7).
- Produces: `void confirmAndDelete(Set<Path> targets, boolean permanent)`.

Behavior (spec *Delete flow* and *UI*):
- `pathMenu` adds **Move to Trash** (only when `Deleter.trashAvailable()`) and **Delete permanently**; also on `filesList` rows (context menu + `Delete` / `Shift+Delete` keys). Disabled for the scan root or anything above it, and while an operation runs.
- Dialog (`Alert`, Cancel is the default button — set `setDefaultButton(false)` on the others): path; bytes counted here (`totalSize(mode)` or file size); Trash → "space is not freed until the Trash is emptied"; Permanent → `"frees X on disk"` from `freedOnDisk(...).freed()` and, if `staying > 0`, `"Y stays (linked elsewhere)"`. A shared file with other links in the tree → extra button **"Delete all N links in the tree"** (targets = `linksOf(key)`); if links exist outside the scan root it still reports 0 freed.
- Run in `runTask(..., keepTrail=true)`: Trash → `Deleter.trash` per target; Permanent → `deletePermanently(t, stopFlag::get)`. The status-row button reads **Stop** during a permanent delete and sets `stopFlag` (do not `task.cancel` — the rescan must still run). Then `rescan` each target, whatever the outcome; if trashed and `Deleter.trashDir()` is under the scan root, also rescan it. Then `refreshDf()` and `reloadTreeItem` of each target's parent.
- `FAILED` → dialog listing up to 25 paths (`TOOLTIP_MAX_LINES`). Trash `IOException` → error dialog with **"Delete permanently instead?"**; yes → reopen the flow with `permanent=true` (its own confirmation).
- Largest files row: `"%9s  %s"` plus `"  ⛓ +N links"` when `otherLinks` non-empty; tooltip lists the path then each other link.

- [ ] **Step 1: Implement the above**

- [ ] **Step 2: Run tests and the app**

Run: `mvn -q test` → all pass.
Run the app on a scratch directory holding a hardlinked pair (`ln a/f b/f`) and a plain file:
1. Delete permanently `a/f` from Largest files: dialog says frees 0, `1 stays`; after delete, `b` now holds the bytes, root total unchanged.
2. Shift+Delete a plain directory: totals drop, `df` refreshes, tree loses the node.
3. Move to Trash a directory: it appears in the Trash; on macOS use Put Back, then ↻ and confirm totals return (spec manual check).
4. Start a permanent delete of a large directory, press Stop: the tree shows what's left.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/kodewerk/diskinventory/ui README.md
git commit -m "feat: delete to Trash or permanently from the pie, tree and Largest files"
```
