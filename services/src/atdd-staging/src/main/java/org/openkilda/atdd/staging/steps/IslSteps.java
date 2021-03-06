/* Copyright 2018 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.atdd.staging.steps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.openkilda.atdd.staging.model.topology.TopologyDefinition;
import org.openkilda.atdd.staging.model.topology.TopologyDefinition.ASwitch;
import org.openkilda.atdd.staging.model.topology.TopologyDefinition.Isl;
import org.openkilda.atdd.staging.service.aswitch.ASwitchService;
import org.openkilda.atdd.staging.service.aswitch.model.ASwitchFlow;
import org.openkilda.atdd.staging.service.northbound.NorthboundService;
import org.openkilda.atdd.staging.steps.helpers.TopologyUnderTest;
import org.openkilda.messaging.info.event.IslChangeType;
import org.openkilda.messaging.info.event.IslInfoData;
import org.openkilda.messaging.info.event.PathNode;

import cucumber.api.java.en.And;
import cucumber.api.java.en.Given;
import cucumber.api.java.en.Then;
import cucumber.api.java.en.When;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.Assume;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class IslSteps {

    @Autowired
    private NorthboundService northboundService;

    @Autowired
    private ASwitchService aswitchService;

    @Autowired
    private TopologyUnderTest topologyUnderTest;

    @Autowired
    private TopologyDefinition topologyDefinition;

    private List<TopologyDefinition.Isl> changedIsls = new ArrayList<>();
    private List<IslInfoData> linksResponse;

    /**
     * Breaks the connection of given ISL by removing rules from intermediate switch.
     * Breaking ISL this way is not equal to physically unplugging the cable
     * because port_down event is not being produced
     */
    @When("ISL between switches loses connectivity")
    public void transitIslDown() {
        topologyUnderTest.getFlowIsls().forEach((flow, isls) -> {
            TopologyDefinition.Isl islToRemove = isls.stream().filter(isl -> isl.getAswitch() != null)
                    .findFirst().get();
            TopologyDefinition.ASwitch aswitch = islToRemove.getAswitch();
            ASwitchFlow aswFlowForward = new ASwitchFlow(aswitch.getInPort(), aswitch.getOutPort());
            ASwitchFlow aswFlowReverse = new ASwitchFlow(aswitch.getOutPort(), aswitch.getInPort());
            aswitchService.removeFlows(Arrays.asList(aswFlowForward, aswFlowReverse));
            changedIsls.add(islToRemove);
        });
    }

    /**
     * Restores rules on intermediate switch for given ISLs. This reverts the actions done by {@link #transitIslDown()}
     */
    @When("Changed ISLs? restores? connectivity")
    public void transitIslUp() {
        changedIsls.forEach(isl -> {
            TopologyDefinition.ASwitch aswitch = isl.getAswitch();
            ASwitchFlow aswFlowForward = new ASwitchFlow(aswitch.getInPort(), aswitch.getOutPort());
            ASwitchFlow aswFlowReverse = new ASwitchFlow(aswitch.getOutPort(), aswitch.getInPort());
            aswitchService.addFlows(Arrays.asList(aswFlowForward, aswFlowReverse));
        });
    }

    /**
     * This method waits for default amount of retries before the ISL status has the desired state.
     * Throws assertion error otherwise. Verifications are done via Northbound.
     *
     * @param islStatus required ISL status
     */
    @Then("ISLs? status changes? to (\\w*)$")
    public void waitForIslStatus(String islStatus) {
        waitIslStatusChange(changedIsls, islStatus);
    }

    @Then("ISLs? status is (\\w*)$")
    public void checkIslStatus(String islStatus) {
        IslChangeType expectedIslState = IslChangeType.valueOf(islStatus);
        changedIsls.forEach(isl -> {
            IslChangeType actualIslState = northboundService.getAllLinks().stream().filter(link -> {
                PathNode src = link.getPath().get(0);
                PathNode dst = link.getPath().get(1);
                return src.getPortNo() == isl.getSrcPort() && dst.getPortNo() == isl.getDstPort()
                        && src.getSwitchId().equals(isl.getSrcSwitch().getDpId())
                        && dst.getSwitchId().equals(isl.getDstSwitch().getDpId());
            }).findFirst().get().getState();
            assertEquals(expectedIslState, actualIslState);
        });
    }

    @When("^request all available links from Northbound$")
    public void requestAllAvailableLinksFromNorthbound() {
        linksResponse = northboundService.getAllLinks();
    }

    @Then("^response has at least (\\d+) links?$")
    public void responseHasAtLeastLink(int linksAmount) {
        assertTrue(linksResponse.size() >= linksAmount);
    }

    private RetryPolicy retryPolicy() {
        return new RetryPolicy()
                .withDelay(3, TimeUnit.SECONDS)
                .withMaxRetries(15);
    }

    @Given("^select a random isl and alias it as '(.*)'$")
    public void selectARandomIslAndAliasItAsIsl(String islAlias) {
        List<Isl> isls = getUnaliasedIsls();
        Random r = new Random();
        Isl theIsl = isls.get(r.nextInt(isls.size()));
        log.info("Selected random isl: {}", theIsl.toString());
        topologyUnderTest.addAlias(islAlias, theIsl);
    }

    private List<Isl> getUnaliasedIsls() {
        List<Isl> aliasedIsls = topologyUnderTest.getAliasedObjects(Isl.class);
        List<Isl> isls = (List<Isl>) CollectionUtils.subtract(
                topologyDefinition.getIslsForActiveSwitches(), aliasedIsls);
        Assume.assumeTrue("No unaliased isls left, unable to proceed", !isls.isEmpty());
        return isls;
    }

    @Given("^select a random ISL with A-Switch and alias it as '(.*)'$")
    public void selectARandomIslWithASwitch(String islAlias) {
        List<Isl> isls = getUnaliasedIsls().stream()
                .filter(isl -> isl.getAswitch() != null && isl.getAswitch().getInPort() != null
                        && isl.getAswitch().getOutPort() != null)
                .collect(Collectors.toList());
        Random r = new Random();
        Isl theIsl = isls.get(r.nextInt(isls.size()));
        log.info("Selected random isl with A-switch: {}", theIsl.toString());
        topologyUnderTest.addAlias(islAlias, theIsl);
    }

    @And("^select a reverse path ISL for '(.*)' and alias it as '(.*)'$")
    public void selectAReversePathIsl(String islAlias, String newIslAlias) {
        Isl theIsl = topologyUnderTest.getAliasedObject(islAlias);
        Isl reversedIsl = Isl.factory(theIsl.getDstSwitch(), theIsl.getDstPort(), theIsl.getSrcSwitch(),
                theIsl.getSrcPort(), theIsl.getMaxBandwidth(), theIsl.getAswitch());
        topologyUnderTest.addAlias(newIslAlias, reversedIsl);
    }

    @When("^(source|destination) port for ISL '(.*)' goes down$")
    public void portsDown(String isSourceStr, String islAlias) {
        boolean isSourcePort = "source".equals(isSourceStr);
        ASwitch aswitch = ((Isl) topologyUnderTest.getAliasedObject(islAlias)).getAswitch();
        List<Integer> portsToBringDown = Collections.singletonList(
                isSourcePort ? aswitch.getInPort() : aswitch.getOutPort());
        aswitchService.portsDown(portsToBringDown);
    }

    @When("^(source|destination) port for ISL '(.*)' goes up")
    public void portsUp(String isSourceStr, String islAlias) {
        boolean isSourcePort = "source".equals(isSourceStr);
        ASwitch aswitch = ((Isl) topologyUnderTest.getAliasedObject(islAlias)).getAswitch();
        List<Integer> portsToBringUp = Collections.singletonList(
                isSourcePort ? aswitch.getInPort() : aswitch.getOutPort());
        aswitchService.portsUp(portsToBringUp);
    }

    @Then("^ISL status changes to (.*) for ISLs: (.*)$")
    public void islsStatusChanges(String expectedStatus, List<String> islAliases) {
        List<Isl> isls = islAliases.stream()
                .map(alias -> (Isl) topologyUnderTest.getAliasedObject(alias))
                .collect(Collectors.toList());
        waitIslStatusChange(isls, expectedStatus);
    }

    @Then("^ISL status is (.*) for ISLs: (.*)$")
    public void islsStatusIs(String expectedStatus, List<String> islAliases) {
        List<Isl> isls = islAliases.stream()
                .map(alias -> (Isl) topologyUnderTest.getAliasedObject(alias))
                .collect(Collectors.toList());
        IslChangeType expectedIslState = IslChangeType.valueOf(expectedStatus);

        List<IslInfoData> allLinks = northboundService.getAllLinks();
        List<IslChangeType> actualIslStates = isls.stream().map(isl -> allLinks.stream().filter(link -> {
            PathNode src = link.getPath().get(0);
            PathNode dst = link.getPath().get(1);
            return src.getPortNo() == isl.getSrcPort() && dst.getPortNo() == isl.getDstPort()
                    && src.getSwitchId().equals(isl.getSrcSwitch().getDpId())
                    && dst.getSwitchId().equals(isl.getDstSwitch().getDpId());
        }).findFirst().get().getState()).collect(Collectors.toList());
        assertTrue(actualIslStates.stream().allMatch(state -> state.equals(expectedIslState)));
    }

    /**
     * Waits until all passed isls have the specified status. Fails after defined timeout.
     * Checks happen via Northbound API calls
     *
     * @param isls which isls should have the specified status
     * @param expectedStatus which status to wait on specified isls
     */
    private void waitIslStatusChange(List<Isl> isls, String expectedStatus) {
        IslChangeType expectedIslState = IslChangeType.valueOf(expectedStatus);

        List<IslInfoData> actualIsl = Failsafe.with(retryPolicy()
                .retryIf(states -> ((List<IslInfoData>) states).stream()
                        .map(IslInfoData::getState)
                        .anyMatch(state -> !state.equals(expectedIslState))))
                .get(() -> {
                    List<IslInfoData> allLinks = northboundService.getAllLinks();
                    return isls.stream().flatMap(isl -> allLinks.stream().filter(link -> {
                        PathNode src = link.getPath().get(0);
                        PathNode dst = link.getPath().get(1);
                        return src.getPortNo() == isl.getSrcPort() && dst.getPortNo() == isl.getDstPort()
                                && src.getSwitchId().equals(isl.getSrcSwitch().getDpId())
                                && dst.getSwitchId().equals(isl.getDstSwitch().getDpId());
                    })).collect(Collectors.toList());
                });

        assertThat(actualIsl, everyItem(hasProperty("state",  equalTo(expectedIslState))));
    }

    @And("^select a random not connected A-Switch link and alias it as '(.*)'$")
    public void selectNotConnectedASwitchLink(String alias) {
        List<Isl> links = topologyDefinition.getNotConnectedIsls().stream()
                .filter(isl -> isl.getAswitch() != null && isl.getAswitch().getOutPort() == null)
                .collect(Collectors.toList());
        Random r = new Random();
        Isl theLink = links.get(r.nextInt(links.size()));
        topologyUnderTest.addAlias(alias, theLink);
    }

    @And("^a potential ISL from '(.*)' (source|destination) to '(.*)' (source|destination) aliased as '(.*)'$")
    public void potentialIsl(String srcAlias, String srcIsSourceStr, String dstAlias, String dstIsSourceStr,
                             String newAlias) {
        final boolean srcIsSource = "source".equals(srcIsSourceStr);
        final boolean dstIsSource = "source".equals(dstIsSourceStr);
        Isl srcIsl = topologyUnderTest.getAliasedObject(srcAlias);
        Isl dstIsl = topologyUnderTest.getAliasedObject(dstAlias);
        Isl newIsl = Isl.factory(
                srcIsSource ? srcIsl.getSrcSwitch() : srcIsl.getDstSwitch(),
                srcIsSource ? srcIsl.getSrcPort() : srcIsl.getDstPort(),
                dstIsSource ? dstIsl.getSrcSwitch() : dstIsl.getDstSwitch(),
                dstIsSource ? dstIsl.getSrcPort() : dstIsl.getDstPort(),
                0,
                new ASwitch(
                        srcIsSource ? srcIsl.getAswitch().getInPort() : srcIsl.getAswitch().getOutPort(),
                        dstIsSource ? dstIsl.getAswitch().getInPort() : dstIsl.getAswitch().getOutPort()
                ));
        topologyUnderTest.addAlias(newAlias, newIsl);
    }

    @When("^replug '(.*)' (source|destination) to '(.*)' (source|destination)$")
    public void replug(String srcAlias, String srcIsSourceStr, String dstAlias, String dstIsSourceStr) {
        final boolean dstIsSource = "source".equals(dstIsSourceStr);
        Isl srcIsl = topologyUnderTest.getAliasedObject(srcAlias);
        Isl dstIsl = topologyUnderTest.getAliasedObject(dstAlias);

        //unplug
        portsDown(srcIsSourceStr, srcAlias);

        //change flow on aSwitch
        //delete old flow
        ASwitch srcASwitch = srcIsl.getAswitch();
        ASwitch dstASwitch = dstIsl.getAswitch();
        aswitchService.removeFlows(Arrays.asList(
                new ASwitchFlow(srcASwitch.getInPort(), srcASwitch.getOutPort()),
                new ASwitchFlow(srcASwitch.getOutPort(), srcASwitch.getInPort())));
        //create new flow
        ASwitchFlow aswFlowForward = new ASwitchFlow(srcASwitch.getInPort(),
                dstIsSource ? dstASwitch.getInPort() : dstASwitch.getOutPort());
        ASwitchFlow aswFlowReverse = new ASwitchFlow(aswFlowForward.getOutPort(), aswFlowForward.getInPort());
        aswitchService.addFlows(Arrays.asList(aswFlowForward, aswFlowReverse));

        //plug back
        portsUp(srcIsSourceStr, srcAlias);
    }
}
