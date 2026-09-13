package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * The layout document this client is drawing, and where it is kept.
 *
 * <p>Owns one file, one in-memory document, and the rule that connects them: what
 * is on screen is always a document that parsed. Everything else follows from
 * that.
 *
 * <ul>
 *   <li><b>First run</b> writes the default package to disk, so an operator has a
 *       real file to open and edit rather than a document that only exists in
 *       memory.</li>
 *   <li><b>A corrupt file is never overwritten.</b> If the document on disk does
 *       not parse, the preset is used in memory and the file is left alone. The
 *       operator is very likely halfway through an edit, and a package that
 *       silently reverted their work on startup is worse than one that ignored
 *       it.</li>
 *   <li><b>Writes are atomic.</b> Through a temporary file and a move, so a
 *       removal or a crash mid-write cannot leave a half-written document where
 *       the package expects one — the failure mode being a black screen during a
 *       broadcast.</li>
 * </ul>
 *
 * <p>No Minecraft types and no dependency on how the config directory is found: the
 * caller passes the path, which is what makes the whole store testable.
 */
public final class LayoutStore {

    private final Path file;
    private final Consumer<String> log;

    private volatile Layout current;
    private volatile String savedJson = "";
    private volatile String preset = Layouts.PRESET_ARENA;

    /**
     * @param file where the document lives; its parent is created if needed
     * @param log  where to report; never thrown at a caller
     */
    public LayoutStore(Path file, Consumer<String> log) {
        this.file = file.toAbsolutePath().normalize();
        this.log = log;
        this.current = Layouts.load(preset);
    }

    /** The document being drawn. Never null. */
    public Layout current() {
        return current;
    }

    /** Which built-in package the current document came from. */
    public String preset() {
        return preset;
    }

    public Path file() {
        return file;
    }

    /** Compact JSON, for the SSE stream and the API. */
    public String wireJson() {
        return Layouts.toJson(current);
    }

    /** Indented JSON, for the file on disk and the editor's text pane. */
    public String prettyJson() {
        return Layouts.toPrettyJson(current);
    }

    // ---- loading ------------------------------------------------------------

    /**
     * Reads the document from disk, or installs the preset when there is none.
     *
     * @return the document now in use
     */
    public synchronized Layout load() {
        if (!Files.isRegularFile(file)) {
            write();
            log.accept("No layout on disk; wrote the " + preset + " package to " + file);
            return current;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.accept("Could not read " + file + " (" + e.getMessage() + "); using the "
                    + preset + " package");
            return current;
        }

        Layout parsed = Layouts.fromJson(text);
        if (parsed == null) {
            // Left on disk on purpose: see the class comment.
            log.accept("The layout at " + file + " could not be read; using the "
                    + preset + " package and leaving the file untouched");
            return current;
        }

        current = parsed;
        savedJson = Layouts.toJson(parsed);
        log.accept("Loaded layout '" + parsed.name + "' (" + parsed.elements.size()
                + " elements) from " + file);
        return current;
    }

    /** True when the document on disk differs from the one in memory. */
    public synchronized boolean fileChangedSinceRead() {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.equals(savedJson) || text.equals(prettyJson())) {
                return false;
            }
            Layout parsed = Layouts.fromJson(text);
            if (parsed == null) {
                return false;
            }
            current = parsed;
            savedJson = Layouts.toJson(parsed);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- changing -----------------------------------------------------------

    /**
     * Switches to a built-in package and writes it out.
     *
     * @return the new document, or null when {@code name} is not a shipped package
     */
    public synchronized Layout applyPreset(String name) {
        if (!Layouts.isPreset(name)) {
            return null;
        }
        preset = name;
        current = Layouts.load(name);
        write();
        log.accept("Layout preset changed to '" + name + "' and saved to " + file);
        return current;
    }

    /**
     * Installs a document that arrived from the editor.
     *
     * @return the normalised document, or null when the text is not one. A null
     *         return is the caller's cue to answer the browser with a 400 rather
     *         than pretend the save worked.
     */
    public synchronized Layout accept(String json) {
        Layout parsed = Layouts.fromJson(json);
        if (parsed == null) {
            log.accept("Rejected a layout from the editor: it is not a layout document");
            return null;
        }
        current = parsed;
        write();
        return current;
    }

    /** Re-reads the document from disk, for an operator editing the file by hand. */
    public synchronized Layout reload() {
        return load();
    }

    // ---- writing ------------------------------------------------------------

    private void write() {
        String json = Layouts.toPrettyJson(current);
        Path parent = file.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Written beside the target and moved into place, so a reader (this mod
            // on the next start, or an operator with the file open) never sees a
            // partially written document.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot do it atomically. A plain replace is still
                // better than writing the target in place.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            savedJson = Layouts.toJson(current);
        } catch (IOException e) {
            // Reported rather than thrown: a package that cannot be saved is worth
            // knowing about, but it is not a reason to take the graphics down.
            log.accept("Could not save the layout to " + file + ": " + e.getMessage());
        }
    }
}
