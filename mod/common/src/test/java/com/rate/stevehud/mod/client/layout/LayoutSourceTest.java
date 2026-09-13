package com.rate.stevehud.mod.client.layout;

import com.rate.stevehud.protocol.model.Layout;
import com.rate.stevehud.protocol.model.Layouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The precedence rule between the two scopes is the whole of the permission model
 * on the client side, so it is worth more than a comment.
 */
class LayoutSourceTest {

    @TempDir
    Path configDir;

    private final List<String> log = new ArrayList<>();

    private LayoutSource source() {
        LayoutStore store = new LayoutStore(configDir.resolve("stevehud/layout.json"), log::add);
        store.load();
        return new LayoutSource(store);
    }

    @Test
    @DisplayName("with no server package, each client draws its own")
    void localByDefault() {
        LayoutSource source = source();

        // The default matters: a server that forced its package on everyone would make
        // the local scope unreachable, and the local scope is where OBS packaging
        // actually happens.
        assertFalse(source.serverActive());
        assertEquals(LayoutSource.SCOPE_LOCAL, source.scope());
        assertEquals(source.local().name, source.effective().name);
        assertEquals(source.local().name, source.onAirName());
    }

    @Test
    @DisplayName("a server package takes over, and clearing it hands control back")
    void serverWinsWhileSet() {
        LayoutSource source = source();
        String localName = source.local().name;

        Layout olympic = Layouts.load(Layouts.PRESET_OLYMPIA);
        assertTrue(source.acceptServer(olympic, "奥林匹亚", "__RATE__"));

        assertEquals(LayoutSource.SCOPE_SERVER, source.scope());
        assertEquals(olympic.name, source.effective().name,
                "the server's package is what every client must draw");
        // And the local document is untouched, so the operator's own work survives.
        assertEquals(localName, source.local().name);
        assertEquals("奥林匹亚", source.onAirName());
        assertEquals("__RATE__", source.serverAuthor());

        assertTrue(source.acceptServer(null, "", ""));
        assertEquals(LayoutSource.SCOPE_LOCAL, source.scope());
        assertEquals(localName, source.effective().name);
    }

    @Test
    @DisplayName("a package with no elements counts as no package")
    void anEmptyPackageIsNotAPackage() {
        LayoutSource source = source();

        Layout empty = new Layout();
        empty.name = "空的";
        source.acceptServer(empty, "", "");

        // An element-less document would blank every screen in the match. Reading it
        // as "no package" keeps the local one on air instead.
        assertNotNull(source.effective());
        assertFalse(source.effective().elements.isEmpty());
    }

    @Test
    @DisplayName("a disconnecting server leaves nothing of itself behind")
    void clearOnDisconnect() {
        LayoutSource source = source();
        source.acceptServer(Layouts.load(Layouts.PRESET_CLEAN), "边线", "op");
        assertTrue(source.serverActive());

        // The package belonged to that server. Carrying it into the next one would
        // silently restyle a session that never asked for it.
        assertTrue(source.clearServer());
        assertFalse(source.clearServer(), "clearing twice is not a change");

        assertFalse(source.serverActive());
        assertEquals(LayoutSource.SCOPE_LOCAL, source.scope());
    }

    @Test
    @DisplayName("a malformed server package is repaired rather than trusted")
    void serverPackagesAreNormalised() {
        LayoutSource source = source();

        Layout broken = Layouts.fromJson("""
                {"name":"server","elements":[
                  {"id":"a","type":"timer","anchor":"nonsense","scale":900},
                  {"id":"b","type":"not-a-type"}]}
                """);
        assertNotNull(broken);
        source.acceptServer(broken, "", "");

        Layout onAir = source.effective();
        assertEquals(1, onAir.elements.size(), "the unknown type is dropped");
        assertEquals(Layout.ANCHOR_TOP_LEFT, onAir.elements.get(0).anchor);
        assertEquals(8f, onAir.elements.get(0).scale, "clamped into a drawable range");
    }

    @Test
    @DisplayName("reporting whether anything changed, so the caller can skip a broadcast")
    void reportsChanges() {
        LayoutSource source = source();
        Layout olympic = Layouts.load(Layouts.PRESET_OLYMPIA);

        assertTrue(source.acceptServer(olympic, "奥林匹亚", "op"), "setting one is a change");
        assertFalse(source.acceptServer(olympic, "奥林匹亚", "op"), "setting the same one is not");
        assertTrue(source.acceptServer(null, "", ""), "clearing is a change");
        assertFalse(source.acceptServer(null, "", ""), "clearing twice is not");
    }
}
