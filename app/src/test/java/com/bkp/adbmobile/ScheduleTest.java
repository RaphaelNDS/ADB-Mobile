package com.bkp.adbmobile;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/** Agendamento: quanto falta até o próximo horário. */
public class ScheduleTest {

    private static Calendar at(int hour, int minute, int second) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo"));
        c.set(2026, Calendar.NOVEMBER, 13, hour, minute, second);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static final long MIN = 60_000L;

    @Test
    public void maisTardeHoje() {
        assertEquals(90 * MIN, Schedule.nextDelay(at(6, 30, 0), 8, 0));
    }

    @Test
    public void horarioJaPassou_amanha() {
        assertEquals((24 * 60 - 60) * MIN, Schedule.nextDelay(at(9, 0, 0), 8, 0));
    }

    @Test
    public void exatamenteAgora_amanha() {
        // Rodou agora: o próximo é daqui a 24 h, não de novo neste minuto.
        assertEquals(24 * 60 * MIN, Schedule.nextDelay(at(8, 0, 0), 8, 0));
    }

    @Test
    public void segundosContam() {
        assertEquals(MIN - 30_000, Schedule.nextDelay(at(7, 59, 30), 8, 0));
    }

    @Test
    public void viradaDoDia() {
        assertEquals(10 * MIN, Schedule.nextDelay(at(23, 55, 0), 0, 5));
    }

    @Test
    public void formatoDoHorario() {
        assertEquals("08:05", Schedule.time(8, 5));
        assertEquals("23:00", Schedule.time(23, 0));
    }
}
