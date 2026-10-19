package com.lumora.photoeditor.data

import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of [nextCopyName] — the Lightroom-style
 * virtual-copy naming in data/LibraryNaming.kt (pure Kotlin, zero imports).
 *
 * Virtual copies are independent library entries on the same source photo;
 * a naming bug here means duplicate display names or (worse) a copy that
 * can never be told apart from its original in the library.
 */
class LibraryNamingTest {

    @Test fun plainNameGetsCopySuffixBeforeExtension() {
        assertEquals("IMG_1234 copy.jpg", nextCopyName("IMG_1234.jpg"))
    }

    @Test fun nameWithoutExtension() {
        assertEquals("photo copy", nextCopyName("photo"))
    }

    @Test fun copyOfCopyIncrementsInsteadOfStacking() {
        assertEquals("IMG_1 copy 2.jpg", nextCopyName("IMG_1 copy.jpg"))
    }

    @Test fun numberedCopyIncrements() {
        assertEquals("IMG_1 copy 3.jpg", nextCopyName("IMG_1 copy 2.jpg"))
    }

    @Test fun multiDigitNumberIncrements() {
        assertEquals("IMG copy 23.jpg", nextCopyName("IMG copy 22.jpg"))
    }

    @Test fun bareCopySuffixAfterExtensionPeelsCorrectly() {
        // "IMG.jpg copy": Case 1 peels the suffix off the full name, then
        // the suffix is re-inserted before the extension.
        assertEquals("IMG copy 2.jpg", nextCopyName("IMG.jpg copy"))
    }

    @Test fun bareCopyNoExtension() {
        assertEquals("photo copy 2", nextCopyName("photo copy"))
    }

    @Test fun leadingDotIsNotAnExtension() {
        // A dot at index 0 is not treated as an extension separator.
        assertEquals(".jpg copy", nextCopyName(".jpg"))
    }

    @Test fun multiDotNameKeepsInnerDots() {
        assertEquals("a.b copy.jpg", nextCopyName("a.b.jpg"))
    }

    @Test fun nameEndingInCopyWithoutSpaceGetsSuffix() {
        // The suffix requires " copy" with a leading space.
        assertEquals("photocopy copy.jpg", nextCopyName("photocopy.jpg"))
    }

    @Test fun copyZeroIncrementsToOne() {
        // The suffix number is incremented blindly; "copy 0" is not special.
        assertEquals("IMG copy 1.jpg", nextCopyName("IMG copy 0.jpg"))
    }

    @Test fun negativeCopySuffixStacksInsteadOfIncrementing() {
        assertEquals("IMG copy -1 copy.jpg", nextCopyName("IMG copy -1.jpg"))
    }

    @Test fun renameEndingInBareCopyStartsNewStack() {
        // "IMG copy 2 copy.jpg" ends with a bare " copy" (after a manual
        // rename): Case 2 peels it off the extension-less base and starts a
        // new stack rather than producing "... copy 3".
        assertEquals("IMG copy 2 copy 2.jpg", nextCopyName("IMG copy 2 copy.jpg"))
    }

    @Test fun emptyCopyStemDoesNotCrash() {
        assertEquals(" copy copy 2", nextCopyName(" copy"))
    }

    @Test fun chainedCopiesKeepIncrementing() {
        var name = "IMG_1234.jpg"
        repeat(3) { name = nextCopyName(name) }
        assertEquals("IMG_1234 copy 3.jpg", name)
    }

    @Test fun pngAndDngExtensionsPreserved() {
        assertEquals("shot copy.png", nextCopyName("shot.png"))
        assertEquals("shot copy 2.dng", nextCopyName("shot copy.dng"))
    }
}
