package com.endpointposture.warranty;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WarrantyServiceTest {

    @Mock WarrantyRecordRepository repo;
    WarrantyService service;

    @BeforeEach
    void setUp() {
        service = new WarrantyService(repo);
    }

    private MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "w.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private List<WarrantyRecord> saved() {
        ArgumentCaptor<List<WarrantyRecord>> c = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(c.capture());
        return c.getValue();
    }

    @Test
    void bomBeforeTheHeaderIsIgnored() {
        WarrantyUploadResult r = service.ingest(
                csv("\uFEFFserial_number,vendor,expires_on\nsn1,Dell,2030-01-31\n"), "admin");
        assertEquals(1, r.savedCount());
        assertEquals("SN1", saved().get(0).getSerialNumber());
    }

    @Test
    void quotedFieldWithACommaStaysOneColumn() {
        WarrantyUploadResult r = service.ingest(
                csv("serial_number,vendor,expires_on,product_name\nSN1,Dell,2030-01-31,\"Latitude 5540, i7\"\n"),
                "admin");
        assertEquals(1, r.savedCount());
        assertEquals("Latitude 5540, i7", saved().get(0).getProductName());
    }

    @Test
    void usDateFormatIsAccepted() {
        service.ingest(csv("serial_number,vendor,expires_on\nSN1,HP,06/30/2030\n"), "admin");
        assertEquals(LocalDate.of(2030, 6, 30), saved().get(0).getExpiresOn());
    }

    @Test
    void badDateAndBlankSerialAreRowErrorsNotFailures() {
        WarrantyUploadResult r = service.ingest(csv(
                "serial_number,vendor,expires_on\nSN1,Dell,13/45/2030\n,Dell,2030-01-01\nSN3,Dell,2030-01-01\n"),
                "admin");
        assertEquals(1, r.savedCount());
        assertEquals(2, r.rowErrors().size());
    }

    @Test
    void missingRequiredColumnsAreRejected() {
        assertThrows(WarrantyParseException.class,
                () -> service.ingest(csv("serial_number,vendor\nSN1,Dell\n"), "admin"));
    }

    @Test
    void emptyFileHeaderIsRejected() {
        assertThrows(WarrantyParseException.class, () -> service.ingest(csv(""), "admin"));
    }

    @Test
    void statusBoundaries() {
        assertEquals("EXPIRED", WarrantyService.statusFor(-1));
        assertEquals("EXPIRING_SOON", WarrantyService.statusFor(0));
        assertEquals("EXPIRING_SOON", WarrantyService.statusFor(30));
        assertEquals("COVERED", WarrantyService.statusFor(31));
    }

    @Test
    void describeNormalizesTheSerialAndComputesDays() {
        WarrantyRecord rec = WarrantyRecord.builder().serialNumber("SN1").vendor("Dell")
                .expiresOn(LocalDate.now().plusDays(10)).uploadedBy("a").build();
        when(repo.findLatestBySerialNumber("SN1")).thenReturn(Optional.of(rec));

        WarrantyService.Info info = service.describe("  sn1 ");

        assertEquals("EXPIRING_SOON", info.status());
        assertEquals(10, info.daysRemaining());
    }

    @Test
    void describeReturnsNullForBlankOrUnknownSerial() {
        assertNull(service.describe(" "));
        assertNull(service.describe(null));
        when(repo.findLatestBySerialNumber(any())).thenReturn(Optional.empty());
        assertNull(service.describe("NOPE"));
    }
}