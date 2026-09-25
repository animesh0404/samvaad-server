package com.samvaad.samvaad_server.e2ee.dto;

import java.util.List;

public class DeviceListDto {

    private EnrollmentState enrollmentState;
    private List<DeviceDto> devices;

    public DeviceListDto() {
    }

    public DeviceListDto(EnrollmentState enrollmentState, List<DeviceDto> devices) {
        this.enrollmentState = enrollmentState;
        this.devices = devices;
    }

    public EnrollmentState getEnrollmentState() {
        return enrollmentState;
    }

    public void setEnrollmentState(EnrollmentState enrollmentState) {
        this.enrollmentState = enrollmentState;
    }

    public List<DeviceDto> getDevices() {
        return devices;
    }

    public void setDevices(List<DeviceDto> devices) {
        this.devices = devices;
    }
}
