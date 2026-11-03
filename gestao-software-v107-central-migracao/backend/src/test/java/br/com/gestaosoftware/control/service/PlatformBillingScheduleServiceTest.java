package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlatformBillingScheduleServiceTest {
    @Test void firstCycleAlwaysHasTwelveMonthsAndAdhesionCountsAsFirst() {
        assertCycle("MONTHLY", true,12);
        assertCycle("QUARTERLY",true,5);
        assertCycle("SEMIANNUAL",true,3);
        assertCycle("ANNUAL",true,2);
    }
    @Test void renewalsOnlyChargeTheChosenPeriodAndStillCoverOneYear() {
        assertCycle("MONTHLY",false,12);
        assertCycle("QUARTERLY",false,4);
        assertCycle("SEMIANNUAL",false,2);
        assertCycle("ANNUAL",false,1);
    }
    private void assertCycle(String term,boolean first,int expectedCount) {
        List<PlatformBillingScheduleService.Installment> plan=
                PlatformBillingScheduleService.planInstallments(term,first);
        assertEquals(expectedCount,plan.size(),term);
        assertEquals(12,plan.stream().mapToInt(
                PlatformBillingScheduleService.Installment::coveredMonths).sum(),term);
        assertEquals(0,plan.get(0).monthOffset());
        if(first)assertEquals(1,plan.get(0).coveredMonths());
        for(int i=1;i<plan.size();i++)
            assertTrue(plan.get(i).monthOffset()>plan.get(i-1).monthOffset());
    }
}
