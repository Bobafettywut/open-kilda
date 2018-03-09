package org.openkilda.atdd.staging.steps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import cucumber.api.Scenario;
import cucumber.api.java.Before;
import cucumber.api.java.en.Then;
import cucumber.api.java8.En;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.openkilda.atdd.staging.model.floodlight.FlowEntriesMap;
import org.openkilda.atdd.staging.model.floodlight.SwitchEntry;
import org.openkilda.atdd.staging.model.topology.TopologyDefinition;
import org.openkilda.atdd.staging.service.FloodlightService;
import org.openkilda.atdd.staging.service.TopologyEngineService;
import org.openkilda.atdd.staging.utils.DefaultFlowsChecker;
import org.openkilda.atdd.staging.utils.TopologyChecker;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.SwitchInfoData;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.Collectors;

public class DiscoveryMechanismSteps implements En {

    @Autowired
    private FloodlightService floodlightService;

    @Autowired
    private TopologyEngineService topologyEngineService;

    @Autowired
    private TopologyDefinition topologyDefinition;

    private Scenario scenario;

    @Before
    public void before(Scenario scenario) {
        this.scenario = scenario;
    }

    @Then("^all provided switches should be discovered")
    public void checkDiscoveredSwitches() {
        List<SwitchInfoData> discoveredSwitches = topologyEngineService.getActiveSwitches();
        assertFalse("No switches were discovered", CollectionUtils.isEmpty(discoveredSwitches));

        List<TopologyDefinition.Switch> expectedSwitches = topologyDefinition.getActiveSwitches();
        assertFalse("Expected switches should be provided", expectedSwitches.isEmpty());
        assertEquals("Expected and discovered switches amount are not the same", expectedSwitches.size(),
                discoveredSwitches.size());

        expectedSwitches.forEach(switchDef -> {
            SwitchInfoData switchInfoData = discoveredSwitches.stream()
                    .filter(sw -> StringUtils.equalsIgnoreCase(sw.getSwitchId(), switchDef.getDpId()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(String.format("Switch %s is not discovered", switchDef.getDpId()), switchInfoData);
            assertTrue(String.format("Switch %s should be active", switchDef.getDpId()),
                    switchInfoData.getState().isActive());
        });
    }

    @Then("^all provided links should be detected")
    public void checkDiscoveredLinks() {
        List<IslInfoData> discoveredLinks = topologyEngineService.getAllLinks();
        List<TopologyDefinition.Isl> expectedLinks = topologyDefinition.getIslsForActiveSwitches();

        if (CollectionUtils.isEmpty(discoveredLinks) && expectedLinks.isEmpty()) {
            scenario.write("There are no links discovered as expected");
            return;
        }

        assertFalse("Links were not discovered / not provided",
                CollectionUtils.isEmpty(discoveredLinks) || expectedLinks.isEmpty());
        List<TopologyDefinition.Isl> result = expectedLinks.stream()
                .filter(link -> !linkIsPresent(link, discoveredLinks))
                .collect(Collectors.toList());

        //print out links that were not discovered
        if (!result.isEmpty()) {
            result.forEach(link ->
                    scenario.write(String.format("Not found ISL between %s - %s",
                            link.getSrcSwitch(), link.getDstSwitch())));
        }
        assertTrue(String.format("%s link were not discovered", result.size()), result.isEmpty());

        //in kilda we have forward and reverse isl, that's why we have to divide into 2
        int singleLinks = discoveredLinks.size() / 2;
        assertEquals(String.format("There were %s more links discovered than expected",
                singleLinks - expectedLinks.size()), expectedLinks.size(), singleLinks);
    }

    @Then("^floodlight should not find redundant switches")
    public void checkFloodlightSwitches() {
        List<SwitchEntry> switches = floodlightService.getSwitches();
        //find switches that weren't defined, but was found by floodlight
        List<SwitchEntry> ignoredSwitches = switches.stream()
                .filter(sw -> !topologyContainsSwitch(sw))
                .collect(Collectors.toList());

        if (!ignoredSwitches.isEmpty()) {
            ignoredSwitches.forEach(sw ->
                    scenario.write(String.format("Switch %s was found by floodlight, but is not provided in json file",
                        sw.getSwitchId())));
        }

        assertTrue("Floodlight has detected unexpected switches", ignoredSwitches.isEmpty());
    }

    @Then("^default rules for switches are installed")
    public void checkDefaultRules() {
        List<SwitchEntry> switches = floodlightService.getSwitches();

        List<SwitchEntry> switchesWithInvalidFlows = switches.stream()
                .filter(sw -> {
                    FlowEntriesMap flows = floodlightService.getFlows(sw.getSwitchId());
                    return !DefaultFlowsChecker.validateDefaultRules(sw, flows, scenario);
                })
                .collect(Collectors.toList());
        assertTrue("There were found switches with incorrect default flows",
                switchesWithInvalidFlows.isEmpty());
    }

    private boolean linkIsPresent(TopologyDefinition.Isl expectedLink, List<IslInfoData> discoveredLinks) {
        return discoveredLinks.stream()
                .anyMatch(isl -> TopologyChecker.isIslEqual(expectedLink, isl));
    }

    private boolean topologyContainsSwitch(SwitchEntry switchEntry) {
        return topologyDefinition.getActiveSwitches().stream()
                .anyMatch(sw -> sw.getDpId().equalsIgnoreCase(switchEntry.getSwitchId()));
    }
}