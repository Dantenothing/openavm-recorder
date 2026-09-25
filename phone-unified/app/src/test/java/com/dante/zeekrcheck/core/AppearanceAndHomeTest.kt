package com.dante.zeekrcheck.core

import org.junit.Assert.*
import org.junit.Test

class AppearanceAndHomeTest {
    private val a = "a".repeat(64); private val b = "b".repeat(64)
    @Test fun homeBufferIsSharedAndStaleLocationCannotTriggerAway() {
        val home = CarLocation(-35.0,138.0,null,"Synthetic home",true)
        val inside = home.copy(latitude = -35.001,source = 1_000_000)
        val edge = home.copy(latitude = -35.0023,source = 1_000_000)
        val outside = home.copy(latitude = -35.01,source = 1_000_000)
        assertEquals(HomeZone.Area.HOME,HomeZone.area(inside,home,200))
        assertEquals(HomeZone.Area.NEAR_HOME,HomeZone.area(edge,home,200))
        assertFalse(HomeZone.permitsAwayCheck(edge,home,200,1_100_000))
        assertTrue(HomeZone.permitsAwayCheck(outside,home,200,1_100_000))
        assertFalse(HomeZone.permitsAwayCheck(outside,home,200,1_500_000))
        assertFalse(HomeZone.permitsAwayCheck(outside.copy(source=null),home,200,1_100_000))
        assertTrue(HomeZone.label(edge,home,200,1_500_000).contains("上次位置"))
    }
    @Test fun homeRequiresVerifiedCenterAndHandlesDateline() {
        val home=CarLocation(0.0,179.999,null,"",true); val car=CarLocation(0.0,-179.999,10)
        assertEquals(HomeZone.Area.HOME,HomeZone.area(car,home,300))
        assertEquals(HomeZone.Area.UNKNOWN,HomeZone.area(car,home.copy(verified=false),300))
        assertEquals(HomeZone.Area.UNKNOWN,HomeZone.area(null,home,300))
    }
    @Test fun appearanceValidationDoesNotTruncateOrChangePlate() {
        val accepted=VehicleAppearance(plateEnabled=true,plateText="ABC 123-XY")
        assertNull(accepted.problem())
        assertNotNull(accepted.copy(plateText="A".repeat(13)).problem())
        assertNotNull(accepted.copy(plateText="ABC\n123").problem())
        assertNotNull(accepted.copy(plateText="<ABC>").problem())
        assertNotNull(accepted.copy(bodyColor="red").problem())
        assertNotNull(accepted.copy(plateText=" ").problem())
        assertEquals("ABC 123-XY",VehicleAppearance.parse(accepted.json()).plateText)
        assertFalse(accepted.toString().contains("ABC"))
    }
    @Test fun revisionsAndWidgetBindingsStayWithTheirOwnVehicle() {
        val original=AppearanceData(widgets=mapOf(1 to WidgetAppearance(a),2 to WidgetAppearance(b,true)))
            .saved(a,VehicleAppearance(bodyColor="#356EAD"),"First",mapOf(1 to true,2 to false))
        val next=original.saved(a,VehicleAppearance(bodyColor="#B83F3F"),"First",emptyMap())
        assertEquals(2L,next.vehicles[a]!!.revision)
        assertEquals("#356EAD",original.vehicles[a]!!.bodyColor)
        assertTrue(next.widgets[2]!!.showPlateText)
        assertTrue(next.widgets[1]!!.canControl(a)); assertFalse(next.widgets[1]!!.canControl(b)); assertFalse(next.widgets[1]!!.canControl(null))
        assertEquals(next,AppearanceData.parse(next.encode()))
    }
    @Test fun privacyAndAssetDimensionsArePartOfCacheKey() {
        val appearance=VehicleAppearance(plateEnabled=true,plateText="DEMO 123")
        assertNotEquals(appearance.cacheKey(520,true),appearance.cacheKey(520,false))
        assertEquals(appearance.cacheKey(520,false),appearance.copy(plateText="OTHER").cacheKey(520,false))
        assertNotEquals(appearance.cacheKey(520,true),appearance.copy(plateText="OTHER").cacheKey(520,true))
        assertNotEquals(appearance.cacheKey(520,true),appearance.cacheKey(1000,true))
        assertNotEquals(appearance.cacheKey(520,true),appearance.copy(revision=2).cacheKey(520,true))
        assertFalse(appearance.cacheKey(520,true).contains("DEMO"))
    }
    @Test fun contrastSuggestionLeavesTheDraftUnchanged() {
        val low=VehicleAppearance(plateBackground="#EEEEEE",plateForeground="#EAEAEA")
        assertTrue(low.contrast()<4.5)
        assertEquals("#000000",low.suggestedForeground())
        assertEquals("#EAEAEA",low.plateForeground)
        assertTrue(low.copy(plateForeground=low.suggestedForeground()).contrast()>4.5)
    }
    @Test fun paintMaskLeavesNeutralTrimGlassAndLightsUnchanged() {
        listOf(0xFF111111.toInt(),0xFF99A0A4.toInt(),0xFFEDEDED.toInt(),0x00000000,0xFFB02010.toInt()).forEach {
            assertFalse(PaintMaster.isPaint(it)); assertEquals(it,PaintMaster.recolor(it,0xFFFF0000.toInt()))
        }
        val magenta=0xFFC02090.toInt()
        assertTrue(PaintMaster.isPaint(magenta))
        assertNotEquals(magenta,PaintMaster.recolor(magenta,0xFFF2F3EF.toInt()))
        assertEquals(255,PaintMaster.recolor(magenta,0xFF20272B.toInt()) ushr 24)
    }
}
