package com.kodewerk.diskinventory.ui;

import com.kodewerk.diskinventory.model.DirectoryNode;
import com.kodewerk.diskinventory.model.DiskUsageModel;
import com.kodewerk.diskinventory.model.SizeMode;
import com.kodewerk.diskinventory.model.Sizes;
import javafx.application.Application;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point, kept separate from {@link DiskInventoryApp} because a native
 * image is launched from the classpath, where JavaFX refuses to start an
 * Application subclass directly ("JavaFX runtime components are missing").
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("--scan")) {
            System.exit(scan(Path.of(args[1])));
        }
        Application.launch(DiskInventoryApp.class, args);
    }

    /**
     * Headless scan. Prints totals and returns the process exit code: 0 on
     * success, 1 if the path cannot be scanned, 3 if allocated sizes silently
     * degraded to logical ones because the FFM downcall is unavailable.
     */
    static int scan(Path root) {
        boolean allocatedWorks = DiskUsageModel.allocatedSizeSupported();
        DirectoryNode tree;
        try {
            tree = new DiskUsageModel().scan(root.toAbsolutePath().normalize()).root();
        } catch (IOException e) {
            System.err.println("scan failed: " + e.getMessage());
            return 1;
        }
        System.out.println("root      " + tree.path());
        System.out.println("logical   " + Sizes.human(tree.totalSize(SizeMode.LOGICAL)));
        System.out.println("allocated " + Sizes.human(tree.totalSize(SizeMode.ALLOCATED)));
        System.out.println("errors    " + tree.errorCount());
        if (!allocatedWorks) {
            System.err.println("allocated sizes unavailable: the lstat downcall did not work");
            return 3;
        }
        return 0;
    }
}
