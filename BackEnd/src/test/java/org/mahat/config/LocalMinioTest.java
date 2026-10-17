package org.mahat.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalMinioTest {
    private static final String ID = "012345abcdef";
    private static final String PORTS = "{\"9000/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"9000\"}],"
            + "\"9001/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"9001\"}]}";

    private LocalMinio.Commands container(String image, String ports, String settings) {
        return args -> {
            if (args.get(1).equals("ps")) return ID + " " + image;
            assertEquals(ID, args.get(args.size() - 1));
            return args.get(3).contains("NetworkSettings") ? ports : settings;
        };
    }

    @Test void detectsExistingSiloContainerWithoutDependingOnItsName() throws Exception {
        Map<String, String> result = LocalMinio.discover("http://localhost:9000", container(
                "pgsty/silo:RELEASE.2026-09-16T00-00-00Z", PORTS,
                "MINIO_ROOT_USER=existing-account\nMINIO_ROOT_PASSWORD=existing=password#value\n"));
        assertEquals("existing-account", result.get("S3_ACCESS_KEY"));
        assertEquals("existing=password#value", result.get("S3_SECRET_KEY"));
    }

    @Test void recognizesOfficialAndVendorMinioImages() throws Exception {
        for (String image : List.of("minio/minio:release", "quay.io/minio/minio:release", "bitnamilegacy/minio@sha256:abc")) {
            assertFalse(LocalMinio.discover("http://127.0.0.1:9000", container(image, PORTS,
                    "MINIO_ROOT_USER=account\nMINIO_ROOT_PASSWORD=password\n")).isEmpty());
        }
    }

    @Test void refusesRemoteOrCredentialBearingEndpointsWithoutInvokingDocker() throws Exception {
        for (String endpoint : List.of("https://storage.example.com", "http://localhost.attacker.invalid:9000",
                "http://user:password@localhost:9000", "invalid", "file:///localhost")) {
            assertTrue(LocalMinio.discover(endpoint, args -> fail("Must not inspect Docker for this endpoint")).isEmpty());
        }
    }

    @Test void requiresTheConfiguredHostPortAndMatchingLocalBinding() throws Exception {
        assertTrue(LocalMinio.discover("http://localhost:19000", container("minio/minio:release", PORTS,
                "MINIO_ROOT_USER=account\nMINIO_ROOT_PASSWORD=password\n")).isEmpty());
        assertTrue(LocalMinio.discover("http://localhost:9000", container("minio/minio:release",
                PORTS.replace("0.0.0.0", "192.0.2.10"), "MINIO_ROOT_USER=account\nMINIO_ROOT_PASSWORD=password\n")).isEmpty());
    }

    @Test void doesNotInspectUnrelatedContainersOrGuessMissingPasswords() throws Exception {
        assertTrue(LocalMinio.discover("http://localhost:9000", container("other/server:latest", PORTS,
                "MINIO_ROOT_USER=account\nMINIO_ROOT_PASSWORD=password\n")).isEmpty());
        assertTrue(LocalMinio.discover("http://localhost:9000", container("minio/minio:release", PORTS,
                "MINIO_ROOT_USER=account\n")).isEmpty());
    }

    @Test void refusesAmbiguousInstancesBeforeReadingTheirCredentials() throws Exception {
        AtomicInteger settingsReads = new AtomicInteger();
        var result = LocalMinio.discover("http://localhost:9000", args -> {
            if (args.get(1).equals("ps")) return ID + " minio/minio:release\nfedcba543210 minio/minio:release";
            if (args.get(3).contains("NetworkSettings")) return PORTS;
            settingsReads.incrementAndGet(); return "MINIO_ROOT_USER=account\nMINIO_ROOT_PASSWORD=password";
        });
        assertTrue(result.isEmpty()); assertEquals(0, settingsReads.get());
    }

    @Test void dockerIsOptionalWhenUnavailable() throws Exception {
        assertTrue(LocalMinio.discover("http://localhost:9000", args -> { throw new IOException("unavailable"); }).isEmpty());
    }
}
