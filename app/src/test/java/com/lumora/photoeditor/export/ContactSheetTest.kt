package com.lumora.photoeditor.export

import com.lumora.photoeditor.data.ProofMark
import org.junit.Assert.*
import org.junit.Test

/**
 * First-ever executed verification of the contact-sheet layout contract —
 * export/ContactSheet.kt's pure functions (Phase C#7 slice 2).
 *
 * These are the deterministic rules behind proofing-gallery contact sheets:
 * [contactSheetAutoColumns]/[contactSheetColumns] pick the grid width,
 * [contactSheetPages] paginates the 48-photo bitmap budget,
 * [contactSheetLayout] computes every pixel of a page (grid math feeds the
 * GPU composer), [markBadgeText] labels client picks/rejects,
 * [contactSheetFileName] builds share-safe file names through the engine's
 * [sanitizeExportName], and [sheetCellCaption] formats per-cell captions.
 * SelfTest check 66 already pins these inside the app (GL-bound, unrunnable
 * here), so this test runs the REAL sources on the JVM to prove the rules
 * hold outside the app too.
 */
class ContactSheetTest {

    @Test fun `page budget constants are pinned`() {
        assertEquals(48, PHOTOS_PER_SHEET_PAGE)
        assertEquals(480, MAX_CONTACT_SHEET_PHOTOS)
        assertEquals(640, CONTACT_SHEET_CELL_LONG_EDGE)
    }

    // ---- contactSheetAutoColumns ----

    @Test fun `auto columns tier boundaries`() {
        assertEquals(3, contactSheetAutoColumns(9))
        assertEquals(4, contactSheetAutoColumns(10))
        assertEquals(4, contactSheetAutoColumns(36))
        assertEquals(5, contactSheetAutoColumns(37))
    }

    @Test fun `auto columns small and huge counts`() {
        assertEquals(3, contactSheetAutoColumns(1))
        assertEquals(5, contactSheetAutoColumns(480))
    }

    @Test fun `auto columns degenerate counts stay in range`() {
        assertEquals(3, contactSheetAutoColumns(0))
        assertEquals(3, contactSheetAutoColumns(-5))
    }

    // ---- contactSheetColumns ----

    @Test fun `columns zero means auto`() {
        assertEquals(4, contactSheetColumns(10, ContactSheetOptions(columns = 0)))
        assertEquals(3, contactSheetColumns(5, ContactSheetOptions()))
    }

    @Test fun `explicit columns honored inside range`() {
        assertEquals(2, contactSheetColumns(100, ContactSheetOptions(columns = 2)))
        assertEquals(6, contactSheetColumns(4, ContactSheetOptions(columns = 6)))
        assertEquals(4, contactSheetColumns(100, ContactSheetOptions(columns = 4)))
    }

    @Test fun `explicit columns clamp to 2 to 6`() {
        assertEquals(2, contactSheetColumns(10, ContactSheetOptions(columns = 1)))
        assertEquals(6, contactSheetColumns(10, ContactSheetOptions(columns = 9)))
        assertEquals(2, contactSheetColumns(10, ContactSheetOptions(columns = -3)))
    }

    // ---- contactSheetPages ----

    @Test fun `single page up to the budget`() {
        assertEquals(1, contactSheetPages(1, 4))
        assertEquals(1, contactSheetPages(48, 4))
    }

    @Test fun `page boundary at budget plus one`() {
        assertEquals(2, contactSheetPages(49, 4))
        assertEquals(2, contactSheetPages(96, 4))
        assertEquals(3, contactSheetPages(97, 4))
    }

    @Test fun `full gallery paginates`() {
        assertEquals(10, contactSheetPages(480, 5))
    }

    @Test fun `empty gallery still yields one page`() {
        assertEquals(1, contactSheetPages(0, 4))
    }

    // ---- contactSheetLayout ----

    @Test fun `layout columns coerce to 2 to 6`() {
        assertEquals(2, contactSheetLayout(48, 1).columns)
        assertEquals(6, contactSheetLayout(48, 9).columns)
        assertEquals(4, contactSheetLayout(48, 4).columns)
    }

    @Test fun `layout row count`() {
        assertEquals(12, contactSheetLayout(48, 4).rows)
        assertEquals(2, contactSheetLayout(5, 4).rows)
        assertEquals(1, contactSheetLayout(3, 4).rows)
        assertEquals(1, contactSheetLayout(0, 4).rows)
    }

    @Test fun `layout fixed page furniture`() {
        val l = contactSheetLayout(48, 4)
        assertEquals(2400, l.sheetW)
        assertEquals(72, l.margin)
        assertEquals(36, l.gap)
        assertEquals(320, l.headerH)
        assertEquals(100, l.captionH)
    }

    @Test fun `layout cell width follows the grid`() {
        // (2400 - 2*72 - 3*36) / 4 = 2148 / 4
        assertEquals(537, contactSheetLayout(48, 4).cellW)
        // (2400 - 2*72 - 1*36) / 2 = 2220 / 2
        assertEquals(1110, contactSheetLayout(48, 2).cellW)
    }

    @Test fun `layout sheet height follows rows`() {
        // 72 + 320 + 36 + 12*(537+100) + 11*36 + 72
        assertEquals(8540, contactSheetLayout(48, 4).sheetH)
        // single row: 72 + 320 + 36 + 1*(537+100) + 0*36 + 72
        assertEquals(1137, contactSheetLayout(3, 4).sheetH)
    }

    @Test fun `layout height grows monotonically with photo count`() {
        val heights = (0..48 step 4).map { contactSheetLayout(it, 4).sheetH }
        assertEquals(heights.sorted(), heights)
    }

    // ---- markBadgeText ----

    @Test fun `badge text for every mark`() {
        assertEquals("PICKED", markBadgeText(ProofMark.APPROVED))
        assertEquals("REJECTED", markBadgeText(ProofMark.REJECTED))
        assertNull(markBadgeText(null))
    }

    // ---- contactSheetFileName ----

    @Test fun `single page file name`() {
        assertEquals(
            "Contact sheet - Wedding",
            contactSheetFileName("Wedding", 1, 1),
        )
    }

    @Test fun `multi page file name carries the page suffix`() {
        assertEquals(
            "Contact sheet - Wedding - page 2 of 3",
            contactSheetFileName("Wedding", 2, 3),
        )
    }

    @Test fun `gallery name whitespace collapses`() {
        assertEquals(
            "Contact sheet - My Gallery",
            contactSheetFileName("My  Gallery", 1, 1),
        )
    }

    @Test fun `illegal filename characters are dropped`() {
        assertEquals(
            "Contact sheet - ABC",
            contactSheetFileName("A/B:C", 1, 1),
        )
    }

    @Test fun `blank gallery name falls back to Gallery`() {
        assertEquals(
            "Contact sheet - Gallery",
            contactSheetFileName("   ", 1, 1),
        )
    }

    @Test fun `name reduced to nothing by sanitizing falls back to Gallery`() {
        assertEquals(
            "Contact sheet - Gallery",
            contactSheetFileName("///", 1, 1),
        )
    }

    @Test fun `leading dot stripped so the file is not hidden`() {
        assertEquals(
            "Contact sheet - Hidden",
            contactSheetFileName(".Hidden", 1, 1),
        )
    }

    @Test fun `degenerate page count omits the suffix`() {
        assertEquals(
            "Contact sheet - W",
            contactSheetFileName("W", 1, 0),
        )
    }

    // ---- sheetCellCaption ----

    @Test fun `cell caption formats index and name`() {
        assertEquals("12. IMG_2041.jpg", sheetCellCaption(12, "IMG_2041.jpg"))
    }

    @Test fun `blank display name falls back to Photo`() {
        assertEquals("1. Photo", sheetCellCaption(1, ""))
        assertEquals("3. Photo", sheetCellCaption(3, "   "))
    }
}
