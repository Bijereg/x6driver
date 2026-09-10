package dev.sbelx.x6driver.service;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PrinterSetupTest {
    @Test void filtersModelsAndDeduplicatesAdvertisements() {
        var devices=JsonParser.parseString("""
          [{"id":"a","name":"X6h-1234"},{"id":"a","name":"X6h-1234"},
           {"id":"b","name":"X6"},{"id":"c","name":"X60"},{"id":"d","name":"Other"}]
          """).getAsJsonArray();
        var found=PrinterSetup.candidates(devices);
        assertEquals(2,found.size());assertEquals("a",found.get(0).get("id").getAsString());
        assertTrue(PrinterSetup.candidates(JsonParser.parseString("[]").getAsJsonArray()).isEmpty());
    }
    @Test void selectionMustReferToDisplayedDevice() {
        assertEquals(1,PrinterSetup.choice(" 2 ",2));
        for(String s:new String[]{"0","3","-1","text",""})assertEquals(-1,PrinterSetup.choice(s,2));
    }
}
