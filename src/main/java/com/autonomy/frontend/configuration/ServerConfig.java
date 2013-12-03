package com.autonomy.frontend.configuration;

import com.autonomy.aci.actions.common.GetVersionProcessor;
import com.autonomy.aci.actions.common.Version;
import com.autonomy.aci.client.services.AciService;
import com.autonomy.aci.client.transport.AciServerDetails;
import com.autonomy.aci.client.util.AciParameters;
import com.autonomy.common.lang.StringUtils;
import com.autonomy.nonaci.ServerDetails;
import com.autonomy.nonaci.indexing.IndexingException;
import com.autonomy.nonaci.indexing.IndexingService;
import com.autonomy.nonaci.indexing.impl.IndexCommandImpl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Data;

import java.util.Arrays;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for an ACI server, which can also include index and service ports.
 */
@Data
@JsonDeserialize(builder = ServerConfig.Builder.class)
public class ServerConfig implements ConfigurationComponent {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);

    private final AciServerDetails.TransportProtocol protocol;
    private final String host;
    private final int port;

    private final ServerDetails.TransportProtocol indexProtocol;
    private final int indexPort;
    private final AciServerDetails.TransportProtocol serviceProtocol;
    private final int servicePort;

    /**
     * The producttypecsv of the server, used for validation
     */
    private final ProductType productType;

    /**
     * The error message to expect when testing the index port of the server.  If not defined it is assumed that
     * this server does not support indexing.
     */
    private final String indexErrorMessage;

    private ServerConfig(final Builder builder) {
        this.protocol = builder.getProtocol();
        this.host = builder.getHost();
        this.port = builder.getPort();
        this.indexPort = builder.getIndexPort();
        this.indexProtocol = builder.getIndexProtocol();
        this.servicePort = builder.getServicePort();
        this.serviceProtocol = builder.getServiceProtocol();
        this.productType = builder.getProductType();
        this.indexErrorMessage = builder.getIndexErrorMessage();
    }

    public ServerConfig merge(final ServerConfig serverConfig) {
        if(serverConfig != null) {
            final Builder builder = new Builder();

            builder.setProtocol(this.protocol == null ? serverConfig.protocol : this.protocol);
            builder.setHost(this.host == null ? serverConfig.host : this.host);
            builder.setPort(this.port == 0 ? serverConfig.port : this.port);
            builder.setIndexPort(this.indexPort == 0 ? serverConfig.indexPort : this.indexPort);
            builder.setIndexProtocol(this.indexProtocol == null ? serverConfig.indexProtocol : this.indexProtocol);
            builder.setServicePort(this.servicePort == 0 ? serverConfig.servicePort : this.servicePort);
            builder.setServiceProtocol(this.serviceProtocol == null ? serverConfig.serviceProtocol : this.serviceProtocol);
            builder.setProductType(this.productType == null ? serverConfig.productType : this.productType);
            builder.setIndexErrorMessage(this.indexErrorMessage == null ? serverConfig.indexErrorMessage : this.indexErrorMessage);

            return builder.build();
        }

        return this;
    }

    /**
     * @param serverDetails The IndexServer to use
     * @return A new ServerConfig with the supplied indexing details
     */
    public ServerConfig withIndexServer(final ServerDetails serverDetails) {
        final Builder builder = new Builder();

        builder.setProtocol(this.protocol);
        builder.setHost(this.host);
        builder.setPort(this.port);
        builder.setIndexProtocol(serverDetails.getProtocol());
        builder.setIndexPort(serverDetails.getPort());
        builder.setServiceProtocol(this.serviceProtocol);
        builder.setServicePort(this.servicePort);

        return builder.build();
    }

    /**
     * @param aciService The {@link AciService} used to discover the ports.
     * @param indexingService The {@link IndexingService} used to test the index port.
     * @return A new ServerConfig with its indexing and service details filled in.
     */
    public ServerConfig fetchServerDetails(final AciService aciService, final IndexingService indexingService) {
        final Builder builder = new Builder();

        builder.setProtocol(this.protocol);
        builder.setHost(this.host);
        builder.setPort(this.port);

        final PortsResponse response;

        try {
            // getStatus doesn't always return ports, but does when an index port is used
            if(this.indexErrorMessage == null) {
                response = aciService.executeAction(this.toAciServerDetails(), new AciParameters("getChildren"), new PortsResponseProcessor("autn:port", "autn:serviceport"));
            }
            else {
                response = aciService.executeAction(this.toAciServerDetails(), new AciParameters("getStatus"), new PortsResponseProcessor("aciport", "serviceport", "indexport"));
            }
        } catch (final RuntimeException e) {
            throw new IllegalArgumentException("Unable to connect to ACI server");
        }

        if(this.indexErrorMessage != null) {
            final int indexPort = response.getIndexPort();
            final ServerDetails indexDetails = new ServerDetails();
            indexDetails.setHost(this.getHost());
            indexDetails.setPort(indexPort);
            boolean isIndexPortValid = false;

            for (final ServerDetails.TransportProtocol protocol : Arrays.asList(ServerDetails.TransportProtocol.HTTP, ServerDetails.TransportProtocol.HTTPS)) {
                indexDetails.setProtocol(protocol);

                if (testIndexingConnection(indexDetails, indexingService, this.indexErrorMessage)) {
                    // test http first. If the server is https, it will give an error (quickly),
                    // whereas the timeout when doing https to a http server takes a really long time
                    builder.setIndexProtocol(protocol);
                    builder.setIndexPort(indexPort);

                    isIndexPortValid = true;
                    break;
                }
            }

            if(!isIndexPortValid) {
                throw new IllegalArgumentException("Server does not have a valid index port");
            }
        }

        final int servicePort = response.getServicePort();
        final AciServerDetails servicePortDetails = new AciServerDetails();
        servicePortDetails.setHost(this.getHost());
        servicePortDetails.setPort(servicePort);

        for (final AciServerDetails.TransportProtocol protocol : Arrays.asList(AciServerDetails.TransportProtocol.HTTP, AciServerDetails.TransportProtocol.HTTPS)) {
            servicePortDetails.setProtocol(protocol);
            servicePortDetails.setPort(servicePort);

            if (testServicePortConnection(servicePortDetails, aciService)) {
                // test http first. If the server is https, it will give an error (quickly),
                // whereas the timeout when doing https to a http server takes a really long time
                builder.setServiceProtocol(protocol);
                builder.setServicePort(servicePort);

                //Both index and service ports are valid
                return builder.build();
            }
        }

        //Index port valid but service port invalid
        throw new IllegalArgumentException("Server does not have a valid service port");
    }

    private boolean testServicePortConnection(final AciServerDetails serviceDetails, final AciService aciService) {
        try {
            return aciService.executeAction(serviceDetails, new AciParameters("getstatus"), new NoopProcessor());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean testIndexingConnection(final ServerDetails indexDetails, final IndexingService indexingService, final String errorMessage) {
        try {
            indexingService.executeCommand(indexDetails, new IndexCommandImpl("test"));
        }
        catch (final IndexingException e) {
            // we got back a response from the index port
            return e.getMessage().contains(errorMessage);
        }
        catch (final RuntimeException e) {
            // any other kind of exception is bad
        }

        return false;
    }

    /**
     * @return A representation of this server as an {@link AciServerDetails}
     */
    public AciServerDetails toAciServerDetails() {
        return new AciServerDetails(getProtocol(), getHost(), getPort());
    }

    /**
     * @return A representation of this server as an {@link ServerDetails}
     */
    public ServerDetails toServerDetails() {
        final ServerDetails serverDetails = new ServerDetails();

        serverDetails.setHost(getHost());
        serverDetails.setPort(getIndexPort());
        serverDetails.setProtocol(indexProtocol);

        return serverDetails;
    }

    /**
     * Validates that the required settings are supplied and that the target server is responding
     *
     * @param aciService The {@link com.autonomy.aci.client.services.AciService} to use for validation
     * @param indexingService The {@link com.autonomy.nonaci.indexing.IndexingService} to use for validation. If the server does not support indexing
     *                        this may be null
     * @return true if the server is valid; false otherwise
     */
    public ValidationResult<?> validate(final AciService aciService, final IndexingService indexingService) {
        // if the host is blank further testing is futile
        try {
            // string doesn't matter here as we swallow the exception
            basicValidate(null);
        }
        catch(ConfigException e) {
            return new ValidationResult<>(false, "One or more of the required field is missing");
        }


        final boolean isCorrectVersion;

        try {
            isCorrectVersion = testServerVersion(aciService);
        }
        catch(RuntimeException e) {
            LOGGER.debug("Error validating server version for {}", this.productType);
            LOGGER.debug("", e);
            return new ValidationResult<>(false, "An error occurred contacting the ACI server");
        }

        if(!isCorrectVersion) {
            return new ValidationResult<>(false, "Incorrect server type.  " +
                "Make sure you are using a " + productType.getFriendlyName());
        }

        try {
            final ServerConfig serverConfig = fetchServerDetails(aciService, indexingService);

            final boolean result = serverConfig.getServicePort() > 0;

            if(this.indexErrorMessage == null) {
                return new ValidationResult<>(result, "The server's service port could not be determined");
            }
            else {
                return new ValidationResult<>(result && serverConfig.getIndexPort() > 0,
                    "The server's service or index ports could not be determined");
            }

        } catch (final RuntimeException e) {
            LOGGER.debug("Error validating config", e);
            return new ValidationResult<>(false, "An error occurred " +
                "fetching the details of the server's index and service ports");
        }
    }

    /**
     * @param component The name of the configuration section, to be used in case of failure
     * @return true if all the required settings exist
     * @throws ConfigException If the ServerConfig is invalid
     */
    public boolean basicValidate(final String component) throws ConfigException  {
        if(this.getPort() <= 0 || StringUtils.isBlank(this.getHost())){
            throw new ConfigException(component,
                component + " attributes have not been defined.");
        }

        return true;
    }

    private boolean testServerVersion(final AciService aciService) {
        // Community's ProductName is just IDOL, so we need to check the product type
        final Version version = aciService.executeAction(toAciServerDetails(), new AciParameters("getversion"), new GetVersionProcessor());
        return version.getProductTypes().contains(this.productType.name());
    }

    /**
     * @return The service port details of this ServerConfig as an {@link AciServerDetails}
     */
    public AciServerDetails toServiceServerDetails() {
        return new AciServerDetails(getServiceProtocol(), getHost(), getServicePort());
    }

    @Override
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    @Data
    @Accessors(chain = true)
    @JsonPOJOBuilder(withPrefix = "set")
    @JsonIgnoreProperties(ignoreUnknown = true) // for compatibility with old AciServerDetails config files
    public static class Builder {
        private AciServerDetails.TransportProtocol protocol = AciServerDetails.TransportProtocol.HTTP;
        private AciServerDetails.TransportProtocol serviceProtocol = AciServerDetails.TransportProtocol.HTTP;
        private ServerDetails.TransportProtocol indexProtocol = ServerDetails.TransportProtocol.HTTP;
        private String host;
        private int port;
        private int indexPort;
        private int servicePort;
        private ProductType productType;
        private String indexErrorMessage;

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
