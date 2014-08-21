package com.autonomy.frontend.configuration;

import com.autonomy.aci.client.transport.AciServerDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.Assert.fail;

/*
 * $Id:$
 *
 * Copyright (c) 2014, Autonomy Systems Ltd.
 *
 * Last modified by $Author:$ on $Date:$
 */
public class CommunityAuthenticationTest {

    private ObjectMapper objectMapper;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    public void jsonSerialization() {
        final DefaultLogin defaultLogin = new DefaultLogin.Builder()
            .build().generateDefaultLogin();

        final ServerConfig community = new ServerConfig.Builder()
            .setProtocol(AciServerDetails.TransportProtocol.HTTP)
            .setHost("test-server")
            .setPort(9030)
            .build();

        final CommunityAuthentication communityAuthentication = new CommunityAuthentication.Builder()
            .setCommunity(community)
            .setDefaultLogin(defaultLogin)
            .setMethod("autonomy")
            .build();

        final JsonNode jsonNode = objectMapper.valueToTree(communityAuthentication);

        // hard coding this would prevent package movement
        assertThat(jsonNode.get("className").asText(), is(CommunityAuthentication.class.getCanonicalName()));
        assertThat(jsonNode.get("method").asText(), is("autonomy"));
        assertThat(jsonNode.get("community").get("host").asText(), is("test-server"));
        assertThat(jsonNode.get("community").get("port").asInt(), is(9030));
        assertThat(jsonNode.get("community").get("protocol").asText(), is("HTTP"));
        assertThat(jsonNode.get("defaultLogin").get("username").asText(), is("admin"));
        assertThat(jsonNode.get("defaultLogin").get("password").asText(), notNullValue());
    }

    @Test
    public void jsonDeserialization() throws IOException {
        final InputStream inputStream = getClass().getResourceAsStream("/com/autonomy/frontend/configuration/communityAuthentication.json");

        final TestConfig testConfig = objectMapper.readValue(inputStream, TestConfig.class);
        final Authentication<?> authentication = testConfig.getAuthentication();

        if(authentication instanceof CommunityAuthentication) {
            final CommunityAuthentication casAuthentication = (CommunityAuthentication) authentication;
            final ServerConfig cas = casAuthentication.getCommunity();

            assertThat(cas.getHost(), is("localhost"));
            assertThat(cas.getProtocol(), is(AciServerDetails.TransportProtocol.HTTP));
            assertThat(cas.getPort(), is(9030));
        }
        else {
            fail("Deserialized class not of correct type");
        }
    }
}
