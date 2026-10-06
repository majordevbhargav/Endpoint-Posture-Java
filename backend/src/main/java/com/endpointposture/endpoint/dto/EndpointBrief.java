package com.endpointposture.endpoint.dto;

/** Just enough identity to label a row on another page. */
public record EndpointBrief(String hostname, String macAddress, String ipAddress) {}