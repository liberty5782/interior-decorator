package app.roomplanner.ui

import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemFormTest {

    private fun ok(form: ItemForm) = (form.validate() as ItemForm.Result.Ok).fields

    @Test
    fun convertsCentimetresToMetres() {
        val f = ok(ItemForm(name = " Sofa ", widthCm = "210", depthCm = "90,5", heightCm = "80"))
        assertEquals("Sofa", f.name)
        assertEquals(2.1f, f.widthM, 1e-5f)
        assertEquals(0.905f, f.depthM, 1e-5f)
        assertEquals(0.8f, f.heightM, 1e-5f)
        assertNull(f.price)
        assertNull(f.sourceUrl)
    }

    @Test
    fun wallDecorMayOmitDepth() {
        val f = ok(ItemForm(name = "Print", kind = ItemKinds.WALL_DECOR, widthCm = "50", heightCm = "70"))
        assertEquals(0f, f.depthM)
        val print = Item(2, "Print", ItemKinds.WALL_DECOR, widthM = 0.5f, depthM = 0f, heightM = 0.7f)
        assertEquals("50 × 70 cm", ItemForm.dimensions(print))
    }

    @Test
    fun rejectsMissingOrSillyValues() {
        fun invalid(form: ItemForm) = form.validate() is ItemForm.Result.Invalid
        assertTrue(invalid(ItemForm(widthCm = "1", depthCm = "1", heightCm = "1")))
        assertTrue(invalid(ItemForm(name = "x", widthCm = "abc", depthCm = "1", heightCm = "1")))
        assertTrue("furniture needs depth", invalid(ItemForm(name = "x", widthCm = "1", heightCm = "1")))
        assertTrue("metres typed by mistake", invalid(ItemForm(name = "x", widthCm = "9000", depthCm = "1", heightCm = "1")))
        assertTrue(invalid(ItemForm(name = "x", widthCm = "1", depthCm = "1", heightCm = "1", price = "cheap")))
    }

    @Test
    fun roundTripsAnExistingItem() {
        val item = Item(
            id = 1, name = "Lamp", kind = ItemKinds.FURNITURE,
            widthM = 0.355f, depthM = 0.3f, heightM = 1.6f, price = 49.9f,
        )
        val form = ItemForm.from(item)
        assertEquals("35.5", form.widthCm)
        assertEquals("30", form.depthCm)
        assertEquals("160", form.heightCm)
        assertEquals("49.9", form.price)
        assertEquals("35.5 × 30 × 160 cm", ItemForm.dimensions(item))
        assertEquals(0.355f, ok(form).widthM, 1e-5f)
    }
}
