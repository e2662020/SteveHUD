package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store is the thing that decides what is on air, so its rules about a missing,
 * malformed or hand-edited file are what these tests pin down.
 */
class LayoutStoreTest {

    @TempDir
    Path configDir;

    private final List<String> log = new ArrayList<>();

    private LayoutStore store() {
        return new LayoutStore(configDir.resolve("stevehud/layout.json"), log::add);
    }

    @Test
    @DisplayName("the first run writes the default package so there is a file to edit")
    void firstRunWritesTheDefault() {
        LayoutStore store = store();

        store.load();

        Path file = configDir.resolve("stevehud/layout.json");
        assertTrue(Files.isRegularFile(file), "a file should exist after the first load");
        assertEquals(Layouts.PRESET_ESPORTS, store.preset());
        assertFalse(store.current().elements.isEmpty());
        assertEquals(store.current().elements.size(),
                Layouts.load(Layouts.PRESET_ESPORTS).elements.size());
    }

    @Test
    @DisplayName("a corrupt file is left alone and the preset is used instead")
    void corruptFileIsNotOverwritten() throws IOException {
        Path file = configDir.resolve("stevehud/layout.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        LayoutStore store = store();
        store.load();

        // The operator is very likely halfway through an edit. Reverting their work
        // on startup would be worse than ignoring it for this session.
        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8),
                "the unreadable file must survive");
        assertFalse(store.current().elements.isEmpty(), "and the package still draws");
    }

    @Test
    @DisplayName("a document from the editor is stored and becomes current")
    void acceptsAnEditedDocument() {
        LayoutStore store = store();
        store.load();

        Layout edited = Layouts.load(Layouts.PRESET_MINIMAL);
        edited.name = "我的包装";

        Layout accepted = store.accept(Layouts.toJson(edited));

        assertNotNull(accepted);
        assertEquals("我的包装", store.current().name);
        // And it reached the disk, so a restart keeps the change.
        assertNotNull(Layouts.fromJson(readFile()));
        assertEquals("我的包装", Layouts.fromJson(readFile()).name);
    }

    @Test
    @DisplayName("a body that is not a document is rejected without touching the current one")
    void rejectsJunk() {
        LayoutStore store = store();
        store.load();
        String before = store.wireJson();

        assertNull(store.accept("{ not a document"));
        assertNull(store.accept(""));
        assertNull(store.accept("[]"));

        assertEquals(before, store.wireJson(), "a rejected save must not change the package");
    }

    @Test
    @DisplayName("a document with an unknown element type still loads, minus that element")
    void partialDocumentsLoad() {
        LayoutStore store = store();

        // What the editor sends when it is a version ahead of the mod.
        Layout accepted = store.accept("""
                {"name":"future","elements":[
                  {"id":"a","type":"timer"},
                  {"id":"b","type":"hologram-from-the-future"}]}
                """);

        assertNotNull(accepted);
        assertEquals(1, accepted.elements.size());
    }

    @Test
    @DisplayName("switching preset replaces the document and saves it")
    void applyPreset() {
        LayoutStore store = store();
        store.load();

        Layout olympic = store.applyPreset(Layouts.PRESET_OLYMPIC);

        assertNotNull(olympic);
        assertEquals(Layouts.PRESET_OLYMPIC, store.preset());
        Layout onDisk = Layouts.fromJson(readFile());
        assertNotNull(onDisk, "the new preset should have been written out");
        assertEquals(Layouts.load(Layouts.PRESET_OLYMPIC).name, onDisk.name);
        // A package the model does not ship must not be silently accepted.
        assertNull(store.applyPreset("no-such-package"));
        assertEquals(Layouts.PRESET_OLYMPIC, store.preset(), "and must not change the preset");
    }

    @Test
    @DisplayName("an edit made in the file is picked up on the next check")
    void picksUpAnExternalEdit() throws IOException {
        LayoutStore store = store();
        store.load();
        assertFalse(store.fileChangedSinceRead(), "nothing has changed yet");

        Layout handEdited = Layouts.load(Layouts.PRESET_MINIMAL);
        handEdited.name = "改过的";
        Files.writeString(configDir.resolve("stevehud/layout.json"),
                Layouts.toPrettyJson(handEdited), StandardCharsets.UTF_8);

        assertTrue(store.fileChangedSinceRead(), "an external edit should be noticed");
        assertEquals("改过的", store.current().name);
        // Idempotent: the same content must not report a change twice, or the mod
        // would rebroadcast the package on every tick.
        assertFalse(store.fileChangedSinceRead());
    }

    @Test
    @DisplayName("both serialisations parse back to the same document")
    void serialisationsAgree() {
        LayoutStore store = store();
        store.load();

        Layout wire = Layouts.fromJson(store.wireJson());
        Layout pretty = Layouts.fromJson(store.prettyJson());

        assertNotNull(wire);
        assertNotNull(pretty);
        assertEquals(store.current().elements.size(), wire.elements.size());
        assertEquals(store.current().elements.size(), pretty.elements.size());
        assertTrue(store.prettyJson().contains("\n"), "the file on disk is indented");
        assertFalse(store.wireJson().contains("\n"), "the stream is not");
    }

    @Test
    @DisplayName("a store with no writable location still serves the package")
    void unwritableLocationIsSurvivable() {
        // A read-only config directory must degrade to "cannot save", not to
        // "no graphics": the signal path is more important than the file.
        Path blocked = configDir.resolve("a-file");
        try {
            Files.writeString(blocked, "x", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        // A store whose parent path is a regular file cannot create its directory.
        LayoutStore store = new LayoutStore(blocked.resolve("layout.json"), log::add);

        assertFalse(store.current().elements.isEmpty());
        assertNotNull(store.accept("{\"name\":\"still works\"}"));
        assertEquals("still works", store.current().name);
        assertTrue(log.stream().anyMatch(line -> line.contains("Could not save")),
                "the failure should be reported, was: " + log);
    }

    private String readFile() {
        try {
            return Files.readString(configDir.resolve("stevehud/layout.json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read the layout file", e);
        }
    }
}
