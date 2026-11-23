package com.presenceiq;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresenceServiceTest {
    @Test void dividesPlannedSessionIntoFiveWindows() {
        assertEquals(0, PresenceService.windowFor(0, 30));
        assertEquals(0, PresenceService.windowFor(359, 30));
        assertEquals(1, PresenceService.windowFor(360, 30));
        assertEquals(4, PresenceService.windowFor(1799, 30));
        assertEquals(4, PresenceService.windowFor(5000, 30));
    }
    @Test void csvEscapesFormulaAndQuotes() {
        assertEquals("\"'=SUM(1,2)\"", PresenceService.safeCell("=SUM(1,2)"));
        assertEquals("\"A\"\"B\"", PresenceService.safeCell("A\"B"));
    }
    @Test void rejectsInvalidCounts() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                     () -> PresenceService.integer(-1,0,100,"count"));
    }
}
