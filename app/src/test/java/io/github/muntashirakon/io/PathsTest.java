// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PathsTest {

    @Test
    public void getLastPathSegment() {
        assertEquals("", Paths.getLastPathSegment(""));
        assertEquals("", Paths.getLastPathSegment("/"));
        assertEquals("", Paths.getLastPathSegment("//"));
        assertEquals("", Paths.getLastPathSegment("///"));
        assertEquals("a", Paths.getLastPathSegment("a/"));
        assertEquals("a", Paths.getLastPathSegment("a//"));
        assertEquals("a", Paths.getLastPathSegment("a///"));
        assertEquals("c", Paths.getLastPathSegment("a/b/c"));
        assertEquals("c", Paths.getLastPathSegment("a/b//c"));
        assertEquals("c", Paths.getLastPathSegment("a/b///c"));
        assertEquals("c", Paths.getLastPathSegment("a/b/c/"));
        assertEquals("c", Paths.getLastPathSegment("a/b/c//"));
        assertEquals("c", Paths.getLastPathSegment("a/b/c///"));
        assertEquals(".", Paths.getLastPathSegment("a/b/c/."));
        assertEquals("..", Paths.getLastPathSegment("a/b/c/.."));
        assertEquals("..", Paths.getLastPathSegment("a/b/c/../"));
        assertEquals("..", Paths.getLastPathSegment("a/b/c/..//"));
        assertEquals("ewrjpoewiwfjfpwrejtp", Paths.getLastPathSegment("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp"));
        assertEquals("ewrjpoewiwfjfpwrejtp", Paths.getLastPathSegment("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp/"));
    }

    @Test
    public void removeLastPathSegment() {
        assertEquals("", Paths.removeLastPathSegment(""));
        assertEquals("/", Paths.removeLastPathSegment("/"));
        assertEquals("/", Paths.removeLastPathSegment("///"));
        assertEquals("/", Paths.removeLastPathSegment("/."));
        assertEquals("", Paths.removeLastPathSegment(".ext"));
        assertEquals("/", Paths.removeLastPathSegment("/.ext"));
        assertEquals("", Paths.removeLastPathSegment("a/"));
        assertEquals("a/b", Paths.removeLastPathSegment("a/b/."));
        assertEquals("a/b", Paths.removeLastPathSegment("a/b//."));
        assertEquals("a/b", Paths.removeLastPathSegment("a/b/.."));
        assertEquals("a", Paths.removeLastPathSegment("a/b/"));
        assertEquals("a", Paths.removeLastPathSegment("a/b.c"));
        assertEquals("a", Paths.removeLastPathSegment("a/b.c/"));
        assertEquals("a/b.c", Paths.removeLastPathSegment("a/b.c/d"));
        assertEquals("a", Paths.removeLastPathSegment("a/b.c.d"));
        assertEquals("a", Paths.removeLastPathSegment("a/b.c.d.e"));
        assertEquals("asdkjrejvncnmiet/eru43jffn", Paths.removeLastPathSegment("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp.ext"));
    }

    @Test
    public void appendPathSegment() {
        assertEquals("", Paths.appendPathSegment("", ""));
        assertEquals("", Paths.appendPathSegment("", "/"));
        assertEquals("/", Paths.appendPathSegment("/", ""));
        assertEquals("/", Paths.appendPathSegment("/", "/"));
        assertEquals("/a", Paths.appendPathSegment("/", "a"));
        assertEquals("/a", Paths.appendPathSegment("/", "/a"));
        assertEquals("/a/", Paths.appendPathSegment("/", "a/"));
        assertEquals("/a/", Paths.appendPathSegment("/", "/a/"));
        assertEquals("/a/b/c/d", Paths.appendPathSegment("/a/b/c", "d"));
        assertEquals("/a/b/c/d", Paths.appendPathSegment("/a/b/c/", "d"));
        assertEquals("/a/b/c/d", Paths.appendPathSegment("/a/b/c/", "/d"));
        assertEquals("/a/b/c/d/", Paths.appendPathSegment("/a/b/c/", "/d/"));
    }

    @Test
    public void trimPathExtension() {
        assertEquals("", Paths.trimPathExtension(""));
        assertEquals("/", Paths.trimPathExtension("/"));
        assertEquals("/.", Paths.trimPathExtension("/."));
        assertEquals(".ext", Paths.trimPathExtension(".ext"));
        assertEquals("/.ext", Paths.trimPathExtension("/.ext"));
        assertEquals("a/", Paths.trimPathExtension("a/"));
        assertEquals("a/b/.", Paths.trimPathExtension("a/b/."));
        assertEquals("a/b/..", Paths.trimPathExtension("a/b/.."));
        assertEquals("a/b/", Paths.trimPathExtension("a/b/"));
        assertEquals("a/b/", Paths.trimPathExtension("a/b/"));
        assertEquals("a/b", Paths.trimPathExtension("a/b.c"));
        assertEquals("a/b", Paths.trimPathExtension("a/b.c/"));
        assertEquals("a/b.c/d", Paths.trimPathExtension("a/b.c/d"));
        assertEquals("a/b.c", Paths.trimPathExtension("a/b.c.d"));
        assertEquals("a/b.c.d", Paths.trimPathExtension("a/b.c.d.e"));
        assertEquals("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp",
                Paths.trimPathExtension("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp.ext"));
    }

    @Test
    public void getPathExtension() {
        assertNull(Paths.getPathExtension(""));
        assertNull(Paths.getPathExtension("/"));
        assertNull(Paths.getPathExtension("/."));
        assertEquals("ext", Paths.getPathExtension(".ext"));
        assertEquals("ext", Paths.getPathExtension("/.ext"));
        assertNull(Paths.getPathExtension("a/"));
        assertNull(Paths.getPathExtension("a/b/."));
        assertNull(Paths.getPathExtension("a/b/.."));
        assertNull(Paths.getPathExtension("a/b/"));
        assertNull(Paths.getPathExtension("a/b/"));
        assertEquals("c", Paths.getPathExtension("a/b.c"));
        assertEquals("c", Paths.getPathExtension("a/b.c/"));
        assertNull(Paths.getPathExtension("a/b.c/d"));
        assertEquals("d", Paths.getPathExtension("a/b.c.d"));
        assertEquals("e", Paths.getPathExtension("a/b.c.d.e"));
        assertNull(Paths.getPathExtension("a/b.c.d.e."));
        assertEquals("ext", Paths.getPathExtension("asdkjrejvncnmiet/eru43jffn/ewrjpoewiwfjfpwrejtp.ext"));
    }
}