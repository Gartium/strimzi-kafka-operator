/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common;

import io.fabric8.kubernetes.api.model.Secret;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SslConfigs;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Provides the default Kafka Admin client
 */
public class DefaultAdminClientProvider implements AdminClientProvider {
    @Override
    public Admin createAdminClient(String bootstrapHostnames, Secret clusterCaCertSecret, Secret keyCertSecret, String keyCertName) {
        return createAdminClient(bootstrapHostnames, clusterCaCertSecret, keyCertSecret, keyCertName, new Properties());
    }

    /**
     * Create a Kafka Admin interface instance handling the following different scenarios:
     *
     * 1. No TLS connection, no TLS client authentication:
     *
     * If {@code clusterCaCertSecret}, {@code keyCertSecret} and {@code keyCertName} are null, the returned Admin Client instance
     * is configured to connect to the Apache Kafka bootstrap (defined via {@code hostname}) on plain connection with no
     * TLS encryption and no TLS client authentication.
     *
     * 2. TLS connection, no TLS client authentication
     *
     * If only {@code clusterCaCertSecret} is provided as not null, the returned Admin Client instance is configured to
     * connect to the Apache Kafka bootstrap (defined via {@code hostname}) on TLS encrypted connection but with no
     * TLS authentication.
     *
     * 3. TLS connection and TLS client authentication
     *
     * If {@code clusterCaCertSecret}, {@code keyCertSecret} and {@code keyCertName} are provided as not null, the returned
     * Admin Client instance is configured to connect to the Apache Kafka bootstrap (defined via {@code hostname}) on
     * TLS encrypted connection and with TLS client authentication.
     */
    @Override
    public Admin createAdminClient(String bootstrapHostnames, Secret clusterCaCertSecret, Secret keyCertSecret, String keyCertName, Properties config) {
        return Admin.create(adminClientConfiguration(bootstrapHostnames, clusterCaCertSecret, keyCertSecret, keyCertName, config));
    }

    /**
     * Utility method for preparing the Admin client configuration
     *
     * @param bootstrapHostnames    Kafka bootstrap address
     * @param clusterCaCertSecret   Secret with public TLS certificates
     * @param keyCertSecret         Secret with client public and private key
     * @param keyCertName           Key under which the client public and private key is stored
     * @param config                Custom Admin client configuration or empty properties instance
     *
     * @return  Admin client configuration
     */
    /* test */ static Properties adminClientConfiguration(String bootstrapHostnames, Secret clusterCaCertSecret, Secret keyCertSecret, String keyCertName, Properties config)    {
        if (config == null) {
            throw new InvalidConfigurationException("The config parameter should not be null");
        }

        String trustedCertificates = null;
        String privateKey = null;
        String certificateChain = null;

        // provided Secret with cluster CA certificate for TLS encryption
        if (clusterCaCertSecret != null) {
            if (clusterCaCertSecret.getData() == null
                    || clusterCaCertSecret.getData().entrySet().stream().noneMatch(record -> record.getKey().endsWith(".crt")))    {
                // No CRT files in this secret => throw an exception
                throw new InvalidConfigurationException("The Secret " + clusterCaCertSecret.getMetadata().getName() + " does not seem to contain any .crt entries");
            }

            trustedCertificates = Util.certsToPemString(clusterCaCertSecret);
        }

        // provided Secret and related key for getting the private key for TLS client authentication
        if (keyCertSecret != null && keyCertName != null && !keyCertName.isEmpty()) {
            if (keyCertSecret.getData() == null
                    || !keyCertSecret.getData().containsKey(keyCertName + ".key")
                    || !keyCertSecret.getData().containsKey(keyCertName + ".crt"))    {
                // No public and/or private key => throw an exception
                throw new InvalidConfigurationException("The Secret " + keyCertSecret.getMetadata().getName() + " does not seem to contain " + keyCertName + ".key and " + keyCertName + ".crt entries");
            }

            privateKey = new String(Util.decodeFromSecret(keyCertSecret, keyCertName + ".key"), StandardCharsets.US_ASCII);
            certificateChain = new String(Util.decodeFromSecret(keyCertSecret, keyCertName + ".crt"), StandardCharsets.US_ASCII);
        }

        config.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapHostnames);

        // configuring TLS encryption if requested
        if (trustedCertificates != null) {
            config.putIfAbsent(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SSL");
            config.setProperty(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
            config.setProperty(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, trustedCertificates);
        }

        // configuring TLS client authentication
        if (certificateChain != null && privateKey != null) {
            config.putIfAbsent(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, "SSL");
            config.setProperty(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
            config.setProperty(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, certificateChain);
            config.setProperty(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, privateKey);
        }

        config.putIfAbsent(AdminClientConfig.METADATA_MAX_AGE_CONFIG, "30000");
        config.putIfAbsent(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        config.putIfAbsent(AdminClientConfig.RETRIES_CONFIG, "3");
        config.putIfAbsent(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "40000");

        return config;
    }
}
