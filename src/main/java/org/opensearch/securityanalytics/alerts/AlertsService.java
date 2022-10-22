/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.securityanalytics.alerts;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListener;
import org.opensearch.action.support.GroupedActionListener;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.client.Client;
import org.opensearch.client.node.NodeClient;
import org.opensearch.commons.alerting.AlertingPluginInterface;
import org.opensearch.commons.alerting.action.AcknowledgeAlertRequest;
import org.opensearch.commons.alerting.action.AcknowledgeAlertResponse;
import org.opensearch.commons.alerting.action.GetAlertsRequest;
import org.opensearch.commons.alerting.model.Alert;
import org.opensearch.commons.alerting.model.Table;
import org.opensearch.securityanalytics.action.AckAlertsResponse;
import org.opensearch.securityanalytics.action.AlertDto;
import org.opensearch.securityanalytics.action.GetAlertsResponse;
import org.opensearch.securityanalytics.action.GetDetectorAction;
import org.opensearch.securityanalytics.action.GetDetectorRequest;
import org.opensearch.securityanalytics.action.GetDetectorResponse;
import org.opensearch.securityanalytics.config.monitors.DetectorMonitorConfig;
import org.opensearch.securityanalytics.model.Detector;
import org.opensearch.securityanalytics.util.SecurityAnalyticsException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alerts Service implements operations involving interaction with Alerting Plugin
 */
public class AlertsService {

    public AlertsService() {
    }

    private Client client;

    private static final Logger log = LogManager.getLogger(AlertsService.class);

    public AlertsService(Client client) {
        this.client = client;
    }

    /**
     * Searches alerts generated by specific Detector
     *
     * @param detectorId id of Detector
     * @param table      group of search related parameters
     * @param listener   ActionListener to get notified on response or error
     */
    public void getAlertsByDetectorId(
            String detectorId,
            Table table,
            String severityLevel,
            String alertState,
            ActionListener<GetAlertsResponse> listener
    ) {
        this.client.execute(GetDetectorAction.INSTANCE, new GetDetectorRequest(detectorId, -3L), new ActionListener<>() {

            @Override
            public void onResponse(GetDetectorResponse getDetectorResponse) {
                // Get all monitor ids from detector
                Detector detector = getDetectorResponse.getDetector();
                List<String> monitorIds = detector.getMonitorIds();
                // monitor --> detectorId mapping
                Map<String, String> monitorToDetectorMapping = new HashMap<>();
                detector.getMonitorIds().forEach(
                        monitorId -> monitorToDetectorMapping.put(monitorId, detector.getId())
                );
                // Get alerts for all monitor ids
                AlertsService.this.getAlertsByMonitorIds(
                        monitorToDetectorMapping,
                        monitorIds,
                        DetectorMonitorConfig.getAlertsIndex(detector.getDetectorType()),
                        table,
                        severityLevel,
                        alertState,
                        new ActionListener<>() {
                            @Override
                            public void onResponse(GetAlertsResponse getAlertsResponse) {
                                // Send response back
                                listener.onResponse(getAlertsResponse);
                            }

                            @Override
                            public void onFailure(Exception e) {
                                log.error("Failed to fetch alerts for detectorId: " + detectorId, e);
                                listener.onFailure(SecurityAnalyticsException.wrap(e));
                            }
                        }
                );
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(SecurityAnalyticsException.wrap(e));
            }
        });
    }

    /**
     * Searches alerts generated by specific Monitor
     *
     * @param monitorIds id of Monitor
     * @param table      group of search related parameters
     * @param listener   ActionListener to get notified on response or error
     */
    public void getAlertsByMonitorIds(
            Map<String, String> monitorToDetectorMapping,
            List<String> monitorIds,
            String alertIndex,
            Table table,
            String severityLevel,
            String alertState,
            ActionListener<GetAlertsResponse> listener
    ) {

        org.opensearch.commons.alerting.action.GetAlertsRequest req =
                new org.opensearch.commons.alerting.action.GetAlertsRequest(
                        table,
                        severityLevel,
                        alertState,
                        null,
                        alertIndex,
                        monitorIds,
                        null
                );

        AlertingPluginInterface.INSTANCE.getAlerts((NodeClient) client, req, new ActionListener<>() {
                    @Override
                    public void onResponse(
                            org.opensearch.commons.alerting.action.GetAlertsResponse getAlertsResponse
                    ) {
                        // Convert response to SA's GetAlertsResponse
                        listener.onResponse(new GetAlertsResponse(
                                getAlertsResponse.getAlerts()
                                        .stream().map(e ->
                                                mapAlertToAlertDto(e, monitorToDetectorMapping.get(e.getMonitorId()))
                                        ).collect(Collectors.toList()),
                                getAlertsResponse.getTotalAlerts()
                        ));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                }
        );

    }

    void setIndicesAdminClient(Client client) {
        this.client = client;
    }

    public void getAlerts(
            List<Detector> detectors,
            Detector.DetectorType detectorType,
            Table table,
            String severityLevel,
            String alertState,
            ActionListener<GetAlertsResponse> listener
    ) {
        if (detectors.size() == 0) {
            throw SecurityAnalyticsException.wrap(new IllegalArgumentException("detector list is empty!"));
        }

        List<String> allMonitorIds = new ArrayList<>();
        // Used to convert monitorId back to detectorId to store in result FindingDto
        Map<String, String> monitorToDetectorMapping = new HashMap<>();
        detectors.forEach(detector -> {
            // monitor --> detector map
            detector.getMonitorIds().forEach(
                    monitorId -> monitorToDetectorMapping.put(monitorId, detector.getId())
            );
            // all monitorIds
            allMonitorIds.addAll(detector.getMonitorIds());
        });

        // Execute GetFindingsAction for each monitor
        AlertsService.this.getAlertsByMonitorIds(
            monitorToDetectorMapping,
            allMonitorIds,
            DetectorMonitorConfig.getAlertsIndex(detectorType.getDetectorType()),
            table,
            severityLevel,
            alertState,
            new ActionListener<>() {
                @Override
                public void onResponse(GetAlertsResponse getAlertsResponse) {
                    listener.onResponse(getAlertsResponse);
                }

                    @Override
                    public void onFailure(Exception e) {
                        log.error("Failed to fetch alerts for detectors: [" +
                                detectors.stream().map(d -> d.getId()).collect(Collectors.joining(",")) + "]", e);
                        listener.onFailure(SecurityAnalyticsException.wrap(e));
                    }
                }
        );
    }

    private AlertDto mapAlertToAlertDto(Alert alert, String detectorId) {
        return new AlertDto(
                detectorId,
                alert.getId(),
                alert.getVersion(),
                alert.getSchemaVersion(),
                alert.getTriggerId(),
                alert.getTriggerName(),
                alert.getFindingIds(),
                alert.getRelatedDocIds(),
                alert.getState(),
                alert.getStartTime(),
                alert.getEndTime(),
                alert.getLastNotificationTime(),
                alert.getAcknowledgedTime(),
                alert.getErrorMessage(),
                alert.getErrorHistory(),
                alert.getSeverity(),
                alert.getActionExecutionResults(),
                alert.getAggregationResultBucket()
        );
    }

    public void getAlerts(List<String> alertIds,
                          Detector detector,
                          Table table,
                          ActionListener<org.opensearch.commons.alerting.action.GetAlertsResponse> actionListener) {
        GetAlertsRequest request = new GetAlertsRequest(
                table,
                "ALL",
                "ALL",
                null,
                DetectorMonitorConfig.getAlertsIndex(detector.getDetectorType()),
                null,
                alertIds);
        AlertingPluginInterface.INSTANCE.getAlerts(
                (NodeClient) client,
                request, actionListener);

    }

    /**
     * @param getAlertsResponse
     * @param getDetectorResponse
     * @param actionListener
     */
    public void ackknowledgeAlerts(org.opensearch.commons.alerting.action.GetAlertsResponse getAlertsResponse,
                                   GetDetectorResponse getDetectorResponse,
                                   ActionListener<AckAlertsResponse> actionListener) {
        Map<String, List<String>> alertsByMonitor = new HashMap<>();
        for (Alert alert : getAlertsResponse.getAlerts()) {
            List<String> alerts = alertsByMonitor.getOrDefault(alert.getMonitorId(), new ArrayList<>());
            alerts.add(alert.getId());
            alertsByMonitor.put(alert.getMonitorId(), alerts);
        }
        GroupedActionListener<AcknowledgeAlertResponse> listener = new GroupedActionListener<>(new ActionListener<Collection<AcknowledgeAlertResponse>>() {

            @Override
            public void onResponse(Collection<AcknowledgeAlertResponse> responses) {
                final List<AlertDto> acks = new ArrayList<>(), fails = new ArrayList<>();
                final ArrayList<String> misses = new ArrayList<>();
                for (AcknowledgeAlertResponse acknowledgeAlertResponse : responses) {
                    acks.addAll(acknowledgeAlertResponse.getAcknowledged().stream()
                            .map(a -> mapAlertToAlertDto(a, getDetectorResponse.getId())).collect(Collectors.toList()));
                    fails.addAll(acknowledgeAlertResponse.getFailed().stream()
                            .map(a -> mapAlertToAlertDto(a, getDetectorResponse.getId())).collect(Collectors.toList()));
                    misses.addAll(acknowledgeAlertResponse.getMissing());
                }
                actionListener.onResponse(new AckAlertsResponse(acks, fails, misses));
            }

            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        }, alertsByMonitor.size());
        for (Map.Entry<String, List<String>> entry : alertsByMonitor.entrySet()) {
            AlertingPluginInterface.INSTANCE.acknowledgeAlerts(
                    (NodeClient) client,
                    new AcknowledgeAlertRequest(entry.getKey(), entry.getValue(), WriteRequest.RefreshPolicy.IMMEDIATE),
                    listener);
        }

    }
}